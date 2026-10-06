package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.junit.jupiter.api.Test;

class SearchPatternsTest {

	@Test
	void literalTextIsNotARegex() {
		Pattern pattern = SearchPatterns.create("a.b(", true, false, false);
		assertTrue(pattern.matcher("x a.b( y").find());
		assertFalse(pattern.matcher("axb(").find());
	}

	@Test
	void caseSensitivity() {
		assertFalse(SearchPatterns.create("Foo", true, false, false).matcher("foo").find());
		assertTrue(SearchPatterns.create("Foo", false, false, false).matcher("FOO").find());
		assertTrue(SearchPatterns.create("ä", false, false, false).matcher("Ä").find());
	}

	@Test
	void wholeWordRejectsWordCharactersAround() {
		Pattern pattern = SearchPatterns.create("foo", false, true, false);
		assertTrue(pattern.matcher("a foo b").find());
		assertTrue(pattern.matcher("foo.bar").find());
		assertTrue(pattern.matcher("(foo)").find());
		assertFalse(pattern.matcher("foobar").find());
		assertFalse(pattern.matcher("foo_bar").find());
		assertFalse(pattern.matcher("myfoo").find());
	}

	@Test
	void wholeWordWorksWithNonWordCharactersInTheQuery() {
		Pattern pattern = SearchPatterns.create("foo.", false, true, false);
		assertTrue(pattern.matcher("x foo. y").find());
		assertFalse(pattern.matcher("foo.bar").find());
	}

	@Test
	void wholeWordWorksWithRegexAlternatives() {
		Pattern pattern = SearchPatterns.create("foo|bar", false, true, true);
		Matcher matcher = pattern.matcher("foobar bar");
		assertTrue(matcher.find());
		assertEquals(7, matcher.start());
	}

	@Test
	void regexAnchorsWorkPerLine() {
		Pattern pattern = SearchPatterns.create("^end$", true, false, true);
		assertTrue(pattern.matcher("start\nend\nmore").find());
	}

	@Test
	void invalidRegexThrows() {
		assertThrows(PatternSyntaxException.class, () -> SearchPatterns.create("(", true, false, true));
	}

	@Test
	void escapeRegexQuotesMetaCharacters() {
		String escaped = SearchPatterns.escapeRegex("a.b(c)*");
		assertEquals("a\\.b\\(c\\)\\*", escaped);
		assertTrue(Pattern.compile(escaped).matcher("a.b(c)*").matches());
	}
}
