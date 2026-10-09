package com.eklipse.search.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Combo;

/**
 * The values recently entered in a combo box, offered in its drop-down list. A value is remembered when the field loses
 * the focus or Enter is pressed, not every state while typing.
 */
final class ComboHistory extends FieldHistory {

	static final int MAX_ENTRIES = 15;

	private final Combo combo;

	ComboHistory(Combo combo) {
		super(MAX_ENTRIES);
		this.combo = combo;
		combo.setVisibleItemCount(MAX_ENTRIES);
		combo.addListener(SWT.FocusOut, e -> remember());
		combo.addListener(SWT.DefaultSelection, e -> remember());
	}

	Combo getCombo() {
		return combo;
	}

	@Override
	String getValue() {
		return combo.getText();
	}

	@Override
	void entriesChanged() {
		// replacing the items clears the text on Windows
		String text = combo.getText();
		Point selection = combo.getSelection();
		combo.setItems(getEntries().toArray(String[]::new));
		if (!combo.getText().equals(text)) {
			combo.setText(text);
			combo.setSelection(selection);
		}
	}
}
