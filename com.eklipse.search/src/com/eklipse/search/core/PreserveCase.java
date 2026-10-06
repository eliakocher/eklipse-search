package com.eklipse.search.core;

import java.util.Locale;

/**
 * Adapts the case of a replacement to the case of the replaced text, like VS Code's "Preserve Case" toggle.
 */
public final class PreserveCase {

	private PreserveCase() {
	}

	/**
	 * Examples with replacement {@code newName}: {@code OLD_NAME} gives {@code NEWNAME}, {@code oldname} gives
	 * {@code newname}, {@code OldName} gives {@code NewName}.
	 *
	 * @param matched the text being replaced
	 * @param replacement the replacement as typed by the user
	 * @return the replacement in the case style of {@code matched}
	 */
	public static String apply(String matched, String replacement) {
		if (matched.isEmpty() || replacement.isEmpty()) {
			return replacement;
		}
		String upper = matched.toUpperCase(Locale.ROOT);
		String lower = matched.toLowerCase(Locale.ROOT);
		boolean hasLetters = !upper.equals(lower);
		if (hasLetters && matched.equals(upper)) {
			return replacement.toUpperCase(Locale.ROOT);
		}
		if (hasLetters && matched.equals(lower)) {
			return replacement.toLowerCase(Locale.ROOT);
		}
		int first = matched.codePointAt(0);
		int replacementFirst = replacement.codePointAt(0);
		int rest = Character.charCount(replacementFirst);
		if (Character.isUpperCase(first)) {
			return new StringBuilder().appendCodePoint(Character.toUpperCase(replacementFirst))
					.append(replacement, rest, replacement.length()).toString();
		}
		if (Character.isLowerCase(first)) {
			return new StringBuilder().appendCodePoint(Character.toLowerCase(replacementFirst))
					.append(replacement, rest, replacement.length()).toString();
		}
		return replacement;
	}
}
