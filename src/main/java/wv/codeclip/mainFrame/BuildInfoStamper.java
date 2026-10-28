// ===== mainFrame/BuildInfoStamper.java =====
package wv.codeclip.mainFrame;

import wv.codeclip.model.ClassRepository;
import wv.codeclip.patch.PatchUndoManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns reading/writing buildinfo.properties: timestamp + auto-incrementing
 * base-36 build number. Pure file/business logic — no Swing.
 *
 * Callers must supply UI hooks (panel existence check + refresh triggers)
 * since this class has no reference to the frame or its components.
 */
public final class BuildInfoStamper {

    public static final String BUILD_INFO_FILE = "buildinfo.properties";

    private final ClassRepository repo;
    private final PatchUndoManager undoManager;
    private final Consumer<String> logSink;
    private final Runnable refreshText;
    private final Runnable refreshTitle;
    private final BiConsumer<String, String> addClassPanel; // (path, fileName)
    private final java.util.function.Predicate<String> panelExistsForPath;
    private final Consumer<String> setPendingTargetBuild;

    public BuildInfoStamper(
            ClassRepository repo,
            PatchUndoManager undoManager,
            Consumer<String> logSink,
            Runnable refreshText,
            Runnable refreshTitle,
            BiConsumer<String, String> addClassPanel,
            java.util.function.Predicate<String> panelExistsForPath,
            Consumer<String> setPendingTargetBuild) {
        this.repo = repo;
        this.undoManager = undoManager;
        this.logSink = logSink;
        this.refreshText = refreshText;
        this.refreshTitle = refreshTitle;
        this.addClassPanel = addClassPanel;
        this.panelExistsForPath = panelExistsForPath;
        this.setPendingTargetBuild = setPendingTargetBuild;
    }

    /** Stamps a fresh timestamp + incremented build number, writes to disk. */

public void stamp() {
    String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("EEE-HH:mm:ss"));

    File sourceRoot = detectSourceRoot();
    if (sourceRoot == null) {
        System.out.println("[Stamper] no source root, aborting");
        return;
    }
    System.out.println("[Stamper] writing to: " + sourceRoot.getAbsolutePath());

    int nextBuildNo = 1;
    File file = new File(sourceRoot, BUILD_INFO_FILE);
    if (file.exists()) {
        String oldContent = repo.getClassCodeMap().get(file.getAbsolutePath());
        if (oldContent == null) {
            try { oldContent = Files.readString(file.toPath()); }
            catch (IOException ignored) {}
        }
        if (oldContent != null) {
            for (String line : oldContent.split("\n")) {
                if (line.startsWith("BUILD_NO=")) {
                    String oldNo = line.substring("BUILD_NO=".length()).trim();
                    try { nextBuildNo = Integer.parseInt(oldNo, 36) + 1; }
                    catch (NumberFormatException ignored) {}
                    break;
                }
            }
        }
    }
    String buildNo36 = Integer.toString(nextBuildNo, 36);

    String path = file.getAbsolutePath();
    String oldContent = repo.getClassCodeMap().get(path);
    if (oldContent == null && file.exists()) {
        try { oldContent = Files.readString(file.toPath()); }
        catch (IOException ignored) {}
    }

    setPendingTargetBuild.accept(buildNo36);
    logSink.accept("Target Build: #" + buildNo36 + " --- " + timestamp);

    // Read-modify-write via ProjectSettings — same lock, same format,
    // same encoding as saveProjectName. Properties.load/store round-trips
    // via FileReader (platform charset) and misreads UTF-8; alternating
    // with saveProjectName is exactly how projectName was getting lost.
    new wv.codeclip.io.ProjectSettings(sourceRoot, null)
            .saveBuildInfo(timestamp, buildNo36);

    String content;
    try { content = Files.readString(file.toPath()); }
    catch (IOException ex) { ex.printStackTrace(); return; }

    repo.getClassCodeMap().put(path, content);
    repo.getClassFileMap().put(path, file);
    repo.setCheckpoint(path, content);

