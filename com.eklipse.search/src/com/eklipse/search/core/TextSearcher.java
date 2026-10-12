package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.ProgressMonitorWrapper;
import org.eclipse.core.runtime.Status;
import org.eclipse.search.core.text.TextSearchEngine;
import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;

/**
 * Runs the platform text search engine and turns its matches into {@link LineMatch}es.
 * <p>
 * The engine searches files in parallel and also looks at the content of open (possibly unsaved) editors.
 */
public final class TextSearcher {

	/** Same cap as VS Code, protects the UI from searches like {@code e} in a huge workspace. */
	public static final int DEFAULT_MAX_RESULTS = 20_000;

	private static final int MAX_LINE_SCAN = 1000;
	private static final int MAX_PREFIX = 60;
	private static final int KEPT_PREFIX = 40;
	private static final int MAX_SUFFIX = 250;
	private static final String ELLIPSIS = "…";

	private TextSearcher() {
	}

	/**
	 * The outcome of a search.
	 *
	 * @param status problems with individual files (e.g. unsupported charsets), {@code OK} if there were none
	 * @param matchCount the number of reported matches
	 * @param limitReached whether the search stopped early because {@code maxResults} was reached
	 */
	public record Result(IStatus status, int matchCount, boolean limitReached) {
	}

	/**
	 * Searches all files of a scope.
	 *
	 * @param scope the files to search
	 * @param pattern the pattern to search for
	 * @param maxResults the number of matches after which the search stops
	 * @param collector receives the matches, called from several threads
	 * @param monitor the monitor used for cancellation
	 * @return the outcome of the search
	 * @throws OperationCanceledException if {@code monitor} was canceled
	 */
	public static Result search(TextSearchScope scope, Pattern pattern, int maxResults, Consumer<LineMatch> collector,
			IProgressMonitor monitor) {
		return run((requestor, searchMonitor) -> TextSearchEngine.create().search(scope, requestor, pattern,
				searchMonitor), maxResults, collector, monitor);
	}

	/**
	 * Searches the given files, used to refresh the results of changed files.
	 *
	 * @param files the files to search
	 * @param pattern the pattern to search for
	 * @param maxResults the number of matches after which the search stops
	 * @param collector receives the matches, called from several threads
	 * @param monitor the monitor used for cancellation
	 * @return the outcome of the search
	 * @throws OperationCanceledException if {@code monitor} was canceled
	 */
	public static Result search(IFile[] files, Pattern pattern, int maxResults, Consumer<LineMatch> collector,
			IProgressMonitor monitor) {
		return run((requestor, searchMonitor) -> TextSearchEngine.create().search(files, requestor, pattern,
				searchMonitor), maxResults, collector, monitor);
	}

	/**
	 * Matches the pattern against the names of the files. It takes milliseconds where searching their content takes
	 * seconds, so the file looked for shows up before the content search even starts.
	 *
	 * @param files the files whose names to match
	 * @param pattern the pattern to search for
	 * @param names receives the files whose name matches
	 * @param monitor the monitor used for cancellation, stops the matching when canceled
	 */
	public static void searchNames(IFile[] files, Pattern pattern, Consumer<FileNameMatch> names,
			IProgressMonitor monitor) {
		Matcher matcher = pattern.matcher("");
		for (IFile file : files) {
			if (monitor.isCanceled()) {
				return;
			}
			matcher.reset(file.getName());
			// a regex like a* also matches nothing, that's no reason to list every file
			while (matcher.find()) {
				if (matcher.end() > matcher.start()) {
					names.accept(new FileNameMatch(file, matcher.start(), matcher.end()));
					break;
				}
			}
		}
	}

	/**
	 * Finds files by a path, which may be absolute, relative to the workspace or to anywhere else (a Git repository,
	 * another checkout): of the files named like its last segment, those whose folders match the most segments before
	 * it, compared from the end.
	 *
	 * @param files the files to look at
	 * @param path the path, segments separated by {@code /}
	 * @param caseSensitive whether upper and lower case must match exactly
	 * @param names receives the files found
	 * @param monitor the monitor used for cancellation, stops the search when canceled
	 */
	public static void searchPath(IFile[] files, String path, boolean caseSensitive, Consumer<FileNameMatch> names,
			IProgressMonitor monitor) {
		List<String> segments = new ArrayList<>();
		for (String segment : path.split("/")) {
			if (!segment.isEmpty() && !segment.equals(".")) {
				segments.add(segment);
			}
		}
		if (segments.isEmpty()) {
			return;
		}
		String name = segments.get(segments.size() - 1);
		List<IFile> best = new ArrayList<>();
		int bestScore = 0;
		for (IFile file : files) {
			if (monitor.isCanceled()) {
				return;
			}
			if (!(caseSensitive ? file.getName().equals(name) : file.getName().equalsIgnoreCase(name))) {
				continue;
			}
			// the location for paths from outside the workspace, the full path for those copied in Eclipse
			int score = Math.max(trailingMatches(segments, file.getLocation(), caseSensitive),
					trailingMatches(segments, file.getFullPath(), caseSensitive));
			if (score > bestScore) {
				best.clear();
				bestScore = score;
			}
			if (score == bestScore) {
				best.add(file);
			}
		}
		for (IFile file : best) {
			names.accept(new FileNameMatch(file, 0, file.getName().length()));
		}
	}

