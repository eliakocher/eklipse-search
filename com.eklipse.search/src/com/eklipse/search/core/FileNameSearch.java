package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Finds files by their name: the search text in the name, a camel case abbreviation of it like in Open Resource, or a
 * reference like a path, a qualified class name or a stack trace line, see {@link FileReference}. It takes
 * milliseconds where searching the content of the files takes seconds, so the file looked for shows up before the
 * content search even starts.
 */
public final class FileNameSearch {

	private FileNameSearch() {
	}

	/**
	 * @param files the files to look at
	 * @param query the query
	 * @param pattern the pattern of the query
	 * @param names receives the files found
	 * @param monitor the monitor used for cancellation, stops the search when canceled
	 */
	public static void search(IFile[] files, SearchQuery query, Pattern pattern, Consumer<FileNameMatch> names,
			IProgressMonitor monitor) {
		boolean caseSensitive = query.caseSensitive();
		String abbreviation = query.isCamelCase() ? query.text() : null;
		FileReference reference = query.fileReference();
		List<IFile> referenced = new ArrayList<>();
		Matcher matcher = pattern.matcher("");
		for (IFile file : files) {
			if (monitor.isCanceled()) {
				return;
			}
			String name = file.getName();
			if (reference != null && reference.matchesName(name, caseSensitive)) {
				referenced.add(file);
			}
			FileNameMatch match = find(file, name, matcher);
			if (match == null && abbreviation != null) {
				int[] highlights = camelCaseMatch(abbreviation, name, caseSensitive);
				match = highlights != null ? FileNameMatch.camelCase(file, highlights) : null;
			}
			if (match != null) {
				names.accept(match);
			}
		}
		if (reference != null) {
			for (IFile file : closest(referenced, reference.folders(), caseSensitive)) {
				names.accept(FileNameMatch.referenced(file, reference.line()));
			}
		}
	}

	private static FileNameMatch find(IFile file, String name, Matcher matcher) {
		matcher.reset(name);
		// a regex like a* also matches nothing, that's no reason to list every file
		while (matcher.find()) {
			if (matcher.end() > matcher.start()) {
				return new FileNameMatch(file, matcher.start(), matcher.end());
			}
		}
		return null;
	}

	/**
	 * Matches a camel case abbreviation: each of its words, starting with an upper case letter, is the start of a word
	 * in the name, in the same order; words in between may be left out, the first word starts the name. So
	 * {@code AdaCarCss} and {@code ACV} match {@code AdaptiveCardCssVariables.java}.
	 *
	 * @param abbreviation the abbreviation
	 * @param name the file name
	 * @param caseSensitive whether upper and lower case must match exactly
	 * @return the parts of the name matching the words, as start/end pairs, {@code null} if the name doesn't match
	 */
	static int[] camelCaseMatch(String abbreviation, String name, boolean caseSensitive) {
		// most names don't even start with the same letter
		if (name.isEmpty() || !sameChar(abbreviation.charAt(0), name.charAt(0), caseSensitive)) {
			return null;
		}
		List<Integer> highlights = new ArrayList<>();
		int position = 0;
		int wordStart = 0;
		while (wordStart < abbreviation.length()) {
			int wordEnd = wordStart + 1;
			while (wordEnd < abbreviation.length() && !Character.isUpperCase(abbreviation.charAt(wordEnd))) {
				wordEnd++;
			}
			int length = wordEnd - wordStart;
			// the earliest word that fits leaves the most room for the following ones
			int start = wordStart == 0 ? 0 : nextWordStart(name, position);
			while (start >= 0 && !name.regionMatches(!caseSensitive, start, abbreviation, wordStart, length)) {
				start = wordStart == 0 ? -1 : nextWordStart(name, start + 1);
			}
			if (start < 0) {
				return null;
			}
			highlights.add(start);
			highlights.add(start + length);
			position = start + length;
			wordStart = wordEnd;
		}
		return highlights.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * @return the first index from {@code from} on where a word starts: an upper case letter, or a letter or digit
	 *         after a separator like {@code _}, {@code -} or {@code .}; {@code -1} if there is none
	 */
	private static int nextWordStart(String name, int from) {
		for (int i = Math.max(from, 1); i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isUpperCase(c)
					|| Character.isLetterOrDigit(c) && !Character.isLetterOrDigit(name.charAt(i - 1))) {
				return i;
			}
		}
		return -1;
	}

	private static boolean sameChar(char a, char b, boolean caseSensitive) {
		return a == b || !caseSensitive && Character.toLowerCase(a) == Character.toLowerCase(b);
	}

	/**
	 * @return the files whose folders match the most of the given ones, compared from the innermost; all of them if
	 *         none matches
	 */
	private static List<IFile> closest(List<IFile> files, List<String> folders, boolean caseSensitive) {
		List<IFile> best = new ArrayList<>();
		int bestScore = 0;
		for (IFile file : files) {
			// the location for paths from outside the workspace, the full path for those copied in Eclipse
			int score = Math.max(trailingMatches(folders, file.getLocation(), caseSensitive),
					trailingMatches(folders, file.getFullPath(), caseSensitive));
			if (score > bestScore) {
				best.clear();
				bestScore = score;
			}
			if (score == bestScore) {
				best.add(file);
			}
		}
		return best;
	}

	/**
	 * @return how many of the last folders are those the file is in
	 */
	private static int trailingMatches(List<String> folders, IPath filePath, boolean caseSensitive) {
		if (filePath == null) {
			return 0;
		}
		IPath path = filePath.removeLastSegments(1);
		int count = 0;
		int max = Math.min(folders.size(), path.segmentCount());
		while (count < max) {
			String expected = folders.get(folders.size() - 1 - count);
			String actual = path.segment(path.segmentCount() - 1 - count);
			if (!(caseSensitive ? actual.equals(expected) : actual.equalsIgnoreCase(expected))) {
				break;
			}
			count++;
		}
		return count;
	}
}
