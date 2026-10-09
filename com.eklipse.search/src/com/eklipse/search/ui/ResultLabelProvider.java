package com.eklipse.search.ui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.TextLayout;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.model.WorkbenchLabelProvider;

import com.eklipse.search.core.FileMatch;
import com.eklipse.search.core.FileNameMatch;
import com.eklipse.search.core.LineMatch;

/**
 * Renders files as {@code Name.java 3  project/folder} and matches as their line with the match highlighted. While the
 * replace field is in use, the match is struck through and followed by its replacement. The selection is painted in a
 * muted gray rather than the native accent color, so the highlighted match stays visible in the selected row.
 */
final class ResultLabelProvider extends StyledCellLabelProvider {

	/** Defined by org.eclipse.search, also used by the File Search view, has a dark theme value. */
	private static final String MATCH_HIGHLIGHT = "org.eclipse.search.ui.match.highlight";
	private static final String REMOVED_HIGHLIGHT = "com.eklipse.search.replace.removed";
	private static final String ADDED_HIGHLIGHT = "com.eklipse.search.replace.added";
	private static final String ELLIPSIS = "…";
	private static final String PATH_SEPARATOR = "  ";
	/** How much of the text color is mixed into the background for the selection, with and without the focus. */
	private static final double FOCUSED_SELECTION = 0.2;
	private static final double SELECTION = 0.12;
	/** How much of the text color is in the line between the files found by their name and the other results. */
	private static final double SEPARATOR = 0.3;

	private final Function<LineMatch, String> replacementPreview;
	private final IntSupplier contextChars;
	private final IntSupplier nameMatchCount;
	private final WorkbenchLabelProvider workbenchLabels = new WorkbenchLabelProvider();
	private final Map<String, Image> fileImages = new HashMap<>();
	private final Styler matchStyler = background(MATCH_HIGHLIGHT, false);
	private final Styler removedStyler = background(REMOVED_HIGHLIGHT, true);
	private final Styler addedStyler = background(ADDED_HIGHLIGHT, false);
	private final Font pathFont;
	private TextLayout fileLayout;

	/**
	 * @param replacementPreview returns the replacement to preview for a match, {@code null} for no preview
	 * @param contextChars returns how many characters before a match fit into the view
	 * @param nameMatchCount returns how many files were found by their name, they are the first ones in the tree
	 * @param pathFont the font of the folder after a file name
	 */
	ResultLabelProvider(Function<LineMatch, String> replacementPreview, IntSupplier contextChars,
			IntSupplier nameMatchCount, Font pathFont) {
		// the selection is painted here, in a gray the match highlight works on
		super(COLORS_ON_SELECTION);
		this.replacementPreview = replacementPreview;
		this.contextChars = contextChars;
		this.nameMatchCount = nameMatchCount;
		this.pathFont = pathFont;
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
		IFile file = element instanceof FileMatch fileMatch ? fileMatch.getFile()
				: element instanceof FileNameMatch nameMatch ? nameMatch.getFile() : null;
		cell.setImage(file != null ? fileImage(file) : null);
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
			text.append(PATH_SEPARATOR + folder(file), StyledString.QUALIFIER_STYLER);
			return text;
		}
		if (element instanceof FileNameMatch nameMatch) {
			IFile file = nameMatch.getFile();
			StyledString text = new StyledString(file.getName());
			text.setStyle(nameMatch.getStart(), nameMatch.getEnd() - nameMatch.getStart(), matchStyler);
			text.append(PATH_SEPARATOR + folder(file), StyledString.QUALIFIER_STYLER);
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
		if (element instanceof FileNameMatch nameMatch) {
			return nameMatch.getFile().getFullPath().makeRelative().toString();
		}
		return null;
	}

	/**
	 * Paints the selection, before the row's content, and clears the selected state: SWT then skips its own selection
	 * and the text keeps its colors.
	 */
	@Override
	protected void erase(Event event, Object element) {
		super.erase(event, element);
		if ((event.detail & SWT.SELECTED) != 0) {
			Tree tree = (Tree) event.widget;
			GC gc = event.gc;
			gc.setBackground(mix(tree, tree.isFocusControl() ? FOCUSED_SELECTION : SELECTION));
			gc.fillRectangle(event.x, event.y, event.width, event.height);
			gc.setForeground(tree.getForeground());
			event.detail &= ~SWT.SELECTED;
		}
	}

	private static Color mix(Tree tree, double textShare) {
		RGB background = tree.getBackground().getRGB();
		RGB text = tree.getForeground().getRGB();
		return new Color((int) (background.red + (text.red - background.red) * textShare),
				(int) (background.green + (text.green - background.green) * textShare),
				(int) (background.blue + (text.blue - background.blue) * textShare));
	}

