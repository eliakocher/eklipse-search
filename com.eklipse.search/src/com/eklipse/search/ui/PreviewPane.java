package com.eklipse.search.ui;

import static org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants.EDITOR_CURRENT_LINE_COLOR;
import static org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER_COLOR;
import static org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants.EDITOR_TAB_WIDTH;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_BACKGROUND;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_BACKGROUND_SYSTEM_DEFAULT;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_FOREGROUND;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_FOREGROUND_SYSTEM_DEFAULT;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_SELECTION_BACKGROUND;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_SELECTION_BACKGROUND_SYSTEM_DEFAULT;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_SELECTION_FOREGROUND;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_SELECTION_FOREGROUND_SYSTEM_DEFAULT;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.LineNumberRulerColumn;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.editors.text.EditorsUI;

import com.eklipse.search.core.LineMatch;
import com.eklipse.search.core.PreviewText;

/**
 * Shows the file of the selected result below the results, like Quick Search does: the line of the match is
 * highlighted and centered, all matches of the file are marked. Read-only, in the colors and font of the text editor.
 * The syntax is colored if JDT (Java files) or TM4E (TextMate grammars) is installed.
 */
final class PreviewPane {

	private static final ILog LOG = ILog.of(PreviewPane.class);
	/** Defined by org.eclipse.search, also used for the results, has a dark theme value. */
	private static final String MATCH_HIGHLIGHT = "org.eclipse.search.ui.match.highlight";
	/** Longer lines are shortened, the text widget gets slow with lines of minified code. */
	private static final int MAX_LINE_LENGTH = 2_000;
	/** Bigger files aren't previewed. */
	private static final int MAX_FILE_SIZE = 20 * 1024 * 1024;
	/** Bigger files aren't colored, the Java highlighting runs in the UI thread. */
	private static final int MAX_HIGHLIGHT_LENGTH = 1_000_000;

	private final Display display;
	private final Composite control;
	private final SourceViewer viewer;
	private final StyledText widget;
	private final LineNumberRulerColumn lineNumbers = new LineNumberRulerColumn();
	private final IPreferenceStore editorPreferences = EditorsUI.getPreferenceStore();
	private final IPropertyChangeListener preferenceListener = this::preferenceChanged;
	private final IPropertyChangeListener fontListener = this::fontChanged;
	private final Listener skinListener = this::skinned;

	private Color targetLineBackground;
	/** Offset of the highlighted line in the widget, {@code -1} for none. */
	private int targetLineOffset = -1;
	private int targetStart = -1;
	private int targetEnd = -1;

	private LineMatch target;
	private List<LineMatch> matches = List.of();
	/** The marked matches in the shown document, sorted and without overlaps. */
	private StyleRange[] marks = {};
	/** The file whose content is shown, {@code null} for a message. */
	private IFile shownFile;
	/** Colors the shown file, {@code null} if it is plain text. */
	private IPresentationReconciler reconciler;
	/** The colors that need the meaning of the code, e.g. of fields, sorted and without overlaps. */
	private StyleRange[] semanticStyles = {};
	private Job semanticJob;
	/** {@code null} until the first file is shown. */
	private List<SyntaxHighlighting> highlightings;
	/** Counts the requests, so a file read in the background isn't shown once another result is selected. */
	private int request;
	private Job loadJob;
	private IFile cachedFile;
	private long cachedStamp;
	private String cachedContent;

	PreviewPane(Composite parent) {
		display = parent.getDisplay();
		control = new Composite(parent, SWT.BORDER);
		control.setLayout(new FillLayout());
		viewer = new SourceViewer(control, new CompositeRuler(), SWT.H_SCROLL | SWT.V_SCROLL | SWT.READ_ONLY);
		viewer.setEditable(false);
		viewer.addVerticalRulerColumn(lineNumbers);
		widget = viewer.getTextWidget();
		widget.setFont(JFaceResources.getTextFont());
		lineNumbers.setFont(JFaceResources.getTextFont());
		widget.addLineBackgroundListener(e -> {
			if (e.lineOffset == targetLineOffset) {
				e.lineBackground = targetLineBackground;
			}
		});
		viewer.addTextPresentationListener(this::mergeStyles);
		// the widget only knows its size once laid out, e.g. when the preview is shown
		widget.addListener(SWT.Resize, e -> reveal());
		applyPreferences();
		editorPreferences.addPropertyChangeListener(preferenceListener);
		// runs after the theme engine's listener, registered when the workbench started
		display.addListener(SWT.Skin, skinListener);
		JFaceResources.getFontRegistry().addListener(fontListener);
		control.addDisposeListener(e -> {
			editorPreferences.removePropertyChangeListener(preferenceListener);
			display.removeListener(SWT.Skin, skinListener);
			JFaceResources.getFontRegistry().removeListener(fontListener);
			cancelLoad();
			// stops TM4E's tokenizer thread
			uninstallReconciler();
			cancelSemanticStyles();
			if (highlightings != null) {
				highlightings.forEach(SyntaxHighlighting::dispose);
			}
		});
	}

