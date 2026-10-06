package com.eklipse.search.ui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.ui.model.WorkbenchLabelProvider;

import com.eklipse.search.core.FileMatch;
import com.eklipse.search.core.LineMatch;

/**
 * Renders files as {@code Name.java  project/folder  3} and matches as their line with the match highlighted. While
 * the replace field is in use, the match is struck through and followed by its replacement.
 */
final class ResultLabelProvider extends StyledCellLabelProvider {

	/** Defined by org.eclipse.search, also used by the File Search view, has a dark theme value. */
	private static final String MATCH_HIGHLIGHT = "org.eclipse.search.ui.match.highlight";
	private static final String REMOVED_HIGHLIGHT = "com.eklipse.search.replace.removed";
	private static final String ADDED_HIGHLIGHT = "com.eklipse.search.replace.added";
	private static final String ELLIPSIS = "…";

	private final Function<LineMatch, String> replacementPreview;
	private final IntSupplier contextChars;
	private final WorkbenchLabelProvider workbenchLabels = new WorkbenchLabelProvider();
	private final Map<String, Image> fileImages = new HashMap<>();
	private final Styler matchStyler = background(MATCH_HIGHLIGHT, false);
	private final Styler removedStyler = background(REMOVED_HIGHLIGHT, true);
	private final Styler addedStyler = background(ADDED_HIGHLIGHT, false);

	/**
	 * @param replacementPreview returns the replacement to preview for a match, {@code null} for no preview
	 * @param contextChars returns how many characters before a match fit into the view
	 */
	ResultLabelProvider(Function<LineMatch, String> replacementPreview, IntSupplier contextChars) {
		this.replacementPreview = replacementPreview;
		this.contextChars = contextChars;
	}

	/**
	 * Deliberately doesn't call {@code super.update(cell)}: that only redraws the cell, but asking for its bounds makes
	 * SWT on macOS reload the whole native tree, once per row, which froze the UI for seconds with thousands of
	 * results. The view redraws the tree once after each batch instead.
	 */
	@Override
	public void update(ViewerCell cell) {
		Object element = cell.getElement();
		StyledString text = getStyledText(element);
		cell.setText(text.getString());
		cell.setStyleRanges(text.getStyleRanges());
		cell.setImage(element instanceof FileMatch fileMatch ? fileImage(fileMatch.getFile()) : null);
	}

	/**
	 * Icons are looked up once per file extension: the lookup goes through the content type catalog, whose lock is
	 * also taken by the search threads.
	 */
	private Image fileImage(IFile file) {
		String extension = file.getFileExtension();
		String key = extension != null ? extension.toLowerCase(Locale.ROOT) : file.getName();
		return fileImages.computeIfAbsent(key, k -> workbenchLabels.getImage(file));
	}

	private StyledString getStyledText(Object element) {
		if (element instanceof FileMatch fileMatch) {
			IFile file = fileMatch.getFile();
			// the count comes before the path, so it stays visible when a narrow view cuts the path
			StyledString text = new StyledString(file.getName());
			text.append(" " + fileMatch.getMatchCount(), StyledString.COUNTER_STYLER);
			text.append("  " + file.getParent().getFullPath().makeRelative(), StyledString.QUALIFIER_STYLER);
			return text;
		}
		if (element instanceof LineMatch match) {
			String preview = match.getPreview();
			int start = match.getPreviewMatchStart();
			int end = start + match.getPreviewMatchLength();
			// keep the match visible: the narrower the view, the less context before it
			int context = contextChars.getAsInt();
			StyledString text = new StyledString(start > context + 1
					? ELLIPSIS + preview.substring(start - context, start).stripLeading()
					: preview.substring(0, start));
			String replacement = replacementPreview.apply(match);
			if (replacement == null) {
				text.append(preview.substring(start, end), matchStyler);
			} else {
				text.append(preview.substring(start, end), removedStyler);
				text.append(singleLine(replacement), addedStyler);
			}
			text.append(preview.substring(end));
			return text;
		}
		return new StyledString(String.valueOf(element));
	}

	@Override
	public String getToolTipText(Object element) {
		if (element instanceof LineMatch match) {
			return match.getLineNumber() + ": " + match.getPreview();
		}
		if (element instanceof FileMatch fileMatch) {
			return fileMatch.getFile().getFullPath().makeRelative().toString();
		}
		return null;
	}

	@Override
	public void dispose() {
		// the images are owned by the workbench label provider
		fileImages.clear();
		workbenchLabels.dispose();
		super.dispose();
	}

	private static String singleLine(String text) {
		return text.replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎').replace('\t', ' ');
	}

	private static Styler background(String colorKey, boolean strikeout) {
		return new Styler() {
			@Override
			public void applyStyles(TextStyle style) {
				style.background = JFaceResources.getColorRegistry().get(colorKey);
				style.strikeout = strikeout;
			}
		};
	}
}
