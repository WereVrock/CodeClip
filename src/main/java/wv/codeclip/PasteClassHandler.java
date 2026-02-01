// ===== PasteClassHandler.java =====
package wv.codeclip;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PasteClassHandler {

    private final ClassRepository repo;
    private final JFrame parent;
    private final Runnable refreshCallback;
    private final java.util.function.Consumer<String> statusLogger;

    // Matches: class / interface / enum / record with any modifiers
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(?:^|\\s)" +
            "(?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp|\\s)*" +
            "(class|interface|enum|record)\\s+" +
            "([A-Za-z_][A-Za-z0-9_]*)",
            Pattern.MULTILINE
    );

    public PasteClassHandler(
            ClassRepository repo,
            JFrame parent,
            Runnable refreshCallback,
            java.util.function.Consumer<String> statusLogger
    ) {
        this.repo = repo;
        this.parent = parent;
        this.refreshCallback = refreshCallback;
        this.statusLogger = statusLogger;
    }

    // --- Main entry point ---
    public void handlePasteFromClipboard() {
        String classCode = getClipboardText();
        if (classCode == null || classCode.isBlank()) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Clipboard is empty or does not contain text.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }
        handlePaste(classCode);
    }

    // --- Core paste handler ---
    private void handlePaste(String classCode) {
        String packageName = parsePackage(classCode);
        String className = parseClassName(classCode);

        if (className == null) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Could not determine the class/interface/enum name from the pasted code.",
                    "Invalid Source",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        // --- Brace integrity check ---
        if (!JavaBraceEndChecker.hasCompleteEnd(classCode)) {
            int choice = JOptionPane.showConfirmDialog(
                    parent,
                    "Class: " + className + "\n\n" +
                            "The pasted source appears to have incomplete or unbalanced braces.\n\n" +
                            "Do you want to continue anyway?",
                    "Brace Validation Failed",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
        }

        File file = findExistingFile(packageName, className);
        boolean isNewFile = file == null;
        String oldCode = null;

        if (!isNewFile) {
            try {
                oldCode = Files.readString(file.toPath());
            } catch (IOException e) {
                JOptionPane.showMessageDialog(
                        parent,
                        "Class: " + className + "\n\n" +
                                "Failed to read existing file:\n" + e.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                );
                return;
            }

            List<String> missingMethods =
                    MissingMethodDetector.findMissingMethods(oldCode, classCode);

            if (!missingMethods.isEmpty()) {

                StringBuilder errorText = new StringBuilder();
                errorText.append("Error: The new code for class ")
                         .append(className)
                         .append(" has these methods missing:\n");

                for (String m : missingMethods) {
                    errorText.append("• ").append(m).append("\n");
                }

                while (true) {
                    Object[] options = {
                            "Overwrite",
                            "Copy Error",
                            "Cancel"
                    };

                    int choice = JOptionPane.showOptionDialog(
                            parent,
                            errorText.toString(),
                            "Missing Methods Detected",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE,
                            null,
                            options,
                            options[0]
                    );

                    if (choice == 0) { // Overwrite
                        break;
                    }

                    if (choice == 1) { // Copy Error
                        copyToClipboard(errorText.toString());
                        continue;
                    }

                    return; // Cancel / closed
                }
            }
        }

        try {
            if (isNewFile) {
                int choice = JOptionPane.showConfirmDialog(
                        parent,
                        "Class: " + className + "\n\n" +
                                "File does not exist.\n\n" +
                                "Target Directory:\n" +
                                detectSourceRoot(packageName).getAbsolutePath() + "\n\n" +
                                "Create new file?",
                        "Create Class",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );

                if (choice != JOptionPane.OK_OPTION) {
                    return;
                }

                file = createClassFile(packageName, className, classCode);
            } else {
                Files.writeString(file.toPath(), classCode);
            }

            String path = file.getAbsolutePath();
            repo.getClassCodeMap().put(path, classCode);
            repo.getClassFileMap().put(path, file);
            repo.getDisabledClasses().remove(path);

            refreshCallback.run();

            if (statusLogger != null) {
                statusLogger.accept(
                        (isNewFile ? "Class Created: " : "Class Updated: ")
                                + className + " (" + path + ")"
                );
            }

        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Class: " + className + "\n\n" +
                            "Failed to create/update file:\n" + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    // --- Clipboard ---
    private String getClipboardText() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable t = clipboard.getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return (String) t.getTransferData(DataFlavor.stringFlavor);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    // --- Package ---
    private String parsePackage(String code) {
        Matcher m = Pattern.compile("package\\s+([a-zA-Z0-9_.]+)\\s*;")
                .matcher(code);
        return m.find() ? m.group(1) : null;
    }

    // --- Class / Interface / Enum / Record ---
    private String parseClassName(String code) {
        Matcher m = TYPE_PATTERN.matcher(code);
        return m.find() ? m.group(2) : null;
    }

    // --- Source root detection ---
    private File detectSourceRoot(String packageName) {
        if (repo.getClassFileMap().isEmpty()) {
            return new File(System.getProperty("user.dir"));
        }

        File refFile = repo.getClassFileMap().values().iterator().next();
        String pkgPath = packageName != null
                ? packageName.replace('.', File.separatorChar)
                : "";

        File parent = refFile.getParentFile();
        if (!pkgPath.isEmpty() && parent.getAbsolutePath().endsWith(pkgPath)) {
            return new File(
                    parent.getAbsolutePath()
                            .substring(0, parent.getAbsolutePath().length() - pkgPath.length() - 1)
            );
        }
        return parent;
    }

    // --- File lookup ---
    private File findExistingFile(String packageName, String className) {
        File root = detectSourceRoot(packageName);
        String path = packageName != null
                ? packageName.replace('.', File.separatorChar)
                : "";
        File dir = new File(root, path);
        File f = new File(dir, className + ".java");
        return f.exists() ? f : null;
    }

    // --- File creation ---
    private File createClassFile(String packageName, String className, String code)
            throws IOException {

        File root = detectSourceRoot(packageName);
        String path = packageName != null
                ? packageName.replace('.', File.separatorChar)
                : "";
        File dir = new File(root, path);
        if (!dir.exists()) dir.mkdirs();

        File file = new File(dir, className + ".java");
        Files.writeString(file.toPath(), code);
        return file;
    }
}
