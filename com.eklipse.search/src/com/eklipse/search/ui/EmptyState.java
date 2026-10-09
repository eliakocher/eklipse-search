package com.eklipse.search.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.FontDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.util.Util;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;

import com.eklipse.search.core.SearchQuery;

/**
 * Shown instead of the results and the preview while both would be empty: a magnifier, a search to try and a tip
 * while nothing is searched, or what limited a search that found nothing.
 * <p>
 * Painted in the colors and font of the results tree, so it looks the same in light and dark theme.
 */
final class EmptyState {

	private static final String ELLIPSIS = "…";
	private static final int MARGIN = 16;
	private static final int ICON_SIZE = 72;
	private static final int GAP = 14;
	/** Share of the text color in the icon, the rest is background, so the icon stays in the background. */
	private static final double ICON_TEXT_SHARE = 0.35;

	private final Canvas canvas;
	private final Control colors;
	private final LocalResourceManager resources;
	private final List<String> tips = tips();
	private int tip = ThreadLocalRandom.current().nextInt(tips.size());
	private boolean noResults;
	private String heading;
	private String suggestionPrefix;
	private String suggestion;
	private String suggestionSuffix;
	/** The tip, or what limited the search, {@code null} for none. */
	private String footer;
	private Rectangle suggestionBounds = new Rectangle(0, 0, 0, 0);
	private boolean hover;

	/**
	 * @param parent the composite to create the control in
	 * @param colors the control whose colors and font to paint with
	 * @param resources the resources to create the heading font with
	 * @param search searches for the suggestion when it's clicked
	 */
	EmptyState(Composite parent, Control colors, LocalResourceManager resources, Consumer<String> search) {
		this.colors = colors;
		this.resources = resources;
		// a click shouldn't take the focus from the search field
		canvas = new Canvas(parent, SWT.DOUBLE_BUFFERED | SWT.NO_FOCUS);
		canvas.addListener(SWT.Paint, this::paint);
		canvas.addListener(SWT.Resize, e -> canvas.redraw());
		canvas.addListener(SWT.MouseMove, e -> setHover(suggestionBounds.contains(e.x, e.y)));
		canvas.addListener(SWT.MouseExit, e -> setHover(false));
		canvas.addListener(SWT.MouseUp, e -> {
			if (e.button == 1 && suggestionBounds.contains(e.x, e.y)) {
				search.accept(suggestion);
			}
		});
		canvas.getAccessible().addAccessibleListener(new AccessibleAdapter() {
			@Override
			public void getName(AccessibleEvent e) {
				e.result = heading + ". " + suggestionPrefix + suggestion + suggestionSuffix
						+ (footer != null ? ". " + footer : "");
			}
		});
		showNothingSearched();
	}

	Control getControl() {
		return canvas;
	}

	boolean isNothingSearched() {
		return !noResults;
	}

	/**
	 * Shows that nothing is searched yet, with another tip than last time.
	 */
	void showNothingSearched() {
		tip = (tip + 1 + ThreadLocalRandom.current().nextInt(tips.size() - 1)) % tips.size();
		show(false, "Nothing searched yet", "Try searching for ", "Spellcheck", "", "Tip: " + tips.get(tip));
	}

	/**
	 * @param query the search that found nothing
	 */
	void showNoResults(SearchQuery query) {
		show(true, "Nothing matches “" + query.text() + "”", "Have you tried ", "Autocorrect", "?", limits(query));
	}

	private void show(boolean noResults, String heading, String suggestionPrefix, String suggestion,
			String suggestionSuffix, String footer) {
		this.noResults = noResults;
		this.heading = heading;
		this.suggestionPrefix = suggestionPrefix;
		this.suggestion = suggestion;
		this.suggestionSuffix = suggestionSuffix;
		this.footer = footer;
		canvas.redraw();
	}

	private static List<String> tips() {
		String alt = Util.isMac() ? "⌥" : "Alt+";
		String ctrl = Util.isMac() ? "⌘" : "Ctrl+";
		return List.of("Select text in an editor before opening the view to search for it.",
				"↑ and ↓ in the search field browse your last searches.",
				alt + "C, " + alt + "W and " + alt + "R toggle match case, whole word and regex.",
				"Click a result to preview it, double-click to open it.",
				"Delete hides a result until the next search.",
				ctrl + "C copies the selected results with their line numbers.",
				(Util.isMac() ? "⌘↩" : "Ctrl+Enter") + " in the replace field replaces all matches.",
				"Include src/main/** to leave out the tests, › shows the field.",
				"* matches any text: final*size finds final int size, \\* a star.");
	}

