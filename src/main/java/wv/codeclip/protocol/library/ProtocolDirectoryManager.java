package wv.codeclip.protocol.library;

import wv.codeclip.io.ProjectSettings;
import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.function.Supplier;

public final class ProtocolDirectoryManager {

    private final Supplier<File> sourceRootDetector;
    private ProjectSettings projectSettings;
    private String pendingProtocolsDirectory = null;
    private Path protocolsDir;
    private boolean attached = false;

    public ProtocolDirectoryManager(Supplier<File> sourceRootDetector) {
        this.sourceRootDetector = sourceRootDetector;
        // Placeholder — never used for writes: every write path and the
        // Protocol Manager check isAttached() first.
        this.protocolsDir = new File(System.getProperty("user.dir")).toPath();
    }

    public boolean isAttached() {
        return attached;
    }

    public void attachProjectSettings(ProjectSettings ps) {
        if (ps == null) {
            return;
        }
        this.projectSettings = ps;

        if (pendingProtocolsDirectory != null) {
            ps.saveProtocolsDirectory(pendingProtocolsDirectory);
            pendingProtocolsDirectory = null;
        }

        String saved = ps.loadProtocolsDirectory();
        if (saved != null && !saved.isBlank()) {
            File f = new File(saved);
            if (f.exists() && f.isDirectory()) {
                // Migration: older CodeClip versions treated the saved path
                // as a BASE and put .prtcl files in a "protocols" child. If
                // the saved dir has no .prtcl but a "protocols" child does,
                // switch to that child and rewrite the saved path so the
                // migration runs once.
                if (!containsPrtclFiles(f)) {
                    File nested = new File(f, "protocols");
                    if (nested.exists() && nested.isDirectory() && containsPrtclFiles(nested)) {
                        this.protocolsDir = nested.toPath();
                        ps.saveProtocolsDirectory(nested.getAbsolutePath());
                        this.attached = true;
                        return;
                    }
                }
                // New model: saved IS the protocols dir.
                this.protocolsDir = f.toPath();
                this.attached = true;
                return;
            }
            // Stale path (moved/deleted) — clear so we don't re-check it.
            ps.saveProtocolsDirectory("");
        }

        File detected = sourceRootDetector != null ? sourceRootDetector.get() : null;
        if (detected != null) {
            File defaultDir = new File(detected, "protocols");
            this.protocolsDir = defaultDir.toPath();
            ps.saveProtocolsDirectory(defaultDir.getAbsolutePath());
            this.attached = true;
        }
    }

/**
     * Detaches from the current project without touching disk. Used by Reset.
     * isAttached() becomes false, so protocol paste and the Protocol Manager
     * both refuse to operate until a project is loaded again.
     */
    public void resetToUnattached() {
        this.projectSettings = null;
        this.pendingProtocolsDirectory = null;
        this.protocolsDir = new File(System.getProperty("user.dir")).toPath();
        this.attached = false;
    }

public Path getProtocolsDir() {
        return protocolsDir;
    }

public ProtocolLibrary changeDirectoryInteractively(java.awt.Component parent) {
        File suggestion = suggestStartDirectory();
        if (suggestion == null) {
            // No project loaded and no prior protocols folder — refusing here
            // is what prevents the chooser from opening at the user.dir
            // placeholder (which is where the JVM launched from, not the
            // user's project). The user gets a clear "load a project first"
            // message instead of a filesystem tour.
            JOptionPane.showMessageDialog(parent,
                    "Load a project first.\n\n"
                    + "The protocols folder is per-project — CodeClip needs to know\n"
                    + "which project it belongs to before it can suggest a sensible location.",
                    "No Project Loaded", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Protocols Folder for This Project");
        configureChooserStart(chooser);

        int result = chooser.showOpenDialog(parent);
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }

        File chosen = chooser.getSelectedFile();
        this.protocolsDir = chosen.toPath();
        this.attached = true;

        if (projectSettings != null) {
            projectSettings.saveProtocolsDirectory(chosen.getAbsolutePath());
        } else {
            pendingProtocolsDirectory = chosen.getAbsolutePath();
        }

        return new ProtocolLibrary(protocolsDir);
    }

/**
     * Sets the file chooser's starting point so the user doesn't have to
     * navigate from an unrelated placeholder folder. Prefers (in order):
     *   1. The current protocols folder, if it already exists on disk.
     *   2. The project's source root (where the loaded source files live).
     * Falls back to leaving the chooser's default untouched when neither is
     * available (e.g. no project loaded yet).
     */

/**
     * Configures the chooser's starting point. Callers must have already
     * verified that suggestStartDirectory() returns non-null (otherwise there
     * is nothing sensible to suggest — the old user.dir fallback was what
     * made the chooser open at CodeClip's own install directory).
     */
    private void configureChooserStart(JFileChooser chooser) {
        File suggestion = suggestStartDirectory();
        if (suggestion == null) {
            return;
        }
        // Show the parent so `suggestion` itself appears in the list, then
        // pre-select it — one click on Open accepts the suggestion as-is.
        File parentDir = suggestion.getParentFile();
        if (parentDir != null && parentDir.exists()) {
            chooser.setCurrentDirectory(parentDir);
            chooser.setSelectedFile(suggestion);
        } else {
            chooser.setCurrentDirectory(suggestion);
        }
    }

/** Best guess for where the chooser should start, or null when nothing sensible exists yet. */
    private File suggestStartDirectory() {
        if (attached && protocolsDir != null) {
            File cur = protocolsDir.toFile();
            if (cur.exists() && cur.isDirectory()) {
                return cur;
            }
        }
        File detected = sourceRootDetector != null ? sourceRootDetector.get() : null;
        if (detected != null && detected.exists() && detected.isDirectory()) {
            return detected;
        }
        return null;
    }

public ProtocolLibrary buildLibrary() {
        return new ProtocolLibrary(protocolsDir);
    }

    private static boolean containsPrtclFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".prtcl")) {
                return true;
            }
        }
        return false;
    }
}