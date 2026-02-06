// ===== PasteClassHandler.java =====
package wv.codeclip;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PasteClassHandler {

    private final ClassRepository repo;
    private final JFrame parent;
    private final Runnable refreshCallback;
    private final java.util.function.Consumer<String> statusLogger;

    // Matches: class / interface / enum / record with modifiers
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(?:^|\\s)" +
            "(?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp|\\s)*" +
            "(class|interface|enum|record)\\s+" +
            "([A-Za-z_][A-Za-z0-9_]*)",
            Pattern.MULTILINE
    );

    // Matches main method
    private static final Pattern MAIN_METHOD_PATTERN = Pattern.compile(
            "public\\s+static\\s+void\\s+main\\s*\\(\\s*String\\s*\\[\\]\\s*\\w+\\s*\\)",
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
            if (choice != JOptionPane.OK_OPTION) return;
        }

        File file = findExistingFile(packageName, className);
        boolean isNewFile = file == null;

        if (!isNewFile) {
            String oldCode;
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

                    if (choice == 0) break;
                    if (choice == 1) {
                        copyToClipboard(errorText.toString());
                        continue;
                    }
                    return;
                }
            }
        }

        try {
            if (isNewFile) {
                File root = detectSourceRoot(packageName, className);
                int choice = JOptionPane.showConfirmDialog(
                        parent,
                        "Class: " + className + "\n\n" +
                                "File does not exist.\n\n" +
                                "Target Directory:\n" +
                                root.getAbsolutePath() + "\n\n" +
                                "Create new file?",
                        "Create Class",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );

                if (choice != JOptionPane.OK_OPTION) return;
                file = createClassFile(packageName, className, classCode, root);
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

    // --- Parsing ---
    private String parsePackage(String code) {
        Matcher m = Pattern.compile("package\\s+([a-zA-Z0-9_.]+)\\s*;")
                .matcher(code);
        return m.find() ? m.group(1) : null;
    }

    private String parseClassName(String code) {
        String clean = stripCommentsAndStrings(code);
        Matcher m = TYPE_PATTERN.matcher(clean);
        return m.find() ? m.group(2) : null;
    }

    private String stripCommentsAndStrings(String code) {
        code = code.replaceAll("(?s)/\\*.*?\\*/", " ");
        code = code.replaceAll("(?m)//.*?$", " ");
        code = code.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
        code = code.replaceAll("'(?:\\\\.|[^'\\\\])'", "''");
        return code;
    }

    // --- Source root detection ---
    private File detectSourceRoot(String packageName, String className) {
        if (packageName != null && !packageName.isEmpty()) {
            String pkgPath = packageName.replace('.', File.separatorChar);
            for (File file : repo.getClassFileMap().values()) {
                File parent = file.getParentFile();
                if (parent == null) continue;

                String abs = parent.getAbsolutePath();
                if (abs.endsWith(pkgPath)) {
                    return new File(
                            abs.substring(0, abs.length() - pkgPath.length() - 1)
                    );
                }
            }
        }

        Map<String, File> mainClasses = new HashMap<>();
        for (File file : repo.getClassFileMap().values()) {
            try {
                String code = Files.readString(file.toPath());
                if (MAIN_METHOD_PATTERN.matcher(code).find()) {
                    mainClasses.put(parseClassName(code), file);
                }
            } catch (IOException ignored) {}
        }

        if (mainClasses.containsKey("Main")) {
            return mainClasses.get("Main").getParentFile();
        }

        if (!mainClasses.isEmpty()) {
            String[] options = mainClasses.keySet().toArray(new String[0]);
            String choice = (String) JOptionPane.showInputDialog(
                    parent,
                    "Multiple classes with main method detected. Pick folder for new class:",
                    "Select Main Class Folder",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]
            );
            if (choice != null && mainClasses.containsKey(choice)) {
                return mainClasses.get(choice).getParentFile();
            }
        }

        if (!repo.getClassFileMap().isEmpty()) {
            List<File> parents = new ArrayList<>();
            for (File f : repo.getClassFileMap().values()) {
                parents.add(f.getParentFile());
            }
            File common = findCommonAncestor(parents);
            if (common != null) return common;
        }

        return new File(
                System.getProperty("user.home"),
                "Documents/NetBeansProjects/CodeClip/src/main/java"
        );
    }

    private File findCommonAncestor(List<File> paths) {
        if (paths.isEmpty()) return null;
        File common = paths.get(0);
        while (common != null) {
            boolean allMatch = true;
            for (File f : paths) {
                if (!f.getAbsolutePath().startsWith(common.getAbsolutePath())) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) return common;
            common = common.getParentFile();
        }
        return null;
    }

    // --- File lookup ---
    private File findExistingFile(String packageName, String className) {
        File root = detectSourceRoot(packageName, className);
        String path = packageName != null
                ? packageName.replace('.', File.separatorChar)
                : "";
        File dir = new File(root, path);
        File f = new File(dir, className + ".java");
        return f.exists() ? f : null;
    }

    // --- File creation ---
    private File createClassFile(
            String packageName,
            String className,
            String code,
            File root
    ) throws IOException {

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
