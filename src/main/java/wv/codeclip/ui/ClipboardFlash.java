package wv.codeclip.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Shows a small "Copied" badge in the bottom-right corner of the active
 * window after something was copied to the clipboard. Only the badge area
 * is ever repainted, so it is cheap. Safe to call from any thread.
 */
public final class ClipboardFlash {

    private static final int HOLD_MS = 600;
    private static final int FADE_MS = 300;
    private static final int TICK_MS = 40;
    private static final int MARGIN = 14;
    private static final String PROP = "clipboardIndicator";

    private ClipboardFlash() {}

    public static void flash() {
        if (SwingUtilities.isEventDispatchThread()) {
            show();
        } else {
            SwingUtilities.invokeLater(ClipboardFlash::show);
        }
    }

    private static void show() {
        Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (!(w instanceof RootPaneContainer) || !w.isShowing()) {
            w = null;
            for (Window cand : Window.getWindows()) {
                if (cand instanceof JFrame && cand.isShowing()) {
                    w = cand;
                    break;
                }
            }
        }
        if (!(w instanceof RootPaneContainer rpc)) return;

        JLayeredPane layered = rpc.getRootPane().getLayeredPane();
        Object existing = layered.getClientProperty(PROP);
        Badge badge;
        if (existing instanceof Badge b) {
            badge = b;
        } else {
            badge = new Badge(layered);
            layered.putClientProperty(PROP, badge);
        }
        badge.restart();
    }

    private static final class Badge extends JComponent {
        private static final String TEXT = "✓ Copied";

        private final JLayeredPane layered;
        private final Timer timer;
        private final Font font = new Font(Font.SANS_SERIF, Font.BOLD, 12);
        private int elapsed;
        private float alpha = 1f;
        private boolean attached;

        Badge(JLayeredPane layered) {
            this.layered = layered;
            setOpaque(false);
            FontMetrics fm = getFontMetrics(font);
            setSize(fm.stringWidth(TEXT) + 24, fm.getHeight() + 10);
            timer = new Timer(TICK_MS, e -> tick());
        }

        void restart() {
            elapsed = 0;
            alpha = 1f;
            setLocation(Math.max(0, layered.getWidth() - getWidth() - MARGIN),
                    Math.max(0, layered.getHeight() - getHeight() - MARGIN));
            if (!attached) {
                layered.add(this, JLayeredPane.POPUP_LAYER);
                attached = true;
            }
            repaint();
            timer.restart();
        }

        private void tick() {
            elapsed += TICK_MS;
            if (elapsed >= HOLD_MS + FADE_MS) {
                timer.stop();
                layered.remove(this);
                layered.repaint(getX(), getY(), getWidth(), getHeight());
                attached = false;
                return;
            }
            if (elapsed > HOLD_MS) {
                alpha = 1f - (elapsed - HOLD_MS) / (float) FADE_MS;
                repaint();
            }
        }

        /** Never swallow mouse events. */
        @Override
        public boolean contains(int x, int y) {
            return false;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alpha))));
                g2.setColor(new Color(34, 150, 70));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.setColor(Color.WHITE);
                g2.setFont(font);
                FontMetrics fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(TEXT)) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(TEXT, x, y);
            } finally {
                g2.dispose();
            }
        }
    }
}