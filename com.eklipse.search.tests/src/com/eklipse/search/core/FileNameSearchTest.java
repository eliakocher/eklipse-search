package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class FileNameSearchTest {

	@Test
	void referencesAsTheyAreCopied() {
		assertEquals(new FileReference(List.of("com", "acme"), "Foo.java", false, 42),
				FileReference.parse("at java.base/com.acme.Foo$Inner.lambda$run$0(Foo.java:42)"));
		assertEquals(new FileReference(List.of("com", "acme"), "Foo.java", false, 7),
				FileReference.parse("com.acme.Foo.<init>(Foo.java:7)"));
		assertEquals(new FileReference(List.of("http:", "localhost:7777", "app"), "foo.ts", false, 12),
				FileReference.parse("at Foo.bar (http://localhost:7777/app/foo.ts:12:3)"));
		assertEquals(new FileReference(List.of("src"), "Foo.java", false, 42), FileReference.parse("src/Foo.java:42"));
		assertEquals(new FileReference(List.of("https:", "host", "src"), "Foo.java", false, 10),
				FileReference.parse("https://host/src/Foo.java?ref_type=heads#L10-L20"));
		assertEquals(new FileReference(List.of("com", "acme"), "Foo", true, 0),
				FileReference.parse("com.acme.Foo.Nested#run"));
		assertEquals(new FileReference(List.of("com", "acme"), "Foo", true, 0),
				FileReference.parse("com.acme.Foo.run(String, int)"));

		// searched in the content and the names as they are
		assertNull(FileReference.parse("Foo.java"));
		assertNull(FileReference.parse("readme.md"));
		assertNull(FileReference.parse("list.add(item)"));
		assertNull(FileReference.parse("sendSms"));
	}

	@Test
	void camelCaseAbbreviations() {
		String name = "AbstractDecoratedTextEditor.java";
		assertArrayEquals(new int[] { 0, 3, 8, 11, 17, 20 }, FileNameSearch.camelCaseMatch("AbsDecTex", name, false));
		assertArrayEquals(new int[] { 0, 1, 8, 9, 21, 22 }, FileNameSearch.camelCaseMatch("ADE", name, false),
				"words left out");
		assertArrayEquals(new int[] { 0, 3, 16, 19 }, FileNameSearch.camelCaseMatch("AbsDec",
				"AbstractDocumentDecorator.java", false), "the next word if one doesn't fit");
		assertArrayEquals(new int[] { 0, 1, 4, 5 }, FileNameSearch.camelCaseMatch("FB", "foo_bar.txt", false));
		assertNull(FileNameSearch.camelCaseMatch("DecTex", name, false), "not starting the name");
		assertNull(FileNameSearch.camelCaseMatch("ADX", name, false));
	}
}
