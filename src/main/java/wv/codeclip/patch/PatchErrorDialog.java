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

        // --- Top: error report text ---
        JTextArea text = new JTextArea(errorMessage);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setBackground(UIManager.getColor("Panel.background"));
        text.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane errorScroll = new JScrollPane(text);
        errorScroll.setPreferredSize(new Dimension(580, 220));

        // --- Middle: scrollable vertical list of per-class copy buttons ---
        JPanel classButtonsPanel = new JPanel();
        classButtonsPanel.setLayout(new BoxLayout(classButtonsPanel, BoxLayout.Y_AXIS));
        classButtonsPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        boolean hasClasses = errorsByFile != null && repo != null && !errorsByFile.isEmpty();

        if (hasClasses) {
            for (String fileName : errorsByFile.keySet()) {
                String classCode = findClassCode(repo, fileName);
                JButton btn = new JButton("Copy Class: " + fileName);
                btn.setAlignmentX(Component.LEFT_ALIGNMENT);
                btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, btn.getPreferredSize().height));
                btn.setToolTipText("Copy source code of " + fileName);
                btn.addActionListener(e -> {
                    if (classCode != null) {
                        clipboard.write(classCode);
                        btn.setText("Copied: " + fileName);
                    } else {
                        btn.setText("Source not available: " + fileName);
                    }
                });
                classButtonsPanel.add(btn);
                classButtonsPanel.add(Box.createVerticalStrut(4));
            }
        }

        JScrollPane classScroll = new JScrollPane(classButtonsPanel);
        classScroll.setPreferredSize(new Dimension(580, 120));
        classScroll.getVerticalScrollBar().setUnitIncrement(16);
        classScroll.setBorder(BorderFactory.createTitledBorder("Failed Classes — Click to Copy Source"));

        // --- Center split: error report on top, class buttons below ---
        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.add(errorScroll, BorderLayout.CENTER);
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
                copyAllClasses.setText("Copied!");
            });
            bottomPanel.add(copyAllClasses);
        }

        JButton copyBothBtn = new JButton("Copy All + Error");
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
            copyBothBtn.setText("Copied!");
        });

        JButton copyErrorBtn = new JButton("Copy Error Report");
        copyErrorBtn.addActionListener(e -> {
            clipboard.write(errorMessage);
            copyErrorBtn.setText("Copied!");
        });

        JButton closeBtn = new JButton("Close");
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