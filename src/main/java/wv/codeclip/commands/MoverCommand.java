// ===== MoverCommand.java =====
package wv.codeclip.commands;

import wv.codeclip.model.ClassRepository;
import wv.codeclip.patch.PatchUndoManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Parses "@@Move OldName.java -> new/relative/path.java" (mode-agnostic —
 * works for any loaded file regardless of AppMode). Moves the file on disk,
 * updates the repo's tracked path, and pushes an undo entry so the move can
 * be reverted. Standalone command: never routed through Smart Paste, exactly
 * like @@Enable and @@Copy.
 *
 * Requires a directory root to resolve the destination (HtmlDirectory /
 * GenericDirectory for those modes; for Java/Godot the destination is
 * resolved relative to the source file's own parent directory, since those
 * modes don't have a single project-root concept).
 */
public class MoverCommand {

    private final ClassRepository repo;
    private final Runnable refreshCallback;
    private final Consumer<String> statusLogger;
    private final BiConsumer<String, String> addPanelCallback;
    private final Consumer<String> removePanelCallback;
    private final PatchUndoManager undoManager;

    public MoverCommand(ClassRepository repo, Runnable refreshCallback,
            Consumer<String> statusLogger, BiConsumer<String, String> addPanelCallback,
            Consumer<String> removePanelCallback, PatchUndoManager undoManager) {
        this.repo = repo;
        this.refreshCallback = refreshCallback;
        this.statusLogger = statusLogger;
        this.addPanelCallback = addPanelCallback;
        this.removePanelCallback = removePanelCallback;
        this.undoManager = undoManager;
    }

    /**
     * @param text full command text, e.g. "@@Move Foo.java -> archive/Foo.java"
     * @param projectRoot root directory to resolve relative destination
     *                     paths against; may be null (falls back to the
     *                     source file's own parent directory)
     */
    public boolean handle(String text, File projectRoot) {
        String arg = text.substring("@@Move".length()).trim();
        if (arg.isEmpty()) return false;

        int arrowIdx = arg.indexOf("->");
        if (arrowIdx < 0) {
            if (statusLogger != null) {
                statusLogger.accept("@@Move ERROR: expected format \"@@Move OldName -> new/path\"");
            }
            return false;
        }

        String oldName = arg.substring(0, arrowIdx).trim();
        String newRelPath = arg.substring(arrowIdx + 2).trim();
        if (oldName.isEmpty() || newRelPath.isEmpty()) {
            if (statusLogger != null) {
                statusLogger.accept("@@Move ERROR: expected format \"@@Move OldName -> new/path\"");
            }
            return false;
        }

        String oldPath = null;
        File oldFile = null;
        for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
            File f = entry.getValue();
            if (f != null && f.getName().equalsIgnoreCase(oldName)) {
                oldPath = entry.getKey();
                oldFile = f;
                break;
            }
        }

        if (oldFile == null) {
            if (statusLogger != null) {
                statusLogger.accept("@@Move ERROR: \"" + oldName + "\" not found in loaded classes");
            }
            return false;
        }

        File destRoot = projectRoot != null ? projectRoot : oldFile.getParentFile();
        String normalizedRel = newRelPath.replace('\\', '/');
        while (normalizedRel.startsWith("./")) normalizedRel = normalizedRel.substring(2);
        while (normalizedRel.startsWith("/")) normalizedRel = normalizedRel.substring(1);
        File newFile = new File(destRoot, normalizedRel);

        if (newFile.getAbsolutePath().equals(oldFile.getAbsolutePath())) {
            if (statusLogger != null) {
                statusLogger.accept("@@Move ERROR: destination is the same as source for \"" + oldName + "\"");
            }
            return false;
        }
        if (newFile.exists()) {
            if (statusLogger != null) {
                statusLogger.accept("@@Move ERROR: destination already exists: " + newFile.getAbsolutePath());
            }
            return false;
        }

        String code = repo.getClassCodeMap().get(oldPath);
        try {
            File parentDir = newFile.getParentFile();
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                if (statusLogger != null) {
                    statusLogger.accept("@@Move ERROR: failed to create directory " + parentDir.getAbsolutePath());
                }
                return false;
            }
            Files.writeString(newFile.toPath(), code != null ? code : "");
            Files.deleteIfExists(oldFile.toPath());
        } catch (IOException e) {
            if (statusLogger != null) {
                statusLogger.accept("@@Move ERROR: " + e.getMessage());
            }
            return false;
        }

        String newPath = newFile.getAbsolutePath();

        // Undo snapshot: old path gets null (didn't exist before -> undo deletes
        // it, i.e. moves it back by deleting new and it never existing at old
        // path is wrong — instead we snapshot old path's PRIOR content so undo
        // recreates it there) and new path gets null (undo deletes the new file).
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(oldPath, code);   // undo: recreate old path with old content
        snapshot.put(newPath, null);   // undo: delete new path
        undoManager.pushUndo(snapshot, "Move: " + oldName + " -> " + normalizedRel);

        boolean disabled = repo.getDisabledClasses().remove(oldPath);
        repo.getClassCodeMap().remove(oldPath);
        repo.getClassFileMap().remove(oldPath);
        repo.getCheckpointCodeMap().remove(oldPath);

        repo.getClassCodeMap().put(newPath, code != null ? code : "");
        repo.getClassFileMap().put(newPath, newFile);
        repo.setCheckpoint(newPath, code != null ? code : "");
        if (disabled) {
            repo.getDisabledClasses().add(newPath);
        }

        if (removePanelCallback != null) removePanelCallback.accept(oldPath);
        if (addPanelCallback != null) addPanelCallback.accept(newPath, newFile.getName());
        refreshCallback.run();

        if (statusLogger != null) {
            statusLogger.accept("@@Move: " + oldName + " -> " + normalizedRel);
        }
        return true;
    }
}