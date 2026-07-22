package wv.codeclip.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;

/**
 * "..." dialog shown per loaded file, mode-agnostic (Java/Godot/HTML/Generic
 * all use the same one). Shows the file's directory and three actions:
 *   Edit — opens the file in Notepad++ if installed on the
 *          standard Windows install paths, falling back to
 *          Notepad. Non-Windows falls back to Desktop.edit()/open().
 *   Play — runs the file with the OS default application
 *          (Desktop.open), same as double-clicking it in Explorer.
 *   Open File Location — opens the containing folder in Explorer with the
 *          file pre-selected (falls back to just opening the
 *          folder if selection isn't supported).
 * All actions are best-effort: failures show a message dialog rather than
 * throwing, since none of this is critical-path for CodeClip's own function.
 */
public final class FileActionsDialog {

    private FileActionsDialog() {}

    public static void show(JFrame parent, File file) {
        if (file == null) {
            JOptionPane.showMessageDialog(parent,
                    "No file is associated with this entry.",
                    "File Actions", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(parent, file.getName(), true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel titleLabel = new JLabel(file.getName());
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));

        File parentDir = file.getParentFile();
        JTextArea dirDisplay = new JTextArea(parentDir != null ? parentDir.getAbsolutePath() : "(unknown directory)");
        dirDisplay.setEditable(false);
        dirDisplay.setLineWrap(true);
        dirDisplay.setWrapStyleWord(false);
        dirDisplay.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        dirDisplay.setBackground(UIManager.getColor("Panel.background"));
        dirDisplay.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground"), 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(titleLabel, BorderLayout.NORTH);
        top.add(dirDisplay, BorderLayout.CENTER);
        dialog.add(top, BorderLayout.NORTH);

        boolean exists = file.exists();
        if (!exists) {
            JLabel missing = new JLabel("File not found on disk — actions below may fail.");
            missing.setForeground(new Color(180, 30, 30));
            missing.setFont(missing.getFont().deriveFont(11f));
            dialog.add(missing, BorderLayout.CENTER);
        }

        JButton editBtn = new JButton("Edit");
        editBtn.setToolTipText("Opens the file in Notepad++ if installed, otherwise Notepad.");
        editBtn.addActionListener(e -> openInEditor(dialog, file));

        JButton playBtn = new JButton("Play");
        playBtn.setToolTipText("Opens the file with the system default application.");
        playBtn.addActionListener(e -> openWithDefaultApp(dialog, file));

        JButton locationBtn = new JButton("Open File Location");
        locationBtn.setToolTipText("Opens the containing folder and selects the file.");
        locationBtn.addActionListener(e -> openFileLocation(dialog, file));

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnPanel.add(editBtn);
        btnPanel.add(playBtn);
        btnPanel.add(locationBtn);
        btnPanel.add(closeBtn);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setMinimumSize(new Dimension(420, exists ? 160 : 190));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    // ------------------------------------------------------------------
    // Edit — Notepad++ preferred, Notepad fallback, cross-platform fallback
    // ------------------------------------------------------------------
    private static void openInEditor(Component parent, File file) {
        if (isWindows()) {
            String nppPath = findNotepadPlusPlus();
            if (nppPath != null) {
                if (tryLaunch(parent, new String[]{nppPath, file.getAbsolutePath()})) {
                    return;
                }
            }
            // Fall back to Notepad — always present on Windows.
            if (tryLaunch(parent, new String[]{"notepad.exe", file.getAbsolutePath()})) {
                return;
            }
        }

        // Non-Windows, or both Windows attempts failed to even launch: fall
        // back to the JDK's cross-platform Desktop API.
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.EDIT)) {
                Desktop.getDesktop().edit(file);
                return;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file);
                return;
            }
        } catch (IOException | UnsupportedOperationException ex) {
            showFailure(parent, "Could not open the file in an editor", ex);
            return;
        }
        showFailure(parent, "No editor is available on this system", null);
    }

    /**
     * Checks the standard 64-bit and 32-bit install locations for
     * Notepad++ on Windows. Doesn't touch the registry — those two paths
     * cover the overwhelming majority of real installs and keep this
     * dependency-free.
     */
    private static String findNotepadPlusPlus() {
        String[] candidates = {
                System.getenv("ProgramFiles") + "\\Notepad++\\notepad++.exe",
                System.getenv("ProgramFiles(x86)") + "\\Notepad++\\notepad++.exe",
                "C:\\Program Files\\Notepad++\\notepad++.exe",
                "C:\\Program Files (x86)\\Notepad++\\notepad++.exe"
        };
        for (String candidate : candidates) {
            if (candidate == null) continue;
            File f = new File(candidate);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Play — open with default application
    // ------------------------------------------------------------------
    private static void openWithDefaultApp(Component parent, File file) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
                return;
            }
        } catch (IOException e) {
            showFailure(parent, "Could not open the file with the default application", e);
            return;
        }
        showFailure(parent, "Desktop API not supported on this system", null);
    }

    // ------------------------------------------------------------------
    // Open file location — select file in Explorer (Windows) or open folder
    // ------------------------------------------------------------------
    private static void openFileLocation(Component parent, File file) {
        File parentDir = file.getParentFile();
        if (parentDir == null || !parentDir.exists()) {
            showFailure(parent, "Cannot find the containing folder", null);
            return;
        }

        if (isWindows()) {
            // Explorer with /select highlights the file
            try {
                String[] cmd = {"explorer.exe", "/select,\"" + file.getAbsolutePath() + "\""};
                if (tryLaunch(parent, cmd)) {
                    return;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }

        // Fallback: open just the folder
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(parentDir);
                return;
            }
        } catch (IOException e) {
            showFailure(parent, "Could not open the file location", e);
            return;
        }
        showFailure(parent, "Desktop API not available", null);
    }

    // ------------------------------------------------------------------
    // Utility helpers
    // ------------------------------------------------------------------
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * Tries to launch a process and waits briefly. Returns true if the
     * process was started without throwing, false otherwise.
     */
    private static boolean tryLaunch(Component parent, String[] cmd) {
        try {
            new ProcessBuilder(cmd).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void showFailure(Component parent, String message, Throwable ex) {
        String detail = ex != null ? ("\n\n" + ex.getClass().getSimpleName() + ": " + ex.getMessage()) : "";
        JOptionPane.showMessageDialog(parent,
                message + detail,
                "File Action Failed",
                JOptionPane.ERROR_MESSAGE);
    }
}