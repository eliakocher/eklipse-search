package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;

/**
 * The matches of one search, grouped by file, and the files found by their name. Not thread safe, only modified in the
 * UI thread.
 */
public final class SearchResult {

	private final Map<IFile, FileMatch> files = new LinkedHashMap<>();
	private final Map<IFile, FileNameMatch> names = new LinkedHashMap<>();
	private int matchCount;

	/**
	 * What {@link SearchResult#addNameMatch} changed.
	 *
	 * @param added the name match added, {@code null} if it wasn't relevant enough
	 * @param dropped the name match removed to make room, {@code null} if none had to
	 */
	public record NameMatchChange(FileNameMatch added, FileNameMatch dropped) {
	}

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
	 * @return the files found by their name, in no particular order
	 */
	public Collection<FileNameMatch> getNameMatches() {
		return Collections.unmodifiableCollection(names.values());
	}

	/**
	 * @param file the file
	 * @return the match of the file's name, {@code null} if the file wasn't found by its name
	 */
	public FileNameMatch getNameMatch(IFile file) {
		return names.get(file);
	}

	public int getNameMatchCount() {
		return names.size();
	}

	/**
	 * @return {@code true} if neither the content nor the name of any file matched
	 */
	public boolean isEmpty() {
		return files.isEmpty() && names.isEmpty();
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
	 * Adds a file found by its name. Only the {@code max} most relevant ones are kept, see
	 * {@link FileNameMatch#BY_RELEVANCE}, so a short search text doesn't bury the matches in the content under file
	 * names.
	 *
	 * @param match the file found by its name
	 * @param max the number of name matches to keep at most
	 * @return what changed
	 */
	public NameMatchChange addNameMatch(FileNameMatch match, int max) {
		if (names.containsKey(match.getFile())) {
			return new NameMatchChange(null, null);
		}
		FileNameMatch dropped = null;
		if (names.size() >= max) {
			FileNameMatch least = Collections.max(names.values(), FileNameMatch.BY_RELEVANCE);
			if (FileNameMatch.BY_RELEVANCE.compare(match, least) >= 0) {
				return new NameMatchChange(null, null);
			}
			dropped = names.remove(least.getFile());
		}
		names.put(match.getFile(), match);
		return new NameMatchChange(match, dropped);
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
	 * Removes a file node with all its matches, a match of the file's name stays.
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
	 * @param file the file whose name match to remove
	 * @return the removed name match, {@code null} if the file wasn't found by its name
	 */
	public FileNameMatch removeNameMatch(IFile file) {
		return names.remove(file);
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
