package wv.codeclip.ui;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.*;

/**
 * Hand-drawn icons for the list/tree view toggle. Drawn as vector graphics
 * so there's no image file dependency, and the color follows the button's
 * foreground so it reads correctly on light and dark themes and in the
 * disabled state.
 */
public final class TreeViewIcons {

    private TreeViewIcons() {}

    /**
     * A downward-branching tree: vertical trunk on the left with three
     * horizontal branches ending in small leaf dots. Visually mirrors the
     * tree view it toggles to.
     */
    public static Icon tree() {
        return new Icon() {
            @Override public int getIconWidth()  { return 14; }
            @Override public int getIconHeight() { return 14; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(colorFor(c));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                                             BasicStroke.JOIN_ROUND));
                // trunk
                g2.drawLine(x + 3, y + 2, x + 3, y + 11);
                // three branches
                g2.drawLine(x + 3, y + 3,  x + 10, y + 3);
                g2.drawLine(x + 3, y + 7,  x + 10, y + 7);
                g2.drawLine(x + 3, y + 11, x + 10, y + 11);
                // leaf dots at branch tips
                g2.fillOval(x + 9, y + 2,  3, 3);
                g2.fillOval(x + 9, y + 6,  3, 3);
                g2.fillOval(x + 9, y + 10, 3, 3);
                g2.dispose();
            }
        };
    }

    /** Three horizontal lines (hamburger). Same weight/size as tree(). */
    public static Icon list() {
        return new Icon() {
            @Override public int getIconWidth()  { return 14; }
            @Override public int getIconHeight() { return 14; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(colorFor(c));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                                             BasicStroke.JOIN_ROUND));
                g2.drawLine(x + 2, y + 3,  x + 11, y + 3);
                g2.drawLine(x + 2, y + 7,  x + 11, y + 7);
                g2.drawLine(x + 2, y + 11, x + 11, y + 11);
                g2.dispose();
            }
        };
    }

    private static Color colorFor(Component c) {
        if (c.isEnabled()) return c.getForeground();
        Color disabled = UIManager.getColor("Label.disabledForeground");
        return disabled != null ? disabled : Color.GRAY;
    }
}