	/**
	 * @return what easily hides a match, e.g. "Searched only *.java, with match case.", {@code null} for nothing; the
	 *         excludes are left out, they are set once and rarely the reason
	 */
	private static String limits(SearchQuery query) {
		List<String> options = new ArrayList<>();
		if (query.caseSensitive()) {
			options.add("match case");
		}
		if (query.wholeWord()) {
			options.add("whole word");
		}
		if (query.regex()) {
			options.add("regex");
		}
		String includes = query.includes().trim();
		if (includes.isEmpty() && options.isEmpty()) {
			return null;
		}
		StringBuilder text = new StringBuilder("Searched");
		if (!includes.isEmpty()) {
			text.append(" only ").append(includes);
		}
		if (!options.isEmpty()) {
			text.append(includes.isEmpty() ? " with " : ", with ");
			int last = options.size() - 1;
			text.append(last == 0 ? options.get(0)
					: String.join(", ", options.subList(0, last)) + " and " + options.get(last));
		}
		return text.append('.').toString();
	}

	private void setHover(boolean hover) {
		if (this.hover != hover) {
			this.hover = hover;
			canvas.setCursor(hover ? canvas.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
			canvas.redraw();
		}
	}

	private void paint(Event e) {
		GC gc = e.gc;
		Rectangle area = canvas.getClientArea();
		Color background = colors.getBackground();
		Color foreground = colors.getForeground();
		gc.setBackground(background);
		gc.fillRectangle(area);
		gc.setAntialias(SWT.ON);
		int width = Math.max(1, area.width - 2 * MARGIN);

		Font font = colors.getFont();
		Font headingFont = resources.create(FontDescriptor.createFrom(font).setStyle(SWT.BOLD).increaseHeight(1));
		gc.setFont(headingFont);
		List<String> headingLines = wrap(gc, heading, width);
		int headingLine = gc.getFontMetrics().getHeight();
		gc.setFont(font);
		List<String> footerLines = footer != null ? wrap(gc, footer, width) : List.of();
		int line = gc.getFontMetrics().getHeight();
		int height = ICON_SIZE + GAP + headingLines.size() * headingLine + GAP / 2 + line
				+ (footerLines.isEmpty() ? 0 : GAP + footerLines.size() * line);
		// a bit above the middle looks centered
		int y = area.y + Math.max(MARGIN, (area.height - height) * 2 / 5);

		drawIcon(gc, area.x + (area.width - ICON_SIZE) / 2, y, mix(background, foreground, ICON_TEXT_SHARE),
				!noResults);
		y += ICON_SIZE + GAP;

		gc.setForeground(foreground);
		gc.setFont(headingFont);
		y = drawCentered(gc, headingLines, area, y, headingLine);
		y += GAP / 2;

		gc.setFont(font);
		Color qualifier = JFaceResources.getColorRegistry().get(JFacePreferences.QUALIFIER_COLOR);
		Color link = JFaceResources.getColorRegistry().get(JFacePreferences.HYPERLINK_COLOR);
		int prefixWidth = gc.textExtent(suggestionPrefix).x;
		Point suggestionSize = gc.textExtent(suggestion);
		int lineWidth = prefixWidth + suggestionSize.x + gc.textExtent(suggestionSuffix).x;
		int x = area.x + Math.max(MARGIN, (area.width - lineWidth) / 2);
		int suggestionX = x + prefixWidth;
		gc.setForeground(qualifier != null ? qualifier : foreground);
		gc.drawText(suggestionPrefix, x, y, true);
		gc.drawText(suggestionSuffix, suggestionX + suggestionSize.x, y, true);
		gc.setForeground(link != null ? link : canvas.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND));
		gc.drawText(suggestion, suggestionX, y, true);
		suggestionBounds = new Rectangle(suggestionX, y, suggestionSize.x, suggestionSize.y);
		if (hover) {
			int underline = y + gc.getFontMetrics().getAscent() + 1;
			gc.setLineWidth(1);
			gc.drawLine(suggestionX, underline, suggestionX + suggestionSize.x, underline);
		}
		y += line + GAP;

		gc.setForeground(qualifier != null ? qualifier : foreground);
		drawCentered(gc, footerLines, area, y, line);
	}

