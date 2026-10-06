package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IFile;

/**
 * The matches found in one file. Not thread safe, only modified in the UI thread.
 */
public final class FileMatch {

	private final IFile file;
	private final List<LineMatch> matches = new ArrayList<>();

	FileMatch(IFile file) {
		this.file = file;
	}

	public IFile getFile() {
		return file;
	}

	/**
	 * @return the matches, in the order they were added
	 */
	public List<LineMatch> getMatches() {
		return Collections.unmodifiableList(matches);
	}

	public int getMatchCount() {
		return matches.size();
	}

	void add(LineMatch match) {
		matches.add(match);
	}

	boolean remove(LineMatch match) {
		return matches.remove(match);
	}

	@Override
	public String toString() {
		return file.getFullPath() + " (" + matches.size() + ")";
	}
}
