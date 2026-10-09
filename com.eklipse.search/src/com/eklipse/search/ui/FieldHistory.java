package com.eklipse.search.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The values recently entered in a field, newest first.
 */
abstract class FieldHistory {

	private static final String SEPARATOR = "\n";

	private final int maxEntries;
	/** Newest first. */
	private final List<String> entries = new ArrayList<>();

	/**
	 * @param maxEntries the number of values to remember at most
	 */
	FieldHistory(int maxEntries) {
		this.maxEntries = maxEntries;
	}

	/**
	 * @return the value of the field
	 */
	abstract String getValue();

	/**
	 * Called after the entries changed.
	 */
	abstract void entriesChanged();

	/**
	 * Adds the value of the field, as the newest one.
	 */
	void remember() {
		add(getValue(), false);
	}

	/**
	 * @param value the value to add as the newest one, moved there if it's already an entry
	 * @param replaceNewest whether it replaces the newest entry
	 */
	void add(String value, boolean replaceNewest) {
		value = value.trim();
		if (value.isEmpty() || entries.indexOf(value) == 0) {
			return;
		}
		if (replaceNewest && !entries.isEmpty()) {
			entries.remove(0);
		}
		entries.remove(value);
		entries.add(0, value);
		if (entries.size() > maxEntries) {
			entries.subList(maxEntries, entries.size()).clear();
		}
		entriesChanged();
	}

	/**
	 * @return the entries, for {@link #restore(String)}
	 */
	String save() {
		return String.join(SEPARATOR, entries);
	}

	/**
	 * @param saved what {@link #save()} returned, {@code null} for none; the value of the field is added if missing
	 */
	void restore(String saved) {
		entries.clear();
		if (saved != null) {
			for (String entry : saved.split(SEPARATOR)) {
				if (!entry.isBlank() && entries.size() < maxEntries) {
					entries.add(entry);
				}
			}
		}
		if (!entries.contains(getValue().trim())) {
			remember();
		}
		entriesChanged();
	}

	/**
	 * @return the entries, newest first
	 */
	List<String> getEntries() {
		return List.copyOf(entries);
	}
}
