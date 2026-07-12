package wv.codeclip.patch;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Best-effort Java compilation check using the JDK's built-in compiler API
 * (javax.tools.JavaCompiler). Compiles only the .java files currently loaded
 * in CodeClip — there is no knowledge of external classpath dependencies
 * (third-party libraries, other modules, etc.), so "cannot find symbol"
 * errors referencing types outside the loaded fileset are expected and are
 * not necessarily real bugs. Errors that DO reliably indicate a real problem
 * regardless of classpath — mismatched method arity/types, syntax errors,
 * duplicate class definitions — are exactly the class of error this exists
 * to catch immediately after a patch, instead of hours or days later in an
 * external build.
 *
 * Requires CodeClip itself to be running on a JDK (not a JRE) for
 * ToolProvider.getSystemJavaCompiler() to be non-null; if it's unavailable,
 * checkAll() reports compilerAvailable=false rather than failing.
 */
public final class JavaCompileChecker {

    private JavaCompileChecker() {}

    public record CompileDiagnostic(String fileName, long lineNumber, String message) {}

    public record CompileCheckResult(boolean compilerAvailable, boolean success,
                                      List<CompileDiagnostic> diagnostics) {}

    public static CompileCheckResult checkAll(Collection<File> javaFiles) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileCheckResult(false, true, List.of());
        }

        List<File> distinct = dedupeByAbsolutePath(javaFiles);
        if (distinct.isEmpty()) {
            return new CompileCheckResult(true, true, List.of());
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path tempOut = null;

        try {
            tempOut = Files.createTempDirectory("codeclip_compilecheck");

            try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(distinct);

                List<String> options = List.of(
                        "-d", tempOut.toAbsolutePath().toString(),
                        "-Xlint:none",
                        "-nowarn",
                        "-proc:none"
                );

                JavaCompiler.CompilationTask task =
                        compiler.getTask(null, fm, diagnostics, options, null, units);
                task.call();
            }

            List<CompileDiagnostic> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                String fileName = "?";
                if (d.getSource() != null) {
                    try { fileName = new File(d.getSource().toUri()).getName(); }
                    catch (Exception ignored) {}
                }
                errors.add(new CompileDiagnostic(fileName, d.getLineNumber(), d.getMessage(null)));
            }

            return new CompileCheckResult(true, errors.isEmpty(), errors);

        } catch (IOException e) {
            // Couldn't even set up the check (e.g. temp dir creation failed) —
            // don't block the user's patch on a checker-infrastructure problem.
            return new CompileCheckResult(true, true, List.of());
        } finally {
            if (tempOut != null) deleteRecursively(tempOut.toFile());
        }
    }

    private static List<File> dedupeByAbsolutePath(Collection<File> files) {
        Set<String> seen = new HashSet<>();
        List<File> result = new ArrayList<>();
        for (File f : files) {
            if (f == null || !f.exists()) continue;
            if (seen.add(f.getAbsolutePath())) result.add(f);
        }
        return result;
    }

    private static void deleteRecursively(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        dir.delete();
    }
}