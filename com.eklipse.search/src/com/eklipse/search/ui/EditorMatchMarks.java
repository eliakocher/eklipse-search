package com.eklipse.search.ui;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.eklipse.search.core.FileMatch;
import com.eklipse.search.core.LineMatch;
import com.eklipse.search.core.SearchResult;

/**
 * Marks the matches in the open text editors of a page like File Search does: highlighted and in the overview ruler,
 * as configured in General > Editors > Text Editors > Annotations > Search Results. The marks move with edits like
 * other annotations and are updated when the results change or an editor opens.
 */
final class EditorMatchMarks {

	/** Defined by org.eclipse.search for the matches of File Search. */
	private static final String ANNOTATION_TYPE = "org.eclipse.search.results";
	/** Waits for results that stream in. */
	private static final int UPDATE_DELAY_MS = 200;

	private record Marks(List<LineMatch> matches, Annotation[] annotations) {
	}

	private record Editor(IDocument document, List<LineMatch> matches) {
	}

	private final IWorkbenchPage page;
	private final Supplier<SearchResult> results;
	private final Display display;
	private final Runnable updater = this::update;
	private final IPartListener2 partListener = new IPartListener2() {

		@Override
		public void partOpened(IWorkbenchPartReference reference) {
			editorsChanged(reference);
		}

		@Override
		public void partClosed(IWorkbenchPartReference reference) {
			editorsChanged(reference);
		}

		@Override
		public void partInputChanged(IWorkbenchPartReference reference) {
			editorsChanged(reference);
		}
	};
	/** The marks per annotation model, editors of the same file share one. */
	private final Map<IAnnotationModel, Marks> marked = new IdentityHashMap<>();

	/**
	 * @param results the shown results
	 */
	EditorMatchMarks(IWorkbenchPage page, Supplier<SearchResult> results) {
		this.page = page;
		this.results = results;
		display = page.getWorkbenchWindow().getShell().getDisplay();
		page.addPartListener(partListener);
	}

	/**
	 * Updates the marks soon, so results streaming in don't redraw the editors each time.
	 */
	void scheduleUpdate() {
		display.timerExec(-1, updater);
		display.timerExec(UPDATE_DELAY_MS, updater);
	}

	/**
	 * Removes the marks.
	 */
	void dispose() {
		display.timerExec(-1, updater);
		page.removePartListener(partListener);
		marked.forEach((model, marks) -> replace(model, marks.annotations(), Map.of()));
		marked.clear();
	}

	private void editorsChanged(IWorkbenchPartReference reference) {
		if (reference instanceof IEditorReference) {
			scheduleUpdate();
		}
	}

	private void update() {
		Map<IAnnotationModel, Editor> editors = openEditorsWithMatches();
		for (Iterator<Map.Entry<IAnnotationModel, Marks>> i = marked.entrySet().iterator(); i.hasNext();) {
			Map.Entry<IAnnotationModel, Marks> entry = i.next();
			Editor editor = editors.get(entry.getKey());
			if (editor != null && editor.matches().equals(entry.getValue().matches())) {
				// unchanged, the marks keep following the edits since the search
				editors.remove(entry.getKey());
			} else {
				replace(entry.getKey(), entry.getValue().annotations(), Map.of());
				i.remove();
			}
		}
		editors.forEach((model, editor) -> {
			Map<Annotation, Position> annotations = new HashMap<>();
			for (LineMatch match : editor.matches()) {
				if (isAt(editor.document(), match)) {
					annotations.put(new Annotation(ANNOTATION_TYPE, true, null),
							new Position(match.getOffset(), match.getLength()));
				}
			}
			replace(model, new Annotation[0], annotations);
			marked.put(model, new Marks(editor.matches(), annotations.keySet().toArray(Annotation[]::new)));
		});
	}

	/**
	 * @return the editors showing files with matches, by annotation model; editors not restored yet are skipped,
	 *         they are marked once opened
	 */
	private Map<IAnnotationModel, Editor> openEditorsWithMatches() {
		SearchResult result = results.get();
		Map<IAnnotationModel, Editor> editors = new IdentityHashMap<>();
		for (IEditorReference reference : page.getEditorReferences()) {
			ITextEditor editor = Adapters.adapt(reference.getEditor(false), ITextEditor.class);
			IEditorInput input = editor != null ? editor.getEditorInput() : null;
			IFile file = input != null ? ResourceUtil.getFile(input) : null;
			FileMatch fileMatch = file != null && result != null ? result.get(file) : null;
			IDocumentProvider provider = fileMatch != null ? editor.getDocumentProvider() : null;
			if (provider == null) {
				continue;
			}
			IAnnotationModel model = provider.getAnnotationModel(input);
			IDocument document = provider.getDocument(input);
			if (model != null && document != null) {
				editors.put(model, new Editor(document, List.copyOf(fileMatch.getMatches())));
			}
		}
		return editors;
	}

	/**
	 * @return {@code true} if the document still has the matched text at the offset of the match, it may have been
	 *         edited since the search
	 */
	private static boolean isAt(IDocument document, LineMatch match) {
		try {
			return match.getOffset() + match.getLength() <= document.getLength()
					&& document.get(match.getOffset(), match.getLength()).equals(match.getMatchedText());
		} catch (BadLocationException e) {
			return false;
		}
	}

	private static void replace(IAnnotationModel model, Annotation[] remove, Map<Annotation, Position> add) {
		if (model instanceof IAnnotationModelExtension extension) {
			// one change event, one redraw
			extension.replaceAnnotations(remove, add);
		} else {
			for (Annotation annotation : remove) {
				model.removeAnnotation(annotation);
			}
			add.forEach(model::addAnnotation);
		}
	}
}