	@Override
	protected void paint(Event event, Object element) {
		if (element instanceof FileMatch fileMatch) {
			drawSeparator(event);
			paintFile(event, fileMatch.getFile(), " " + fileMatch.getMatchCount(), -1, -1);
		} else if (element instanceof FileNameMatch nameMatch) {
			paintFile(event, nameMatch.getFile(), "", nameMatch.getStart(), nameMatch.getEnd());
		} else {
			super.paint(event, element);
		}
	}

	/**
	 * Like {@code super.paint}, with the folder in a smaller font and shortened in the middle to fit: the project and
	 * the innermost folders say more than the cut off end of a long path.
	 *
	 * @param count the number of matches after the name, empty for none
	 * @param highlightStart the start of the part of the name to highlight, {@code -1} for none
	 * @param highlightEnd the end of the part of the name to highlight
	 */
	private void paintFile(Event event, IFile file, String count, int highlightStart, int highlightEnd) {
		TreeItem item = (TreeItem) event.item;
		GC gc = event.gc;
		Image image = item.getImage();
		if (image != null) {
			Rectangle area = item.getImageBounds(0);
			Rectangle size = image.getBounds();
			gc.drawImage(image, area.x + Math.max(0, (area.width - size.width) / 2),
					area.y + Math.max(0, (area.height - size.height) / 2));
		}
		String name = file.getName() + count + PATH_SEPARATOR;
		Rectangle textArea = item.getTextBounds(0);
		Font font = gc.getFont();
		int width = item.getParent().getClientArea().width - textArea.x - gc.textExtent(name).x;
		gc.setFont(pathFont);
		String text = name + shorten(gc, folder(file), width);
		gc.setFont(font);

		if (fileLayout == null) {
			fileLayout = new TextLayout(event.display);
		}
		// a new text clears the styles, the same one keeps them
		fileLayout.setText("");
		fileLayout.setText(text);
		fileLayout.setFont(font);
		int countStart = file.getName().length() + 1;
		int pathStart = name.length();
		ColorRegistry colors = JFaceResources.getColorRegistry();
		if (highlightStart >= 0) {
			fileLayout.setStyle(new TextStyle(null, null, colors.get(MATCH_HIGHLIGHT)), highlightStart,
					highlightEnd - 1);
		}
		fileLayout.setStyle(new TextStyle(null, colors.get(JFacePreferences.COUNTER_COLOR), null), countStart,
				pathStart - 1);
		fileLayout.setStyle(new TextStyle(pathFont, colors.get(JFacePreferences.QUALIFIER_COLOR), null), pathStart,
				text.length() - 1);
		fileLayout.draw(gc, textArea.x,
				textArea.y + Math.max(0, (textArea.height - fileLayout.getBounds().height) / 2));
	}

	/**
	 * A line above the first file with matches in its content, below the files found by their name.
	 */
	private void drawSeparator(Event event) {
		int names = nameMatchCount.getAsInt();
		TreeItem item = (TreeItem) event.item;
		Tree tree = item.getParent();
		if (names > 0 && names < tree.getItemCount() && tree.getItem(names) == item) {
			GC gc = event.gc;
			Color foreground = gc.getForeground();
			gc.setForeground(mix(tree, SEPARATOR));
			gc.drawLine(0, event.y, tree.getClientArea().width, event.y);
			gc.setForeground(foreground);
		}
	}

	private static String folder(IFile file) {
		return file.getParent().getFullPath().makeRelative().toString();
	}

	/**
	 * @param gc measures with its font
	 * @return the path, or its first segment and as many of its last ones as fit, e.g. {@code project/…/main/java}
	 */
	private static String shorten(GC gc, String path, int width) {
		String[] segments = path.split("/");
		if (segments.length < 3 || gc.textExtent(path).x <= width) {
			return path;
		}
		// the more last segments are kept, the wider: find the most that fit, at least one
		int low = 1;
		int high = segments.length - 2;
		while (low < high) {
			int middle = (low + high + 1) / 2;
			if (gc.textExtent(keepLast(segments, middle)).x <= width) {
				low = middle;
			} else {
				high = middle - 1;
			}
		}
		return keepLast(segments, low);
	}

	private static String keepLast(String[] segments, int count) {
		StringBuilder path = new StringBuilder(segments[0]).append('/').append(ELLIPSIS);
		for (int i = segments.length - count; i < segments.length; i++) {
			path.append('/').append(segments[i]);
		}
		return path.toString();
	}

	@Override
	public void dispose() {
		// the images are owned by the workbench label provider
		fileImages.clear();
		workbenchLabels.dispose();
		if (fileLayout != null) {
			fileLayout.dispose();
		}
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