	Control getControl() {
		return control;
	}

	/**
	 * @return {@code true} if the preview text has the keyboard focus
	 */
	boolean hasFocus() {
		return !widget.isDisposed() && widget.isFocusControl();
	}

	/**
	 * Copies the text selected in the preview.
	 */
	void copy() {
		widget.copy();
	}

	void selectAll() {
		widget.selectAll();
	}

	/**
	 * Shows the file of a match.
	 *
	 * @param match the match to center and highlight
	 * @param fileMatches all matches of the file, to mark them
	 */
	void show(LineMatch match, List<LineMatch> fileMatches) {
		request++;
		target = match;
		matches = List.copyOf(fileMatches);
		IFile file = match.getFile();
		String content = openContent(file);
		if (content == null && file.equals(cachedFile) && file.getModificationStamp() == cachedStamp) {
			content = cachedContent;
		}
		if (content != null) {
			cancelLoad();
			render(content);
		} else {
			load(file, request);
		}
	}

	void clear() {
		request++;
		cancelLoad();
		target = null;
		matches = List.of();
		setText("");
	}

	/**
	 * Forgets what was read of files that changed.
	 */
	void invalidate(Collection<IFile> files) {
		if (cachedFile != null && files.contains(cachedFile)) {
			cachedFile = null;
			cachedContent = null;
		}
	}

