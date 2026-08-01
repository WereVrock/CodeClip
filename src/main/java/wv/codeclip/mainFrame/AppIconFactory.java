// ===== mainFrame/AppIconFactory.java =====
package wv.codeclip.mainFrame;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Builds the application icon. Tries to load /icon.png from the classpath
 * first; falls back to a programmatically drawn clipboard/code icon if that
 * resource is missing.
 */
public final class AppIconFactory {

    private static final int ICON_SIZE = 64;

    private AppIconFactory() {
    }

    /** Returns the app icon image with no tint applied (legacy behavior). */
    public static Image build() {
        return build(null);
    }

    /**
     * Returns the app icon image, loading /icon.png if present, otherwise
     * generating a fallback icon, then applying a hue tint derived from
     * tintSeed. Pass null to skip tinting.
     */
    public static Image build(Long tintSeed) {
        Image base;
        java.net.URL iconURL = AppIconFactory.class.getResource("/icon.png");
        if (iconURL != null) {
            base = new ImageIcon(iconURL).getImage();
        } else {
            base = buildFallbackIcon();
        }
        if (tintSeed == null) {
            return base;
        }
        return applyTint(base, tintSeed);
    }

    /**
     * Applies a hue rotation + a colored corner badge to the base image,
     * derived deterministically from the seed. Using a fixed palette of
     * well-separated hues (rather than a fully continuous random hue) keeps
     * adjacent instances visually distinct instead of two similar blues.
     */
    private static Image applyTint(Image base, long seed) {
        float[] palette = {
                0f, 30f, 50f, 120f, 160f, 200f, 260f, 300f, 330f
        };
        java.util.Random rnd = new java.util.Random(seed);
        float hueDeg = palette[rnd.nextInt(palette.length)];
        Color tint = Color.getHSBColor(hueDeg / 360f, 0.75f, 0.95f);

        int w = base.getWidth(null);
        int h = base.getHeight(null);
        if (w <= 0 || h <= 0) {
            w = ICON_SIZE;
            h = ICON_SIZE;
        }

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = result.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.drawImage(base, 0, 0, w, h, null);

        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, 0.35f));
        g2.setColor(tint);
        g2.fillRoundRect(0, 0, w, h, (int) (w * 0.22), (int) (h * 0.22));
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));

        int badgeSize = Math.max(10, (int) (w * 0.34));
        int bx = w - badgeSize - Math.max(1, (int) (w * 0.03));
        int by = Math.max(1, (int) (h * 0.03));
        g2.setColor(tint.darker());
        g2.fillOval(bx, by, badgeSize, badgeSize);
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(Math.max(1.5f, w * 0.03f)));
        g2.drawOval(bx, by, badgeSize, badgeSize);

        g2.dispose();
        return result;
    }

    private static Image buildFallbackIcon() {
        BufferedImage img = new BufferedImage(
                ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Background rounded square
        GradientPaint bgGrad = new GradientPaint(0, 0, new Color(79, 110, 247),
                ICON_SIZE, ICON_SIZE, new Color(43, 156, 216));
        g2.setPaint(bgGrad);
        g2.fillRoundRect(2, 2, ICON_SIZE - 4, ICON_SIZE - 4, 14, 14);

        // Clipboard body
        g2.setColor(Color.WHITE);
        g2.fillRoundRect(12, 20, ICON_SIZE - 24, ICON_SIZE - 28, 8, 8);

        // Clip
        GradientPaint clipGrad = new GradientPaint(0, 0, new Color(208, 213, 221),
                ICON_SIZE, 0, new Color(160, 170, 181));
        g2.setPaint(clipGrad);
        g2.fillRoundRect(ICON_SIZE / 2 - 12, 14, 24, 10, 4, 4);
        g2.setColor(new Color(176, 184, 194));
        g2.fillRect(ICON_SIZE / 2 - 12, 24, 24, 2);

        // Code brackets
        g2.setColor(new Color(79, 110, 247, 220));
        g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cy = ICON_SIZE / 2 + 8;
        g2.drawLine(26, cy - 10, 24, cy);
        g2.drawLine(24, cy, 26, cy + 10);
        g2.drawLine(ICON_SIZE - 26, cy - 10, ICON_SIZE - 24, cy);
        g2.drawLine(ICON_SIZE - 24, cy, ICON_SIZE - 26, cy + 10);

        // Center dot
        g2.fillOval(ICON_SIZE / 2 - 3, cy - 3, 6, 6);
        g2.dispose();
        return img;
    }
}