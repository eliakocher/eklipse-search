package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;

/**
 * The matches of one search, grouped by file. Not thread safe, only modified in the UI thread.
 */
public final class SearchResult {

	private final Map<IFile, FileMatch> files = new LinkedHashMap<>();
	private int matchCount;

	/**
	 * @param file the file
	 * @return the matches of the file, {@code null} if it has none
	 */
	public FileMatch get(IFile file) {
		return files.get(file);
	}

	public Collection<FileMatch> getFiles() {
		return Collections.unmodifiableCollection(files.values());
	}

	public int getFileCount() {
		return files.size();
	}

	public int getMatchCount() {
		return matchCount;
	}

	/**
	 * Adds a match, creating its {@link FileMatch} if needed.
	 *
	 * @param match the match to add
	 * @return the file node the match was added to
	 */
	public FileMatch add(LineMatch match) {
		FileMatch fileMatch = files.computeIfAbsent(match.getFile(), FileMatch::new);
		fileMatch.add(match);
		matchCount++;
		return fileMatch;
	}

	/**
	 * Removes a single match. The file node is removed as well once it has no matches left.
	 *
	 * @param match the match to remove
	 * @return {@code true} if the file node was removed as a consequence
	 */
	public boolean remove(LineMatch match) {
		FileMatch fileMatch = files.get(match.getFile());
		if (fileMatch == null || !fileMatch.remove(match)) {
			return false;
		}
		matchCount--;
		if (fileMatch.getMatchCount() == 0) {
			files.remove(match.getFile());
			return true;
		}
		return false;
	}

	/**
	 * Removes a file node with all its matches.
	 *
	 * @param file the file to remove
	 * @return the removed node, {@code null} if the file had no matches
	 */
	public FileMatch remove(IFile file) {
		FileMatch removed = files.remove(file);
		if (removed != null) {
			matchCount -= removed.getMatchCount();
		}
		return removed;
	}

	/**
	 * @return all matches of all files
	 */
	public List<LineMatch> getAllMatches() {
		List<LineMatch> all = new ArrayList<>(matchCount);
		for (FileMatch fileMatch : files.values()) {
			all.addAll(fileMatch.getMatches());
		}
		return all;
	}
}
