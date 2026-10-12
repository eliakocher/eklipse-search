package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A file referred to the way developers copy it: a path ({@code src/main/java/com/acme/Foo.java}, also in a link), a
 * qualified class name ({@code com.acme.Foo}), a stack trace line ({@code at com.acme.Foo.run(Foo.java:42)}), or a
 * path or name with a line ({@code Foo.java:42}, {@code Foo.java#L42} in a GitLab or GitHub link).
 *
 * @param folders the folders the file is in as far as they're known, the innermost last
 * @param name the name of the file, without its extension for a class
 * @param isClass whether {@code name} is a class name, the file then has any extension
 * @param line the line referred to, starting at 1, 0 for none
 */
public record FileReference(List<String> folders, String name, boolean isClass, int line) {

	/**
	 * A Java stack trace line: an optional module or class loader ({@code java.base/}, {@code app//}), the package,
	 * the class, the method, the file and line, and the jar Logback may add.
	 */
	private static final Pattern STACK_FRAME = Pattern.compile("(?:at\\s+)?(?:\\S*/)?((?:[\\w$]+\\.)*)([\\w$]+)"
			+ "\\.(?:[\\w$]+|<c?init>)\\(([\\w$.-]+\\.\\w+)(?::(\\d+))?\\)(?:\\s+~?\\[.*])?");
	/** Any other stack trace line ending with the file in parentheses, like in JavaScript. */
	private static final Pattern FILE_IN_PARENTHESES = Pattern.compile(".*\\((\\S+\\.\\w+):(\\d+)(?::\\d+)?\\)");
	private static final Pattern WITH_LINE = Pattern.compile("(\\S+\\.\\w+)(?::(\\d+)(?::\\d+)?|#L(\\d+)(?:-L?\\d+)?)");
	/** A package with a class, maybe followed by a member as copied from Eclipse or IntelliJ. */
	private static final Pattern QUALIFIED_NAME = Pattern.compile("([a-z_][\\w$]*(?:\\.[\\w$]+)+)(?:#.*|\\(.*\\))?");

	/**
	 * @param text the search text
	 * @return the file the text refers to, {@code null} if it doesn't look like a reference to a file
	 */
	public static FileReference parse(String text) {
		String s = text.strip();
		Matcher frame = STACK_FRAME.matcher(s);
		if (frame.matches()) {
			return new FileReference(split(frame.group(1), "\\."), frame.group(3), false, line(frame.group(4)));
		}
		Matcher parentheses = FILE_IN_PARENTHESES.matcher(s);
		if (parentheses.matches()) {
			return path(parentheses.group(1), line(parentheses.group(2)));
		}
		// the query of a link isn't part of the path, its anchor may be the line
		if (s.contains("://")) {
			s = s.replaceFirst("\\?[^#]*", "");
		}
		Matcher withLine = WITH_LINE.matcher(s);
		if (withLine.matches()) {
			return path(withLine.group(1), line(withLine.group(2) != null ? withLine.group(2) : withLine.group(3)));
		}
		if (s.indexOf('/') >= 0 && !s.endsWith("/")) {
			return path(s, 0);
		}
		Matcher qualifiedName = QUALIFIED_NAME.matcher(s);
		if (qualifiedName.matches()) {
			List<String> segments = split(qualifiedName.group(1), "\\.");
			// packages are lower case, the first upper case segment is the class, a nested one is in its file
			for (int i = 1; i < segments.size(); i++) {
				String segment = segments.get(i);
				if (Character.isUpperCase(segment.charAt(0))) {
					int nested = segment.indexOf('$');
					return new FileReference(segments.subList(0, i), nested > 0 ? segment.substring(0, nested) : segment,
							true, 0);
				}
			}
		}
		return null;
	}

	/**
	 * @param fileName the name of a file
	 * @param caseSensitive whether upper and lower case must match exactly
	 * @return {@code true} if the file has the name referred to
	 */
	public boolean matchesName(String fileName, boolean caseSensitive) {
		if (!isClass) {
			return caseSensitive ? fileName.equals(name) : fileName.equalsIgnoreCase(name);
		}
		return fileName.length() > name.length() && fileName.charAt(name.length()) == '.'
				&& fileName.regionMatches(!caseSensitive, 0, name, 0, name.length())
				|| (caseSensitive ? fileName.equals(name) : fileName.equalsIgnoreCase(name));
	}

	private static FileReference path(String path, int line) {
		List<String> segments = split(path, "/");
		if (segments.isEmpty()) {
			return null;
		}
		return new FileReference(segments.subList(0, segments.size() - 1), segments.get(segments.size() - 1), false,
				line);
	}

	private static List<String> split(String text, String separator) {
		List<String> segments = new ArrayList<>();
		for (String segment : text.split(separator)) {
			if (!segment.isEmpty() && !segment.equals(".")) {
				segments.add(segment);
			}
		}
		return List.copyOf(segments);
	}

	private static int line(String digits) {
		try {
			return digits != null ? Integer.parseInt(digits) : 0;
		} catch (NumberFormatException e) {
			// too many digits for a line
			return 0;
		}
	}
}
