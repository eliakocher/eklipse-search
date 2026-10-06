package com.eklipse.search.core;

import java.util.regex.Pattern;

/**
 * Builds the {@link Pattern} used for searching.
 */
public final class SearchPatterns {

	private static final String WORD_CHAR = "[\\p{L}\\p{N}_]";
	private static final String REGEX_META_CHARS = "\\.[]{}()<>*+-=!?^$|";

	private SearchPatterns() {
	}

	/**
	 * Creates a search pattern.
	 * <p>
	 * Whole word matching uses look-arounds instead of {@code \b}, so that searching e.g. {@code foo.} as a whole
	 * word behaves like in VS Code (the match must not be preceded or followed by a letter, digit or underscore).
	 *
	 * @param text the literal text or regular expression
	 * @param caseSensitive whether case must match
	 * @param wholeWord whether the match must not be surrounded by word characters
	 * @param regex whether {@code text} is a regular expression
	 * @return the compiled pattern
	 * @throws java.util.regex.PatternSyntaxException if {@code regex} is set and {@code text} is invalid
	 */
	public static Pattern create(String text, boolean caseSensitive, boolean wholeWord, boolean regex) {
		String body = regex ? text : Pattern.quote(text);
		if (wholeWord) {
			body = "(?<!" + WORD_CHAR + ")(?:" + body + ")(?!" + WORD_CHAR + ")";
		}
		int flags = Pattern.MULTILINE;
		if (!caseSensitive) {
			flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
		}
		return Pattern.compile(body, flags);
	}

	/**
	 * Escapes regular expression meta characters, used when prefilling the search field in regex mode.
	 *
	 * @param text the literal text
	 * @return {@code text} with every meta character prefixed by a backslash
	 */
	public static String escapeRegex(String text) {
		StringBuilder escaped = new StringBuilder(text.length() + 8);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (REGEX_META_CHARS.indexOf(c) >= 0) {
				escaped.append('\\');
			}
			escaped.append(c);
		}
		return escaped.toString();
	}
}
