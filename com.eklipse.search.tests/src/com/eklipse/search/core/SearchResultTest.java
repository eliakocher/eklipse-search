package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.junit.jupiter.api.Test;

class SearchResultTest {

	@Test
	void keepsTheMostRelevantNameMatches() {
		SearchResult result = new SearchResult();
		// searching for "Util"
		result.addNameMatch(name("StringUtil.java", 6, 10), 2);
		result.addNameMatch(name("UtilTest.java", 0, 4), 2);

		SearchResult.NameMatchChange change = result.addNameMatch(name("Util.java", 0, 4), 2);

		assertEquals("StringUtil.java", change.dropped().getFile().getName());
		assertNull(result.addNameMatch(name("MyUtil.java", 2, 6), 2).added(), "less relevant than the kept ones");
		assertEquals(List.of("Util.java", "UtilTest.java"), result.getNameMatches().stream()
				.sorted(FileNameMatch.BY_RELEVANCE).map(m -> m.getFile().getName()).toList());
	}

	@Test
	void excludedNameMatchesAreKeptApart() {
		SearchResult result = new SearchResult();
		result.addNameMatch(name("Util.java", 0, 4), 1);
		// the limit is per kind: a name match doesn't push out an excluded one, nor the other way round
		FileNameMatch excluded = name("Util.js", 0, 4).asExcluded();
		assertEquals(excluded, result.addNameMatch(excluded, 1).added());

		assertEquals(1, result.getNameMatchCount());
		assertEquals(1, result.getExcludedCount());
		assertEquals(excluded, result.getNameMatch(excluded.getFile()));
		assertEquals(excluded, result.removeNameMatch(excluded.getFile()));
		assertEquals(0, result.getExcludedCount());

		SearchResult onlyExcluded = new SearchResult();
		onlyExcluded.addNameMatch(excluded, 1);
		assertEquals(false, onlyExcluded.isEmpty(), "the excluded files are results too");
	}

	private static FileNameMatch name(String fileName, int start, int end) {
		return new FileNameMatch(ResourcesPlugin.getWorkspace().getRoot().getFile(new Path("/project/src/" + fileName)),
				start, end);
	}
}
