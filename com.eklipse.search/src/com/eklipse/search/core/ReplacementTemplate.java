package com.eklipse.search.core;

import java.util.regex.Matcher;

/**
 * Expands a regex replacement string the way VS Code does.
 * <p>
 * Supported: {@code $0} / {@code $&} (whole match), {@code $1}..{@code $99}, {@code ${name}} (named group),
 * {@code $$} (a dollar sign), {@code \n}, {@code \r}, {@code \t}, {@code \\} and {@code \$}. Anything else is
 * copied literally, e.g. {@code $9} when there are fewer than nine groups.
 */
public final class ReplacementTemplate {

	private ReplacementTemplate() {
	}

	/**
	 * @param template the replacement as typed by the user
	 * @param matcher a matcher positioned on the match being replaced
	 * @return the replacement text for this match
	 */
	public static String expand(String template, Matcher matcher) {
		StringBuilder out = new StringBuilder(template.length() + 16);
		int n = template.length();
		for (int i = 0; i < n; i++) {
			char c = template.charAt(i);
			if (c == '\\' && i + 1 < n) {
				char next = template.charAt(++i);
				switch (next) {
					case 'n' -> out.append('\n');
					case 'r' -> out.append('\r');
					case 't' -> out.append('\t');
					case '\\' -> out.append('\\');
					case '$' -> out.append('$');
					default -> out.append('\\').append(next);
				}
			} else if (c == '$' && i + 1 < n) {
				i = appendReference(template, i, matcher, out);
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/**
	 * Appends the group referenced at {@code template[dollar]}.
	 *
	 * @return the index of the last consumed character
	 */
	private static int appendReference(String template, int dollar, Matcher matcher, StringBuilder out) {
		char next = template.charAt(dollar + 1);
		if (next == '$') {
			out.append('$');
			return dollar + 1;
		}
		if (next == '&') {
			out.append(matcher.group());
			return dollar + 1;
		}
		if (isDigit(next)) {
			int group = next - '0';
			int end = dollar + 2;
			if (end < template.length() && isDigit(template.charAt(end))) {
				int twoDigits = group * 10 + template.charAt(end) - '0';
				if (twoDigits <= matcher.groupCount()) {
					group = twoDigits;
					end++;
				}
			}
			if (group <= matcher.groupCount()) {
				appendGroup(out, matcher.group(group));
				return end - 1;
			}
		} else if (next == '{') {
			int close = template.indexOf('}', dollar + 2);
			if (close > 0) {
				String name = template.substring(dollar + 2, close);
				try {
					appendGroup(out, isNumber(name) ? matcher.group(Integer.parseInt(name)) : matcher.group(name));
					return close;
				} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
					// unknown group: keep the text literally
				}
			}
		}
		out.append('$');
		return dollar;
	}

	private static void appendGroup(StringBuilder out, String value) {
		if (value != null) {
			out.append(value);
		}
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isNumber(String text) {
		if (text.isEmpty()) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			if (!isDigit(text.charAt(i))) {
				return false;
			}
		}
		return true;
	}
}
