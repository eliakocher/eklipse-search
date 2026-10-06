package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PreserveCaseTest {

	@Test
	void upperCase() {
		assertEquals("NEWNAME", PreserveCase.apply("OLD_NAME", "newName"));
	}

	@Test
	void lowerCase() {
		assertEquals("newname", PreserveCase.apply("oldname", "newName"));
	}

	@Test
	void capitalized() {
		assertEquals("NewName", PreserveCase.apply("OldName", "newName"));
	}

	@Test
	void camelCase() {
		assertEquals("newName", PreserveCase.apply("oldName", "NewName"));
	}

	@Test
	void noLetters() {
		assertEquals("newName", PreserveCase.apply("123", "newName"));
	}

	@Test
	void emptyValues() {
		assertEquals("", PreserveCase.apply("Foo", ""));
		assertEquals("bar", PreserveCase.apply("", "bar"));
	}
}
