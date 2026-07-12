package wv.codeclip.html;

import wv.codeclip.io.ClipboardService;
import javax.swing.*;
import java.awt.*;

/**
 * Confirmation dialog shown when HTML mode's surgical patching used a fuzzy
 * (non-exact) match for an @@FIND block. Always shown for lower-confidence
 * matches (below 95%); optionally shown for high-confidence matches too, if
 * enabled in Settings > Fuzzy Match Settings.
 *
 * Lets the user accept the match (apply it, as already computed) or reject
 * it (treat this @@FIND as failed — nothing gets written).
 */
public class FuzzyMatchDialog extends JDialog {

    public enum Decision { ACCEPT, REJECT }

    private Decision result = Decision.REJECT;

    public FuzzyMatchDialog(JFrame parent, String fileName, double similarityPercent,
                             String requestedFind, String actualMatch, boolean highConfidence) {
        super(parent, (highConfidence ? "Confirm High-Confidence Match — " : "Confirm Fuzzy Match — ") + fileName, true);
        buildUI(fileName, similarityPercent, requestedFind, actualMatch, highConfidence);
    }

    private void buildUI(String fileName, double similarityPercent, String requestedFind,
                          String actualMatch, boolean highConfidence) {
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        String pct = HtmlFuzzyMatcher.formatPercent(similarityPercent);
        String explanation = highConfidence
                ? "This is a high-confidence match (" + pct + "%), but confirmation is turned on in "
                  + "Settings \u2192 Fuzzy Match Settings. Review it before it's applied."
                : "@@FIND had no exact match. The closest match found was <b>" + pct + "%</b> similar. "
                  + "Review it before deciding whether to apply it.";

        JLabel header = new JLabel(
                "<html><b>" + (highConfidence ? "High-confidence" : "Low-confidence") + " fuzzy match in "
                + escapeHtml(fileName) + "</b><br>" + explanation + "</html>");
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        add(header, BorderLayout.NORTH);

        JPanel splitPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        splitPanel.add(codePanel("Requested (@@FIND)", requestedFind,
                new Color(220, 240, 255), new Color(0, 90, 180)));
        splitPanel.add(codePanel("Actually Matched (" + pct + "%)", actualMatch,
                new Color(255, 243, 205), new Color(180, 130, 0)));
        add(splitPanel, BorderLayout.CENTER);

        String clipboardMessage =
                "Fuzzy match in " + fileName + " at " + pct + "% similarity.\n\n"
                + "=== Requested (@@FIND) ===\n" + requestedFind
                + "\n\n=== Actually Matched ===\n" + actualMatch;

        ClipboardService cb = new ClipboardService();
        JButton copyBtn = new JButton("Copy Message");
        copyBtn.setToolTipText("Copy the requested text, matched text, and similarity to the clipboard.");
        copyBtn.addActionListener(e -> {
            cb.write(clipboardMessage);
            copyBtn.setText("Copied!");
            copyBtn.setForeground(new Color(30, 120, 30));
        });

        JButton rejectBtn = new JButton("Reject");
        rejectBtn.setToolTipText("Do not apply this match — treat this @@FIND as failed.");
        rejectBtn.addActionListener(e -> { result = Decision.REJECT; dispose(); });

        JButton acceptBtn = new JButton("Accept");
        acceptBtn.setToolTipText("Apply this match as shown.");
        acceptBtn.addActionListener(e -> { result = Decision.ACCEPT; dispose(); });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnPanel.add(copyBtn);
        btnPanel.add(rejectBtn);
        btnPanel.add(acceptBtn);
        add(btnPanel, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(860, 460));
        pack();
        setLocationRelativeTo(getOwner());
    }

    private JPanel codePanel(String title, String code, Color bg, Color accent) {
        JTextArea area = new JTextArea(code);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(bg);
        area.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(accent, 1, true));
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        JLabel lbl = new JLabel(title);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        lbl.setForeground(accent);
        p.add(lbl, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public Decision getResult() { return result; }

    public static Decision show(JFrame parent, String fileName, double similarityPercent,
                                 String requestedFind, String actualMatch, boolean highConfidence) {
        Decision[] choice = { Decision.REJECT };
        Runnable r = () -> {
            FuzzyMatchDialog dlg = new FuzzyMatchDialog(parent, fileName, similarityPercent,
                    requestedFind, actualMatch, highConfidence);
            dlg.setVisible(true);
            choice[0] = dlg.getResult();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try { SwingUtilities.invokeAndWait(r); }
            catch (Exception ex) { ex.printStackTrace(); }
        }
        return choice[0];
    }
}