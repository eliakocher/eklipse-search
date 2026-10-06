package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ReplacementTemplateTest {

	private static String expand(String regex, String input, String template) {
		Matcher matcher = Pattern.compile(regex).matcher(input);
		assertTrue(matcher.find());
		return ReplacementTemplate.expand(template, matcher);
	}

	@Test
	void numberedGroups() {
		assertEquals("b-a", expand("(a)(b)", "ab", "$2-$1"));
	}

	@Test
	void wholeMatch() {
		assertEquals("[ab]{ab}", expand("ab", "ab", "[$0]{$&}"));
	}

	@Test
	void namedGroups() {
		assertEquals("key=value", expand("(?<k>\\w+):(?<v>\\w+)", "key:value", "${k}=${v}"));
		assertEquals("key", expand("(\\w+):", "key:", "${1}"));
	}

	@Test
	void escapes() {
		assertEquals("a\nb\tc\\d$e", expand("x", "x", "a\\nb\\tc\\\\d\\$e"));
		assertEquals("$1", expand("(x)", "x", "$$1"));
		assertEquals("\\d", expand("x", "x", "\\d"));
	}

	@Test
	void unknownGroupsStayLiteral() {
		assertEquals("$9", expand("(a)", "a", "$9"));
		assertEquals("${nope}", expand("(a)", "a", "${nope}"));
		assertEquals("a$", expand("(a)", "a", "$1$"));
	}

	@Test
	void twoDigitGroupOnlyIfItExists() {
		assertEquals("a2", expand("(a)", "a", "$12"));
	}

	@Test
	void unmatchedOptionalGroupIsEmpty() {
		assertEquals("[]", expand("a(b)?", "a", "[$1]"));
	}
}
