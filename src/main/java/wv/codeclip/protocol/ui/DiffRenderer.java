package wv.codeclip.protocol.ui;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.List;

public final class DiffRenderer {

    private static final Color ADDED_BG = new Color(210, 245, 210);
    private static final Color REMOVED_BG = new Color(250, 210, 210);

    private DiffRenderer() {}

    public static JTextPane render(List<DiffLine> lines) {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        StyledDocument doc = pane.getStyledDocument();
        Style added = pane.addStyle("added", null);
        StyleConstants.setBackground(added, ADDED_BG);
        Style removed = pane.addStyle("removed", null);
        StyleConstants.setBackground(removed, REMOVED_BG);
        Style unchanged = pane.addStyle("unchanged", null);

        try {
            for (DiffLine line : lines) {
                String prefix = switch (line.type) {
                    case ADDED -> "+ ";
                    case REMOVED -> "- ";
                    case UNCHANGED -> "  ";
                };
                Style style = switch (line.type) {
                    case ADDED -> added;
                    case REMOVED -> removed;
                    case UNCHANGED -> unchanged;
                };
                doc.insertString(doc.getLength(), prefix + line.text + "\n", style);
            }
        } catch (BadLocationException ignored) {}

        return pane;
    }
}