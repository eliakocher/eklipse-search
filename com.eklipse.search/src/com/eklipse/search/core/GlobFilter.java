package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A list of VS Code style globs (e.g. {@code *.java, src/**, node_modules}) matched against workspace relative
 * paths such as {@code my-project/src/main/java/Foo.java}.
 * <p>
 * Semantics:
 * <ul>
 * <li>Patterns are separated by commas (commas inside {@code {a,b}} are kept).</li>
 * <li>A pattern matches anywhere in the path unless it starts with {@code /}, which anchors it at the workspace
 * root (the first segment is the project name).</li>
 * <li>A pattern matching a folder also matches everything below it, so {@code node_modules} excludes the whole
 * folder.</li>
 * <li>Matching is case insensitive.</li>
 * </ul>
 */
public final class GlobFilter {

	private static final GlobFilter EMPTY = new GlobFilter(List.of());
	private static final String REGEX_SPECIAL_CHARS = "\\.[]{}()<>*+-=!?^$|";

	private final List<Pattern> patterns;

	private GlobFilter(List<Pattern> patterns) {
		this.patterns = patterns;
	}

	/**
	 * Parses a comma separated list of globs.
	 *
	 * @param spec the globs as typed by the user, may be {@code null}
	 * @return the filter, empty if {@code spec} contains no globs
	 */
	public static GlobFilter parse(String spec) {
		if (spec == null || spec.isBlank()) {
			return EMPTY;
		}
		List<Pattern> patterns = new ArrayList<>();
		for (String glob : split(spec)) {
			patterns.add(Pattern.compile(toRegex(glob), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
		}
		return new GlobFilter(List.copyOf(patterns));
	}

	/**
	 * @return {@code true} if this filter contains no globs
	 */
	public boolean isEmpty() {
		return patterns.isEmpty();
	}

	/**
	 * @param path a workspace relative path using {@code /} as separator, without leading slash
	 * @return {@code true} if any glob matches the path or one of its parent folders
	 */
	public boolean matches(String path) {
		for (Pattern pattern : patterns) {
			if (pattern.matcher(path).matches()) {
				return true;
			}
		}
		return false;
	}

	static List<String> split(String spec) {
		List<String> globs = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int braceDepth = 0;
		for (int i = 0; i < spec.length(); i++) {
			char c = spec.charAt(i);
			if (c == '{') {
				braceDepth++;
			} else if (c == '}' && braceDepth > 0) {
				braceDepth--;
			} else if (c == ',' && braceDepth == 0) {
				addGlob(globs, current);
				continue;
			}
			current.append(c);
		}
		addGlob(globs, current);
		return globs;
	}

	private static void addGlob(List<String> globs, StringBuilder current) {
		String glob = current.toString().trim();
		if (!glob.isEmpty()) {
			globs.add(glob);
		}
		current.setLength(0);
	}

	static String toRegex(String glob) {
		String g = glob.trim().replace('\\', '/');
		boolean anchored = g.startsWith("/");
		while (g.startsWith("/")) {
			g = g.substring(1);
		}
		if (g.startsWith("./")) {
			g = g.substring(2);
		}
		while (g.endsWith("/")) {
			g = g.substring(0, g.length() - 1);
		}
		StringBuilder regex = new StringBuilder();
		if (!anchored && !g.startsWith("**")) {
			regex.append("(?:.*/)?");
		}
		appendGlob(g, regex);
		// a matching folder includes everything below it
		regex.append("(?:/.*)?");
		return regex.toString();
	}

	private static void appendGlob(String glob, StringBuilder regex) {
		int braceDepth = 0;
		int n = glob.length();
		for (int i = 0; i < n; i++) {
			char c = glob.charAt(i);
			switch (c) {
				case '*' -> {
					if (i + 1 < n && glob.charAt(i + 1) == '*') {
						i++;
						if (i + 1 < n && glob.charAt(i + 1) == '/') {
							i++;
							regex.append("(?:.*/)?");
						} else {
							regex.append(".*");
						}
					} else {
						regex.append("[^/]*");
					}
				}
				case '?' -> regex.append("[^/]");
				case '{' -> {
					braceDepth++;
					regex.append("(?:");
				}
				case '}' -> {
					if (braceDepth > 0) {
						braceDepth--;
						regex.append(')');
					} else {
						regex.append("\\}");
					}
				}
				case ',' -> regex.append(braceDepth > 0 ? "|" : ",");
				case '[' -> i = appendCharClass(glob, i, regex);
				default -> {
					if (REGEX_SPECIAL_CHARS.indexOf(c) >= 0) {
						regex.append('\\');
					}
					regex.append(c);
				}
			}
		}
		// tolerate unbalanced braces
		for (; braceDepth > 0; braceDepth--) {
			regex.append(')');
		}
	}

	private static int appendCharClass(String glob, int start, StringBuilder regex) {
		int close = glob.indexOf(']', start + 1);
		if (close < 0) {
			regex.append("\\[");
			return start;
		}
		regex.append('[');
		int i = start + 1;
		if (i < close && (glob.charAt(i) == '!' || glob.charAt(i) == '^')) {
			regex.append('^');
			i++;
		}
		for (; i < close; i++) {
			char c = glob.charAt(i);
			if (c == '\\' || c == '[' || c == '&') {
				regex.append('\\');
			}
			regex.append(c);
		}
		regex.append(']');
		return close;
	}
}
