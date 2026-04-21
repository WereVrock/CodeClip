package wv.codeclip;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BiConsumer;

public class PasteClassHandler {

    private final ClassRepository repo;
    private final JFrame parent;
    private final Runnable refreshCallback;
    private final java.util.function.Consumer<String> statusLogger;
    private final BiConsumer<String, String> addPanelCallback;

    private final ClipboardService clipboard;
    private final JavaSourceParser parser;
    private final SourceRootDetector rootDetector;
    private final ClassFileWriter fileWriter;

    private static final int CLASS_NAME_WRAP_LENGTH = 40;

    public PasteClassHandler(
            ClassRepository repo,
            JFrame parent,
            Runnable refreshCallback,
            java.util.function.Consumer<String> statusLogger,
            BiConsumer<String, String> addPanelCallback
    ) {
        this.repo = repo;
        this.parent = parent;
        this.refreshCallback = refreshCallback;
        this.statusLogger = statusLogger;
        this.addPanelCallback = addPanelCallback;

        this.clipboard = new ClipboardService();
        this.parser = new JavaSourceParser();
        this.rootDetector = new SourceRootDetector(repo, parent, parser);
        this.fileWriter = new ClassFileWriter(repo);
    }

    // ------------------------------------------------------------------
    // Main entry point
    // ------------------------------------------------------------------

    public void handlePasteFromClipboard() {
        String text = clipboard.read();
        if (text == null || text.isBlank()) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Clipboard is empty or does not contain text.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        // Delegate to patch handler if clipboard contains a patch block
        if (PatchParser.isPatch(text)) {
            handlePatch(text);
            return;
        }

        // Reject text that is clearly not Java source before running regex parsers
        if (!looksLikeJavaSource(text)) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Clipboard does not appear to contain Java source code.",
                    "Invalid Input",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        handlePaste(text);
    }

    // ------------------------------------------------------------------
    // Patch handling
    // ------------------------------------------------------------------

    private void handlePatch(String text) {
        PatchParser parser = new PatchParser();
        List<PatchChange> changes;

        try {
            changes = parser.parse(text);
        } catch (IllegalArgumentException e) {
            PatchErrorDialog.show(parent, "Patch format error:\n\n" + e.getMessage());
            return;
        }

        PatchApplier applier = new PatchApplier(repo);
        List<String> summary;

        try {
            summary = applier.apply(changes);
        } catch (PatchException e) {
            PatchErrorDialog.show(parent, e.getMessage());
            return;
        }

        refreshCallback.run();

        // Log each summary line as a temp log entry (newest at top via appendTempLog)
        if (statusLogger != null) {
            for (int i = summary.size() - 1; i >= 0; i--) {
                statusLogger.accept(summary.get(i));
            }
        }
    }

    // ------------------------------------------------------------------
    // Class paste handling (unchanged)
    // ------------------------------------------------------------------

    private void handlePaste(String classCode) {
        String packageName = parser.parsePackage(classCode);
        String className = parser.parseClassName(classCode);

        if (className == null) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Could not determine the class/interface/enum name from the pasted code.",
                    "Invalid Source",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        if (!JavaBraceEndChecker.hasCompleteEnd(classCode)) {
            int choice = JOptionPane.showConfirmDialog(
                    parent,
                    classLabel(className) +
                            "The pasted source appears to have incomplete or unbalanced braces.\n\n" +
                            "Do you want to continue anyway?",
                    "Brace Validation Failed",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION) return;
        }

        File sourceRoot = rootDetector.detect(packageName);
        File existingFile = fileWriter.findExistingFile(packageName, className, sourceRoot);
        boolean isNewFile = existingFile == null;

        if (!isNewFile && !confirmOverwrite(className, existingFile, classCode)) {
            return;
        }

        try {
            File file;
            if (isNewFile) {
                if (!confirmCreate(className, sourceRoot)) return;
                file = fileWriter.createFile(packageName, className, classCode, sourceRoot);
            } else {
                fileWriter.updateFile(existingFile, classCode);
                file = existingFile;
            }

            fileWriter.registerInRepo(file, classCode);
            refreshCallback.run();

            if (isNewFile) {
                addPanelCallback.accept(file.getAbsolutePath(), file.getName());
            }

            if (statusLogger != null) {
                statusLogger.accept(
                        (isNewFile ? "Class Created: " : "Class Updated: ")
                                + className + " (" + file.getAbsolutePath() + ")"
                );
            }

        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    parent,
                    classLabel(className) +
                            "Failed to create/update file:\n" + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private boolean confirmOverwrite(String className, File existingFile, String newCode) {
        String oldCode;
        try {
            oldCode = Files.readString(existingFile.toPath());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    parent,
                    classLabel(className) + "Failed to read existing file:\n" + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return false;
        }

        List<String> missingMethods = MissingMethodDetector.findMissingMethods(oldCode, newCode);
        if (missingMethods.isEmpty()) return true;

        StringBuilder errorText = new StringBuilder();
        errorText.append("Warning: The new code for class ")
                .append(className)
                .append(" has these methods missing:\n");
        for (String m : missingMethods) {
            errorText.append("• ").append(m).append("\n");
        }
        errorText.append("\n\nMake sure you are not missing functionality.")
                 .append("\nDon't keep them for the sake of compatibility");

        while (true) {
            Object[] options = {"Overwrite", "Copy Error", "Cancel"};
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
            if (choice == 0) return true;
            if (choice == 1) {
                clipboard.write(errorText.toString());
                continue;
            }
            return false;
        }
    }

    private boolean confirmCreate(String className, File sourceRoot) {
        String path = sourceRoot.getAbsolutePath();

        JLabel message = new JLabel(
                "<html>" +
                "<b>Class:</b> " + escapeHtml(className) + "<br><br>" +
                "File does not exist.<br><br>" +
                "<b>Target Directory:</b><br>" +
                "<tt>" + escapeHtml(path) + "</tt><br><br>" +
                "Create new file?" +
                "</html>"
        );
        message.setPreferredSize(new java.awt.Dimension(420, message.getPreferredSize().height));

        int choice = JOptionPane.showConfirmDialog(
                parent,
                message,
                "Create Class",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        return choice == JOptionPane.OK_OPTION;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean looksLikeJavaSource(String text) {
        String trimmed = text.stripLeading();
        return trimmed.startsWith("package ")
            || trimmed.startsWith("import ")
            || trimmed.startsWith("public class")
            || trimmed.startsWith("public interface")
            || trimmed.startsWith("public enum")
            || trimmed.startsWith("public record")
            || trimmed.startsWith("class ")
            || trimmed.startsWith("interface ")
            || trimmed.startsWith("enum ");
    }

    private String classLabel(String className) {
        if (className.length() <= CLASS_NAME_WRAP_LENGTH) {
            return "Class: " + className + "\n\n";
        }
        return "Class:\n" + className + "\n\n";
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }
}