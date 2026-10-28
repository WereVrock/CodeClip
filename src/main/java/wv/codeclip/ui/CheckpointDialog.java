package wv.codeclip.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.util.function.Supplier;
import wv.codeclip.io.ProjectSettings;
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
    private Runnable onRestoreCallback;

    private final ClassRepository repo;
    private final Supplier<ProjectSettings> projectSettingsSupplier;
    private final JCheckBox permanentCheckpointCheck = new JCheckBox("Permanent Checkpoint");
    private boolean suppressCheckboxEvents = false;

    private final JLabel statusLabel    = new JLabel();
    private final JLabel detailLabel    = new JLabel();
    private final JPanel warningBanner  = new JPanel(new BorderLayout(6, 4));

    private final JPanel permanentWarningBanner = new JPanel(new BorderLayout(6, 4));
    private final JLabel permanentWarningLabel  = new JLabel();
    private final JButton convertToPermanentBtn = new JButton("Convert to Permanent");

    private final JButton restoreBtn    = new JButton("Restore to Checkpoint");
    private final JButton commitBtn     = new JButton("Commit to Disk");
    private final JButton setCheckBtn   = new JButton("Set New Checkpoint");

    private static final Color BTN_OK_FG = new Color(30, 120, 30);

    /** Tracks which paths are currently restored (diverge from checkpoint). */
    private final Map<String, String> pendingRestores = new HashMap<>();

