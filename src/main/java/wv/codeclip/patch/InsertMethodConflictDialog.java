package wv.codeclip.patch;

import wv.codeclip.io.ClipboardService;
import javax.swing.*;
import java.awt.*;

/**
 * Shown when @@INSERT_METHOD finds an existing method with the same name and
 * parameter types but a different body. Lets the user pick which version wins.
 */
public class InsertMethodConflictDialog extends JDialog {

    public enum Choice { REPLACE, KEEP }

    private Choice result = Choice.KEEP;

    public InsertMethodConflictDialog(JFrame parent, String methodName,
                                      String existingCode, String incomingCode) {
        super(parent, "Insert Conflict — " + methodName, true);
        buildUI(methodName, existingCode, incomingCode);
    }

    private void buildUI(String methodName, String existingCode, String incomingCode) {
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel header = new JLabel(
                "<html><b>Method already exists:</b> <tt>" + escapeHtml(methodName) + "</tt><br>"
                + "The bodies differ. Choose which version to keep.</html>");
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        add(header, BorderLayout.NORTH);

        JPanel splitPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        splitPanel.add(codePanel("Existing (in file)",
                existingCode, new Color(255, 243, 205), new Color(180, 130, 0)));
        splitPanel.add(codePanel("Incoming (from patch)",
                incomingCode, new Color(220, 240, 255), new Color(0, 90, 180)));
        add(splitPanel, BorderLayout.CENTER);

        ClipboardService cb = new ClipboardService();
        JButton copyBtn    = new JButton("Copy Both");
        JButton copyMsgBtn = new JButton("Copy Both + Message");
        JButton keepBtn    = new JButton("Keep Existing");
        JButton replaceBtn = new JButton("Replace with Incoming");

        copyBtn.setToolTipText("Copy both versions to clipboard for manual comparison.");
        copyMsgBtn.setToolTipText("Copy both versions and show a reminder message.");
        keepBtn.setToolTipText("Leave the file unchanged — skip this insert.");
        replaceBtn.setToolTipText("Overwrite the existing method with the incoming one.");

        copyBtn.addActionListener(e -> {
            cb.write("// === EXISTING (" + methodName + ") ===\n" + existingCode
                    + "\n\n// === INCOMING (" + methodName + ") ===\n" + incomingCode);
            copyBtn.setText("Copied!");
            copyBtn.setForeground(new Color(30, 120, 30));
        });

        copyMsgBtn.addActionListener(e -> {
            String clipboardContent = 
                    "Method insertion has a duplicate. Which one should be kept?\n\n" +
                    "// === EXISTING (" + methodName + ") ===\n" + existingCode +
                    "\n\n// === INCOMING (" + methodName + ") ===\n" + incomingCode;
            cb.write(clipboardContent);
            copyMsgBtn.setText("Copied both + message");
            copyMsgBtn.setForeground(new Color(30, 120, 30));
        });

        keepBtn.addActionListener(e -> { result = Choice.KEEP;    dispose(); });
        replaceBtn.addActionListener(e -> { result = Choice.REPLACE; dispose(); });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnPanel.add(copyBtn);
        btnPanel.add(copyMsgBtn);
        btnPanel.add(keepBtn);
        btnPanel.add(replaceBtn);
        add(btnPanel, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(860, 480));
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

    public Choice getResult() { return result; }

    /**
     * Shows the dialog on the EDT (blocks until dismissed).
     * Safe to call from any thread.
     */
    public static Choice show(JFrame parent, String methodName,
                               String existingCode, String incomingCode) {
        Choice[] choice = { Choice.KEEP };
        Runnable r = () -> {
            InsertMethodConflictDialog dlg =
                    new InsertMethodConflictDialog(parent, methodName, existingCode, incomingCode);
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