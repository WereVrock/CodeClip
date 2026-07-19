package wv.codeclip.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import wv.codeclip.model.ClassRepository;

import javax.swing.*;
import java.util.List;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Dialog for managing checkpoints — saved good states of loaded classes.
 *
 * Restore  : rewinds in-memory code to the last checkpoint (disk untouched).
 * Commit   : writes the current in-memory code to disk and re-checkpoints it.
 * Set Checkpoint : marks the current in-memory state as the new checkpoint
 *                  without touching disk.
 */
public class CheckpointDialog extends JDialog {

    private static final Color WARN_BG   = new Color(255, 243, 205);
    private static final Color WARN_FG   = new Color(133, 77, 14);
    private static final Color OK_BG     = new Color(220, 242, 220);
    private static final Color OK_FG     = new Color(30, 100, 30);

    private Runnable refreshCallback;
    private Runnable onCheckpointSetCallback;

    private final ClassRepository repo;

    private final JLabel statusLabel    = new JLabel();
    private final JLabel detailLabel    = new JLabel();
    private final JPanel warningBanner  = new JPanel(new BorderLayout(6, 4));

    private final JButton restoreBtn    = new JButton("Restore to Checkpoint");
    private final JButton commitBtn     = new JButton("Commit to Disk");
    private final JButton setCheckBtn   = new JButton("Set New Checkpoint");

    private static final Color BTN_OK_FG = new Color(30, 120, 30);

    /** Tracks which paths are currently restored (diverge from checkpoint). */
    private final Map<String, String> pendingRestores = new HashMap<>();

    public CheckpointDialog(JFrame parent, ClassRepository repo, Runnable refreshCallback) {
        super(parent, "Checkpoint Manager", false);
        this.repo = repo;
        this.refreshCallback = refreshCallback;

        buildUI();
        pack();
        setMinimumSize(new Dimension(480, 260));
        setLocationRelativeTo(parent);
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUI() {
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        // --- Banner ---
        warningBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 160, 0), 1, true),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        detailLabel.setFont(detailLabel.getFont().deriveFont(Font.PLAIN, 11f));

        JPanel labelStack = new JPanel();
        labelStack.setLayout(new BoxLayout(labelStack, BoxLayout.Y_AXIS));
        labelStack.setOpaque(false);
        labelStack.add(statusLabel);
        labelStack.add(Box.createVerticalStrut(3));
        labelStack.add(detailLabel);

        warningBanner.add(labelStack, BorderLayout.CENTER);
        add(warningBanner, BorderLayout.NORTH);

        // --- Explanation panel ---
        JTextArea explainArea = new JTextArea(
                "Restore to Checkpoint  —  Rewinds the in-memory code to the last saved checkpoint.\n" +
                "                          Disk files are NOT changed until you press Commit to Disk.\n\n" +
                "Commit to Disk         —  Writes the current in-memory code to disk and updates\n" +
                "                          the checkpoint to match.\n\n" +
                "Set New Checkpoint     —  Marks the current in-memory code as the new checkpoint\n" +
                "                          without writing anything to disk."
        );
        explainArea.setEditable(false);
        explainArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        explainArea.setBackground(UIManager.getColor("Panel.background"));
        explainArea.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(new JScrollPane(explainArea), BorderLayout.CENTER);

        // --- Buttons ---
        restoreBtn.addActionListener(e -> onRestore());
        commitBtn.addActionListener(e -> onCommit());
        setCheckBtn.addActionListener(e -> onSetCheckpoint());
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> setVisible(false));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnPanel.add(restoreBtn);
        btnPanel.add(commitBtn);
        btnPanel.add(setCheckBtn);
        btnPanel.add(closeBtn);
        add(btnPanel, BorderLayout.SOUTH);
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

public void setRefreshCallback(Runnable refreshCallback) {
        this.refreshCallback = refreshCallback;
    }

    public void setOnCheckpointSetCallback(Runnable callback) {
        this.onCheckpointSetCallback = callback;
    }

