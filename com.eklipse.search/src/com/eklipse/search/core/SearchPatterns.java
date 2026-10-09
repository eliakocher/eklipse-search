package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
		return create(text, caseSensitive, wholeWord, regex, false);
	}

	/**
	 * Creates a search pattern, optionally with wildcards, see {@link #wildcardRegex(String)}.
	 *
	 * @param text the literal text, text with wildcards or regular expression
	 * @param caseSensitive whether case must match
	 * @param wholeWord whether the match must not be surrounded by word characters
	 * @param regex whether {@code text} is a regular expression
	 * @param wildcards whether {@code *} in {@code text} matches any text, ignored for a regular expression
	 * @return the compiled pattern
	 * @throws java.util.regex.PatternSyntaxException if {@code regex} is set and {@code text} is invalid
	 */
	public static Pattern create(String text, boolean caseSensitive, boolean wholeWord, boolean regex,
			boolean wildcards) {
		String body = regex ? text : wildcards ? wildcardRegex(text) : Pattern.quote(text);
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
	 * Turns text with wildcards into a regular expression: {@code *} matches any text within a line, as little as
	 * possible, so {@code final*size} finds {@code final int size} in {@code final int size = maxSize}; {@code \*} is
	 * a star. Stars at the start and the end are left out, they would only stretch the match to the line's ends. Text
	 * of nothing but stars is searched as it is.
	 *
	 * @param text the text with wildcards
	 * @return the regular expression
	 */
	static String wildcardRegex(String text) {
		List<String> parts = new ArrayList<>();
		StringBuilder part = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
				part.append('*');
				i++;
			} else if (c == '*') {
				parts.add(part.toString());
				part.setLength(0);
			} else {
				part.append(c);
			}
		}
		parts.add(part.toString());
		// without DOTALL, . doesn't match line breaks
		String regex = parts.stream().filter(p -> !p.isEmpty()).map(Pattern::quote).collect(Collectors.joining(".*?"));
		return regex.isEmpty() ? Pattern.quote(text) : regex;
	}

	/**
	 * Escapes the stars, used when prefilling the search field with wildcards on.
	 *
	 * @param text the literal text
	 * @return {@code text} with every star prefixed by a backslash
	 */
	public static String escapeWildcards(String text) {
		return text.replace("*", "\\*");
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
