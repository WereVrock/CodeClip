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

    // --- Batch mode: multiple PatchResults from one Smart Paste batch ---
    private List<PatchApplier.PatchResult> batchResults;
    private int batchIndex = 0;
    private JPanel batchContentHolder;
    private JLabel batchNavLabel;
    private JButton batchPrevBtn;
    private JButton batchNextBtn;

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

    // New constructor for a batch of PatchResults (e.g. several failed patches from one Smart Paste)
    public PatchErrorDialog(JFrame parent, List<PatchApplier.PatchResult> results, ClassRepository repo) {
        super(parent, "Patch Failed", true);
        this.parent = parent;
        this.repo = repo;
        this.batchResults = results;
        this.batchIndex = 0;
        buildBatchUI();
    }

private void buildUI(String errorMessage, Map<String, String> errorsByFile, PatchApplier.PatchResult result) {
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildResultPanel(errorMessage, errorsByFile, result), BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(parent);
    }

/**
     * Builds the full content (error report, failed-class buttons, and action buttons)
     * for a single PatchResult / error report. Used by the legacy single-result dialog
     * and, per-entry, inside the batch navigator.
     */
    private JPanel buildResultPanel(String errorMessage, Map<String, String> errorsByFile, PatchApplier.PatchResult result) {
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

        // --- Navigation between per-file errors (when this result failed in multiple files) ---
        JPanel navPanel = null;
        if (errorsByFile != null && errorsByFile.size() > 1) {
            List<String> errorFileOrder = new ArrayList<>(errorsByFile.keySet());
            int[] navIndex = {0};
            JButton prevErrorBtn = new JButton("◀ Prev Error");
            JButton nextErrorBtn = new JButton("Next Error ▶");
            JLabel navLabel = new JLabel();

            class ErrorNav {
                void jumpTo(int index) {
                    String fileName = errorFileOrder.get(index);
                    navLabel.setText("Error " + (index + 1) + " of " + errorFileOrder.size() + ": " + fileName);
                    prevErrorBtn.setEnabled(index > 0);
                    nextErrorBtn.setEnabled(index < errorFileOrder.size() - 1);
                    int idx = errorMessage.indexOf("File: " + fileName);
                    if (idx >= 0) {
                        int lineEnd = errorMessage.indexOf('\n', idx);
                        if (lineEnd < 0) lineEnd = errorMessage.length();
                        text.select(idx, lineEnd);
                        try {
                            java.awt.geom.Rectangle2D r2d = text.modelToView2D(idx);
                            if (r2d != null) {
                                text.scrollRectToVisible(r2d.getBounds());
                            }
                        } catch (javax.swing.text.BadLocationException ignored) {
                        }
                    }
                }
            }
            ErrorNav nav = new ErrorNav();

            prevErrorBtn.addActionListener(e -> {
                if (navIndex[0] > 0) {
                    navIndex[0]--;
                    nav.jumpTo(navIndex[0]);
                }
            });
            nextErrorBtn.addActionListener(e -> {
                if (navIndex[0] < errorFileOrder.size() - 1) {
                    navIndex[0]++;
                    nav.jumpTo(navIndex[0]);
                }
            });

            navPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            navPanel.add(prevErrorBtn);
            navPanel.add(navLabel);
            navPanel.add(nextErrorBtn);
            nav.jumpTo(0);
        }

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
        JPanel errorTopStack = new JPanel();
        errorTopStack.setLayout(new BoxLayout(errorTopStack, BoxLayout.Y_AXIS));
        if (warningHeader != null) {
            errorTopStack.add(warningHeader);
        }
        if (navPanel != null) {
            errorTopStack.add(navPanel);
        }
        if (errorTopStack.getComponentCount() > 0) {
            errorPanel.add(errorTopStack, BorderLayout.NORTH);
        }
        errorPanel.add(errorScroll, BorderLayout.CENTER);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.add(errorPanel, BorderLayout.CENTER);
        if (hasClasses) {
            centerPanel.add(classScroll, BorderLayout.SOUTH);
        }

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

            JButton copyPatchAndClassesBtn = new JButton("Copy Patch + Classes");
            copyPatchAndClassesBtn.setToolTipText("Copies the reconstructable @@PATCH block (failed/skipped changes) merged with the full source of every class involved in this error, in one paste.");
            copyPatchAndClassesBtn.addActionListener(e -> {
                clipboard.write(buildPatchPlusClassesText(result));
                copyPatchAndClassesBtn.setText("Copied Patch + Classes!");
                copyPatchAndClassesBtn.setForeground(new Color(30, 120, 30));
            });
            bottomPanel.add(copyPatchAndClassesBtn);
        }

        bottomPanel.add(closeBtn);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.add(centerPanel, BorderLayout.CENTER);
        root.add(bottomPanel, BorderLayout.SOUTH);
        return root;
    }

