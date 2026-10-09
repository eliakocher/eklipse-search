package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;

class SearchQueryTest {

	@Test
	void typingMoreNarrows() {
		assertTrue(query("sendSm", false, false).isNarrowedBy(query("sendSms", false, false)));
		assertTrue(query("SMS", false, false).isNarrowedBy(query("sendSms", false, false)), "ignoring the case");
	}

	@Test
	void otherChangesDont() {
		assertFalse(query("sendSms", false, false).isNarrowedBy(query("sendSm", false, false)), "deleting");
		assertFalse(query("SMS", true, false).isNarrowedBy(query("sendSms", true, false)), "matching the case");
		// "sms" isn't a whole word in "smsSender", but "smsSender" is
		assertFalse(query("sms", false, true).isNarrowedBy(query("smsSender", false, true)), "whole words");
		assertFalse(query("sms", false, false).isNarrowedBy(new SearchQuery("smsS", false, false, false, "*.java", "",
				true)), "other files");
	}

	@Test
	void wildcardsMatchAnyTextWithinALine() {
		Matcher matcher = wildcards("final*size").createPattern().matcher("final int size = maxSize;\nfinal\nsize");
		assertTrue(matcher.find());
		assertEquals("final int size", matcher.group(), "as little as possible");
		assertFalse(matcher.find(), "not across lines");

		Matcher star = wildcards("*2\\*3*").createPattern().matcher("23 2x3 2*3");
		assertTrue(star.find());
		assertEquals("2*3", star.group(), "an escaped star, the outer ones left out");

		assertTrue(wildcards("final*").isNarrowedBy(wildcards("final*size")));
		assertFalse(wildcards("*").isNarrowedBy(wildcards("*size")), "a lone star is searched as it is");
	}

	private static SearchQuery wildcards(String text) {
		return new SearchQuery(text, false, false, false, "", "", true, true, false);
	}

	private static SearchQuery query(String text, boolean caseSensitive, boolean wholeWord) {
		return new SearchQuery(text, caseSensitive, wholeWord, false, "", "", true);
	}
}
