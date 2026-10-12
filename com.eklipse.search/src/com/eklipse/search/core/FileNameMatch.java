package com.eklipse.search.core;

import java.util.Arrays;
import java.util.Comparator;

import org.eclipse.core.resources.IFile;

/**
 * A file found by its name. It's listed apart from the matches in the content of the files, a file can be both.
 */
public final class FileNameMatch {

	/**
	 * The most relevant first: a file referred to by its path or class, the whole name or the name without its
	 * extension, then a name starting with the match, then camel case abbreviations, then any other; shorter names
	 * before longer ones.
	 */
	public static final Comparator<FileNameMatch> BY_RELEVANCE = Comparator.comparingInt((FileNameMatch m) -> m.rank)
			.thenComparingInt(m -> m.file.getName().length())
			.thenComparing(m -> m.file.getName(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(m -> m.file.getFullPath().toString(), String.CASE_INSENSITIVE_ORDER);

	private static final int WHOLE_NAME = 0;
	private static final int PREFIX = 1;
	private static final int CAMEL_CASE = 2;
	private static final int OTHER = 3;

	private final IFile file;
	private final int[] highlights;
	private final int rank;
	private final int line;

	/**
	 * A match of the search text in the name.
	 *
	 * @param file the file
	 * @param start the start of the match in the file name
	 * @param end the end of the match in the file name
	 */
	public FileNameMatch(IFile file, int start, int end) {
		this(file, new int[] { start, end }, rank(file.getName(), start, end), 0);
	}

	private FileNameMatch(IFile file, int[] highlights, int rank, int line) {
		this.file = file;
		this.highlights = highlights;
		this.rank = rank;
		this.line = line;
	}

	/**
	 * @param file the file
	 * @param highlights the parts of the name matching the words of the abbreviation, as start/end pairs
	 * @return a match of a camel case abbreviation, like {@code NPE} for {@code NullPointerException.java}
	 */
	public static FileNameMatch camelCase(IFile file, int[] highlights) {
		return new FileNameMatch(file, highlights, CAMEL_CASE, 0);
	}

	/**
	 * @param file the file
	 * @param line the line referred to, starting at 1, 0 for none
	 * @return a file referred to by its path or class, see {@link FileReference}
	 */
	public static FileNameMatch referenced(IFile file, int line) {
		return new FileNameMatch(file, new int[] { 0, file.getName().length() }, WHOLE_NAME, line);
	}

	public IFile getFile() {
		return file;
	}

	/**
	 * @return the parts of the file name to highlight, as start/end pairs
	 */
	public int[] getHighlights() {
		return highlights.clone();
	}

	/**
	 * @return the line referred to, starting at 1, 0 for none
	 */
	public int getLine() {
		return line;
	}

	private static int rank(String name, int start, int end) {
		if (start == 0 && (end == name.length() || end == name.lastIndexOf('.'))) {
			return WHOLE_NAME;
		}
		return start == 0 ? PREFIX : OTHER;
	}

	@Override
	public String toString() {
		return file.getFullPath() + " (name " + Arrays.toString(highlights) + (line > 0 ? ", line " + line : "") + ")";
	}
}