/** Called by CodeClipFrame whenever something may have changed. */
    public void refresh() {
        boolean hasPending = !pendingRestores.isEmpty();
        boolean hasCheckpointDiff = repo.hasPendingRestores();

        if (hasPending) {
            // Restored in-memory but not yet committed to disk
            int count = pendingRestores.size();
            warningBanner.setBackground(WARN_BG);
            statusLabel.setForeground(WARN_FG);
            detailLabel.setForeground(WARN_FG);
            statusLabel.setText("⚠  " + count + " file" + (count == 1 ? "" : "s") +
                    " restored in memory — disk has NOT been updated");
            detailLabel.setText("Press \"Commit to Disk\" to write restored code to disk, or close to keep working with restored copy.");
        } else if (hasCheckpointDiff) {
            warningBanner.setBackground(WARN_BG);
            statusLabel.setForeground(WARN_FG);
            detailLabel.setForeground(WARN_FG);
            statusLabel.setText("⚠  In-memory code differs from checkpoint");
            detailLabel.setText("You can restore to the last checkpoint, or set a new checkpoint from the current state.");
        } else {
            warningBanner.setBackground(OK_BG);
            statusLabel.setForeground(OK_FG);
            detailLabel.setForeground(OK_FG);
            statusLabel.setText("✓  All files match their checkpoints");
            detailLabel.setText("No pending restores. Disk and memory are in sync.");
        }

        boolean allInSync = !hasPending && !hasCheckpointDiff;
        restoreBtn.setEnabled(hasCheckpointDiff || hasPending);
        commitBtn.setEnabled(true);
        setCheckBtn.setEnabled(!allInSync);
        setCheckBtn.setForeground(allInSync ? BTN_OK_FG : UIManager.getColor("Button.foreground"));
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

private void onRestore() {
        pendingRestores.clear();
        List<String> restored = new ArrayList<>();

        for (Map.Entry<String, String> entry : repo.getCheckpointCodeMap().entrySet()) {
            String path = entry.getKey();
            String checkpoint = entry.getValue();
            String current = repo.getClassCodeMap().get(path);
            if (!checkpoint.equals(current)) {
                pendingRestores.put(path, current);
                repo.getClassCodeMap().put(path, checkpoint);
                File file = repo.getClassFileMap().get(path);
                restored.add(file != null ? file.getName() : path);
            }
        }

        if (restored.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "All files already match their checkpoints.",
                    "Nothing to Restore", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        refreshCallback.run();
        refresh();

        StringBuilder msg = new StringBuilder();
        msg.append(restored.size()).append(" file")
           .append(restored.size() == 1 ? "" : "s")
           .append(" restored in memory:\n\n");
        for (String name : restored) msg.append("  • ").append(name).append("\n");
        msg.append("\nDisk has NOT been changed. Press \"Commit to Disk\" to write.");

        JOptionPane.showMessageDialog(this, msg.toString(), "Restored", JOptionPane.INFORMATION_MESSAGE);
    }

/**
     * Reads a file straight off disk, bypassing repo.getClassCodeMap().
     * Returns null if the file doesn't exist or can't be read.
     */
    private String readDiskContent(File file) {
        if (file == null || !file.exists()) return null;
        try {
            return Files.readString(file.toPath());
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * A hand-edit is a file whose on-disk content differs from BOTH the
     * in-memory code AND the checkpoint. If disk matches memory, nothing to
     * do. If disk matches checkpoint but memory doesn't, that's just the
     * ordinary pending-restore case (memory has unsaved changes) — not a
     * hand-edit, since disk hasn't diverged from the last known-good state.
     * Only when disk has drifted to a THIRD value does it count as a hand-edit.
     */
    private Map<String, String> detectHandEdits() {
        Map<String, String> handEdits = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : repo.getClassCodeMap().entrySet()) {
            String path = entry.getKey();
            String memoryCode = entry.getValue();
            File file = repo.getClassFileMap().get(path);
            String diskCode = readDiskContent(file);
            if (diskCode == null) continue;

            String checkpointCode = repo.getCheckpointCodeMap().get(path);
            boolean diskMatchesMemory = diskCode.equals(memoryCode);
            boolean diskMatchesCheckpoint = checkpointCode != null && diskCode.equals(checkpointCode);

            if (!diskMatchesMemory && !diskMatchesCheckpoint) {
                handEdits.put(path, diskCode);
            }
        }
        return handEdits;
    }

    /**
     * Commit to Disk — always available. Writes current in-memory code to
     * disk for every file that's out of sync with disk, and re-checkpoints
     * those files. Before writing, checks for hand-edits (disk content that
     * diverged from both memory and checkpoint) and lets the user choose to
     * load those changes into CodeClip instead of silently overwriting them.
     * A lightweight confirm guards against accidental clicks when there is
     * nothing unusual to warn about.
     */
    private void onCommit() {
        Map<String, String> handEdits = detectHandEdits();

        if (!handEdits.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append(handEdits.size()).append(" file")
               .append(handEdits.size() == 1 ? "" : "s")
               .append(" on disk ").append(handEdits.size() == 1 ? "has" : "have")
               .append(" changed outside CodeClip since the last checkpoint:\n\n");
            for (String path : handEdits.keySet()) {
                File f = repo.getClassFileMap().get(path);
                msg.append("  • ").append(f != null ? f.getName() : path).append("\n");
            }
            msg.append("\nCommitting now will overwrite those hand-edits with CodeClip's current version.\n")
               .append("(To bring hand-edits into CodeClip instead, close this and use \"Update All\".)\n\n")
               .append("Overwrite disk with CodeClip's version anyway?");

            int choice = JOptionPane.showConfirmDialog(this, msg.toString(),
                    "Hand-Edited Files Detected", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return; // Cancel — commit aborted, nothing changed
            }
        } else {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Write the current in-memory version to disk?",
                    "Confirm Commit", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (confirm != JOptionPane.OK_OPTION) {
                return;
            }
        }

        List<String> written = new ArrayList<>();
        List<String> failed  = new ArrayList<>();

        for (Map.Entry<String, String> entry : repo.getClassCodeMap().entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();
            File file = repo.getClassFileMap().get(path);
            if (file == null) continue;

            String diskCode = readDiskContent(file);
            boolean diskAlreadyMatches = diskCode != null && diskCode.equals(code);
            if (diskAlreadyMatches) continue;

            try {
                Files.writeString(file.toPath(), code);
                repo.setCheckpoint(path, code);
                pendingRestores.remove(path);
                written.add(file.getName());
            } catch (IOException ex) {
                failed.add(file.getName());
            }
        }

        refreshCallback.run();
        refresh();

        if (written.isEmpty() && failed.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Disk already matches CodeClip's current version. Nothing to commit.",
                    "Nothing to Commit", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        StringBuilder msg = new StringBuilder();
        if (!written.isEmpty()) {
            msg.append(written.size()).append(" file")
               .append(written.size() == 1 ? "" : "s")
               .append(" written to disk:\n\n");
            for (String name : written) msg.append("  • ").append(name).append("\n");
        }
        if (!failed.isEmpty()) {
            if (msg.length() > 0) msg.append("\n");
            msg.append(failed.size()).append(" file")
               .append(failed.size() == 1 ? "" : "s")
               .append(" could not be written:\n\n");
            for (String name : failed) msg.append("  • ").append(name).append("\n");
        }

        JOptionPane.showMessageDialog(this, msg.toString(), "Committed", JOptionPane.INFORMATION_MESSAGE);
    }

private void onSetCheckpoint() {
        List<String> updated = new ArrayList<>();

        for (Map.Entry<String, String> entry : repo.getClassCodeMap().entrySet()) {
            String path = entry.getKey();
            String current = entry.getValue();
            String checkpoint = repo.getCheckpointCodeMap().get(path);
            if (!current.equals(checkpoint)) {
                File file = repo.getClassFileMap().get(path);
                updated.add(file != null ? file.getName() : path);
            }
        }

        repo.setAllCheckpoints();
        pendingRestores.clear();
        refreshCallback.run();
        refresh();
        if (onCheckpointSetCallback != null) {
            onCheckpointSetCallback.run();
        }

        StringBuilder msg = new StringBuilder();
        if (updated.isEmpty()) {
            msg.append("Checkpoint is already up to date. No changes detected.");
        } else {
            msg.append("Checkpoint updated for ").append(updated.size()).append(" file")
               .append(updated.size() == 1 ? "" : "s").append(":\n\n");
            for (String name : updated) msg.append("  • ").append(name).append("\n");
        }

        JOptionPane.showMessageDialog(this, msg.toString(), "Checkpoint Set", JOptionPane.INFORMATION_MESSAGE);
    }

}