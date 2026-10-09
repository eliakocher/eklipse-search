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
	/** A toggle's image, in 16 x 16 units: its icon with room for the box around it while it's on. */
	private static final double TOGGLE_SIZE = 20;
	private static final double TOGGLE_RADIUS = 3;
	private static final double TOGGLE_BORDER = 1;
	/** How much of the accent color tints the box. */
	private static final double TOGGLE_FILL = 0.2;
	private static final double TOGGLE_FILL_DARK = 0.35;

	private final boolean dark;
	private final int color;
	private final RGB accent;
	private final ImageDescriptor chevronRight;
	private final ImageDescriptor chevronDown;
	private final ImageDescriptor funnel;
	private final ImageDescriptor preserveCase;

	/**
	 * @param background the background the icons are shown on
	 * @param accent the color of an active toggle's box, e.g. the selection color
	 */
	Icons(RGB background, RGB accent) {
		dark = 0.299 * background.red + 0.587 * background.green + 0.114 * background.blue < 128;
		color = dark ? lighten(GRAY) : GRAY;
		this.accent = accent;
		// as large as the toggles, so the replace toggle lines up with the search field like they do
		chevronRight = toggle(draw(0.8, new double[] { 6, 4, 10, 8, 6, 12 }), false);
		chevronDown = toggle(draw(0.8, new double[] { 4, 6, 8, 10, 12, 6 }), false);
		funnel = draw(0.65, new double[] { 2, 3.5, 14, 3.5, 10, 8.5, 10, 13.5, 6, 12, 6, 8.5, 2, 3.5 });
		// "AB"
		preserveCase = draw(0.6, new double[] { 2, 12, 5, 4, 8, 12 }, new double[] { 3.1, 9.2, 6.9, 9.2 },
				new double[] { 10, 12, 10, 4, 12.3, 4, 13.2, 4.5, 13.6, 5.3, 13.6, 6.4, 13.2, 7.2, 12.3, 7.7, 10, 7.7 },
				new double[] { 10, 7.7, 12.6, 7.7, 13.6, 8.3, 14, 9.2, 14, 10.5, 13.6, 11.4, 12.6, 12, 10, 12 });
	}

	/**
	 * @param expanded {@code true} for a downwards chevron, {@code false} for one pointing right
	 * @return the chevron of the replace toggle
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
	 * @return "AB", for preserving the case when replacing
	 */
	ImageDescriptor preserveCase() {
		return preserveCase;
	}

	/**
	 * A tool bar shows a checked item with a faint frame only. A toggle that's on has its icon in a box of the accent
	 * color, and brighter in a dark theme, darker in a light one. While it's off the icon has the same margin, so the
	 * button keeps its size.
	 *
	 * @param icon a 16 x 16 icon
	 * @param on whether the toggle is on
	 * @return the image of the toggle, a new descriptor on each call
	 */
	ImageDescriptor toggle(ImageDescriptor icon, boolean on) {
		return ImageDescriptor.createFromImageDataProvider(zoom -> {
			ImageData iconData = icon.getImageData(zoom);
			return iconData != null ? toggle(iconData, on) : null;
		});
	}

	private ImageData toggle(ImageData icon, boolean on) {
		double scale = icon.width / 16.0;
		int size = (int) Math.round(TOGGLE_SIZE * scale);
		int offset = (size - icon.width) / 2;
		ImageData mask = transparencyMask(icon);
		double fillAlpha = dark ? TOGGLE_FILL_DARK : TOGGLE_FILL;
		ImageData result = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0x00FF00, 0x0000FF));
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				// the box: its tinted inside, then its border
				double alpha = 0;
				if (on) {
					int inside = 0;
					int edge = 0;
					for (int sy = 0; sy < SUPERSAMPLING; sy++) {
						for (int sx = 0; sx < SUPERSAMPLING; sx++) {
							double px = (x + (sx + 0.5) / SUPERSAMPLING) / scale - TOGGLE_SIZE / 2;
							double py = (y + (sy + 0.5) / SUPERSAMPLING) / scale - TOGGLE_SIZE / 2;
							double distance = roundedBoxDistance(px, py, TOGGLE_SIZE / 2, TOGGLE_RADIUS);
							if (distance <= 0) {
								inside++;
								if (distance > -TOGGLE_BORDER) {
									edge++;
								}
							}
						}
					}
					double samples = SUPERSAMPLING * SUPERSAMPLING;
					double border = edge / samples;
					alpha = border + inside / samples * fillAlpha * (1 - border);
				}
				// the icon over it
				int ix = x - offset;
				int iy = y - offset;
				double iconAlpha = 0;
				int iconRgb = 0;
				if (ix >= 0 && iy >= 0 && ix < icon.width && iy < icon.height) {
					iconAlpha = alpha(icon, mask, ix, iy) / 255.0;
					RGB rgb = icon.palette.getRGB(icon.getPixel(ix, iy));
					iconRgb = on && isGray(rgb) ? (dark ? 0xFFFFFF : 0x202020) : rgb.red << 16 | rgb.green << 8 | rgb.blue;
				}
				double total = iconAlpha + alpha * (1 - iconAlpha);
				int pixel = 0;
				if (total > 0) {
					int boxRgb = accent.red << 16 | accent.green << 8 | accent.blue;
					for (int shift = 0; shift <= 16; shift += 8) {
						double channel = ((iconRgb >> shift & 0xFF) * iconAlpha
								+ (boxRgb >> shift & 0xFF) * alpha * (1 - iconAlpha)) / total;
						pixel |= (int) Math.round(channel) << shift;
					}
				}
				result.setPixel(x, y, pixel);
				result.setAlpha(x, y, (int) Math.round(total * 255));
			}
		}
		return result;
	}

	/**
	 * @return the signed distance of a point to a box with rounded corners centered on 0, 0: negative inside
	 */
	private static double roundedBoxDistance(double px, double py, double half, double radius) {
		double qx = Math.abs(px) - half + radius;
		double qy = Math.abs(py) - half + radius;
		return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - radius;
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
		ImageData mask = transparencyMask(data);
		for (int y = 0; y < data.height; y++) {
			for (int x = 0; x < data.width; x++) {
				RGB rgb = data.palette.getRGB(data.getPixel(x, y));
				int pixel = rgb.red << 16 | rgb.green << 8 | rgb.blue;
				result.setPixel(x, y, isGray(rgb) ? lighten(pixel) : pixel);
				result.setAlpha(x, y, alpha(data, mask, x, y));
			}
		}
		return result;
	}

	/**
	 * @return the mask of an icon with a transparent color or a mask, {@code null} for one with alpha values
	 */
	private static ImageData transparencyMask(ImageData data) {
		int transparency = data.getTransparencyType();
		return transparency == SWT.TRANSPARENCY_MASK || transparency == SWT.TRANSPARENCY_PIXEL
				? data.getTransparencyMask()
				: null;
	}

	private static int alpha(ImageData data, ImageData mask, int x, int y) {
		return mask != null ? (mask.getPixel(x, y) == 0 ? 0 : 255) : data.getAlpha(x, y);
	}

	private static boolean isGray(RGB rgb) {
		int max = Math.max(rgb.red, Math.max(rgb.green, rgb.blue));
		int min = Math.min(rgb.red, Math.min(rgb.green, rgb.blue));
		return max - min < GRAY_TOLERANCE;
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
