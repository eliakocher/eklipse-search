package com.eklipse.search.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;

import com.eklipse.search.core.FileMatch;
import com.eklipse.search.core.FileNameMatch;
import com.eklipse.search.core.LineMatch;
import com.eklipse.search.core.SearchResult;

/**
 * The files found by their name and the files with matches in their content on the first level, the matches below the
 * latter. The files found by their name that the include and exclude globs leave out are below the {@link ExcludedFiles}
 * row, the last one.
 */
final class ResultContentProvider implements ITreeContentProvider {

	private static final Object[] NONE = new Object[0];

	private SearchResult result;
	private ExcludedFiles excludedFiles;

	@Override
	public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		result = newInput instanceof SearchResult searchResult ? searchResult : null;
		excludedFiles = result != null ? new ExcludedFiles(result) : null;
	}

	/**
	 * @return the row of the excluded files of the current result, {@code null} without one
	 */
	ExcludedFiles excludedFiles() {
		return excludedFiles;
	}

	@Override
	public Object[] getElements(Object input) {
		if (!(input instanceof SearchResult searchResult)) {
			return NONE;
		}
		List<Object> elements = new ArrayList<>(searchResult.getNameMatches());
		elements.addAll(searchResult.getFiles());
		if (searchResult.getExcludedCount() > 0) {
			elements.add(excludedFiles);
		}
		return elements.toArray();
	}

	@Override
	public Object[] getChildren(Object parent) {
		if (parent instanceof ExcludedFiles excluded) {
			return excluded.result().getExcludedMatches().toArray();
		}
		return parent instanceof FileMatch fileMatch ? fileMatch.getMatches().toArray() : NONE;
	}

	@Override
	public Object getParent(Object element) {
		if (element instanceof FileNameMatch nameMatch && nameMatch.isExcluded()) {
			return excludedFiles;
		}
		return element instanceof LineMatch match && result != null ? result.get(match.getFile()) : null;
	}

	@Override
	public boolean hasChildren(Object element) {
		return element instanceof FileMatch fileMatch && fileMatch.getMatchCount() > 0
				|| element instanceof ExcludedFiles excluded && excluded.count() > 0;
	}
}
