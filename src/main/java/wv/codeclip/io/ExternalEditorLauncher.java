// ===== ExternalEditorLauncher.java =====
package wv.codeclip.io;

import java.io.File;
import java.io.IOException;

/**
 * Launches a file in an external editor. Tries, in order:
 *   1. The user-configured editor path (SettingsManager), if set and exists.
 *   2. Notepad++ at its default Windows install locations.
 *   3. Windows Notepad.
 *   4. Desktop.open() as a last-resort OS-default fallback (any platform).
 */
public final class ExternalEditorLauncher {

    private static final String[] NOTEPADPP_DEFAULT_PATHS = {
        "C:\\Program Files\\Notepad++\\notepad++.exe",
        "C:\\Program Files (x86)\\Notepad++\\notepad++.exe"
    };

    private ExternalEditorLauncher() {}

    public static void open(File target, SettingsManager settings) {
        if (target == null || !target.exists()) return;

        String configuredPath = settings.loadExternalEditorPath();
        if (configuredPath != null && !configuredPath.isBlank()) {
            File configured = new File(configuredPath);
            if (configured.exists() && launchWith(configured, target)) return;
        }

        for (String candidate : NOTEPADPP_DEFAULT_PATHS) {
            File npp = new File(candidate);
            if (npp.exists() && launchWith(npp, target)) return;
        }

        if (launchWith(new File("C:\\Windows\\System32\\notepad.exe"), target)) return;

        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(target);
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean launchWith(File editorExe, File target) {
        try {
            new ProcessBuilder(editorExe.getAbsolutePath(), target.getAbsolutePath()).start();
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}