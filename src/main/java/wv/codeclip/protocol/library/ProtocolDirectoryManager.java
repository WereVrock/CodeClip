package wv.codeclip.protocol.library;

import wv.codeclip.io.SettingsManager;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Owns where THIS project's protocol (.prtcl) files live. Previously
 * ProtocolLibrary was always rooted at the JVM's working directory (wherever
 * the CodeClip binary was launched from), which meant every project sharing
 * one CodeClip install also shared one "protocols" folder — clearly wrong.
 *
 * Now each project persists its own protocols folder path, keyed by project
 * root so switching projects (or opening a second CodeClip instance against
 * a different project) doesn't cross-contaminate. If no folder has been
 * chosen yet, defaults to the detected source root (same folder
 * buildinfo.properties uses) rather than prompting immediately — the user
 * can change it later via "Change Protocols Folder…" in the Protocol
 * Manager.
 */
public final class ProtocolDirectoryManager {

    private final SettingsManager settings;
    private final Supplier<File> sourceRootDetector;
    private Path protocolsBaseDir;

    public ProtocolDirectoryManager(SettingsManager settings, Supplier<File> sourceRootDetector) {
        this.settings = settings;
        this.sourceRootDetector = sourceRootDetector;
        this.protocolsBaseDir = resolveInitialBaseDir();
    }

    private Path resolveInitialBaseDir() {
        String saved = settings.loadProtocolsDirectory();
        if (saved != null && !saved.isBlank()) {
            File f = new File(saved);
            if (f.exists() && f.isDirectory()) {
                return f.toPath();
            }
            // Saved path no longer exists (moved/deleted project) — fall
            // through to re-detecting rather than silently failing later.
        }

        File detected = sourceRootDetector != null ? sourceRootDetector.get() : null;
        if (detected != null) {
            return detected.toPath();
        }

        // Last resort: previous (buggy) behavior, so we never construct a
        // ProtocolLibrary with a null path.
        return new File(System.getProperty("user.dir")).toPath();
    }

    /** The directory .prtcl files live under (a "protocols" subfolder is created inside this). */
    public Path getProtocolsBaseDir() {
        return protocolsBaseDir;
    }

    /**
     * Opens a folder chooser, and if the user picks a directory, persists it
     * and returns a freshly constructed ProtocolLibrary pointed at it.
     * Returns null if the user cancelled.
     */
    public ProtocolLibrary changeDirectoryInteractively(java.awt.Component parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Protocols Folder for This Project");
        chooser.setCurrentDirectory(protocolsBaseDir.toFile());

        int result = chooser.showOpenDialog(parent);
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }

        File chosen = chooser.getSelectedFile();
        this.protocolsBaseDir = chosen.toPath();
        settings.saveProtocolsDirectory(chosen.getAbsolutePath());
        settings.saveProperties();

        return new ProtocolLibrary(protocolsBaseDir);
    }

    public ProtocolLibrary buildLibrary() {
        return new ProtocolLibrary(protocolsBaseDir);
    }
}