package wv.codeclip;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class PasteClassHandler {

    private final ClassRepository repo;
    private final JFrame parent;
    private final Runnable refreshCallback;
    private final java.util.function.Consumer<String> statusLogger; // temporary log callback

    public PasteClassHandler(ClassRepository repo, JFrame parent, Runnable refreshCallback,
                             java.util.function.Consumer<String> statusLogger) {
        this.repo = repo;
        this.parent = parent;
        this.refreshCallback = refreshCallback;
        this.statusLogger = statusLogger;
    }

    // --- Main entry point: reads clipboard and processes class ---
    public void handlePasteFromClipboard() {
        String classCode = getClipboardText();
        if (classCode == null || classCode.isBlank()) {
            JOptionPane.showMessageDialog(parent, "Clipboard is empty or does not contain text.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        handlePaste(classCode);
    }

    // --- Core paste handler ---
    private void handlePaste(String classCode) {
        String packageName = parsePackage(classCode);
        String className = parseClassName(classCode);

        if (className == null) {
            JOptionPane.showMessageDialog(parent,
                    "Could not find class declaration in the pasted code.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        File file = findExistingFile(packageName, className);
        boolean isNewFile = file == null;

        try {
            if (isNewFile) {
                // Ask for confirmation before creating
                int choice = JOptionPane.showConfirmDialog(
                        parent,
                        "Class file does not exist.\n" +
                                "Class Name: " + className + "\n" +
                                "Directory: " + detectSourceRoot(packageName).getAbsolutePath() + "\n\n" +
                                "Create new class?",
                        "Create Class",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );

                if (choice != JOptionPane.OK_OPTION) {
                    return; // user cancelled
                }

                // Create new file automatically
                file = createClassFile(packageName, className, classCode);
            } else {
                // Overwrite existing file
                Files.writeString(file.toPath(), classCode);
            }

            // --- Automatically add/update in repo ---
            String path = file.getAbsolutePath();
            repo.getClassCodeMap().put(path, classCode);
            repo.getClassFileMap().put(path, file);
            repo.getDisabledClasses().remove(path); // Ensure enabled by default
            refreshCallback.run();

            // --- Log success to notes ---
            if (statusLogger != null) {
                statusLogger.accept((isNewFile ? "Class Created: " : "Class Updated: ") +
                        className + " (" + file.getAbsolutePath() + ")");
            }

        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent,
                    "Failed to create/update file: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // --- Clipboard helper ---
    private String getClipboardText() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable t = clipboard.getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return (String) t.getTransferData(DataFlavor.stringFlavor);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // --- Parse package from class code ---
    private String parsePackage(String code) {
        code = code.replaceAll("\\s+", " "); // condense whitespace
        int pkgIndex = code.indexOf("package ");
        if (pkgIndex != -1) {
            int semi = code.indexOf(";", pkgIndex);
            if (semi != -1) {
                return code.substring(pkgIndex + 8, semi).trim();
            }
        }
        return null;
    }

    // --- Parse class name robustly ---
    private String parseClassName(String code) {
        code = code.replaceAll("\\s+", " "); // condense whitespace
        String[] keywords = {"public class ", "class ", "public abstract class ", "abstract class "};
        for (String kw : keywords) {
            int idx = code.indexOf(kw);
            if (idx != -1) {
                int start = idx + kw.length();
                int end = code.indexOf(" ", start);
                int brace = code.indexOf("{", start);
                int stop = (end != -1 && end < brace) ? end : brace;
                if (stop > start) return code.substring(start, stop).trim();
            }
        }
        return null;
    }

    // --- Detect source root based on already loaded classes ---
    private File detectSourceRoot(String packageName) {
        if (repo.getClassFileMap().isEmpty()) {
            return new File(System.getProperty("user.dir")); // fallback
        }

        // Use first loaded class as reference
        File refFile = repo.getClassFileMap().values().iterator().next();
        String packagePath = packageName != null ? packageName.replace('.', File.separatorChar) : "";

        File fileParent = refFile.getParentFile();
        if (packagePath.length() > 0 && fileParent.getAbsolutePath().endsWith(packagePath)) {
            // Strip package path from parent folder
            String rootPath = fileParent.getAbsolutePath()
                    .substring(0, fileParent.getAbsolutePath().length() - packagePath.length() - 1);
            return new File(rootPath);
        }

        return fileParent; // fallback
    }

    // --- Find existing file based on package and class name ---
    private File findExistingFile(String packageName, String className) {
        File sourceRoot = detectSourceRoot(packageName);
        String path = packageName != null ? packageName.replace('.', File.separatorChar) : "";
        File dir = new File(sourceRoot, path);
        if (dir.exists() && dir.isDirectory()) {
            File f = new File(dir, className + ".java");
            if (f.exists()) return f;
        }
        return null;
    }

    // --- Create new file in detected source root ---
    private File createClassFile(String packageName, String className, String code) throws IOException {
        File sourceRoot = detectSourceRoot(packageName);
        String path = packageName != null ? packageName.replace('.', File.separatorChar) : "";
        File dir = new File(sourceRoot, path);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, className + ".java");
        Files.writeString(file.toPath(), code);
        return file;
    }
}