    if (oldContent != null) {
        undoManager.mergeTimestampSnapshot(path, oldContent);
    }
    if (!panelExistsForPath.test(path)) {
        addClassPanel.accept(path, file.getName());
    }
    refreshText.run();
    refreshTitle.run();
}

/** Writes buildinfo.properties with the exact content given (used by undo/redo restore). */

public void stampWithContent(String content, Consumer<String> setPendingTargetBuild) {
        File sourceRoot = detectSourceRoot();
        if (sourceRoot == null) {
            return;
        }
        File file = new File(sourceRoot, BUILD_INFO_FILE);

        // Undo of a build stamp must revert ONLY the build stamp, not a
        // rename or protocols-folder pick that happened after it. Extract
        // the two build keys from the undo snapshot and merge them into the
        // file's current contents — everything else stays as it is now.
        java.util.Properties old = new java.util.Properties();
        try {
            old.load(new java.io.StringReader(content));
        } catch (IOException ignored) {
        }
        String oldTimestamp = old.getProperty("LAST_UPDATED");
        String oldBuildNo   = old.getProperty("BUILD_NO");

        java.util.Properties current = new java.util.Properties();
        if (file.exists()) {
            try (java.io.FileReader r = new java.io.FileReader(file)) {
                current.load(r);
            } catch (IOException ignored) {
            }
        }
        if (oldTimestamp != null) current.setProperty("LAST_UPDATED", oldTimestamp);
        if (oldBuildNo   != null) current.setProperty("BUILD_NO", oldBuildNo);

        try {
            try (java.io.FileWriter w = new java.io.FileWriter(file)) {
                current.store(w, null);
            }
            String path = file.getAbsolutePath();
            String written = Files.readString(file.toPath());
            repo.getClassCodeMap().put(path, written);
            repo.getClassFileMap().put(path, file);
            repo.setCheckpoint(path, written);
            setPendingTargetBuild.accept(oldBuildNo != null ? oldBuildNo : "?");
            refreshText.run();
            refreshTitle.run();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

public File detectSourceRoot() {
        if (wv.codeclip.modecontext.ModeContext.isHtmlMode()
                && wv.codeclip.html.HtmlDirectory.isSet()) {
            return wv.codeclip.html.HtmlDirectory.get();
        }
        if (wv.codeclip.modecontext.ModeContext.isGenericMode()
                && wv.codeclip.generic.GenericDirectory.isSet()) {
            return wv.codeclip.generic.GenericDirectory.get();
        }

        for (java.util.Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
            File file = entry.getValue();
            if (file == null) {
                continue;
            }
            if (file.getName().equals(BUILD_INFO_FILE)) {
                continue;
            }
            String code = repo.getClassCodeMap().get(entry.getKey());
            if (code == null) {
                continue;
            }
            wv.codeclip.parse.JavaSourceParser p = new wv.codeclip.parse.JavaSourceParser();
            String pkg = p.parsePackage(code);
            if (pkg == null || pkg.isEmpty()) {
                continue;
            }
            String pkgPath = pkg.replace('.', File.separatorChar);
            File dir = file.getParentFile();
            if (dir == null) {
                continue;
            }
            String abs = dir.getAbsolutePath();
            if (abs.endsWith(File.separator + pkgPath)) {
                return new File(abs.substring(0, abs.length() - pkgPath.length() - 1));
            }
        }
        return null;
    }

    public static String extractTimestampFromContent(String content) {
        if (content == null) {
            return null;
        }
        for (String line : content.split("\n")) {
            if (line.startsWith("LAST_UPDATED=")) {
                return line.substring("LAST_UPDATED=".length()).trim();
            }
        }
        return null;
    }

    public static String extractBuildNoFromContent(String content) {
        if (content == null) {
            return "?";
        }
        for (String line : content.split("\n")) {
            if (line.startsWith("BUILD_NO=")) {
                return line.substring("BUILD_NO=".length()).trim();
            }
        }
        return "?";
    }
}