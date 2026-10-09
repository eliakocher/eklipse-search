package com.eklipse.search.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.actions.ActionFactory;

/**
 * Cut, Copy, Paste, Delete and Select All act on the field that has the focus, and run the view's own actions while
 * none has. Like {@link org.eclipse.ui.actions.TextActionHandler}, which only knows {@link Text}: in a {@link Combo}
 * Copy would copy the selected results and Paste would do nothing.
 */
final class FieldActionHandler {

	private final List<FieldAction> actions = new ArrayList<>();
	private Control activeField;

	/**
	 * @param bars the view's action bars
	 * @param copy what Copy does while no field has the focus
	 * @param delete what Delete does while no field has the focus
	 * @param selectAll what Select All does while no field has the focus
	 */
	FieldActionHandler(IActionBars bars, IAction copy, IAction delete, IAction selectAll) {
		add(bars, ActionFactory.CUT, null, Text::cut, Combo::cut);
		add(bars, ActionFactory.COPY, copy, Text::copy, Combo::copy);
		add(bars, ActionFactory.PASTE, null, Text::paste, Combo::paste);
		add(bars, ActionFactory.DELETE, delete, FieldActionHandler::delete, FieldActionHandler::delete);
		add(bars, ActionFactory.SELECT_ALL, selectAll, Text::selectAll,
				combo -> combo.setSelection(new Point(0, combo.getText().length())));
		bars.updateActionBars();
	}

	/**
	 * @param field a {@link Text} or a {@link Combo}
	 */
	void addField(Control field) {
		field.addListener(SWT.FocusIn, e -> setActiveField(field));
		field.addListener(SWT.FocusOut, e -> setActiveField(null));
	}

	private void add(IActionBars bars, ActionFactory id, IAction delegate, Consumer<Text> onText,
			Consumer<Combo> onCombo) {
		FieldAction action = new FieldAction(delegate, onText, onCombo);
		if (delegate != null) {
			delegate.addPropertyChangeListener(e -> {
				if (IAction.ENABLED.equals(e.getProperty())) {
					action.updateEnabled();
				}
			});
		}
		action.updateEnabled();
		actions.add(action);
		bars.setGlobalActionHandler(id.getId(), action);
	}

	private void setActiveField(Control field) {
		activeField = field;
		for (FieldAction action : actions) {
			action.updateEnabled();
		}
	}

	private static void delete(Text text) {
		Point selection = text.getSelection();
		if (selection.x == selection.y && selection.x < text.getCharCount()) {
			text.setSelection(selection.x, selection.x + 1);
		}
		text.insert("");
	}

	private static void delete(Combo combo) {
		String text = combo.getText();
		Point selection = combo.getSelection();
		int end = selection.x == selection.y ? Math.min(selection.x + 1, text.length()) : selection.y;
		combo.setText(text.substring(0, selection.x) + text.substring(end));
		combo.setSelection(new Point(selection.x, selection.x));
	}

	private final class FieldAction extends Action {

		private final IAction delegate;
		private final Consumer<Text> onText;
		private final Consumer<Combo> onCombo;

		FieldAction(IAction delegate, Consumer<Text> onText, Consumer<Combo> onCombo) {
			this.delegate = delegate;
			this.onText = onText;
			this.onCombo = onCombo;
		}

		@Override
		public void runWithEvent(Event event) {
			if (activeField != null && activeField.isDisposed()) {
				activeField = null;
			}
			if (activeField instanceof Text text) {
				onText.accept(text);
			} else if (activeField instanceof Combo combo) {
				onCombo.accept(combo);
			} else if (delegate != null && delegate.isEnabled()) {
				delegate.runWithEvent(event);
			}
		}

		void updateEnabled() {
			setEnabled(activeField != null || delegate != null && delegate.isEnabled());
		}
	}
}
