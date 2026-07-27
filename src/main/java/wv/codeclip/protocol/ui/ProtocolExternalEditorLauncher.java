package wv.codeclip.protocol.ui;

import java.io.*;
import java.nio.file.*;

/**
 * Opens a file in Notepad++ if available on the system PATH or common install
 * locations, falling back to plain Notepad (Windows) or the OS default handler.
 */
public final class ProtocolExternalEditorLauncher {

    private static final String[] NOTEPADPP_CANDIDATES = {
        "notepad++.exe", // if on PATH
        "C:\\Program Files\\Notepad++\\notepad++.exe",
        "C:\\Program Files (x86)\\Notepad++\\notepad++.exe"
    };

    private ProtocolExternalEditorLauncher() {}

    public static void open(Path file) {
        String notepadPlusPlus = findNotepadPlusPlus();
        if (notepadPlusPlus != null) {
            try {
                new ProcessBuilder(notepadPlusPlus, file.toAbsolutePath().toString()).start();
                return;
            } catch (IOException ignored) {
                // fall through to next option
            }
        }

        // Fallback: plain Notepad on Windows
        try {
            new ProcessBuilder("notepad.exe", file.toAbsolutePath().toString()).start();
            return;
        } catch (IOException ignored) {
            // fall through to Desktop API
        }

        // Final fallback: OS default handler
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file.toFile());
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not open file in any editor: " + file, e);
        }
    }

    private static String findNotepadPlusPlus() {
        for (String candidate : NOTEPADPP_CANDIDATES) {
            if (candidate.contains("\\")) {
                if (Files.exists(Paths.get(candidate))) {
                    return candidate;
                }
            } else {
                if (isOnPath(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.exists(Paths.get(dir, executable))) {
                return true;
            }
        }
        return false;
    }

    private static final class Desktop {
        static boolean isDesktopSupported() { return java.awt.Desktop.isDesktopSupported(); }
        static java.awt.Desktop getDesktop() { return java.awt.Desktop.getDesktop(); }
    }
}