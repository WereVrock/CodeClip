// ===== PatchErrorDialog.java =====
package wv.codeclip.patch;

import wv.codeclip.io.ClipboardService;
import wv.codeclip.model.ClassRepository;
import wv.codeclip.model.PatchChange;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PatchErrorDialog extends JDialog {

    private final JFrame parent;  // Store parent frame
    private PatchApplier.PatchResult patchResult;
    private ClassRepository repo;

    public PatchErrorDialog(JFrame parent, String errorMessage,
                             Map<String, String> errorsByFile,
                             ClassRepository repo) {
        super(parent, "Patch Failed", true);
        this.parent = parent;
        this.repo = repo;
        buildUI(errorMessage, errorsByFile, null);
    }

    // New constructor that takes a PatchResult
    public PatchErrorDialog(JFrame parent, PatchApplier.PatchResult result, ClassRepository repo) {
        super(parent, "Patch Failed", true);
        this.parent = parent;
        this.patchResult = result;
        this.repo = repo;
        buildUI(result.buildErrorReport(), result.errorsByFile(), result);
    }

    private void buildUI(String errorMessage, Map<String, String> errorsByFile, PatchApplier.PatchResult result) {
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

        // --- Bottom: copy all classes, copy both, copy error, close, and (if applicable) Copy Failed Patch ---
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

        // ---------- ADD THE "Copy Failed/Skipped Patch" BUTTON if we have a PatchResult ----------
        if (result != null && !result.failedChanges().isEmpty()) {
            JButton copyFailedPatchBtn = new JButton("Copy Failed/Skipped Patch");
            copyFailedPatchBtn.setToolTipText("Copies a reconstructable @@PATCH block containing only the changes that failed or were skipped (errors + file conflicts).");
            copyFailedPatchBtn.addActionListener(e -> {
                String patch = reconstructPatch(result.failedChanges(), errorMessage, errorsByFile);
                clipboard.write(patch);
                copyFailedPatchBtn.setText("Copied Failed/Skipped Patch!");
                copyFailedPatchBtn.setForeground(new Color(30, 120, 30));
            });
            bottomPanel.add(copyFailedPatchBtn);
        }

        bottomPanel.add(closeBtn);
        add(bottomPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(parent);  // now parent field is accessible
    }

    // Enhanced reconstructPatch that marks each offending change with its error
    private String reconstructPatch(List<PatchChange> changes, String errorReport, Map<String, String> errorsByFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("@@PATCH\n");
        sb.append("@@TITLE: Retry failed/skipped patch\n");
        sb.append("@@DESC: Auto-reconstructed from changes that failed or were skipped — correct and re-apply\n\n");

        java.util.LinkedHashMap<String, java.util.List<PatchChange>> byFile = new java.util.LinkedHashMap<>();
        for (PatchChange change : changes) {
            byFile.computeIfAbsent(change.fileName(), k -> new ArrayList<>()).add(change);
        }

        for (Map.Entry<String, java.util.List<PatchChange>> entry : byFile.entrySet()) {
            String fileName = entry.getKey();
            sb.append("@@FILE: ").append(fileName).append("\n");

            String fileError = errorsByFile != null ? errorsByFile.get(fileName) : null;

            for (PatchChange change : entry.getValue()) {
                if (fileError != null && !fileError.isEmpty()) {
                    sb.append("// FAILED: ").append(fileError.replace("\n", " ")).append("\n");
                } else {
                    sb.append("// SKIPPED: file had other failures or was not written\n");
                }

                switch (change) {
                    case PatchChange.FindReplace fr -> {
                        sb.append("@@FIND:\n").append(fr.find()).append("\n");
                        sb.append("@@REPLACE:\n").append(fr.replace()).append("\n");
                    }
                    case PatchChange.MethodReplace mr -> {
                        sb.append("@@METHOD:\n");
                        sb.append("@@REPLACE:\n").append(mr.replace()).append("\n");
                    }
                    case PatchChange.InsertMethod im -> {
                        if (im.afterMethod() != null) {
                            sb.append("@@AFTER_METHOD: ").append(im.afterMethod()).append("\n");
                        }
                        sb.append("@@INSERT_METHOD:\n").append(im.code()).append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        sb.append("@@END\n\n");
        sb.append("// === PATCH FAILURE REPORT ===\n");
        for (String line : errorReport.split("\n")) {
            sb.append("// ").append(line).append("\n");
        }

        return sb.toString();
    }

    private String findClassCode(ClassRepository repo, String fileName) {
        String bareName = fileName;
        int lastSlash = fileName.lastIndexOf('/');
        if (lastSlash < 0) lastSlash = fileName.lastIndexOf('\\');
        if (lastSlash >= 0) {
            bareName = fileName.substring(lastSlash + 1);
        } else if (fileName.contains(".") && fileName.endsWith(".java")) {
            String[] parts = fileName.split("\\.");
            if (parts.length >= 2) {
                bareName = parts[parts.length - 2] + ".java";
            }
        }
        for (Map.Entry<String, java.io.File> entry : repo.getClassFileMap().entrySet()) {
            if (entry.getValue().getName().equalsIgnoreCase(bareName)) {
                return repo.getClassCodeMap().get(entry.getKey());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Public static show methods
    // ------------------------------------------------------------------

    public static void show(JFrame parent, String errorMessage) {
        new PatchErrorDialog(parent, errorMessage, null, null).setVisible(true);
    }

    public static void show(JFrame parent, String errorMessage,
                             Map<String, String> errorsByFile, ClassRepository repo) {
        new PatchErrorDialog(parent, errorMessage, errorsByFile, repo).setVisible(true);
    }

    public static void show(JFrame parent, PatchApplier.PatchResult result, ClassRepository repo) {
        new PatchErrorDialog(parent, result, repo).setVisible(true);
    }
}