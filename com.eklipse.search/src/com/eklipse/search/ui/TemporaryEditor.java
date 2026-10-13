package com.eklipse.search.ui;

import org.eclipse.core.resources.IFile;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.jface.resource.FontDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.IReusableEditor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;

/**
 * The editor tab the selected result is shown in, like the preview editor of VS Code: the next result reuses it, so
 * going through the results doesn't pile up tabs, and its title is in italic. A tab opened for good, edited or pinned
 * is kept, the next result then gets a new one.
 */
final class TemporaryEditor {

	/** The key Eclipse's tab renderer keeps the part of a tab under, see {@code AbstractPartRenderer.OWNING_ME}. */
	private static final String TAB_PART_KEY = "modelElement";

	private final IWorkbenchPage page;
	private final LocalResourceManager resources = new LocalResourceManager(JFaceResources.getResources());
	/** The temporary tab, {@code null} for none. */
	private IEditorPart editor;
	private final IPropertyListener dirtyListener = (source, propertyId) -> {
		if (propertyId == IEditorPart.PROP_DIRTY && source == editor && editor.isDirty()) {
			keep();
		}
	};

	/**
	 * @param page the page to open the editors in
	 */
	TemporaryEditor(IWorkbenchPage page) {
		this.page = page;
	}

	/**
	 * Shows a file without activating its editor: in the editor it's open in, else in the temporary tab.
	 *
	 * @param file the file to show
	 * @return the editor showing the file, {@code null} if it would open outside of Eclipse
	 * @throws PartInitException if the editor couldn't be opened
	 */
	IEditorPart show(IFile file) throws PartInitException {
		FileEditorInput input = new FileEditorInput(file);
		IEditorPart open = page.findEditor(input);
		if (open != null) {
			page.bringToTop(open);
			if (open == editor) {
				setItalic(open, true);
			}
			return open;
		}
		// a selected result must neither ask which editor to use nor start another application
		IEditorDescriptor descriptor = IDE.getEditorDescriptor(file, true, false);
		if (descriptor.isOpenExternal()) {
			return null;
		}
		IEditorPart previous = reusable();
		if (previous instanceof IReusableEditor reusable && descriptor.getId().equals(previous.getSite().getId())) {
			page.reuseEditor(reusable, input);
			page.bringToTop(previous);
			// the tab may have been moved since, which gives it a new one
			setItalic(previous, true);
			return previous;
		}
		IEditorPart opened = page.openEditor(input, descriptor.getId(), false);
		// closed after the new one is open, so the editor area doesn't flash empty
		if (previous != null && previous != opened) {
			keep();
			page.closeEditor(previous, false);
		}
		if (opened != null && opened != editor) {
			keep();
			editor = opened;
			opened.addPropertyListener(dirtyListener);
			setItalic(opened, true);
		}
		return opened;
	}

	/**
	 * @return the temporary tab if it can show another file, {@code null} if there's none (anymore)
	 */
	private IEditorPart reusable() {
		if (editor != null) {
			IEditorReference reference = reference(editor);
			if (reference == null || reference.isPinned() || editor.isDirty()) {
				// closed, pinned or edited since
				keep();
			}
		}
		return editor;
	}

	/**
	 * @return the reference of an editor open on the page, {@code null} once it's closed (unlike
	 *         {@link IWorkbenchPage#getReference}, which keeps returning it)
	 */
	private IEditorReference reference(IEditorPart part) {
		for (IEditorReference reference : page.getEditorReferences()) {
			if (reference.getPart(false) == part) {
				return reference;
			}
		}
		return null;
	}

	/**
	 * Keeps the tab of an editor for good if it's the temporary one, e.g. once it's opened from the results.
	 *
	 * @param part the editor
	 */
	void keep(IEditorPart part) {
		if (part != null && part == editor) {
			keep();
		}
	}

	/**
	 * Keeps the temporary tab for good, the next file gets a new one.
	 */
	void keep() {
		if (editor != null) {
			editor.removePropertyListener(dirtyListener);
			setItalic(editor, false);
			editor = null;
		}
	}

	/**
	 * Keeps the temporary tab for good and frees the font of its title.
	 */
	void dispose() {
		keep();
		resources.dispose();
	}

	/**
	 * Shows the title of an editor's tab in italic, or not anymore. Eclipse has no API for it, the tab is found like
	 * its renderer finds it: by the part it shows. A tab that can't be found stays as it is.
	 */
	private void setItalic(IEditorPart part, boolean italic) {
		if (reference(part) == null) {
			// closed
			return;
		}
		MPart model = part.getSite().getService(MPart.class);
		Object widget = model != null && model.getParent() != null ? model.getParent().getWidget() : null;
		if (!(widget instanceof CTabFolder folder) || folder.isDisposed()) {
			return;
		}
		for (CTabItem tab : folder.getItems()) {
			if (tab.getData(TAB_PART_KEY) == model) {
				tab.setFont(italic ? resources.create(FontDescriptor.createFrom(folder.getFont()).withStyle(SWT.ITALIC))
						: null);
			}
		}
	}
}
