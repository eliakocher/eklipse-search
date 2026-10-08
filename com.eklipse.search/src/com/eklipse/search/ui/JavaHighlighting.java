package com.eklipse.search.ui;

import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.text.IJavaPartitions;
import org.eclipse.jdt.ui.text.JavaSourceViewerConfiguration;
import org.eclipse.jdt.ui.text.JavaTextTools;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.ui.texteditor.ChainedPreferenceStore;

/**
 * Colors Java files in the colors of the Java editor: what the code scanners see (keywords, strings, comments,
 * Javadoc) right away, the semantic highlighting (fields, static members, ...) once the file is parsed.
 */
final class JavaHighlighting implements SyntaxHighlighting {

	private static final ILog LOG = ILog.of(JavaHighlighting.class);

	private final JavaTextTools textTools = new JavaTextTools(PreferenceConstants.getPreferenceStore());
	/** {@code false} once JDT's internal semantic highlighting turned out to be missing. */
	private volatile boolean semantic = true;
	/** Like the Java editor's: the source level in the JDT Core preferences decides about keywords like enum. */
	private final IPreferenceStore preferences = new ChainedPreferenceStore(new IPreferenceStore[] {
			PreferenceConstants.getPreferenceStore(),
			new ScopedPreferenceStore(InstanceScope.INSTANCE, "org.eclipse.jdt.core"),
			EditorsUI.getPreferenceStore() });

	@Override
	public IPresentationReconciler createReconciler(ISourceViewer viewer, IFile file, IDocument document) {
		if (!isJava(file)) {
			return null;
		}
		textTools.setupJavaDocumentPartitioner(document, IJavaPartitions.JAVA_PARTITIONING);
		// created for each file, so changed colors are picked up
		return new JavaSourceViewerConfiguration(JavaUI.getColorManager(), preferences, null,
				IJavaPartitions.JAVA_PARTITIONING).getPresentationReconciler(viewer);
	}

	@Override
	public Supplier<StyleRange[]> computeSemanticStyles(IFile file, String text, IProgressMonitor monitor) {
		if (!semantic || !isJava(file)) {
			return null;
		}
		try {
			return JavaSemanticColors.compute(file, text, preferences, monitor);
		} catch (LinkageError e) {
			semantic = false;
			LOG.warn("JDT's semantic highlighting changed, the preview only shows the colors of the code scanners", e);
			return null;
		}
	}

	@Override
	public void dispose() {
		textTools.dispose();
	}

	private static boolean isJava(IFile file) {
		return "java".equalsIgnoreCase(file.getFileExtension());
	}
}
