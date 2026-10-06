package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.MultiTextEdit;
import org.junit.jupiter.api.Test;

class ReplacerEditTest {

	private static List<LineMatch> find(Pattern pattern, String content) {
		List<LineMatch> matches = new ArrayList<>();
		Matcher matcher = pattern.matcher(content);
		while (matcher.find()) {
			matches.add(new LineMatch(null, matcher.start(), matcher.end() - matcher.start(), 1, matcher.group(), "",
					0, 0));
		}
		return matches;
	}

	private static String apply(String content, MultiTextEdit edit) throws Exception {
		Document document = new Document(content);
		edit.apply(document);
		return document.get();
	}

	@Test
	void literalReplace() throws Exception {
		Pattern pattern = SearchPatterns.create("foo", false, false, false);
		String content = "foo Foo FOO";
		MultiTextEdit edit = Replacer.createEdit(content, find(pattern, content), pattern, false, "bar", false);
		assertEquals("bar bar bar", apply(content, edit));
	}

	@Test
	void preserveCase() throws Exception {
		Pattern pattern = SearchPatterns.create("foo", false, false, false);
		String content = "foo Foo FOO";
		MultiTextEdit edit = Replacer.createEdit(content, find(pattern, content), pattern, false, "bar", true);
		assertEquals("bar Bar BAR", apply(content, edit));
	}

	@Test
	void literalReplacementIsNotATemplate() throws Exception {
		Pattern pattern = SearchPatterns.create("a", true, false, false);
		MultiTextEdit edit = Replacer.createEdit("a", find(pattern, "a"), pattern, false, "$1\\n", false);
		assertEquals("$1\\n", apply("a", edit));
	}

	@Test
	void regexGroupsAndLookBehinds() throws Exception {
		Pattern pattern = SearchPatterns.create("(?<=get)(\\w+)\\(\\)", true, false, true);
		String content = "getName() setName() getAge()";
		MultiTextEdit edit = Replacer.createEdit(content, find(pattern, content), pattern, true, "$1Value()", false);
		assertEquals("getNameValue() setName() getAgeValue()", apply(content, edit));
	}

	@Test
	void onlyTheGivenMatchesAreReplaced() throws Exception {
		Pattern pattern = SearchPatterns.create("x", true, false, false);
		String content = "x x x";
		List<LineMatch> matches = find(pattern, content);
		matches.remove(1);
		MultiTextEdit edit = Replacer.createEdit(content, matches, pattern, false, "y", false);
		assertEquals("y x y", apply(content, edit));
	}

	@Test
	void changedContentIsDetected() {
		Pattern pattern = SearchPatterns.create("foo", true, false, false);
		List<LineMatch> matches = find(pattern, "a foo");
		assertNotNull(Replacer.createEdit("a foo", matches, pattern, false, "bar", false));
		assertNull(Replacer.createEdit("ab foo", matches, pattern, false, "bar", false));
		assertNull(Replacer.createEdit("a", matches, pattern, false, "bar", false));
	}

	@Test
	void changedRegexMatchIsDetected() {
		Pattern pattern = SearchPatterns.create("fo+", true, false, true);
		List<LineMatch> matches = find(pattern, "foo");
		// same text at the same offset, but the regex now matches a longer region
		assertNull(Replacer.createEdit("fooo", matches, pattern, true, "x", false));
	}
}
