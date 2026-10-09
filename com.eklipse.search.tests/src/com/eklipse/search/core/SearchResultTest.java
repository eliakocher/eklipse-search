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
		result.addNameMatch(name("BranchStateLabelUtil.java", 16, 20), 2);
		result.addNameMatch(name("UtilTest.java", 0, 4), 2);

		SearchResult.NameMatchChange change = result.addNameMatch(name("Util.java", 0, 4), 2);

		assertEquals("BranchStateLabelUtil.java", change.dropped().getFile().getName());
		assertNull(result.addNameMatch(name("MyUtil.java", 2, 6), 2).added(), "less relevant than the kept ones");
		assertEquals(List.of("Util.java", "UtilTest.java"), result.getNameMatches().stream()
				.sorted(FileNameMatch.BY_RELEVANCE).map(m -> m.getFile().getName()).toList());
	}

	private static FileNameMatch name(String fileName, int start, int end) {
		return new FileNameMatch(ResourcesPlugin.getWorkspace().getRoot().getFile(new Path("/project/src/" + fileName)),
				start, end);
	}
}
