package com.eklipse.search.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
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
