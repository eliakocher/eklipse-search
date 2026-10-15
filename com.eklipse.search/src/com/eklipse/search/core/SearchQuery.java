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
 * @param wildcards whether {@code *} in {@code text} matches any text, see {@link SearchPatterns#wildcardRegex}
 * @param fileNames whether the names of the files are searched too, not only their content
 */
public record SearchQuery(String text, boolean caseSensitive, boolean wholeWord, boolean regex, String includes,
		String excludes, boolean excludeDerived, boolean wildcards, boolean fileNames) {

	public SearchQuery {
		text = text == null ? "" : text;
		includes = includes == null ? "" : includes;
		excludes = excludes == null ? "" : excludes;
	}

	/**
	 * A query for the content only, without wildcards.
	 */
	public SearchQuery(String text, boolean caseSensitive, boolean wholeWord, boolean regex, String includes,
			String excludes, boolean excludeDerived) {
		this(text, caseSensitive, wholeWord, regex, includes, excludes, excludeDerived, false, false);
	}

	/**
	 * @return {@code true} if there is nothing to search for
	 */
	public boolean isEmpty() {
		return text.isEmpty();
	}

	/**
	 * Compiles the search text into a pattern honoring the case, whole word, regex and wildcard options.
	 *
	 * @return the compiled pattern
	 * @throws PatternSyntaxException if {@code text} is an invalid regular expression
	 */
	public Pattern createPattern() {
		return SearchPatterns.create(text, caseSensitive, wholeWord, regex, wildcards);
	}

	/**
	 * Tells whether a search for {@code other} only needs to look at the files in which this query found something,
	 * like when typing {@code sendSm} → {@code sendSms}: true if the text of {@code other} contains this text and
	 * nothing else changed. Whole words and regular expressions never qualify, there a longer text can match where a
	 * shorter one doesn't ({@code sms} isn't a whole word in {@code smsSender}, {@code smsSender} is). With wildcards a
	 * text of only stars is searched as it is, and a backslash may turn a star into a literal one, so these don't
	 * qualify either.
	 *
	 * @param other the query typed after this one
	 * @return {@code true} if every match of {@code other} contains a match of this query
	 */
	public boolean isNarrowedBy(SearchQuery other) {
		if (isEmpty() || wholeWord || regex || other.wholeWord || other.regex || caseSensitive != other.caseSensitive
				|| !hasSameScope(other) || wildcards != other.wildcards || fileNames != other.fileNames) {
			return false;
		}
		// a file found by its path needn't have found anything in the previous search, nor one found by a camel case
		// abbreviation unless only more was typed at its end: ACV matches AbstractColumnViewer, CV doesn't
		if (fileNames && (other.fileReference() != null
				|| other.isCamelCase() && !other.text.regionMatches(!caseSensitive, 0, text, 0, text.length()))) {
			return false;
		}
		if (wildcards && (text.replace("*", "").isEmpty() || text.indexOf('\\') >= 0
				|| other.text.indexOf('\\') >= 0)) {
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

	/**
	 * @return the file the text refers to, like a path or a stack trace line, {@code null} if it doesn't look like one
	 *         or isn't a plain text
	 */
	public FileReference fileReference() {
		return regex || wildcards && text.indexOf('*') >= 0 ? null : FileReference.parse(text);
	}

	/**
	 * Tells whether the text may be a camel case abbreviation of a file name, like {@code AbsDecTex} or {@code ADE} for
	 * {@code AbstractDecoratedTextEditor.java}, see {@link FileNameSearch#camelCaseMatch}.
	 *
	 * @return {@code true} for a text of letters and digits starting with an upper case letter, with another one
	 */
	public boolean isCamelCase() {
		if (regex || text.length() < 2 || !Character.isUpperCase(text.charAt(0))) {
			return false;
		}
		int upperCase = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (!Character.isLetterOrDigit(c)) {
				return false;
			}
			if (Character.isUpperCase(c)) {
				upperCase++;
			}
		}
		return upperCase >= 2;
	}

	/**
	 * @param other another query
	 * @return {@code true} if both queries look at the same files
	 */
	public boolean hasSameScope(SearchQuery other) {
		return includes.equals(other.includes) && excludes.equals(other.excludes)
				&& excludeDerived == other.excludeDerived;
	}
}
