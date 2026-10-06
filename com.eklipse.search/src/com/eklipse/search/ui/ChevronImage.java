package com.eklipse.search.ui;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;

/**
 * The replace toggle's chevron, drawn in code so it is crisp at any zoom. Mid gray like Eclipse's find/replace
 * overlay icons, so it is visible on light and dark themes (Eclipse's own chevron icons are near black).
 */
final class ChevronImage {

	private static final int GRAY = 0x808080;
	private static final double STROKE_HALF_WIDTH = 0.8;
	private static final int SUPERSAMPLING = 4;

	private ChevronImage() {
	}

	/**
	 * @param expanded {@code true} for a downwards chevron, {@code false} for one pointing right
	 * @return the image, 16x16 logical pixels
	 */
	static ImageDescriptor descriptor(boolean expanded) {
		// polyline in 16 x 16 units
		double[] points = expanded ? new double[] { 4, 6, 8, 10, 12, 6 } : new double[] { 6, 4, 10, 8, 6, 12 };
		return ImageDescriptor.createFromImageDataProvider(zoom -> draw(points, zoom));
	}

	private static ImageData draw(double[] points, int zoom) {
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
						if (distanceToPolyline(points, px, py) <= STROKE_HALF_WIDTH) {
							covered++;
						}
					}
				}
				data.setPixel(x, y, GRAY);
				data.setAlpha(x, y, 255 * covered / (SUPERSAMPLING * SUPERSAMPLING));
			}
		}
		return data;
	}

	private static double distanceToPolyline(double[] points, double px, double py) {
		double min = Double.MAX_VALUE;
		for (int i = 0; i + 3 < points.length; i += 2) {
			min = Math.min(min, distanceToSegment(points[i], points[i + 1], points[i + 2], points[i + 3], px, py));
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