/**
     * Builds the merged "@@PATCH (failed/skipped) + full class source" text for a single
     * PatchResult. Shared by the per-entry "Copy Patch + Classes" button and the
     * batch-wide "Copy All Patches + Classes" button.
     */
    private String buildPatchPlusClassesText(PatchApplier.PatchResult result) {
        String errMsg = result.buildErrorReport();
        Map<String, String> errFiles = result.errorsByFile();
        String patch = reconstructPatch(result.failedChanges(), errMsg, errFiles);
        StringBuilder merged = new StringBuilder();
        merged.append(patch);
        if (errFiles != null && repo != null && !errFiles.isEmpty()) {
            merged.append("\n\n");
            for (String fileName : errFiles.keySet()) {
                String classCode = findClassCode(repo, fileName);
                if (classCode != null) {
                    merged.append("// ===== ").append(fileName).append(" =====\n");
                    merged.append(classCode).append("\n\n");
                }
            }
        }
        return merged.toString().stripTrailing();
    }

/**
     * Builds the dialog shell for batch mode: a persistent Prev/Next nav bar across the
     * top (only shown when there's more than one failed patch in the batch) plus a
     * content area that re-renders to whichever PatchResult is currently selected.
     */
    private void buildBatchUI() {
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        if (batchResults.size() > 1) {
            JPanel batchNav = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            batchPrevBtn = new JButton("◀ Prev Patch");
            batchNextBtn = new JButton("Next Patch ▶");
            batchNavLabel = new JLabel();

            batchPrevBtn.addActionListener(e -> {
                if (batchIndex > 0) {
                    batchIndex--;
                    refreshBatchContent();
                }
            });
            batchNextBtn.addActionListener(e -> {
                if (batchIndex < batchResults.size() - 1) {
                    batchIndex++;
                    refreshBatchContent();
                }
            });

            batchNav.add(batchPrevBtn);
            batchNav.add(batchNavLabel);
            batchNav.add(batchNextBtn);

            JButton copyAllBatchBtn = new JButton("Copy All Patches + Classes (Batch)");
            copyAllBatchBtn.setToolTipText("Merges the failed/skipped patch and affected class source for every failed patch in this batch into one clipboard paste.");
            copyAllBatchBtn.addActionListener(e -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < batchResults.size(); i++) {
                    PatchApplier.PatchResult r = batchResults.get(i);
                    if (r.failedChanges().isEmpty() && !r.hasFailures()) continue;
                    sb.append("// ========== Batch entry ").append(i + 1)
                      .append(" of ").append(batchResults.size()).append(" ==========\n");
                    sb.append(r.buildErrorReport()).append("\n");
                    sb.append("// ---------- Reconstructed patch + classes ----------\n");
                    sb.append(buildPatchPlusClassesText(r)).append("\n\n");
                }
                new ClipboardService().write(sb.toString().stripTrailing());
                copyAllBatchBtn.setText("Copied All Batch Patches + Classes!");
                copyAllBatchBtn.setForeground(new Color(30, 120, 30));
            });
            batchNav.add(copyAllBatchBtn);

            add(batchNav, BorderLayout.NORTH);
        }

        batchContentHolder = new JPanel(new BorderLayout());
        add(batchContentHolder, BorderLayout.CENTER);

        refreshBatchContent();
    }

private void refreshBatchContent() {
        PatchApplier.PatchResult current = batchResults.get(batchIndex);
        if (batchNavLabel != null) {
            batchNavLabel.setText("Patch " + (batchIndex + 1) + " of " + batchResults.size());
            batchPrevBtn.setEnabled(batchIndex > 0);
            batchNextBtn.setEnabled(batchIndex < batchResults.size() - 1);
        }
        batchContentHolder.removeAll();
        batchContentHolder.add(
                buildResultPanel(current.buildErrorReport(), current.errorsByFile(), current),
                BorderLayout.CENTER);
        batchContentHolder.revalidate();
        batchContentHolder.repaint();
        pack();
        setLocationRelativeTo(parent);
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
                        sb.append("@@METHOD: ").append(mr.methodName());
                        if (mr.paramTypes() != null) {
                            sb.append("(").append(mr.paramTypes()).append(")");
                        }
                        sb.append("\n");
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

        sb.append("@@END");
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

public static void show(JFrame parent, List<PatchApplier.PatchResult> results, ClassRepository repo) {
        if (results == null || results.isEmpty()) return;
        if (results.size() == 1) {
            new PatchErrorDialog(parent, results.get(0), repo).setVisible(true);
        } else {
            new PatchErrorDialog(parent, results, repo).setVisible(true);
        }
    }

}