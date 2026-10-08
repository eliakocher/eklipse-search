package com.eklipse.search.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.StyleRange;

/**
 * Colors the preview with an optional plug-in. The implementations reference classes of their plug-in and may only be
 * created through {@link #installed()}.
 */
interface SyntaxHighlighting {

	/**
	 * @return the highlightings whose plug-in is installed, the most specific first
	 */
	static List<SyntaxHighlighting> installed() {
		List<SyntaxHighlighting> highlightings = new ArrayList<>();
		try {
			highlightings.add(new JavaHighlighting());
		} catch (LinkageError e) {
			// JDT isn't installed
		}
		try {
			highlightings.add(new TextMateHighlighting());
		} catch (LinkageError e) {
			// TM4E isn't installed
		}
		return highlightings;
	}

	/**
	 * @param viewer the viewer that will show the document
	 * @param file the file of the document
	 * @param document the content of the file, not shown yet
	 * @return the presentation reconciler that colors the document once installed, {@code null} if the file isn't
	 *         supported
	 */
	IPresentationReconciler createReconciler(ISourceViewer viewer, IFile file, IDocument document);

	/**
	 * Computes the colors that need the meaning of the code, e.g. of fields, in a background thread. Only called for
	 * files this highlighting created a reconciler for.
	 *
	 * @param file the file of the text
	 * @param text the shown text
	 * @return creates the styles in the UI thread, sorted and without overlaps, {@code null} if there are none
	 */
	default Supplier<StyleRange[]> computeSemanticStyles(IFile file, String text, IProgressMonitor monitor) {
		return null;
	}

	void dispose();
}
