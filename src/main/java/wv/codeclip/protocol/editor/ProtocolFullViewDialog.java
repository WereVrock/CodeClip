package wv.codeclip.protocol.editor;

import javax.swing.*;
import java.awt.*;

/**
 * Resizable, maximizable dialog showing the full color-coded .prtcl content
 * for easy reading of long files — distinct from the diff view in
 * DiffAcceptDialog, which only shows changes. This shows everything.
 */
public final class ProtocolFullViewDialog extends JDialog {

    public ProtocolFullViewDialog(Window owner, String fileName, String rawText) {
        super(owner, "Full View — " + fileName, ModalityType.MODELESS);
        setLayout(new BorderLayout());
        setResizable(true);

        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ProtocolSyntaxColorizer.render(pane, rawText);

        JScrollPane scroll = new JScrollPane(pane);
        add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());
        bottom.add(closeBtn);
        add(bottom, BorderLayout.SOUTH);

        setSize(900, 800);
        setMinimumSize(new Dimension(400, 300));
        setLocationRelativeTo(owner);

        // Standard JDialogs support the OS maximize control automatically on
        // most platforms when using a JFrame owner + not undecorated; if the
        // window manager doesn't show a maximize box for dialogs, offer one
        // via a keyboard shortcut and a menu-free toolbar button as a fallback.
        JButton maximizeBtn = new JButton("⛶ Maximize");
        maximizeBtn.addActionListener(e -> {
            GraphicsConfiguration gc = getGraphicsConfiguration();
            Rectangle bounds = gc.getBounds();
            setBounds(bounds);
        });
        bottom.add(maximizeBtn, 0);
    }
}