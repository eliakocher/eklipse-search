package com.eklipse.search.ui;

import static org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants.EDITOR_CURRENT_LINE_COLOR;
import static org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER_COLOR;
import static org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants.EDITOR_TAB_WIDTH;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_BACKGROUND;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_BACKGROUND_SYSTEM_DEFAULT;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_FOREGROUND;
import static org.eclipse.ui.texteditor.AbstractTextEditor.PREFERENCE_COLOR_FOREGROUND_SYSTEM_DEFAULT;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
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
import org.eclipse.ui.editors.text.EditorsUI;

import com.eklipse.search.core.LineMatch;
import com.eklipse.search.core.PreviewText;

/**
 * Shows the file of the selected result below the results, like Quick Search does: the line of the match is
 * highlighted and centered, all matches of the file are marked. Read-only, in the colors and font of the text editor.
 */
final class PreviewPane {

	/** Defined by org.eclipse.search, also used for the results, has a dark theme value. */
	private static final String MATCH_HIGHLIGHT = "org.eclipse.search.ui.match.highlight";
	/** Longer lines are shortened, the text widget gets slow with lines of minified code. */
	private static final int MAX_LINE_LENGTH = 2_000;
	/** Bigger files aren't previewed. */
	private static final int MAX_FILE_SIZE = 20 * 1024 * 1024;

	private final Display display;
	private final Composite control;
	private final SourceViewer viewer;
	private final StyledText widget;
	private final LineNumberRulerColumn lineNumbers = new LineNumberRulerColumn();
	private final IPreferenceStore editorPreferences = EditorsUI.getPreferenceStore();
	private final IPropertyChangeListener preferenceListener = this::preferenceChanged;
	private final IPropertyChangeListener fontListener = this::fontChanged;

	private Color targetLineBackground;
	/** Offset of the highlighted line in the widget, {@code -1} for none. */
	private int targetLineOffset = -1;
	private int targetStart = -1;
	private int targetEnd = -1;

	private LineMatch target;
	private List<LineMatch> matches = List.of();
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
		// the widget only knows its size once laid out, e.g. when the preview is shown
		widget.addListener(SWT.Resize, e -> reveal());
		applyPreferences();
		editorPreferences.addPropertyChangeListener(preferenceListener);
		JFaceResources.getFontRegistry().addListener(fontListener);
		control.addDisposeListener(e -> {
			editorPreferences.removePropertyChangeListener(preferenceListener);
			JFaceResources.getFontRegistry().removeListener(fontListener);
			cancelLoad();
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
		viewer.setDocument(new Document(text));
	}

	private void render(String content) {
		LineMatch match = target;
		boolean targetValid = isAt(content, match);
		PreviewText preview = PreviewText.create(content, targetValid ? match.getOffset() : -1, MAX_LINE_LENGTH);
		IDocument document = viewer.getDocument();
		if (document == null || !document.get().equals(preview.text())) {
			document = new Document(preview.text());
			viewer.setDocument(document);
		}

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
			StyleRange range = new StyleRange(start, end - start, null, highlight);
			if (m.equals(match)) {
				range.borderStyle = SWT.BORDER_SOLID;
				targetStart = start;
				targetEnd = end;
			}
			ranges.add(range);
			previousEnd = end;
		}
		widget.setStyleRanges(ranges.toArray(StyleRange[]::new));

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
		Color background = editorPreferences.getBoolean(PREFERENCE_COLOR_BACKGROUND_SYSTEM_DEFAULT) ? null
				: color(PREFERENCE_COLOR_BACKGROUND);
		widget.setBackground(background);
		lineNumbers.setBackground(background);
		widget.setForeground(editorPreferences.getBoolean(PREFERENCE_COLOR_FOREGROUND_SYSTEM_DEFAULT) ? null
				: color(PREFERENCE_COLOR_FOREGROUND));
		lineNumbers.setForeground(color(EDITOR_LINE_NUMBER_RULER_COLOR));
		targetLineBackground = color(EDITOR_CURRENT_LINE_COLOR);
		int tabWidth = editorPreferences.getInt(EDITOR_TAB_WIDTH);
		widget.setTabs(tabWidth > 0 ? tabWidth : 4);
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
