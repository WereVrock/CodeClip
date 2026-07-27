package wv.codeclip.protocol.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;

/**
 * A large, clickable lock icon showing master-lock state at a glance.
 * Red and visually "closed" when locked, gray and "open" when unlocked.
 * Drawn directly (no image file dependency).
 */
public final class LockIndicator extends JPanel {

    private boolean locked = false;
    private Consumer<Boolean> onToggle = b -> {};

    public LockIndicator() {
        setPreferredSize(new Dimension(56, 56));
        setToolTipText("Click to toggle master lock");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setLocked(!locked);
                onToggle.accept(locked);
            }
            @Override
            public void mouseEntered(MouseEvent e) { setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); }
        });
    }

    public void setOnToggle(Consumer<Boolean> callback) {
        this.onToggle = callback;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        setToolTipText(locked ? "Master Lock: ON — click to unlock all files" : "Master Lock: OFF — click to lock all files");
        repaint();
    }

    public boolean isLocked() {
        return locked;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int bodyW = (int) (w * 0.6);
        int bodyH = (int) (h * 0.45);
        int bodyX = (w - bodyW) / 2;
        int bodyY = (int) (h * 0.48);

        Color mainColor = locked ? new Color(190, 40, 40) : new Color(120, 120, 120);
        g2.setColor(mainColor);

        // Shackle
        int shackleW = (int) (bodyW * 0.65);
        int shackleH = (int) (h * 0.4);
        int shackleX = (w - shackleW) / 2;
        int shackleY = bodyY - shackleH + (int) (shackleH * 0.35);

        g2.setStroke(new BasicStroke(Math.max(3f, w * 0.09f)));
        if (locked) {
            g2.drawArc(shackleX, shackleY, shackleW, shackleH, 0, 180);
        } else {
            // "open" lock: shackle swung to one side
            g2.drawArc(shackleX - (int)(shackleW * 0.3), shackleY - (int)(shackleH*0.1), shackleW, shackleH, 20, 180);
        }

        // Body
        g2.fillRoundRect(bodyX, bodyY, bodyW, bodyH, 6, 6);

        // Keyhole
        g2.setColor(Color.WHITE);
        int keyholeSize = Math.max(4, bodyW / 6);
        int keyholeX = bodyX + bodyW / 2 - keyholeSize / 2;
        int keyholeY = bodyY + bodyH / 3;
        g2.fillOval(keyholeX, keyholeY, keyholeSize, keyholeSize);
        g2.fillRect(keyholeX + keyholeSize / 3, keyholeY + keyholeSize / 2, keyholeSize / 3, keyholeSize);

        g2.dispose();
    }
}