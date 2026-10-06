package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GlobFilterTest {

	@Test
	void extensionMatchesInAnyFolder() {
		GlobFilter filter = GlobFilter.parse("*.java");
		assertTrue(filter.matches("project/src/main/java/Foo.java"));
		assertTrue(filter.matches("project/Foo.java"));
		assertFalse(filter.matches("project/src/Foo.txt"));
	}

	@Test
	void folderNameMatchesTheFolderAndEverythingBelow() {
		GlobFilter filter = GlobFilter.parse("node_modules");
		assertTrue(filter.matches("project/web/node_modules"));
		assertTrue(filter.matches("project/web/node_modules/lib/index.js"));
		assertFalse(filter.matches("project/web/node_modules2/index.js"));
		assertFalse(filter.matches("project/web/my_node_modules/index.js"));
	}

	@Test
	void pathPatternMatchesBelowAnyProject() {
		GlobFilter filter = GlobFilter.parse("src/main/**");
		assertTrue(filter.matches("project/src/main/java/Foo.java"));
		assertTrue(filter.matches("parent/module/src/main/Foo.java"));
		assertFalse(filter.matches("project/src/test/java/FooTest.java"));
	}

	@Test
	void doubleStarMatchesAnyDepth() {
		GlobFilter filter = GlobFilter.parse("**/test/**/*.java");
		assertTrue(filter.matches("project/src/test/FooTest.java"));
		assertTrue(filter.matches("project/src/test/java/com/FooTest.java"));
		assertFalse(filter.matches("project/src/main/java/Foo.java"));
	}

	@Test
	void leadingSlashAnchorsAtWorkspaceRoot() {
		GlobFilter filter = GlobFilter.parse("/project/src");
		assertTrue(filter.matches("project/src/Foo.java"));
		assertFalse(filter.matches("other/project/src/Foo.java"));
	}

	@Test
	void bracesQuestionMarksAndCharacterClasses() {
		assertTrue(GlobFilter.parse("*.{js,ts}").matches("p/a.ts"));
		assertTrue(GlobFilter.parse("*.{js,ts}").matches("p/a.js"));
		assertFalse(GlobFilter.parse("*.{js,ts}").matches("p/a.tsx"));
		assertTrue(GlobFilter.parse("Foo?.java").matches("p/Foo1.java"));
		assertFalse(GlobFilter.parse("Foo?.java").matches("p/Foo12.java"));
		assertTrue(GlobFilter.parse("[ab].txt").matches("p/a.txt"));
		assertFalse(GlobFilter.parse("[ab].txt").matches("p/c.txt"));
		assertTrue(GlobFilter.parse("[!ab].txt").matches("p/c.txt"));
	}

	@Test
	void matchingIsCaseInsensitive() {
		assertTrue(GlobFilter.parse("*.JAVA").matches("p/Foo.java"));
	}

	@Test
	void regexCharactersAreLiteral() {
		assertTrue(GlobFilter.parse("a+b(1).txt").matches("p/a+b(1).txt"));
		assertFalse(GlobFilter.parse("a.txt").matches("p/abtxt"));
	}

	@Test
	void splitsOnCommasOutsideBraces() {
		assertEquals(List.of("*.java", "*.{js,ts}", "target"), GlobFilter.split(" *.java,*.{js,ts} , target,, "));
	}

	@Test
	void emptySpecIsEmpty() {
		assertTrue(GlobFilter.parse("").isEmpty());
		assertTrue(GlobFilter.parse(" , ").isEmpty());
		assertFalse(GlobFilter.parse("").matches("p/a.txt"));
	}
}
