package com.eklipse.search.ui;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Text;

/**
 * The recent searches, browsed in the search field like in a terminal: {@link #showOlder()} goes back,
 * {@link #showNewer()} forward again and finally to the text typed before.
 * <p>
 * A search is remembered once it's done. The searches made while typing don't pile up: one that continues the newest
 * entry, e.g. "Branch" after "Bra", replaces it, unless its results were looked at for a while or the field was left
 * since.
 */
final class SearchHistory extends FieldHistory {

	static final int MAX_ENTRIES = 10;
	/** Typing on later than this after the results came keeps the search they were for. */
	private static final long LOOKED_AT_MS = 2_000;

	private final Text text;
	/** The entry shown, -1 while the field shows what was typed. */
	private int index = -1;
	/** What was typed before browsing. */
	private String typed = "";
	private boolean showing;
	/** Whether the newest entry is a search made while typing, which the next one may replace. */
	private boolean typing;
	private long searchedAt;
	private boolean editedSinceSearch;

	SearchHistory(Text text) {
		super(MAX_ENTRIES);
		this.text = text;
		text.addModifyListener(e -> {
			if (showing) {
				return;
			}
			// typing stops browsing, the next older search is the newest again
			index = -1;
			if (!editedSinceSearch) {
				editedSinceSearch = true;
				typing &= System.currentTimeMillis() - searchedAt < LOOKED_AT_MS;
			}
		});
		text.addListener(SWT.FocusOut, e -> commit());
		text.addListener(SWT.DefaultSelection, e -> commit());
	}

	@Override
	String getValue() {
		return text.getText();
	}

	@Override
	void entriesChanged() {
		index = -1;
	}

	/**
	 * @param search the text of a search that is done
	 */
	void searched(String search) {
		String value = search.trim();
		List<String> entries = getEntries();
		// a search from the history is already in it
		if (index != -1 || value.isEmpty() || !entries.isEmpty() && entries.get(0).equals(value)) {
			return;
		}
		String newest = entries.isEmpty() ? "" : entries.get(0);
		add(value, typing && (value.startsWith(newest) || newest.startsWith(value)));
		typing = true;
		searchedAt = System.currentTimeMillis();
		editedSinceSearch = false;
	}

	/**
	 * Ends a search: the field was left, Enter pressed or the search cleared. The text in the field is the newest
	 * entry, e.g. a search from the history, the next search is a new one.
	 */
	void commit() {
		remember();
		index = -1;
		typing = false;
	}

	/**
	 * Shows the search before the one shown. The typed text is skipped where it's an entry, e.g. the search just made.
	 *
	 * @return {@code false} if there is no older search
	 */
	boolean showOlder() {
		if (index == -1) {
			typed = text.getText();
		}
		List<String> entries = getEntries();
		int older = index + 1;
		while (older < entries.size() && entries.get(older).equals(typed.trim())) {
			older++;
		}
		if (older >= entries.size()) {
			return false;
		}
		typing = false;
		show(older, entries.get(older));
		return true;
	}

	/**
	 * Shows the search after the one shown, or the typed text after the newest one.
	 *
	 * @return {@code false} if the field already shows the typed text
	 */
	boolean showNewer() {
		if (index == -1) {
			return false;
		}
		List<String> entries = getEntries();
		int newer = index - 1;
		while (newer >= 0 && entries.get(newer).equals(typed.trim())) {
			newer--;
		}
		show(newer, newer == -1 ? typed : entries.get(newer));
		return true;
	}

	private void show(int entry, String value) {
		index = entry;
		showing = true;
		try {
			text.setText(value);
		} finally {
			showing = false;
		}
		text.setSelection(value.length());
	}
}
