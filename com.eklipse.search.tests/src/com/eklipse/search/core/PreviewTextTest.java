package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class PreviewTextTest {

	@Test
	void shortLinesStayAsTheyAre() {
		String content = "first\nsecond\r\nthird";
		PreviewText preview = PreviewText.create(content, 7, 10);
		assertSame(content, preview.text());
		assertEquals(7, preview.toPreviewOffset(7));
	}

	@Test
	void longLinesAreShortenedAroundTheMatch() {
		String line = "x".repeat(50) + "MATCH" + "y".repeat(50);
		String content = "before\r\n" + line + "\r\n" + "z".repeat(40) + "\nafter";
		int match = content.indexOf("MATCH");
		PreviewText preview = PreviewText.create(content, match, 30);
		assertEquals("before\r\n" + "…" + "x".repeat(10) + "MATCH" + "y".repeat(15) + "…\r\n" + "z".repeat(30)
				+ "…\nafter", preview.text());
		assertEquals("MATCH", preview.text().substring(preview.toPreviewOffset(match),
				preview.toPreviewOffset(match + 5)));
		assertEquals(preview.text().indexOf("after"), preview.toPreviewOffset(content.indexOf("after")));
		assertEquals(-1, preview.toPreviewOffset(match + 40), "cut away");
	}
}