	/**
	 * A magnifier over two lines of text, the second one underlined like a spelling mistake, or over a red X when
	 * nothing was found.
	 */
	private static void drawIcon(GC gc, int x, int y, Color color, boolean text) {
		int radius = 22;
		int cx = x + 30;
		int cy = y + 30;
		gc.setForeground(color);
		gc.setLineCap(SWT.CAP_ROUND);
		gc.setLineWidth(5);
		gc.drawOval(cx - radius, cy - radius, 2 * radius, 2 * radius);
		int handleStart = (int) Math.round((radius + 3) * Math.sqrt(0.5));
		gc.setLineWidth(8);
		gc.drawLine(cx + handleStart, cy + handleStart, x + ICON_SIZE - 6, y + ICON_SIZE - 6);

		Color error = JFaceResources.getColorRegistry().get(JFacePreferences.ERROR_COLOR);
		Color red = error != null ? error : gc.getDevice().getSystemColor(SWT.COLOR_RED);
		if (text) {
			// both lines and the squiggle as wide, 8 zigzags of 3 pixels; the thicker lines end a pixel earlier, their
			// round caps reach further
			int half = 12;
			gc.setLineWidth(3);
			gc.drawLine(cx - half, cy - 6, cx + half - 1, cy - 6);
			gc.drawLine(cx - half, cy + 3, cx + half - 1, cy + 3);
			gc.setForeground(red);
			gc.setLineWidth(2);
			int[] squiggle = new int[2 * (2 * half / 3 + 1)];
			for (int i = 0; i < squiggle.length / 2; i++) {
				squiggle[2 * i] = cx - half + 3 * i;
				squiggle[2 * i + 1] = cy + (i % 2 == 0 ? 8 : 11);
			}
			gc.drawPolyline(squiggle);
		} else {
			int arm = 8;
			gc.setForeground(red);
			gc.setLineWidth(4);
			gc.drawLine(cx - arm, cy - arm, cx + arm, cy + arm);
			gc.drawLine(cx - arm, cy + arm, cx + arm, cy - arm);
		}
		gc.setLineCap(SWT.CAP_FLAT);
	}

	private static int drawCentered(GC gc, List<String> lines, Rectangle area, int y, int lineHeight) {
		for (String text : lines) {
			int x = area.x + Math.max(MARGIN, (area.width - gc.textExtent(text).x) / 2);
			gc.drawText(text, x, y, true);
			y += lineHeight;
		}
		return y;
	}

	/**
	 * @return the text broken into lines at spaces, each fitting into the width; a word too long for a line of its
	 *         own, e.g. a long search text, is cut off with an ellipsis
	 */
	private static List<String> wrap(GC gc, String text, int width) {
		List<String> lines = new ArrayList<>();
		String line = "";
		for (String word : text.split(" ")) {
			String longer = line.isEmpty() ? word : line + " " + word;
			if (gc.textExtent(longer).x <= width) {
				line = longer;
			} else {
				if (!line.isEmpty()) {
					lines.add(line);
				}
				line = ellipsize(gc, word, width);
			}
		}
		lines.add(line);
		return lines;
	}

	private static String ellipsize(GC gc, String text, int width) {
		if (gc.textExtent(text).x <= width) {
			return text;
		}
		int end = text.length() - 1;
		while (end > 1 && gc.textExtent(text.substring(0, end) + ELLIPSIS).x > width) {
			end--;
		}
		return text.substring(0, end) + ELLIPSIS;
	}

	private static Color mix(Color background, Color text, double textShare) {
		RGB b = background.getRGB();
		RGB t = text.getRGB();
		return new Color(b.red + (int) ((t.red - b.red) * textShare), b.green + (int) ((t.green - b.green) * textShare),
				b.blue + (int) ((t.blue - b.blue) * textShare));
	}
}
