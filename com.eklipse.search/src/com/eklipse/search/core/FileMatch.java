package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IAdaptable;

/**
 * The matches found in one file. Not thread safe, only modified in the UI thread.
 */
public final class FileMatch implements IAdaptable {

	private final IFile file;
	private final List<LineMatch> matches = new ArrayList<>();

	FileMatch(IFile file) {
		this.file = file;
	}

	public IFile getFile() {
		return file;
	}

	/**
	 * Adapts to what the file adapts to, so the contributions of Eclipse to the context menu of a file (Open With,
	 * Compare With, Team, Show In...) work on a result like on the file in the Project Explorer.
	 */
	@Override
	public <T> T getAdapter(Class<T> adapter) {
		return Adapters.adapt(file, adapter);
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
