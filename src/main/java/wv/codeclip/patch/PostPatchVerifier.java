package wv.codeclip.patch;

import wv.codeclip.model.ClassRepository;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs automatically after every successful patch/paste (any mode) to catch
 * classes of error CodeClip would otherwise stay silent about — the exact
 * scenario that prompted this class to exist: a method call whose argument
 * count silently stopped matching its declaration after a patch.
 *
 * Two independent checks:
 *   1. Overload/duplicate-name audit — always on, free, no real false-positive
 *      risk beyond noise for legitimately-overloaded methods. Flags any
 *      method name with 2+ declarations in one file — exactly the pattern
 *      left behind by a partially-applied patch.
 *   2. Compile check — opt-in (PostPatchVerifierSettings), uses the JDK's
 *      built-in compiler on only the files loaded in CodeClip. Reliable for
 *      arity/type/syntax errors in your own code; may false-positive on
 *      types from libraries not loaded into CodeClip.
 */
public final class PostPatchVerifier {

    private PostPatchVerifier() {}

    private static final Pattern METHOD_DECL_PATTERN = Pattern.compile(
            "(?m)^[ \\t]*" +
            "(?:public|protected|private)\\s+" +
            "(?:static\\s+)?(?:final\\s+)?(?:abstract\\s+)?" +
            "[\\w<>\\[\\],\\s]+?\\s+" +
            "(\\w+)\\s*\\("
    );

    public static void verify(ClassRepository repo, JFrame parent, Consumer<String> statusLogger) {
        if (repo == null || repo.getClassFileMap().isEmpty()) return;

        auditOverloads(repo, statusLogger);

        if (PostPatchVerifierSettings.isCompileCheckEnabled()) {
            List<File> javaFiles = new java.util.ArrayList<>();
            for (File f : repo.getClassFileMap().values()) {
                if (f != null && f.getName().endsWith(".java")) javaFiles.add(f);
            }
            if (!javaFiles.isEmpty()) {
                runCompileCheckAsync(javaFiles, parent, statusLogger);
            }
        }
    }

    // ------------------------------------------------------------------
    // Overload / duplicate-name audit
    // ------------------------------------------------------------------

    private static void auditOverloads(ClassRepository repo, Consumer<String> statusLogger) {
        if (statusLogger == null) return;
        for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
            File f = entry.getValue();
            if (f == null || !f.getName().endsWith(".java")) continue;
            String code = repo.getClassCodeMap().get(entry.getKey());
            if (code == null) continue;

            Map<String, Integer> counts = countMethodNames(stripComments(code));
            for (Map.Entry<String, Integer> c : counts.entrySet()) {
                if (c.getValue() > 1) {
                    statusLogger.accept("Note: " + f.getName() + " now has " + c.getValue()
                            + " methods named '" + c.getKey() + "' — verify this is an intentional "
                            + "overload and not a leftover from a partially-applied patch.");
                }
            }
        }
    }

    private static Map<String, Integer> countMethodNames(String code) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        Matcher m = METHOD_DECL_PATTERN.matcher(code);
        while (m.find()) {
            counts.merge(m.group(1), 1, Integer::sum);
        }
        return counts;
    }

    private static String stripComments(String code) {
        code = code.replaceAll("(?s)/\\*.*?\\*/", "");
        code = code.replaceAll("//.*", "");
        return code;
    }

    // ------------------------------------------------------------------
    // Compile check
    // ------------------------------------------------------------------

    private static void runCompileCheckAsync(List<File> javaFiles, JFrame parent, Consumer<String> statusLogger) {
        if (statusLogger != null) {
            statusLogger.accept("Running compile check (" + javaFiles.size() + " loaded .java file"
                    + (javaFiles.size() > 1 ? "s" : "") + ")…");
        }
        SwingWorker<JavaCompileChecker.CompileCheckResult, Void> worker = new SwingWorker<>() {
            @Override
            protected JavaCompileChecker.CompileCheckResult doInBackground() {
                return JavaCompileChecker.checkAll(javaFiles);
            }

            @Override
            protected void done() {
                try {
                    reportResult(get(), javaFiles.size(), parent, statusLogger);
                } catch (Exception ignored) {}
            }
        };
        worker.execute();
    }

    private static void reportResult(JavaCompileChecker.CompileCheckResult result, int fileCount,
                                      JFrame parent, Consumer<String> statusLogger) {
        if (!result.compilerAvailable()) {
            if (statusLogger != null) {
                statusLogger.accept("Compile check skipped: no JDK compiler available in this runtime "
                        + "(CodeClip may be running on a JRE rather than a JDK).");
            }
            return;
        }

        if (result.success()) {
            if (statusLogger != null) {
                statusLogger.accept("\u2713 Compile check passed (" + fileCount + " file"
                        + (fileCount > 1 ? "s" : "") + ").");
            }
            return;
        }

        if (statusLogger != null) {
            statusLogger.accept("\u2717 Compile check FAILED \u2014 " + result.diagnostics().size()
                    + " error(s). See dialog for details.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("This check compiles only the files currently loaded in CodeClip \u2014 there is no\n");
        sb.append("external classpath. Errors about types from libraries or modules not loaded here\n");
        sb.append("are expected and are not necessarily real bugs \u2014 focus on errors that name your\n");
        sb.append("own loaded files or describe argument/type mismatches and syntax problems.\n\n");
        sb.append("\u2500".repeat(60)).append("\n\n");
        for (JavaCompileChecker.CompileDiagnostic d : result.diagnostics()) {
            sb.append(d.fileName()).append(":").append(d.lineNumber()).append("\n");
            sb.append("  ").append(d.message().replace("\n", "\n  ")).append("\n\n");
        }

        JTextArea area = new JTextArea(sb.toString());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(720, 420));
        JOptionPane.showMessageDialog(parent, scroll,
                "Compile Check Failed \u2014 " + result.diagnostics().size() + " error(s)",
                JOptionPane.WARNING_MESSAGE);
    }
}