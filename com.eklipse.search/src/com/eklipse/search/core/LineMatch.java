package com.eklipse.search.core;

import org.eclipse.core.resources.IFile;

/**
 * A single match together with a one line preview of its surroundings.
 * <p>
 * Instances are immutable and compared by identity, which is what the result tree needs.
 */
public final class LineMatch {

	private final IFile file;
	private final int offset;
	private final int length;
	private final int lineNumber;
	private final String matchedText;
	private final String preview;
	private final int previewMatchStart;
	private final int previewMatchLength;

	/**
	 * @param file the file containing the match
	 * @param offset the character offset of the match in the file
	 * @param length the length of the match
	 * @param lineNumber the 1-based line of the match start
	 * @param matchedText the matched text, used to detect files that changed since the search
	 * @param preview the (possibly shortened) line containing the match
	 * @param previewMatchStart the start of the match within {@code preview}
	 * @param previewMatchLength the length of the match within {@code preview}
	 */
	public LineMatch(IFile file, int offset, int length, int lineNumber, String matchedText, String preview,
			int previewMatchStart, int previewMatchLength) {
		this.file = file;
		this.offset = offset;
		this.length = length;
		this.lineNumber = lineNumber;
		this.matchedText = matchedText;
		this.preview = preview;
		this.previewMatchStart = previewMatchStart;
		this.previewMatchLength = previewMatchLength;
	}

	public IFile getFile() {
		return file;
	}

	public int getOffset() {
		return offset;
	}

	public int getLength() {
		return length;
	}

	public int getLineNumber() {
		return lineNumber;
	}

	public String getMatchedText() {
		return matchedText;
	}

	public String getPreview() {
		return preview;
	}

	public int getPreviewMatchStart() {
		return previewMatchStart;
	}

	public int getPreviewMatchLength() {
		return previewMatchLength;
	}

	@Override
	public String toString() {
		return file.getFullPath() + ":" + lineNumber + ": " + preview;
	}
}
