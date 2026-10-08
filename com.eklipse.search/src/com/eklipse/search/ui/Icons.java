package com.eklipse.search.ui;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;

/**
 * The view's icons in one style: thin gray lines like Eclipse's find/replace overlay icons, drawn in code so they are
 * crisp at any zoom. In a dark theme they, and the overlay icons used next to them, are lighter: the overlay icons'
 * mid gray is hard to see on a dark background. Each icon is always the same descriptor, so a resource manager creates
 * its image once.
 */
final class Icons {

	private static final int GRAY = 0x808080;
	/** How far gray is moved towards white in a dark theme. */
	private static final double LIGHTEN = 0.5;
	/** Pixels whose channels differ less than this count as gray, colored parts of an icon keep their color. */
	private static final int GRAY_TOLERANCE = 24;
	private static final int SUPERSAMPLING = 4;
	private static final double[] FRONT_BOX = { 2.5, 5.5, 10.5, 5.5, 10.5, 13.5, 2.5, 13.5, 2.5, 5.5 };
	private static final double[] BACK_BOX = { 5.5, 5.5, 5.5, 2.5, 13.5, 2.5, 13.5, 10.5, 10.5, 10.5 };
	private static final double[] MINUS = { 4.5, 9.5, 8.5, 9.5 };

	private final boolean dark;
	private final int color;
	private final ImageDescriptor chevronRight;
	private final ImageDescriptor chevronDown;
	private final ImageDescriptor funnel;
	private final ImageDescriptor expandAll;
	private final ImageDescriptor collapseAll;

	/**
	 * @param background the background the icons are shown on
	 */
	Icons(RGB background) {
		dark = 0.299 * background.red + 0.587 * background.green + 0.114 * background.blue < 128;
		color = dark ? lighten(GRAY) : GRAY;
		chevronRight = draw(0.8, new double[] { 6, 4, 10, 8, 6, 12 });
		chevronDown = draw(0.8, new double[] { 4, 6, 8, 10, 12, 6 });
		funnel = draw(0.65, new double[] { 2, 3.5, 14, 3.5, 10, 8.5, 10, 13.5, 6, 12, 6, 8.5, 2, 3.5 });
		expandAll = draw(0.6, FRONT_BOX, BACK_BOX, MINUS, new double[] { 6.5, 7.5, 6.5, 11.5 });
		collapseAll = draw(0.6, FRONT_BOX, BACK_BOX, MINUS);
	}

	/**
	 * @param expanded {@code true} for a downwards chevron, {@code false} for one pointing right
	 * @return the chevron of the replace toggle and the history drop-downs
	 */
	ImageDescriptor chevron(boolean expanded) {
		return expanded ? chevronDown : chevronRight;
	}

	/**
	 * @return a funnel, for the derived resources filter
	 */
	ImageDescriptor funnel() {
		return funnel;
	}

	/**
	 * @return two stacked boxes with a plus
	 */
	ImageDescriptor expandAll() {
		return expandAll;
	}

	/**
	 * @return two stacked boxes with a minus
	 */
	ImageDescriptor collapseAll() {
		return collapseAll;
	}

	/**
	 * @param icon one of Eclipse's icons, {@code null} for none
	 * @return the icon, with its gray parts lighter in a dark theme
	 */
	ImageDescriptor adapt(ImageDescriptor icon) {
		if (icon == null || !dark) {
			return icon;
		}
		return ImageDescriptor.createFromImageDataProvider(zoom -> {
			ImageData data = icon.getImageData(zoom);
			return data != null ? lightenGray(data) : null;
		});
	}

	private static ImageData lightenGray(ImageData data) {
		ImageData result = new ImageData(data.width, data.height, 24, new PaletteData(0xFF0000, 0x00FF00, 0x0000FF));
		int transparency = data.getTransparencyType();
		ImageData mask = transparency == SWT.TRANSPARENCY_MASK || transparency == SWT.TRANSPARENCY_PIXEL
				? data.getTransparencyMask()
				: null;
		for (int y = 0; y < data.height; y++) {
			for (int x = 0; x < data.width; x++) {
				RGB rgb = data.palette.getRGB(data.getPixel(x, y));
				int pixel = rgb.red << 16 | rgb.green << 8 | rgb.blue;
				int max = Math.max(rgb.red, Math.max(rgb.green, rgb.blue));
				int min = Math.min(rgb.red, Math.min(rgb.green, rgb.blue));
				result.setPixel(x, y, max - min < GRAY_TOLERANCE ? lighten(pixel) : pixel);
				result.setAlpha(x, y, mask != null ? (mask.getPixel(x, y) == 0 ? 0 : 255) : data.getAlpha(x, y));
			}
		}
		return result;
	}

	private static int lighten(int rgb) {
		int result = 0;
		for (int shift = 0; shift <= 16; shift += 8) {
			int channel = rgb >> shift & 0xFF;
			result |= (int) (channel + (255 - channel) * LIGHTEN) << shift;
		}
		return result;
	}

	/**
	 * @param strokeHalfWidth half the line width, in 16 x 16 units
	 * @param polylines the lines, each as x, y pairs in 16 x 16 units
	 */
	private ImageDescriptor draw(double strokeHalfWidth, double[]... polylines) {
		return ImageDescriptor.createFromImageDataProvider(zoom -> {
			int size = Math.max(16, 16 * zoom / 100);
			double scale = size / 16.0;
			ImageData data = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0x00FF00, 0x0000FF));
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					int covered = 0;
					for (int sy = 0; sy < SUPERSAMPLING; sy++) {
						for (int sx = 0; sx < SUPERSAMPLING; sx++) {
							double px = (x + (sx + 0.5) / SUPERSAMPLING) / scale;
							double py = (y + (sy + 0.5) / SUPERSAMPLING) / scale;
							if (distance(polylines, px, py) <= strokeHalfWidth) {
								covered++;
							}
						}
					}
					data.setPixel(x, y, color);
					data.setAlpha(x, y, 255 * covered / (SUPERSAMPLING * SUPERSAMPLING));
				}
			}
			return data;
		});
	}

	private static double distance(double[][] polylines, double px, double py) {
		double min = Double.MAX_VALUE;
		for (double[] points : polylines) {
			for (int i = 0; i + 3 < points.length; i += 2) {
				min = Math.min(min, distanceToSegment(points[i], points[i + 1], points[i + 2], points[i + 3], px, py));
			}
		}
		return min;
	}

	private static double distanceToSegment(double x1, double y1, double x2, double y2, double px, double py) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)));
		return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
	}
}
