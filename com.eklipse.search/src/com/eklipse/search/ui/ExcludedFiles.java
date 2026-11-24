package com.eklipse.search.ui;

import com.eklipse.search.core.SearchResult;

/**
 * The row at the bottom of the results that groups the files found by their name that the include and exclude globs
 * leave out.
 *
 * @param result the result whose excluded files the row groups
 */
record ExcludedFiles(SearchResult result) {

	static final String LABEL = "Excluded files";

	/**
	 * @return how many files the row groups
	 */
	int count() {
		return result.getExcludedCount();
	}
}
