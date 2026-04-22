package wv.codeclip.ui;

import wv.codeclip.model.ClassRepository;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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

    private final ClassRepository repo;
    private final Runnable refreshCallback;

    private final JLabel statusLabel    = new JLabel();
    private final JLabel detailLabel    = new JLabel();
    private final JPanel warningBanner  = new JPanel(new BorderLayout(6, 4));

    private final JButton restoreBtn    = new JButton("Restore to Checkpoint");
    private final JButton commitBtn     = new JButton("Commit to Disk");
    private final JButton setCheckBtn   = new JButton("Set New Checkpoint");

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

        restoreBtn.setEnabled(hasCheckpointDiff || hasPending);
        commitBtn.setEnabled(hasPending);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void onRestore() {
        pendingRestores.clear();
        int count = 0;

        for (Map.Entry<String, String> entry : repo.getCheckpointCodeMap().entrySet()) {
            String path = entry.getKey();
            String checkpoint = entry.getValue();
            String current = repo.getClassCodeMap().get(path);
            if (!checkpoint.equals(current)) {
                pendingRestores.put(path, current); // remember what we're replacing
                repo.getClassCodeMap().put(path, checkpoint);
                count++;
            }
        }

        if (count == 0) {
            JOptionPane.showMessageDialog(this,
                    "All files already match their checkpoints.",
                    "Nothing to Restore", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        refreshCallback.run();
        refresh();

        JOptionPane.showMessageDialog(this,
                count + " file" + (count == 1 ? "" : "s") + " restored in memory.\n\n" +
                "Disk has NOT been changed. Press \"Commit to Disk\" to write.",
                "Restored", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onCommit() {
        if (pendingRestores.isEmpty()) {
            // Commit current in-memory state (no prior restore)
            commitCurrentState();
            return;
        }

        int errors = 0;
        int written = 0;

        for (String path : new java.util.ArrayList<>(pendingRestores.keySet())) {
            File file = repo.getClassFileMap().get(path);
            if (file == null) { errors++; continue; }
            String code = repo.getClassCodeMap().get(path);
            try {
                Files.writeString(file.toPath(), code);
                repo.setCheckpoint(path, code);
                pendingRestores.remove(path);
                written++;
            } catch (IOException ex) {
                errors++;
            }
        }

        refresh();
        String msg = written + " file" + (written == 1 ? "" : "s") + " written to disk.";
        if (errors > 0) msg += "\n" + errors + " file(s) could not be written.";
        JOptionPane.showMessageDialog(this, msg, "Committed", JOptionPane.INFORMATION_MESSAGE);
    }

    private void commitCurrentState() {
        int written = 0, errors = 0;
        for (Map.Entry<String, String> entry : repo.getClassCodeMap().entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();
            String checkpoint = repo.getCheckpointCodeMap().get(path);
            if (checkpoint == null || checkpoint.equals(code)) continue;
            File file = repo.getClassFileMap().get(path);
            if (file == null) { errors++; continue; }
            try {
                Files.writeString(file.toPath(), code);
                repo.setCheckpoint(path, code);
                written++;
            } catch (IOException ex) {
                errors++;
            }
        }
        refresh();
        if (written == 0 && errors == 0) {
            JOptionPane.showMessageDialog(this, "All files already match their checkpoints.",
                    "Nothing to Commit", JOptionPane.INFORMATION_MESSAGE);
        } else {
            String msg = written + " file" + (written == 1 ? "" : "s") + " written to disk.";
            if (errors > 0) msg += "\n" + errors + " file(s) could not be written.";
            JOptionPane.showMessageDialog(this, msg, "Committed", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void onSetCheckpoint() {
        repo.setAllCheckpoints();
        pendingRestores.clear();
        refresh();
        JOptionPane.showMessageDialog(this,
                "Checkpoint updated. Current in-memory state is now the checkpoint.",
                "Checkpoint Set", JOptionPane.INFORMATION_MESSAGE);
    }
}