	/**
	 * @return how many of the last segments are those at the end of the path
	 */
	private static int trailingMatches(List<String> segments, IPath path, boolean caseSensitive) {
		if (path == null) {
			return 0;
		}
		int count = 0;
		int max = Math.min(segments.size(), path.segmentCount());
		while (count < max) {
			String expected = segments.get(segments.size() - 1 - count);
			String actual = path.segment(path.segmentCount() - 1 - count);
			if (!(caseSensitive ? actual.equals(expected) : actual.equalsIgnoreCase(expected))) {
				break;
			}
			count++;
		}
		return count;
	}

	private interface EngineCall {
		IStatus search(TextSearchRequestor requestor, IProgressMonitor monitor);
	}

	private static Result run(EngineCall call, int maxResults, Consumer<LineMatch> collector, IProgressMonitor monitor) {
		IProgressMonitor callerMonitor = monitor != null ? monitor : new NullProgressMonitor();
		Requestor requestor = new Requestor(maxResults, collector, callerMonitor);
		// the engine only stops through cancellation; reaching the limit must not cancel the caller's monitor
		IProgressMonitor searchMonitor = new ProgressMonitorWrapper(callerMonitor) {
			@Override
			public boolean isCanceled() {
				return requestor.limitReached || super.isCanceled();
			}
		};
		try {
			IStatus status = call.search(requestor, searchMonitor);
			return new Result(status, Math.min(requestor.count.get(), maxResults), requestor.limitReached);
		} catch (OperationCanceledException e) {
			if (!requestor.limitReached || callerMonitor.isCanceled()) {
				throw e;
			}
			return new Result(Status.OK_STATUS, maxResults, true);
		}
	}

	/**
	 * Creates a match with a single line preview from the engine's match access.
	 *
	 * @param access the match reported by the engine
	 * @param lineNumber the 1-based line number of the match
	 * @return the match
	 */
	static LineMatch createMatch(TextSearchMatchAccess access, int lineNumber) {
		int offset = access.getMatchOffset();
		int length = access.getMatchLength();
		int contentLength = access.getFileContentLength();

		int lineStart = offset;
		int minStart = Math.max(0, offset - MAX_LINE_SCAN);
		while (lineStart > minStart && !isLineBreak(access.getFileContentChar(lineStart - 1))) {
			lineStart--;
		}
		while (lineStart < offset && Character.isWhitespace(access.getFileContentChar(lineStart))) {
			lineStart++;
		}
		int lineEnd = offset;
		int maxEnd = Math.min(contentLength, offset + length + MAX_LINE_SCAN);
		while (lineEnd < maxEnd && !isLineBreak(access.getFileContentChar(lineEnd))) {
			lineEnd++;
		}
		// a multi-line regex match is previewed up to the end of its first line
		int matchEnd = Math.min(offset + length, lineEnd);

		String prefix = "";
		if (offset - lineStart > MAX_PREFIX) {
			lineStart = offset - KEPT_PREFIX;
			prefix = ELLIPSIS;
		}
		String suffix = "";
		if (lineEnd - matchEnd > MAX_SUFFIX) {
			lineEnd = matchEnd + MAX_SUFFIX;
			suffix = ELLIPSIS;
		}
		// tabs render inconsistently in trees, a space keeps the match offsets intact
		String line = access.getFileContent(lineStart, lineEnd - lineStart).replace('\t', ' ');
		String preview = prefix + line + suffix;
		return new LineMatch(access.getFile(), offset, length, lineNumber, access.getFileContent(offset, length), preview,
				prefix.length() + offset - lineStart, matchEnd - offset);
	}

	private static boolean isLineBreak(char c) {
		return c == '\n' || c == '\r';
	}

	private static final class Requestor extends TextSearchRequestor {

		private final int maxResults;
		private final Consumer<LineMatch> collector;
		private final IProgressMonitor monitor;
		private final AtomicInteger count = new AtomicInteger();
		private final Map<IFile, LineCounter> lineCounters = new ConcurrentHashMap<>();
		private volatile boolean limitReached;

		Requestor(int maxResults, Consumer<LineMatch> collector, IProgressMonitor monitor) {
			this.maxResults = maxResults;
			this.collector = collector;
			this.monitor = monitor;
		}

		@Override
		public boolean canRunInParallel() {
			return true;
		}

		@Override
		public boolean acceptPatternMatch(TextSearchMatchAccess access) {
			if (monitor.isCanceled()) {
				return false;
			}
			if (count.incrementAndGet() > maxResults) {
				limitReached = true;
				return false;
			}
			// the matches of one file are reported in order by a single thread
			LineCounter counter = lineCounters.computeIfAbsent(access.getFile(), file -> new LineCounter());
			collector.accept(createMatch(access, counter.lineAt(access, access.getMatchOffset())));
			return true;
		}
	}

	/**
	 * Counts lines incrementally, so computing the line numbers of all matches of a file is linear.
	 */
	private static final class LineCounter {

		private int offset;
		private int line = 1;

		int lineAt(TextSearchMatchAccess access, int target) {
			int contentLength = access.getFileContentLength();
			for (; offset < target; offset++) {
				char c = access.getFileContentChar(offset);
				if (c == '\n' || c == '\r' && (offset + 1 >= contentLength || access.getFileContentChar(offset + 1) != '\n')) {
					line++;
				}
			}
			return line;
		}
	}
}
