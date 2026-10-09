package com.eklipse.search.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;

import com.eklipse.search.core.FileMatch;
import com.eklipse.search.core.LineMatch;
import com.eklipse.search.core.SearchResult;

/**
 * The files found by their name and the files with matches in their content on the first level, the matches below the
 * latter.
 */
final class ResultContentProvider implements ITreeContentProvider {

	private static final Object[] NONE = new Object[0];

	private SearchResult result;

	@Override
	public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		result = newInput instanceof SearchResult searchResult ? searchResult : null;
	}

	@Override
	public Object[] getElements(Object input) {
		if (!(input instanceof SearchResult searchResult)) {
			return NONE;
		}
		List<Object> elements = new ArrayList<>(searchResult.getNameMatches());
		elements.addAll(searchResult.getFiles());
		return elements.toArray();
	}

	@Override
	public Object[] getChildren(Object parent) {
		return parent instanceof FileMatch fileMatch ? fileMatch.getMatches().toArray() : NONE;
	}

	@Override
	public Object getParent(Object element) {
		return element instanceof LineMatch match && result != null ? result.get(match.getFile()) : null;
	}

	@Override
	public boolean hasChildren(Object element) {
		return element instanceof FileMatch fileMatch && fileMatch.getMatchCount() > 0;
	}
}
