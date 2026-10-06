package com.eklipse.search.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Opens the Search view and focuses the search field, prefilled with the selected text of the active editor.
 */
public class OpenSearchViewHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
		if (page == null) {
			return null;
		}
		String initialText = singleLineText(HandlerUtil.getCurrentSelection(event));
		try {
			SearchView view = (SearchView) page.showView(SearchView.ID);
			view.activateSearch(initialText);
		} catch (PartInitException e) {
			throw new ExecutionException("Could not open the Search view", e);
		}
		return null;
	}

	private static String singleLineText(ISelection selection) {
		if (selection instanceof ITextSelection textSelection) {
			String text = textSelection.getText();
			if (text != null && !text.isEmpty() && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
				return text;
			}
		}
		return null;
	}
}