public CheckpointDialog(JFrame parent, ClassRepository repo, Runnable refreshCallback,
                        Supplier<ProjectSettings> projectSettingsSupplier) {
    super(parent, "Checkpoint Manager", false);
    this.repo = repo;
    this.refreshCallback = refreshCallback;
    this.projectSettingsSupplier = projectSettingsSupplier;

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

        // Second banner (initially hidden): shows when "Permanent Checkpoint"
        // is checked but the current in-memory checkpoint has never been
        // written to the backup folder — typically because the user ticked
        // permanent AFTER setting the checkpoint. Without converting, the
        // checkpoint is lost on the next launch.
        permanentWarningBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 130, 0), 1, true),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)
        ));
        permanentWarningBanner.setBackground(WARN_BG);
        permanentWarningLabel.setForeground(WARN_FG);
        permanentWarningLabel.setFont(permanentWarningLabel.getFont()
                .deriveFont(Font.PLAIN, 12f));
        permanentWarningLabel.setText(
                "⚠  Permanent Checkpoint is on, but the current checkpoint was never backed up.");
        permanentWarningBanner.add(permanentWarningLabel, BorderLayout.CENTER);

        convertToPermanentBtn.setToolTipText(
                "Write the current checkpoint to the backup folder so it survives restart.");
        convertToPermanentBtn.addActionListener(e -> onConvertToPermanent());
        JPanel convertHolder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        convertHolder.setOpaque(false);
        convertHolder.add(convertToPermanentBtn);
        permanentWarningBanner.add(convertHolder, BorderLayout.EAST);
        permanentWarningBanner.setVisible(false);

        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.add(warningBanner);
        northStack.add(Box.createVerticalStrut(6));
        northStack.add(permanentWarningBanner);
        add(northStack, BorderLayout.NORTH);

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

        JPanel backupRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        permanentCheckpointCheck.addActionListener(e -> {
            if (suppressCheckboxEvents) return;
            ProjectSettings ps = projectSettingsSupplier.get();
            if (ps == null) return;
            boolean wasSelected = ps.loadPermanentCheckpointEnabled();
            boolean nowEnabled = permanentCheckpointCheck.isSelected();
            ps.savePermanentCheckpointEnabled(nowEnabled);
            System.out.println("[CPDialog] permanent=" + nowEnabled
                    + " wasSelected=" + wasSelected
                    + " currentBackupDir='" + ps.loadBackupDirectory() + "'");

            if (nowEnabled && !wasSelected) {
                String dir = ps.loadBackupDirectory();
                if (dir == null || dir.isBlank()) {
                    String chosen = promptForBackupDirectory();
                    if (chosen == null || chosen.isBlank()) {
                        File root = projectRootOrNull();
                        if (root != null) {
                            File fallback = new File(root.getParentFile(),
                                    root.getName() + "_codeclip_backups");
                            ps.saveBackupDirectory(fallback.getAbsolutePath());
                            System.out.println("[CPDialog] default backup dir: "
                                    + fallback.getAbsolutePath());
                            JOptionPane.showMessageDialog(this,
                                    "No folder was chosen — using default:\n"
                                            + fallback.getAbsolutePath()
                                            + "\n\nChange it later with the \"...\" button.",
                                    "Backup Directory", JOptionPane.INFORMATION_MESSAGE);
                        }
                    }
                }
            }
            // Refresh immediately so the "permanent is on but the current
            // checkpoint isn't backed up" banner appears without having to
            // close and reopen the dialog.
            refresh();
        });
        backupRow.add(permanentCheckpointCheck);

        JButton backupDirBtn = new JButton("...");
        backupDirBtn.setToolTipText("Configure backup directory");
        backupDirBtn.setMargin(new Insets(0, 4, 0, 4));
        backupDirBtn.setFocusable(false);
        backupDirBtn.addActionListener(e -> {
            String chosen = promptForBackupDirectory();
            if (chosen != null) {
                JOptionPane.showMessageDialog(this,
                        "Backup directory set to:\n" + chosen,
                        "Backup Directory", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        backupRow.add(backupDirBtn);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnPanel.add(restoreBtn);
        btnPanel.add(commitBtn);
        btnPanel.add(setCheckBtn);
        btnPanel.add(closeBtn);

        JPanel southPanel = new JPanel(new BorderLayout(0, 6));
        southPanel.add(backupRow, BorderLayout.NORTH);
        southPanel.add(btnPanel, BorderLayout.SOUTH);
        add(southPanel, BorderLayout.SOUTH);
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

    /** Called AFTER the "N files restored" dialog is shown, so the frame
     *  can clear the undo stack and truncate version history to the
     *  checkpoint boundary. */
    public void setOnRestoreCallback(Runnable callback) {
        this.onRestoreCallback = callback;
    }

/** Called by CodeClipFrame whenever something may have changed. */

public void refresh() {
        syncPermanentCheckpointUI();

        String backupSuffix = formatBackupSuffix();
        boolean hasPending = !pendingRestores.isEmpty();
        // hasPendingRestores() only walks the checkpoint map; files created
        // AFTER the checkpoint are not in that map and used to be invisible,
        // so the dialog reported "all files match" and Set New Checkpoint
        // stayed disabled even though memory had clearly moved on.
        boolean hasNewFiles = hasFilesNotInCheckpoint();
        boolean hasCheckpointDiff = repo.hasPendingRestores() || hasNewFiles;
        System.out.println("[CPDialog] refresh hasPending=" + hasPending
                + " hasNewFiles=" + hasNewFiles
                + " hasCheckpointDiff=" + hasCheckpointDiff);

        if (hasPending) {
            int count = pendingRestores.size();
            warningBanner.setBackground(WARN_BG);
            statusLabel.setForeground(WARN_FG);
            detailLabel.setForeground(WARN_FG);
            statusLabel.setText("⚠  " + count + " file" + (count == 1 ? "" : "s") +
                    " restored in memory — disk has NOT been updated" + backupSuffix);
            detailLabel.setText("Press \"Commit to Disk\" to write restored code to disk, or close to keep working with restored copy.");
        } else if (hasCheckpointDiff) {
            warningBanner.setBackground(WARN_BG);
            statusLabel.setForeground(WARN_FG);
            detailLabel.setForeground(WARN_FG);
            statusLabel.setText("⚠  In-memory code differs from checkpoint" + backupSuffix);
            detailLabel.setText("You can restore to the last checkpoint, or set a new checkpoint from the current state.");
        } else {
            warningBanner.setBackground(OK_BG);
            statusLabel.setForeground(OK_FG);
            detailLabel.setForeground(OK_FG);
            statusLabel.setText("✓  All files match their checkpoints" + backupSuffix);
            detailLabel.setText("No pending restores. Disk and memory are in sync.");
        }

        boolean allInSync = !hasPending && !hasCheckpointDiff;
        restoreBtn.setEnabled(hasCheckpointDiff || hasPending);
        commitBtn.setEnabled(true);
        setCheckBtn.setEnabled(!allInSync);
        setCheckBtn.setForeground(allInSync ? BTN_OK_FG : UIManager.getColor("Button.foreground"));

        updatePermanentConversionBanner();
    }

/** True when any loaded file is NOT represented in the checkpoint map —
 *  i.e. it was created after the last "Set New Checkpoint". */
private boolean hasFilesNotInCheckpoint() {
    for (String path : repo.getClassCodeMap().keySet()) {
        if (!repo.getCheckpointCodeMap().containsKey(path)) {
            System.out.println("[CPDialog] new-file divergence: " + path);
            return true;
        }
    }
    return false;
}

/**
     * Decides whether the "Permanent is on but current checkpoint isn't
     * backed up" banner should be visible, and shows it if so. Compares
     * every checkpointed file's content against the backup folder — file
     * existence alone isn't enough because the backup may hold a stale
     * snapshot from an older checkpoint.
     */
    private void updatePermanentConversionBanner() {
        ProjectSettings ps = projectSettingsSupplier.get();
        boolean needsConversion = ps != null
                && ps.loadPermanentCheckpointEnabled()
                && !repo.getCheckpointCodeMap().isEmpty()
                && !currentCheckpointIsFullyBackedUp(ps);
        System.out.println("[CPDialog] conversionBanner needsConversion=" + needsConversion
                + " permanent=" + (ps != null && ps.loadPermanentCheckpointEnabled())
                + " checkpointFiles=" + repo.getCheckpointCodeMap().size());
        permanentWarningBanner.setVisible(needsConversion);
        if (needsConversion) {
            revalidate();
            repaint();
        }
    }

    /** True when every checkpointed file has an identical backup copy. */

private boolean currentCheckpointIsFullyBackedUp(ProjectSettings ps) {
    File subfolder = ps.resolveBackupFolder();
    if (subfolder == null || !subfolder.isDirectory()) return false;

    File[] backupFiles = subfolder.listFiles(File::isFile);
    if (backupFiles == null || backupFiles.length == 0) return false;

    Map<String, String> backupByName = new HashMap<>();
    for (File f : backupFiles) {
        try { backupByName.put(f.getName(), Files.readString(f.toPath())); }
        catch (IOException ignored) {}
    }

    for (Map.Entry<String, String> e : repo.getCheckpointCodeMap().entrySet()) {
        String path = e.getKey();
        if (isAutoGenerated(path)) continue;
        String checkpoint = e.getValue();
        if (checkpoint == null) continue;
        File source = repo.getClassFileMap().get(path);
        String name = source != null ? source.getName() : new File(path).getName();
        String backup = backupByName.get(name);
        if (backup == null || !backup.equals(checkpoint)) {
            return false;
        }
    }
    return true;
}

private void onConvertToPermanent() {
    ProjectSettings ps = projectSettingsSupplier.get();
    if (ps == null) return;
    if (repo.getCheckpointCodeMap().isEmpty()) {
        JOptionPane.showMessageDialog(this,
                "There is no checkpoint yet. Set one first, then convert it.",
                "Nothing to Convert", JOptionPane.INFORMATION_MESSAGE);
        return;
    }

    String base = ps.loadBackupDirectory();
    if (base == null || base.isBlank()) {
        String chosen = promptForBackupDirectory();
        if (chosen == null || chosen.isBlank()) {
            File root = projectRootOrNull();
            if (root == null) {
                JOptionPane.showMessageDialog(this,
                        "Could not determine a backup folder. Pick one with the \"...\" button.",
                        "Conversion Cancelled", JOptionPane.WARNING_MESSAGE);
                return;
            }
            File fallback = new File(root.getParentFile(),
                    root.getName() + "_codeclip_backups");
            ps.saveBackupDirectory(fallback.getAbsolutePath());
        }
    }

    // Single-checkpoint semantics: the backup must represent ONLY the
    // current checkpoint. Stale files from previous checkpoints would
    // otherwise accumulate and make currentCheckpointIsFullyBackedUp
    // see name collisions (e.g. multiple buildinfo.properties) that
    // hide real writes and keep the banner up forever.
    File subfolder = ps.resolveBackupFolder();
    if (subfolder == null) {
        JOptionPane.showMessageDialog(this,
                "Could not resolve a backup folder.", "Conversion Cancelled",
                JOptionPane.WARNING_MESSAGE);
        return;
    }
    wipeBackupFolder(subfolder);

    String summary;
    try {
        summary = backupCheckpointFiles();
    } catch (Exception ex) {
        ex.printStackTrace();
        summary = null;
    }

    refresh();

    ProjectSettings ps2 = projectSettingsSupplier.get();
    boolean actuallyBackedUp = ps2 != null && currentCheckpointIsFullyBackedUp(ps2);

    if (!actuallyBackedUp) {
        StringBuilder msg = new StringBuilder(
                "Could not back up every checkpoint file. Check the backup folder\n"
                + "permissions and that it isn't the same as the source root.\n\n");
        if (summary != null && !summary.isBlank()) msg.append(summary);
        JOptionPane.showMessageDialog(this, msg.toString(),
                "Conversion Incomplete", JOptionPane.WARNING_MESSAGE);
        return;
    }

    // Register the version entry NOW, chronologically at the moment the
    // checkpoint was actually created. The old flow added it only on the
    // next dialog open (via applyBackupCheckpointsIfNeeded), which placed
    // it after any patches applied in between — the user saw "checkpoint"
    // as newer than a patch that predated it.
    if (onCheckpointSetCallback != null) {
        onCheckpointSetCallback.run();
    }
    refresh();

    JOptionPane.showMessageDialog(this,
            summary != null ? summary : "Checkpoint backed up.",
            "Converted to Permanent", JOptionPane.INFORMATION_MESSAGE);
}

/** Empties a folder recursively. Silent on individual failures — a locked
 *  file shouldn't abort the whole conversion; the post-check will still
 *  catch anything that genuinely couldn't be written. */
private void wipeBackupFolder(File folder) {
    if (folder == null || !folder.isDirectory()) return;
    File[] files = folder.listFiles();
    if (files == null) return;
    for (File f : files) {
        try {
            if (f.isFile()) java.nio.file.Files.delete(f.toPath());
            else deleteRecursively(f);
        } catch (IOException ignored) {}
    }
}

private void deleteRecursively(File dir) throws IOException {
    File[] files = dir.listFiles();
    if (files != null) {
        for (File f : files) {
            if (f.isFile()) java.nio.file.Files.delete(f.toPath());
            else deleteRecursively(f);
        }
    }
    java.nio.file.Files.delete(dir.toPath());
}

private static boolean isAutoGenerated(String path) {
    if (path == null) return false;
    String name = new File(path).getName().toLowerCase();
    // buildinfo.properties lives at every source root AND at parent levels
    // that CodeClip attached to. Backing it up under a flat folder causes
    // name collisions with itself across projects, so exclude it from
    // both the write side and the completeness check.
    return name.equals("buildinfo.properties");
}

/** Suffix showing checkpoint name + backup file count. Empty when nothing to show. */
    private String formatBackupSuffix() {
        ProjectSettings ps = projectSettingsSupplier.get();
        if (ps == null) return "";

        StringBuilder sb = new StringBuilder();
        String name = ps.loadCheckpointName();
        if (name != null && !name.isBlank() && !name.equals("Checkpoint set")) {
            sb.append("  —  \"").append(name).append("\"");
        }

        if (ps.loadPermanentCheckpointEnabled()) {
            File subfolder = ps.resolveBackupFolder();
            if (subfolder != null && subfolder.exists()) {
                File[] files = subfolder.listFiles(File::isFile);
                if (files != null && files.length > 0) {
                    sb.append(sb.length() > 0 ? " · " : "  —  ")
                      .append(files.length).append(" backup file")
                      .append(files.length == 1 ? "" : "s");
                }
            }
        }
        return sb.toString();
    }

// ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

private void onRestore() {
    pendingRestores.clear();
    List<String> restored = new ArrayList<>();
    List<String> deleted  = new ArrayList<>();

    // 1) Revert files that exist in the checkpoint back to checkpoint content.
    for (Map.Entry<String, String> entry : repo.getCheckpointCodeMap().entrySet()) {
        String path = entry.getKey();
        String checkpoint = entry.getValue();
        String current = repo.getClassCodeMap().get(path);
        if (!java.util.Objects.equals(checkpoint, current)) {
            pendingRestores.put(path, current);
            repo.getClassCodeMap().put(path, checkpoint);
            File file = repo.getClassFileMap().get(path);
            restored.add(file != null ? file.getName() : path);
        }
    }

    // 2) Files created AFTER the checkpoint are not in checkpointCodeMap.
    //    Rewinding to the checkpoint means they must not exist in CodeClip's
    //    in-memory view. Disk is intentionally left untouched — that's what
    //    "Commit to Disk" is for.
    for (String path : new ArrayList<>(repo.getClassCodeMap().keySet())) {
        if (repo.getCheckpointCodeMap().containsKey(path)) continue;
        File file = repo.getClassFileMap().get(path);
        deleted.add(file != null ? file.getName() : path);
        repo.getClassCodeMap().remove(path);
        repo.getClassFileMap().remove(path);
        repo.getDisabledClasses().remove(path);
    }

    if (restored.isEmpty() && deleted.isEmpty()) {
        JOptionPane.showMessageDialog(this,
                "All files already match their checkpoints.",
                "Nothing to Restore", JOptionPane.INFORMATION_MESSAGE);
        return;
    }

    refreshCallback.run();
    refresh();

    StringBuilder msg = new StringBuilder();
    if (!restored.isEmpty()) {
        msg.append(restored.size()).append(" file")
           .append(restored.size() == 1 ? "" : "s")
           .append(" restored in memory:\n\n");
        for (String name : restored) msg.append("  • ").append(name).append("\n");
    }
    if (!deleted.isEmpty()) {
        if (msg.length() > 0) msg.append("\n");
        msg.append(deleted.size()).append(" file")
           .append(deleted.size() == 1 ? "" : "s")
           .append(" created after checkpoint — removed from CodeClip view (file stays on disk):\n\n");
        for (String name : deleted) msg.append("  • ").append(name).append("\n");
    }
        msg.append("\nDisk has NOT been changed. Press \"Commit to Disk\" to write.");

        JOptionPane.showMessageDialog(this, msg.toString(), "Restored", JOptionPane.INFORMATION_MESSAGE);

        // Undo history and version events after the checkpoint no longer
        // correspond to reachable states — clear them now that the restore
        // itself has succeeded and the user has been notified.
        if (onRestoreCallback != null) {
            onRestoreCallback.run();
        }
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

        String backupInfo = permanentCheckpointCheck.isSelected() ? backupCheckpointFiles() : null;

        boolean hadChanges = !updated.isEmpty();
        if (onCheckpointSetCallback != null) {
            onCheckpointSetCallback.run();
        }
        refreshCallback.run();
        refresh();

        StringBuilder msg = new StringBuilder();
        if (updated.isEmpty()) {
            msg.append("Checkpoint is already up to date. No changes detected.");
        } else {
            msg.append("Checkpoint updated for ").append(updated.size()).append(" file")
               .append(updated.size() == 1 ? "" : "s").append(":\n\n");
            for (String name : updated) msg.append("  • ").append(name).append("\n");
        }
        if (backupInfo != null) {
            msg.append("\n\n").append(backupInfo);
        }

        JOptionPane.showMessageDialog(this, msg.toString(), "Checkpoint Set", JOptionPane.INFORMATION_MESSAGE);
    }

// ------------------------------------------------------------------
    // Permanent checkpoint backup
    // ------------------------------------------------------------------

    /** Backs up every checkpointed file to the configured backup directory. Returns a user-facing summary, or null if nothing to report. */

/** Backs up every checkpointed file to the project's subfolder inside the base backup directory. */

private String backupCheckpointFiles() {
    if (repo.getCheckpointCodeMap().isEmpty()) return null;
    if (resolveBackupDirectory() == null) return null;

    ProjectSettings ps = projectSettingsSupplier.get();
    if (ps == null) return null;

    File subfolder = ps.resolveBackupFolder();
    if (subfolder == null) return null;

    migrateStrayBackupFiles(ps, subfolder);

    if (!subfolder.exists() && !subfolder.mkdirs()) {
        JOptionPane.showMessageDialog(this,
                "Could not create backup folder:\n" + subfolder.getAbsolutePath(),
                "Backup Failed", JOptionPane.ERROR_MESSAGE);
        return null;
    }

    List<String> failures = new ArrayList<>();
    int count = 0;
    for (Map.Entry<String, String> entry : repo.getCheckpointCodeMap().entrySet()) {
        String path = entry.getKey();
        if (isAutoGenerated(path)) continue;
        String content = entry.getValue();
        if (content == null) continue;

        File source = repo.getClassFileMap().get(path);
        String name = source != null ? source.getName() : new File(path).getName();
        File dest = new File(subfolder, name);
        try {
            Files.writeString(dest.toPath(), content);
            count++;
        } catch (IOException e) {
            failures.add(name + ": " + e.getMessage());
        }
    }

    if (count == 0 && failures.isEmpty()) return null;

    StringBuilder sb = new StringBuilder();
    if (count > 0) {
        sb.append("Backed up ").append(count).append(" file(s) to:\n")
          .append(subfolder.getAbsolutePath());
    }
    if (!failures.isEmpty()) {
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("Some files failed:\n").append(String.join("\n", failures));
    }
    return sb.toString();
}

/**
     * One-time migration: files written by the old flat-in-base-folder scheme
     * are moved into the new project subfolder. Never overwrites files already
     * present in the subfolder — those are considered live backups.
     */
    private int migrateStrayBackupFiles(ProjectSettings ps, File subfolder) {
        String basePath = ps.loadBackupDirectory();
        if (basePath == null || basePath.isBlank()) return 0;
        File base = new File(basePath);
        if (!base.exists() || !base.isDirectory()) return 0;
        if (base.getAbsolutePath().equals(subfolder.getAbsolutePath())) return 0;

        File[] strays = base.listFiles(File::isFile);
        if (strays == null || strays.length == 0) return 0;

        if (!subfolder.exists() && !subfolder.mkdirs()) return 0;

        int migrated = 0;
        for (File f : strays) {
            File dest = new File(subfolder, f.getName());
            if (dest.exists()) continue;
            try {
                java.nio.file.Files.move(f.toPath(), dest.toPath());
                migrated++;
            } catch (IOException ignored) {
            }
        }
        return migrated;
    }

/** Returns a usable backup directory path, creating it if missing. Prompts the user once if none is configured. Returns null on cancel. */

/** Returns a usable backup directory path (per project), creating it if missing. Prompts the user once if none is configured. Returns null on cancel or when no project is loaded. */
    private String resolveBackupDirectory() {
        ProjectSettings ps = projectSettingsSupplier.get();
        if (ps == null) return null;

        String saved = ps.loadBackupDirectory();
        if (saved != null && !saved.isBlank()) {
            File dir = new File(saved);
            if (dir.exists() || dir.mkdirs()) return saved;
            ps.saveBackupDirectory(""); // stale — clear so we prompt again
        }
        return promptForBackupDirectory();
    }

/** Opens a directory chooser starting at the current backup dir (or the first loaded file's folder). Saves the chosen dir. */

/** Opens a directory chooser starting at the current backup dir (or the first loaded file's folder). Saves the chosen dir to the current project. Returns null on cancel or when no project is loaded. */

private String promptForBackupDirectory() {
    ProjectSettings ps = projectSettingsSupplier.get();
    if (ps == null) return null;

    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle("Select Backup Directory for This Project's Checkpoints");

    File start = null;
    String current = ps.loadBackupDirectory();
    if (current != null && !current.isBlank()) {
        File cur = new File(current);
        if (cur.exists() && cur.isDirectory()) start = cur;
    }
    if (start == null && ps.getFile() != null) {
        File sourceRoot = ps.getFile().getParentFile();
        if (sourceRoot != null && sourceRoot.exists()) start = sourceRoot;
    }
    if (start == null) start = findFirstFileParent();
    if (start != null && start.exists()) chooser.setCurrentDirectory(start);

    int result = chooser.showOpenDialog(this);
    if (result != JFileChooser.APPROVE_OPTION) return null;

    String chosen = chooser.getSelectedFile().getAbsolutePath();
    ps.saveBackupDirectory(chosen);
    return chosen;
}

/** First loaded file's parent directory — used as the initial folder for the backup-dir picker. */
    private File findFirstFileParent() {
        for (File f : repo.getClassFileMap().values()) {
            if (f != null && f.getParentFile() != null && f.getParentFile().exists()) {
                return f.getParentFile();
            }
        }
        return null;
    }

/** Best-guess project source root: first loaded file's ancestor that
 *  contains buildinfo.properties, else the first file's grandparent. */
private File projectRootOrNull() {
    for (File f : repo.getClassFileMap().values()) {
        if (f == null) continue;
        File dir = f.getParentFile();
        for (int depth = 0; depth < 8 && dir != null; depth++) {
            if (new File(dir, "buildinfo.properties").exists()) return dir;
            dir = dir.getParentFile();
        }
    }
    File first = findFirstFileParent();
    return first != null ? first.getParentFile() : null;
}

/** Syncs the checkbox state and enabled-ness with the current project's settings. */
    private void syncPermanentCheckpointUI() {
        ProjectSettings ps = projectSettingsSupplier.get();
        suppressCheckboxEvents = true;
        if (ps == null) {
            permanentCheckpointCheck.setSelected(false);
            permanentCheckpointCheck.setEnabled(false);
            permanentCheckpointCheck.setToolTipText("Load a project to enable permanent checkpoints");
        } else {
            permanentCheckpointCheck.setEnabled(true);
            permanentCheckpointCheck.setSelected(ps.loadPermanentCheckpointEnabled());
            permanentCheckpointCheck.setToolTipText(
                    "When enabled, checkpointed files are also saved to this project's backup directory");
        }
        suppressCheckboxEvents = false;
    }

}