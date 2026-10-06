package com.eklipse.search.core;

import java.util.Arrays;

/**
 * The text shown in the preview: the content of a file with very long lines (e.g. minified JavaScript) shortened, so
 * the text widget stays fast. Every line of the file stays one line, so line numbers don't change. The line of the
 * selected match is shortened around the match, all other lines keep their beginning.
 */
public final class PreviewText {

	private static final String ELLIPSIS = "…";

	private final String text;
	/** {@code null} if no line was shortened and offsets are the same in the file and the preview. */
	private final int[] lineStarts;
	private final int[] keptStarts;
	private final int[] keptLengths;
	private final int[] previewStarts;

	private PreviewText(String text, int[] lineStarts, int[] keptStarts, int[] keptLengths, int[] previewStarts) {
		this.text = text;
		this.lineStarts = lineStarts;
		this.keptStarts = keptStarts;
		this.keptLengths = keptLengths;
		this.previewStarts = previewStarts;
	}

	/**
	 * @param content the content of the file
	 * @param targetOffset the offset of the selected match, its line is shortened around it, {@code -1} for none
	 * @param maxLineLength the number of characters kept of a long line
	 * @return the preview of the content
	 */
	public static PreviewText create(String content, int targetOffset, int maxLineLength) {
		if (!hasLongLine(content, maxLineLength)) {
			return new PreviewText(content, null, null, null, null);
		}
		StringBuilder text = new StringBuilder();
		int capacity = 256;
		int[] lineStarts = new int[capacity];
		int[] keptStarts = new int[capacity];
		int[] keptLengths = new int[capacity];
		int[] previewStarts = new int[capacity];
		int lines = 0;
		int lineStart = 0;
		while (true) {
			int lineEnd = lineEnd(content, lineStart);
			int next = nextLineStart(content, lineEnd);
			int keptStart = lineStart;
			int keptEnd = lineEnd;
			if (lineEnd - lineStart > maxLineLength) {
				if (targetOffset >= lineStart && targetOffset <= lineEnd) {
					// some context before the match, like the results show it
					keptStart = Math.max(lineStart, Math.min(targetOffset - maxLineLength / 3, lineEnd - maxLineLength));
				}
				keptEnd = keptStart + maxLineLength;
			}
			if (lines == capacity) {
				capacity *= 2;
				lineStarts = Arrays.copyOf(lineStarts, capacity);
				keptStarts = Arrays.copyOf(keptStarts, capacity);
				keptLengths = Arrays.copyOf(keptLengths, capacity);
				previewStarts = Arrays.copyOf(previewStarts, capacity);
			}
			if (keptStart > lineStart) {
				text.append(ELLIPSIS);
			}
			lineStarts[lines] = lineStart;
			keptStarts[lines] = keptStart;
			keptLengths[lines] = keptEnd - keptStart;
			previewStarts[lines] = text.length();
			lines++;
			text.append(content, keptStart, keptEnd);
			if (keptEnd < lineEnd) {
				text.append(ELLIPSIS);
			}
			text.append(content, lineEnd, next);
			if (next == lineEnd) {
				break;
			}
			lineStart = next;
		}
		return new PreviewText(text.toString(), Arrays.copyOf(lineStarts, lines), keptStarts, keptLengths,
				previewStarts);
	}

	private static boolean hasLongLine(String content, int maxLineLength) {
		int lineStart = 0;
		for (int i = 0; i < content.length(); i++) {
			char c = content.charAt(i);
			if (c == '\n' || c == '\r') {
				lineStart = i + 1;
			} else if (i - lineStart >= maxLineLength) {
				return true;
			}
		}
		return false;
	}

	private static int lineEnd(String content, int from) {
		for (int i = from; i < content.length(); i++) {
			char c = content.charAt(i);
			if (c == '\n' || c == '\r') {
				return i;
			}
		}
		return content.length();
	}

	private static int nextLineStart(String content, int lineEnd) {
		if (lineEnd >= content.length()) {
			return lineEnd;
		}
		return content.startsWith("\r\n", lineEnd) ? lineEnd + 2 : lineEnd + 1;
	}

	/**
	 * @return the text to show
	 */
	public String text() {
		return text;
	}

	/**
	 * @param fileOffset an offset in the file, the end of a range included
	 * @return the offset in {@link #text()}, {@code -1} if it was cut away
	 */
	public int toPreviewOffset(int fileOffset) {
		if (lineStarts == null) {
			return fileOffset >= 0 && fileOffset <= text.length() ? fileOffset : -1;
		}
		int line = Arrays.binarySearch(lineStarts, fileOffset);
		if (line < 0) {
			line = -line - 2;
		}
		if (line < 0) {
			return -1;
		}
		int offsetInKept = fileOffset - keptStarts[line];
		return offsetInKept >= 0 && offsetInKept <= keptLengths[line] ? previewStarts[line] + offsetInKept : -1;
	}
}
