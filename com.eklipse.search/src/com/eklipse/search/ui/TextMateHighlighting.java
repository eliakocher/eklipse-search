package com.eklipse.search.ui;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.registry.IGrammarRegistryManager;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.text.TMPresentationReconciler;

/**
 * Colors files with the TextMate grammars of TM4E, e.g. the ones of its language pack. The grammar is found like
 * TM4E's editors find it. The tokenizing runs in the background.
 */
final class TextMateHighlighting implements SyntaxHighlighting {

	private final IGrammarRegistryManager grammars = TMEclipseRegistryPlugin.getGrammarRegistryManager();

	@Override
	public IPresentationReconciler createReconciler(ISourceViewer viewer, IFile file, IDocument document) {
		IGrammar grammar = grammars.getGrammarFor(Platform.getContentTypeManager().findContentTypesFor(file.getName()));
		String extension = file.getFileExtension();
		if (grammar == null && extension != null) {
			grammar = grammars.getGrammarForFileExtension(extension);
		}
		if (grammar == null) {
			return null;
		}
		TMPresentationReconciler reconciler = new TMPresentationReconciler();
		reconciler.setGrammar(grammar);
		return reconciler;
	}

	@Override
	public void dispose() {
		// nothing to release
	}
}
