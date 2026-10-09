package com.eklipse.search.core;

import java.util.Comparator;

import org.eclipse.core.resources.IFile;

/**
 * A file found by its name. It's listed apart from the matches in the content of the files, a file can be both.
 */
public final class FileNameMatch {

	/**
	 * The most relevant first: the whole name or the name without its extension, then a name starting with the match,
	 * then any other; shorter names before longer ones.
	 */
	public static final Comparator<FileNameMatch> BY_RELEVANCE = Comparator.comparingInt(FileNameMatch::rank)
			.thenComparingInt(m -> m.file.getName().length())
			.thenComparing(m -> m.file.getName(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(m -> m.file.getFullPath().toString(), String.CASE_INSENSITIVE_ORDER);

	private final IFile file;
	private final int start;
	private final int end;

	/**
	 * @param file the file
	 * @param start the start of the match in the file name
	 * @param end the end of the match in the file name
	 */
	public FileNameMatch(IFile file, int start, int end) {
		this.file = file;
		this.start = start;
		this.end = end;
	}

	public IFile getFile() {
		return file;
	}

	/**
	 * @return the start of the match in the file name
	 */
	public int getStart() {
		return start;
	}

	/**
	 * @return the end of the match in the file name
	 */
	public int getEnd() {
		return end;
	}

	private int rank() {
		String name = file.getName();
		if (start == 0 && (end == name.length() || end == name.lastIndexOf('.'))) {
			return 0;
		}
		return start == 0 ? 1 : 2;
	}

	@Override
	public String toString() {
		return file.getFullPath() + " (name " + start + "-" + end + ")";
	}
}
