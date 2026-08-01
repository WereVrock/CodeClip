package wv.codeclip.protocol.editor;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;

/**
 * Renders raw .prtcl file text into a StyledDocument with syntax coloring:
 * !id lines, !locked, numbers/counters implied by entry position, and plain
 * content. Shared between the live preview panel (StructuredProtocolEditorPanel)
 * and the standalone full-screen ProtocolFullViewDialog, so both always look
 * identical.
 */
public final class ProtocolSyntaxColorizer {

    private ProtocolSyntaxColorizer() {}

    private static final Color ID_COLOR      = new Color(120, 40, 150);   // purple — !id declarations
    private static final Color LOCK_COLOR    = new Color(190, 40, 40);   // red — !locked marker
    private static final Color NUMBER_COLOR  = new Color(20, 110, 150);  // teal — numeric tokens in content
    private static final Color ENTRY_NO_COLOR= new Color(140, 140, 140); // gray — entry index gutter
    private static final Color CONTENT_COLOR_LIGHT = new Color(30, 30, 30);
    private static final Color CONTENT_COLOR_DARK  = new Color(220, 220, 220);

    /** Applies syntax coloring to the given pane's document, replacing its content entirely. */
    public static void render(JTextPane pane, String rawText) {
        pane.setText(""); // reset styles/content
        StyledDocument doc = pane.getStyledDocument();

        Color contentColor = resolveContentColor(pane);

        Style base = pane.addStyle("base", null);
        StyleConstants.setFontFamily(base, Font.MONOSPACED);
        StyleConstants.setFontSize(base, 12);
        StyleConstants.setForeground(base, contentColor);

        Style idStyle = pane.addStyle("id", base);
        StyleConstants.setForeground(idStyle, ID_COLOR);
        StyleConstants.setBold(idStyle, true);

        Style lockStyle = pane.addStyle("lock", base);
        StyleConstants.setForeground(lockStyle, LOCK_COLOR);
        StyleConstants.setBold(lockStyle, true);

        Style entryNoStyle = pane.addStyle("entryNo", base);
        StyleConstants.setForeground(entryNoStyle, ENTRY_NO_COLOR);
        StyleConstants.setItalic(entryNoStyle, true);

        Style numberStyle = pane.addStyle("number", base);
        StyleConstants.setForeground(numberStyle, NUMBER_COLOR);

        try {
            String[] lines = rawText.split("\n", -1);
            int entryCount = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();

                if (trimmed.equals("!locked")) {
                    doc.insertString(doc.getLength(), line, lockStyle);
                } else if (trimmed.startsWith("!id ")) {
                    entryCount++;
                    doc.insertString(doc.getLength(), "[" + entryCount + "] ", entryNoStyle);
                    doc.insertString(doc.getLength(), line, idStyle);
                } else {
                    insertContentLineWithNumbers(doc, line, base, numberStyle);
                }

                if (i < lines.length - 1) {
                    doc.insertString(doc.getLength(), "\n", base);
                }
            }
        } catch (BadLocationException ignored) {
        }

        pane.setCaretPosition(0);
    }

    /** Highlights standalone numeric tokens within a content line (e.g. counts, versions, line refs). */
    private static void insertContentLineWithNumbers(StyledDocument doc, String line,
                                                       Style base, Style numberStyle) throws BadLocationException {
        int i = 0;
        int len = line.length();
        while (i < len) {
            char c = line.charAt(i);
            if (Character.isDigit(c)) {
                int start = i;
                while (i < len && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.')) i++;
                doc.insertString(doc.getLength(), line.substring(start, i), numberStyle);
            } else {
                int start = i;
                while (i < len && !Character.isDigit(line.charAt(i))) i++;
                doc.insertString(doc.getLength(), line.substring(start, i), base);
            }
        }
    }

    private static Color resolveContentColor(JTextPane pane) {
        Color uiColor = UIManager.getColor("TextArea.foreground");
        if (uiColor != null) return uiColor;
        return isDarkBackground(pane) ? CONTENT_COLOR_DARK : CONTENT_COLOR_LIGHT;
    }

    private static boolean isDarkBackground(JTextPane pane) {
        Color bg = pane.getBackground() != null ? pane.getBackground() : UIManager.getColor("Panel.background");
        if (bg == null) return false;
        double luminance = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255.0;
        return luminance < 0.5;
    }
}