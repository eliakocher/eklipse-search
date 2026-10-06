package com.eklipse.search.core;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable description of what to search for and which files to look at.
 *
 * @param text the text or regular expression to search for
 * @param caseSensitive whether upper and lower case must match exactly
 * @param wholeWord whether matches must not be surrounded by word characters
 * @param regex whether {@code text} is a regular expression
 * @param includes comma separated globs a file must match, empty to include all files
 * @param excludes comma separated globs of files and folders to skip
 * @param excludeDerived whether derived resources (e.g. Maven {@code target} folders) are skipped
 */
public record SearchQuery(String text, boolean caseSensitive, boolean wholeWord, boolean regex, String includes,
		String excludes, boolean excludeDerived) {

	public SearchQuery {
		text = text == null ? "" : text;
		includes = includes == null ? "" : includes;
		excludes = excludes == null ? "" : excludes;
	}

	/**
	 * @return {@code true} if there is nothing to search for
	 */
	public boolean isEmpty() {
		return text.isEmpty();
	}

	/**
	 * Compiles the search text into a pattern honoring the case, whole word and regex options.
	 *
	 * @return the compiled pattern
	 * @throws PatternSyntaxException if {@code text} is an invalid regular expression
	 */
	public Pattern createPattern() {
		return SearchPatterns.create(text, caseSensitive, wholeWord, regex);
	}

	/**
	 * Tells whether a search for {@code other} only needs to look at the files in which this query found something,
	 * like when typing {@code sendSm} → {@code sendSms}: true if the text of {@code other} contains this text and
	 * nothing else changed. Whole words and regular expressions never qualify, there a longer text can match where a
	 * shorter one doesn't ({@code sms} isn't a whole word in {@code smsSender}, {@code smsSender} is).
	 *
	 * @param other the query typed after this one
	 * @return {@code true} if every match of {@code other} contains a match of this query
	 */
	public boolean isNarrowedBy(SearchQuery other) {
		if (isEmpty() || wholeWord || regex || other.wholeWord || other.regex || caseSensitive != other.caseSensitive
				|| excludeDerived != other.excludeDerived || !includes.equals(other.includes)
				|| !excludes.equals(other.excludes)) {
			return false;
		}
		// compares character by character like the case insensitive pattern does, unlike toLowerCase()
		for (int i = 0; i + text.length() <= other.text.length(); i++) {
			if (other.text.regionMatches(!caseSensitive, i, text, 0, text.length())) {
				return true;
			}
		}
		return false;
	}
}