	/**
	 * @return the content of an editor showing the file, including unsaved changes like the search sees them,
	 *         {@code null} if the file isn't open
	 */
	private static String openContent(IFile file) {
		ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(),
				LocationKind.IFILE);
		return buffer != null ? buffer.getDocument().get() : null;
	}

	/**
	 * Reads a closed file in the background, so a big file or a slow disk can't freeze the UI.
	 */
	private void load(IFile file, int forRequest) {
		cancelLoad();
		Job job = Job.create("Search: preview " + file.getName(), monitor -> {
			String content = null;
			String message = null;
			try {
				content = read(file);
				if (content == null) {
					message = file.getName() + " is too big for a preview";
				}
			} catch (CoreException | IOException e) {
				message = "Could not read " + file.getName() + ": " + e.getMessage();
			}
			String loaded = content;
			String failure = message;
			if (!monitor.isCanceled() && !display.isDisposed()) {
				display.asyncExec(() -> {
					if (forRequest != request || control.isDisposed()) {
						return;
					}
					if (loaded == null) {
						setText(failure);
					} else {
						cachedFile = file;
						cachedStamp = file.getModificationStamp();
						cachedContent = loaded;
						render(loaded);
					}
				});
			}
			return Status.OK_STATUS;
		});
		job.setSystem(true);
		loadJob = job;
		job.schedule();
	}

	private void cancelLoad() {
		if (loadJob != null) {
			loadJob.cancel();
			loadJob = null;
		}
	}

	/**
	 * @return the content like the search reads it, {@code null} if the file is bigger than {@link #MAX_FILE_SIZE}
	 */
	private static String read(IFile file) throws CoreException, IOException {
		byte[] bytes;
		try (InputStream in = file.getContents(true)) {
			bytes = in.readNBytes(MAX_FILE_SIZE + 1);
		}
		if (bytes.length > MAX_FILE_SIZE) {
			return null;
		}
		Charset charset;
		try {
			charset = Charset.forName(file.getCharset());
		} catch (IllegalArgumentException e) {
			charset = StandardCharsets.UTF_8;
		}
		String content = new String(bytes, charset);
		// the search doesn't count a byte order mark, the match offsets would be off by one
		return !content.isEmpty() && content.charAt(0) == '\uFEFF' ? content.substring(1) : content;
	}

	private void setText(String text) {
		targetLineOffset = -1;
		targetStart = -1;
		targetEnd = -1;
		marks = new StyleRange[0];
		setDocument(null, new Document(text), null);
	}

	private void render(String content) {
		LineMatch match = target;
		boolean targetValid = isAt(content, match);
		PreviewText preview = PreviewText.create(content, targetValid ? match.getOffset() : -1, MAX_LINE_LENGTH);
		StyleRange[] newMarks = createMarks(content, preview);
		IFile file = match.getFile();
		IDocument document = viewer.getDocument();
		if (document != null && file.equals(shownFile) && document.get().equals(preview.text())) {
			updateMarks(newMarks);
		} else {
			marks = newMarks;
			document = new Document(preview.text());
			setDocument(file, document, createColoring(file, document, preview));
			// TM4E colors in the background, plain text not at all
			if (marks.length > 0) {
				StyleRange last = marks[marks.length - 1];
				paintMarks(marks[0].start, last.start + last.length);
			}
		}

		try {
			int line = targetStart >= 0 ? document.getLineOfOffset(targetStart)
					: Math.min(match.getLineNumber() - 1, document.getNumberOfLines() - 1);
			targetLineOffset = document.getLineOffset(line);
		} catch (BadLocationException e) {
			targetLineOffset = -1;
		}
		widget.setCaretOffset(targetStart >= 0 ? targetStart : Math.max(targetLineOffset, 0));
		reveal();
		widget.redraw();
	}

	/**
	 * Marks the matches of the file and boxes the target, also sets {@link #targetStart} and {@link #targetEnd}.
	 *
	 * @return the marks in the preview, sorted and without overlaps
	 */
	private StyleRange[] createMarks(String content, PreviewText preview) {
		Color highlight = JFaceResources.getColorRegistry().get(MATCH_HIGHLIGHT);
		List<LineMatch> sorted = new ArrayList<>(matches);
		sorted.sort(Comparator.comparingInt(LineMatch::getOffset));
		List<StyleRange> ranges = new ArrayList<>();
		int previousEnd = 0;
		targetStart = -1;
		targetEnd = -1;
		for (LineMatch m : sorted) {
			// the file may have changed since the search, e.g. in an editor
			if (m.getLength() == 0 || !isAt(content, m)) {
				continue;
			}
			int start = preview.toPreviewOffset(m.getOffset());
			int end = preview.toPreviewOffset(m.getOffset() + m.getLength());
			if (start < previousEnd || end <= start) {
				continue;
			}
			// the text color is added when merged, see mergeStyles
			StyleRange range = new StyleRange(start, end - start, null, highlight);
			if (m.equals(target)) {
				range.borderStyle = SWT.BORDER_SOLID;
				targetStart = start;
				targetEnd = end;
			}
			ranges.add(range);
			previousEnd = end;
		}
		return ranges.toArray(StyleRange[]::new);
	}

	/**
	 * Replaces the marks of the shown document, only repainting the ones that changed, e.g. when the box moves to
	 * another match.
	 */
	private void updateMarks(StyleRange[] newMarks) {
		Set<StyleRange> previous = new HashSet<>(Arrays.asList(marks));
		Set<StyleRange> current = new HashSet<>(Arrays.asList(newMarks));
		marks = newMarks;
		for (StyleRange mark : previous) {
			if (!current.contains(mark)) {
				if (reconciler != null) {
					// colors the range again, the presentation listener adds the marks still there
					viewer.invalidateTextPresentation(mark.start, mark.length);
				} else {
					widget.replaceStyleRanges(mark.start, mark.length, new StyleRange[0]);
				}
			}
		}
		for (StyleRange mark : newMarks) {
			if (!previous.contains(mark)) {
				paintMarks(mark.start, mark.start + mark.length);
			}
		}
	}

	/**
	 * Adds the marks to the colors the text has now.
	 */
	private void paintMarks(int start, int end) {
		TextPresentation presentation = new TextPresentation(new Region(start, end - start), 16);
		for (StyleRange range : widget.getStyleRanges(start, end - start)) {
			presentation.addStyleRange(range);
		}
		// the presentation listener adds the marks
		viewer.changeTextPresentation(presentation, false);
	}

	/**
	 * Adds the semantic colors and the marks on top to a presentation before it is applied, e.g. to the colors of the
	 * code scanners.
	 */
	private void mergeStyles(TextPresentation presentation) {
		IRegion extent = presentation.getExtent();
		if (extent != null) {
			merge(presentation, extent, semanticStyles, null);
			// in the text color, syntax colors can be as bright as the mark, e.g. keywords in the dark theme; the
			// current one, the theme and the preferences may change it after the marks are created
			merge(presentation, extent, marks, widget.getForeground());
		}
	}

	/**
	 * @param styles sorted and without overlaps
	 * @param textColor the color for the text and the boxes, {@code null} to keep the styles'
	 */
	private static void merge(TextPresentation presentation, IRegion extent, StyleRange[] styles, Color textColor) {
		int start = extent.getOffset();
		int end = start + extent.getLength();
		// the first style ending after the start, the ends are sorted as well
		int low = 0;
		int high = styles.length;
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (styles[middle].start + styles[middle].length <= start) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		List<StyleRange> parts = new ArrayList<>();
		for (int i = low; i < styles.length && styles[i].start < end; i++) {
			// the presentation replaces the styles of its extent only
			StyleRange part = (StyleRange) styles[i].clone();
			part.start = Math.max(styles[i].start, start);
			part.length = Math.min(styles[i].start + styles[i].length, end) - part.start;
			if (textColor != null) {
				part.foreground = textColor;
				if (part.borderStyle != SWT.NONE) {
					// one box although the syntax colors split the match, a box without color takes the text's
					part.borderColor = textColor;
				}
			}
			parts.add(part);
		}
		if (!parts.isEmpty()) {
			// all at once, one by one takes quadratic time
			presentation.mergeStyleRanges(parts.toArray(StyleRange[]::new));
		}
	}

	/**
	 * Shows a new document.
	 *
	 * @param file the file of the document, {@code null} for a message
	 * @param coloring colors the document, {@code null} for plain text
	 */
	private void setDocument(IFile file, IDocument document, Coloring coloring) {
		uninstallReconciler();
		cancelSemanticStyles();
		semanticStyles = new StyleRange[0];
		// the offset in the previous document, the line number ruler can resize the widget while the new one is set
		targetLineOffset = -1;
		viewer.setDocument(document);
		shownFile = file;
		if (coloring != null) {
			coloring.reconciler().install(viewer);
			reconciler = coloring.reconciler();
			// TM4E paints the widget in the colors of its theme, the preview keeps the editor's
			applyPreferences();
			computeSemanticStyles(coloring.highlighting(), file, document);
		}
	}

	private record Coloring(SyntaxHighlighting highlighting, IPresentationReconciler reconciler) {
	}

	/**
	 * @return how to color the document, {@code null} for plain text
	 */
	private Coloring createColoring(IFile file, IDocument document, PreviewText preview) {
		// a shortened line can confuse the highlighting, e.g. with a comment cut before its end
		if (preview.isShortened() || preview.text().length() > MAX_HIGHLIGHT_LENGTH) {
			return null;
		}
		if (highlightings == null) {
			highlightings = SyntaxHighlighting.installed();
		}
		for (SyntaxHighlighting highlighting : highlightings) {
			try {
				IPresentationReconciler created = highlighting.createReconciler(viewer, file, document);
				if (created != null) {
					return new Coloring(highlighting, created);
				}
			} catch (RuntimeException e) {
				// e.g. a broken grammar, the file is still previewed
				LOG.warn("Could not color " + file.getFullPath(), e);
			}
		}
		return null;
	}

	/**
	 * Adds the colors that need the meaning of the code, e.g. of fields, once computed in the background.
	 */
	private void computeSemanticStyles(SyntaxHighlighting highlighting, IFile file, IDocument document) {
		String text = document.get();
		Job job = Job.create("Search: color " + file.getName(), monitor -> {
			Supplier<StyleRange[]> styles;
			try {
				styles = highlighting.computeSemanticStyles(file, text, monitor);
			} catch (OperationCanceledException e) {
				return Status.CANCEL_STATUS;
			} catch (RuntimeException e) {
				// e.g. a compiler problem, the preview keeps the colors it has
				LOG.warn("Could not color " + file.getFullPath(), e);
				return Status.OK_STATUS;
			}
			if (styles != null && !monitor.isCanceled() && !display.isDisposed()) {
				display.asyncExec(() -> {
					if (!control.isDisposed() && viewer.getDocument() == document) {
						semanticStyles = styles.get();
						// colors the document again, the presentation listener adds the semantic colors
						viewer.invalidateTextPresentation();
					}
				});
			}
			return Status.OK_STATUS;
		});
		job.setSystem(true);
		semanticJob = job;
		job.schedule();
	}

	private void cancelSemanticStyles() {
		if (semanticJob != null) {
			semanticJob.cancel();
			semanticJob = null;
		}
	}

	private void uninstallReconciler() {
		if (reconciler != null) {
			reconciler.uninstall();
			reconciler = null;
		}
	}

	/**
	 * @return {@code true} if the content still has the matched text at the offset of the match
	 */
	private static boolean isAt(String content, LineMatch match) {
		return match != null && content.startsWith(match.getMatchedText(), match.getOffset());
	}

	/**
	 * Centers the line of the match and scrolls sideways if needed, so the match is visible with some text before it.
	 */
	private void reveal() {
		if (targetLineOffset < 0 || widget.isDisposed()) {
			return;
		}
		int visibleLines = widget.getClientArea().height / Math.max(1, widget.getLineHeight());
		if (visibleLines <= 0) {
			return;
		}
		int line = widget.getLineAtOffset(targetLineOffset);
		widget.setTopIndex(Math.max(0, line - visibleLines / 2));
		widget.setHorizontalPixel(0);
		if (targetStart >= 0) {
			int width = widget.getClientArea().width;
			int startX = widget.getLocationAtOffset(targetStart).x;
			int endLine = widget.getLineAtOffset(targetEnd);
			int endX = endLine == line ? widget.getLocationAtOffset(targetEnd).x : startX;
			if (endX > width - widget.getLineHeight()) {
				widget.setHorizontalPixel(Math.max(0, startX - width / 3));
			}
		}
	}

	private void preferenceChanged(PropertyChangeEvent event) {
		if (!widget.isDisposed()) {
			applyPreferences();
			lineNumbers.redraw();
			widget.redraw();
		}
	}

	/**
	 * The theme's CSS colors the text widgets in views, the preview keeps the colors of the text editor.
	 */
	private void skinned(Event event) {
		if (event.widget == widget) {
			applyPreferences();
		}
	}

	private void fontChanged(PropertyChangeEvent event) {
		if (JFaceResources.TEXT_FONT.equals(event.getProperty()) && !widget.isDisposed()) {
			widget.setFont(JFaceResources.getTextFont());
			lineNumbers.setFont(JFaceResources.getTextFont());
			reveal();
		}
	}

	/**
	 * Uses the colors of the text editor, which the dark theme sets.
	 */
	private void applyPreferences() {
		Color background = color(PREFERENCE_COLOR_BACKGROUND, PREFERENCE_COLOR_BACKGROUND_SYSTEM_DEFAULT);
		widget.setBackground(background);
		lineNumbers.setBackground(background);
		widget.setForeground(color(PREFERENCE_COLOR_FOREGROUND, PREFERENCE_COLOR_FOREGROUND_SYSTEM_DEFAULT));
		widget.setSelectionBackground(
				color(PREFERENCE_COLOR_SELECTION_BACKGROUND, PREFERENCE_COLOR_SELECTION_BACKGROUND_SYSTEM_DEFAULT));
		widget.setSelectionForeground(
				color(PREFERENCE_COLOR_SELECTION_FOREGROUND, PREFERENCE_COLOR_SELECTION_FOREGROUND_SYSTEM_DEFAULT));
		lineNumbers.setForeground(color(EDITOR_LINE_NUMBER_RULER_COLOR));
		targetLineBackground = color(EDITOR_CURRENT_LINE_COLOR);
		int tabWidth = editorPreferences.getInt(EDITOR_TAB_WIDTH);
		widget.setTabs(tabWidth > 0 ? tabWidth : 4);
	}

	/**
	 * @return the color of the preference, {@code null} for the system's
	 */
	private Color color(String key, String systemDefaultKey) {
		return editorPreferences.getBoolean(systemDefaultKey) ? null : color(key);
	}

	private Color color(String key) {
		if (!editorPreferences.contains(key)) {
			return null;
		}
		RGB rgb = editorPreferences.isDefault(key) ? PreferenceConverter.getDefaultColor(editorPreferences, key)
				: PreferenceConverter.getColor(editorPreferences, key);
		return EditorsUI.getSharedTextColors().getColor(rgb);
	}

	// ---------------------------------------------------------------------------------------------------- UI tests

	StyledText getTextWidget() {
		return widget;
	}

	/**
	 * @return the widget offset of the highlighted line, {@code -1} for none
	 */
	int getTargetLineOffset() {
		return targetLineOffset;
	}
}
