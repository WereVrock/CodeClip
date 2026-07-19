// ===== DeleterCommand.java =====
package wv.codeclip.commands;

import wv.codeclip.model.ClassRepository;
import wv.codeclip.patch.PatchUndoManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Parses "@@Delete Foo.java, Bar.java" — deletes the named files from disk
 * and from the repo, mode-agnostic. Pushes an undo entry so deletion can be
 * reverted. Standalone command: never routed through Smart Paste, exactly
 * like @@Enable and @@Copy.
 */
public class DeleterCommand {

    private final ClassRepository repo;
    private final Runnable refreshCallback;
    private final Consumer<String> statusLogger;
    private final Consumer<String> removePanelCallback;
    private final PatchUndoManager undoManager;

    public DeleterCommand(ClassRepository repo, Runnable refreshCallback,
            Consumer<String> statusLogger, Consumer<String> removePanelCallback,
            PatchUndoManager undoManager) {
        this.repo = repo;
        this.refreshCallback = refreshCallback;
        this.statusLogger = statusLogger;
        this.removePanelCallback = removePanelCallback;
        this.undoManager = undoManager;
    }

    public boolean handle(String text) {
        String arg = text.substring("@@Delete".length()).trim();
        if (arg.isEmpty()) return false;

        String[] parts = arg.split("[,]+");
        List<String> targets = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) targets.add(trimmed);
        }
        if (targets.isEmpty()) return false;

        Map<String, String> snapshot = new LinkedHashMap<>();
        List<String> deleted = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String target : targets) {
            String matchPath = null;
            File matchFile = null;
            for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
                File f = entry.getValue();
                if (f != null && f.getName().equalsIgnoreCase(target)) {
                    matchPath = entry.getKey();
                    matchFile = f;
                    break;
                }
            }
            if (matchFile == null) {
                notFound.add(target);
                continue;
            }

            String code = repo.getClassCodeMap().get(matchPath);
            try {
                Files.deleteIfExists(matchFile.toPath());
            } catch (IOException e) {
                if (statusLogger != null) {
                    statusLogger.accept("@@Delete ERROR: failed to delete \"" + target + "\": " + e.getMessage());
                }
                continue;
            }

            // undo: recreate this path with its old content
            snapshot.put(matchPath, code);

            repo.getDisabledClasses().remove(matchPath);
            repo.getClassCodeMap().remove(matchPath);
            repo.getClassFileMap().remove(matchPath);
            repo.getCheckpointCodeMap().remove(matchPath);

            if (removePanelCallback != null) removePanelCallback.accept(matchPath);
            deleted.add(target);
        }

        if (!snapshot.isEmpty()) {
            String title = deleted.size() == 1 ? "Delete: " + deleted.get(0)
                    : "Delete: " + deleted.size() + " files";
            undoManager.pushUndo(snapshot, title);
            refreshCallback.run();
        }

        if (!deleted.isEmpty() && statusLogger != null) {
            statusLogger.accept("@@Delete: " + String.join(", ", deleted));
        }
        for (String missing : notFound) {
            if (statusLogger != null) {
                statusLogger.accept("@@Delete ERROR: \"" + missing + "\" not found in loaded classes");
            }
        }

        return !deleted.isEmpty();
    }
}