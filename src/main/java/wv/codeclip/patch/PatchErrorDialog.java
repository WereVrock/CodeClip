package wv.codeclip.patch;

import wv.codeclip.io.ClipboardService;
import wv.codeclip.model.ClassRepository;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

public class PatchErrorDialog extends JDialog {

    public PatchErrorDialog(JFrame parent, String errorMessage,
                             Map<String, String> errorsByFile,
                             ClassRepository repo) {
        super(parent, "Patch Failed", true);
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        ClipboardService clipboard = new ClipboardService();

        // Determine if this is a user file-not-found error
        boolean isUserError = errorMessage != null && errorMessage.contains("File not found in loaded classes:");

        // --- Theming ---
        Color warnBg     = isUserError ? new Color(255, 243, 205) : UIManager.getColor("Panel.background");
        Color warnFg     = isUserError ? new Color(133, 77, 14)     : UIManager.getColor("TextArea.foreground");
        Color warnBorder = isUserError ? new Color(200, 160, 0)     : UIManager.getColor("Separator.foreground");

        // --- Warning header (only for user errors) ---
        JPanel warningHeader = null;
        if (isUserError) {
            warningHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            warningHeader.setBackground(warnBg);
            JLabel warnIcon = new JLabel("⚠");
            warnIcon.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            warnIcon.setForeground(new Color(180, 30, 30));
            JLabel warnLabel = new JLabel("User Error");
            warnLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            warnLabel.setForeground(new Color(180, 30, 30));
            warningHeader.add(warnIcon);
            warningHeader.add(warnLabel);
            warningHeader.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        }

        // --- Top: error report text ---
        JTextArea text = new JTextArea(errorMessage);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setBackground(warnBg);
        text.setForeground(warnFg);
        text.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(warnBorder, 1, true),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        JScrollPane errorScroll = new JScrollPane(text);
        errorScroll.setPreferredSize(new Dimension(580, 220));
        errorScroll.setBorder(BorderFactory.createEmptyBorder());

        // --- Middle: scrollable vertical list of per-class copy buttons ---
        JPanel classButtonsPanel = new JPanel();
        classButtonsPanel.setLayout(new BoxLayout(classButtonsPanel, BoxLayout.Y_AXIS));
        classButtonsPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        boolean hasClasses = errorsByFile != null && repo != null && !errorsByFile.isEmpty();

        if (hasClasses) {
            for (String fileName : errorsByFile.keySet()) {
                String classCode = findClassCode(repo, fileName);
                JButton btn = new JButton("Copy Class: " + fileName);
                if (isUserError) btn.setForeground(warnFg);
                btn.setAlignmentX(Component.LEFT_ALIGNMENT);
                btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, btn.getPreferredSize().height));
                btn.setToolTipText("Copy source code of " + fileName);
                btn.addActionListener(e -> {
                    if (classCode != null) {
                        clipboard.write(classCode);
                        btn.setText("Copied: " + fileName);
                        btn.setForeground(new Color(30, 120, 30));
                    } else {
                        btn.setText("Source not available: " + fileName);
                        btn.setForeground(Color.RED);
                    }
                });
                classButtonsPanel.add(btn);
                classButtonsPanel.add(Box.createVerticalStrut(4));
            }
        }

        JScrollPane classScroll = new JScrollPane(classButtonsPanel);
        classScroll.setPreferredSize(new Dimension(580, 120));
        classScroll.getVerticalScrollBar().setUnitIncrement(16);
        if (isUserError) {
            classScroll.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(warnBorder, 1, true),
                    BorderFactory.createTitledBorder(
                            BorderFactory.createEmptyBorder(),
                            "Failed Classes — Click to Copy Source",
                            javax.swing.border.TitledBorder.LEFT,
                            javax.swing.border.TitledBorder.TOP,
                            null,
                            warnFg)));
        } else {
            classScroll.setBorder(BorderFactory.createTitledBorder("Failed Classes — Click to Copy Source"));
        }

        // --- Center split: warning header (if any) + error report on top, class buttons below ---
        JPanel errorPanel = new JPanel(new BorderLayout(0, 4));
        if (warningHeader != null) {
            errorPanel.add(warningHeader, BorderLayout.NORTH);
        }
        errorPanel.add(errorScroll, BorderLayout.CENTER);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.add(errorPanel, BorderLayout.CENTER);
        if (hasClasses) {
            centerPanel.add(classScroll, BorderLayout.SOUTH);
        }
        add(centerPanel, BorderLayout.CENTER);

        // --- Bottom: copy all classes, copy error report, close ---
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

        if (hasClasses && errorsByFile.size() > 1) {
            JButton copyAllClasses = new JButton("Copy All Failed Classes");
            copyAllClasses.addActionListener(e -> {
                StringBuilder sb = new StringBuilder();
                for (String fileName : errorsByFile.keySet()) {
                    String classCode = findClassCode(repo, fileName);
                    if (classCode != null) {
                        sb.append("// ===== ").append(fileName).append(" =====\n");
                        sb.append(classCode).append("\n\n");
                    }
                }
                clipboard.write(sb.toString().stripTrailing());
                copyAllClasses.setText("Copied All Failed Classes!");
                copyAllClasses.setForeground(new Color(30, 120, 30));
            });
            bottomPanel.add(copyAllClasses);
        }

        JButton copyBothBtn = new JButton("Copy All + Error");
        if (isUserError) copyBothBtn.setForeground(warnFg);
        copyBothBtn.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            if (hasClasses) {
                for (String fileName : errorsByFile.keySet()) {
                    String classCode = findClassCode(repo, fileName);
                    if (classCode != null) {
                        sb.append("// ===== ").append(fileName).append(" =====\n");
                        sb.append(classCode).append("\n\n");
                    }
                }
            }
            sb.append(errorMessage);
            clipboard.write(sb.toString().stripTrailing());
            copyBothBtn.setText("Copied All + Error!");
            copyBothBtn.setForeground(new Color(30, 120, 30));
        });

        JButton copyErrorBtn = new JButton("Copy Error Report");
        if (isUserError) copyErrorBtn.setForeground(warnFg);
        copyErrorBtn.addActionListener(e -> {
            clipboard.write(errorMessage);
            copyErrorBtn.setText("Copied Error Report!");
            copyErrorBtn.setForeground(new Color(30, 120, 30));
        });

        JButton closeBtn = new JButton("Close");
        if (isUserError) closeBtn.setForeground(warnFg);
        closeBtn.addActionListener(e -> dispose());

        if (hasClasses) bottomPanel.add(copyBothBtn);
        bottomPanel.add(copyErrorBtn);
        bottomPanel.add(closeBtn);
        add(bottomPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(parent);
    }

    private String findClassCode(ClassRepository repo, String fileName) {
        for (Map.Entry<String, java.io.File> entry : repo.getClassFileMap().entrySet()) {
            if (entry.getValue().getName().equalsIgnoreCase(fileName)) {
                return repo.getClassCodeMap().get(entry.getKey());
            }
        }
        return null;
    }

    public static void show(JFrame parent, String errorMessage) {
        new PatchErrorDialog(parent, errorMessage, null, null).setVisible(true);
    }

    public static void show(JFrame parent, String errorMessage,
                             Map<String, String> errorsByFile, ClassRepository repo) {
        new PatchErrorDialog(parent, errorMessage, errorsByFile, repo).setVisible(true);
    }

    public static void show(JFrame parent, PatchApplier.PatchResult result, ClassRepository repo) {
        new PatchErrorDialog(parent, result.buildErrorReport(), result.errorsByFile(), repo)
                .setVisible(true);
    }
}