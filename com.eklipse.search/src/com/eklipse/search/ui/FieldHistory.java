package com.eklipse.search.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.jface.fieldassist.ContentProposalAdapter;
import org.eclipse.jface.fieldassist.SimpleContentProposalProvider;
import org.eclipse.jface.fieldassist.TextContentAdapter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The values recently entered in a text field, offered in a drop-down list: the arrow next to the field or
 * {@code ↓} in it opens it. A value is remembered when the field loses the focus or Enter is pressed, not every
 * state while typing. A {@link Text} with field assist rather than a {@code Combo}, which has no hint text and isn't
 * supported by the view's {@code TextActionHandler} (Copy and Delete would act on the results).
 */
final class FieldHistory {

	private static final int MAX_ENTRIES = 15;
	private static final String SEPARATOR = "\n";

	private final Text text;
	private final ToolItem dropDown;
	private final SimpleContentProposalProvider proposals = new SimpleContentProposalProvider();
	private final ContentProposalAdapter adapter;
	/** Newest first. */
	private final List<String> entries = new ArrayList<>();

	FieldHistory(Text text, ToolItem dropDown) {
		this.text = text;
		this.dropDown = dropDown;
		adapter = new ContentProposalAdapter(text, new TextContentAdapter(), proposals,
				KeyStroke.getInstance(SWT.ARROW_DOWN), null);
		adapter.setProposalAcceptanceStyle(ContentProposalAdapter.PROPOSAL_REPLACE);
		text.addListener(SWT.FocusOut, e -> remember());
		text.addListener(SWT.DefaultSelection, e -> remember());
		dropDown.addListener(SWT.Selection, e -> {
			text.setFocus();
			adapter.openProposalPopup();
		});
		update();
	}

	Text getText() {
		return text;
	}

	/**
	 * Adds the value of the field, as the newest one.
	 */
	void remember() {
		String value = text.getText().trim();
		if (!value.isEmpty()) {
			entries.remove(value);
			entries.add(0, value);
			if (entries.size() > MAX_ENTRIES) {
				entries.subList(MAX_ENTRIES, entries.size()).clear();
			}
			update();
		}
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
				if (!entry.isBlank() && entries.size() < MAX_ENTRIES) {
					entries.add(entry);
				}
			}
		}
		if (!entries.contains(text.getText().trim())) {
			remember();
		}
		update();
	}

	List<String> getEntries() {
		return List.copyOf(entries);
	}

	private void update() {
		proposals.setProposals(entries.toArray(String[]::new));
		// an empty list would only beep
		adapter.setEnabled(!entries.isEmpty());
		dropDown.setEnabled(!entries.isEmpty());
	}
}
