package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	private static SearchQuery query(String text, boolean caseSensitive, boolean wholeWord) {
		return new SearchQuery(text, caseSensitive, wholeWord, false, "", "", true);
	}
}
