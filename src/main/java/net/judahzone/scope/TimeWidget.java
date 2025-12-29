package net.judahzone.scope;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Display something (spectrum, RMS) in the Time Domain */
public abstract class TimeWidget extends BufferedImage {

	protected static final Color BACKGROUND = Color.WHITE;
	protected static final Color HEAD = Color.DARK_GRAY;

	protected int w, h;
	protected final Graphics2D g2d;
	protected final Transform[] db;

	public TimeWidget(Dimension size, Transform[] db) { // fixed length
		super(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
		this.db = db;
		w = size.width;
		h = size.height;
		g2d = createGraphics();
		g2d.setBackground(BACKGROUND);
		g2d.clearRect(0, 0, w, h);
	}

	/**
	 * Regenerate the offscreen image for a given viewport over db[].
	 *
	 * @param unit        pixels per index (can be fractional)
	 * @param startIndex  inclusive start index in db[]
	 * @param endIndex    inclusive end index in db[]
	 */
	abstract void generateImage(float unit, int startIndex, int endIndex);

	/**
	 * Render/update a single index within the current viewport.
	 *
	 * @param xOnScreen   pixel X coordinate (already mapped)
	 * @param t           transform for that index
	 * @param cellWidth   width in pixels of this cell/bar
	 */
	abstract void analyze(int xOnScreen, Transform t, int cellWidth);

	public void clearRect(int x, int width) {
		g2d.clearRect(x, 0, width, h);
	}

	protected void drawBorder() {
		g2d.setColor(Color.LIGHT_GRAY);
		g2d.drawLine(0, 0, w, 0);
		g2d.drawLine(w - 1, 0, w - 1, h);
		g2d.drawLine(0, h - 1, w, h - 1);
		g2d.drawLine(0, 0, 0, h);
	}

	public final void close() {
		g2d.dispose();
	}
}