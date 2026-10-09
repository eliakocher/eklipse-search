package com.eklipse.search.ui;

import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;

import com.eklipse.search.core.FileMatch;
import com.eklipse.search.core.FileNameMatch;
import com.eklipse.search.core.LineMatch;

/**
 * Sorts the files found by their name first, most relevant on top, then the other files by path, and matches by
 * position. The search runs in parallel, so without sorting the order would change from one search to the next.
 */
final class ResultComparator extends ViewerComparator {

	@Override
	public int category(Object element) {
		return element instanceof FileNameMatch ? 0 : 1;
	}

	@Override
	public int compare(Viewer viewer, Object e1, Object e2) {
		int category = Integer.compare(category(e1), category(e2));
		if (category != 0) {
			return category;
		}
		if (e1 instanceof FileMatch f1 && e2 instanceof FileMatch f2) {
			return String.CASE_INSENSITIVE_ORDER.compare(f1.getFile().getFullPath().toString(),
					f2.getFile().getFullPath().toString());
		}
		if (e1 instanceof FileNameMatch n1 && e2 instanceof FileNameMatch n2) {
			return FileNameMatch.BY_RELEVANCE.compare(n1, n2);
		}
		if (e1 instanceof LineMatch m1 && e2 instanceof LineMatch m2) {
			return Integer.compare(m1.getOffset(), m2.getOffset());
		}
		return 0;
	}
}
