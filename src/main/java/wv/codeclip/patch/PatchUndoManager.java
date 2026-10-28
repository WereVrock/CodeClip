package wv.codeclip.patch;
import wv.codeclip.model.ClassRepository;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
public class PatchUndoManager {
    private static final int MAX_HISTORY = 20;
    public record Entry(Map<String, String> snapshot, String title, java.util.List<String> allTitles) {
        public Entry(Map<String, String> snapshot, String title) {
            this(snapshot, title, title != null ? java.util.List.of(title) : java.util.List.of());
        }
    }
    private final Deque<Entry> undoStack = new ArrayDeque<>();
    private final Deque<Entry> redoStack = new ArrayDeque<>();
    private java.util.function.Consumer<String> panelRemovalCallback;
    private java.util.function.BiConsumer<String, String> panelAddCallback;
    public void setPanelRemovalCallback(java.util.function.Consumer<String> callback) {
        this.panelRemovalCallback = callback;
    }
    public void setPanelAddCallback(java.util.function.BiConsumer<String, String> callback) {
        this.panelAddCallback = callback;
    }

public void pushUndo(Map<String, String> snapshot, String title) {
    undoStack.addFirst(new Entry(snapshot, title));
    if (undoStack.size() > MAX_HISTORY) undoStack.removeLast();
    redoStack.clear();
}

public void pushUndo(Map<String, String> snapshot, String title, java.util.List<String> allTitles) {
    undoStack.addFirst(new Entry(snapshot, title, allTitles));
    if (undoStack.size() > MAX_HISTORY) undoStack.removeLast();
    redoStack.clear();
}

public void mergeTimestampSnapshot(String path, String oldContent) {
    if (undoStack.isEmpty()) return;
    Entry top = undoStack.peekFirst();
    if (top == null) return;
    // Only add if not already captured (don't overwrite an earlier old value)
    if (!top.snapshot().containsKey(path)) {
        top.snapshot().put(path, oldContent);
    }
}

public boolean canUndo() { return !undoStack.isEmpty(); }
public boolean canRedo() { return !redoStack.isEmpty(); }

public void clear() {
    undoStack.clear();
    redoStack.clear();
}
    public Entry undo(ClassRepository repo) throws IOException {
        if (!canUndo()) return null;
        Entry entry = undoStack.removeFirst();
        Map<String, String> current = captureCurrentState(entry.snapshot(), repo);
        redoStack.addFirst(new Entry(current, entry.title()));
        restore(entry.snapshot(), repo);
        return entry;
    }
    public Entry redo(ClassRepository repo) throws IOException {
        if (!canRedo()) return null;
        Entry entry = redoStack.removeFirst();
        Map<String, String> current = captureCurrentState(entry.snapshot(), repo);
        undoStack.addFirst(new Entry(current, entry.title()));
        restore(entry.snapshot(), repo);
        return entry;
    }
    private Map<String, String> captureCurrentState(Map<String, String> snapshot,
                                                     ClassRepository repo) {
        Map<String, String> current = new java.util.LinkedHashMap<>();
        for (String path : snapshot.keySet()) {
            if (repo.getClassCodeMap().containsKey(path)) {
                current.put(path, repo.getClassCodeMap().get(path));
            } else {
                current.put(path, null);
            }
        }
        return current;
    }

private void restore(Map<String, String> snapshot, ClassRepository repo) throws IOException {
    for (Map.Entry<String, String> entry : snapshot.entrySet()) {
        String path = entry.getKey();
        String code = entry.getValue();
        File file = repo.getClassFileMap().get(path);
        if (code == null) {
            File target = (file != null) ? file : new File(path);
            File parent = target.getParentFile();
            if (target.exists()) {
                Files.delete(target.toPath());
            }
            repo.getClassCodeMap().remove(path);
            repo.getClassFileMap().remove(path);
            repo.getDisabledClasses().remove(path);
            if (panelRemovalCallback != null) panelRemovalCallback.accept(path);
            deleteEmptyAncestors(parent);
        } else {
            File target = (file != null) ? file : new File(path);
            File parentDir = target.getParentFile();
            // Undo may have deleted the package folders. Redo must recreate
            // them, otherwise Files.writeString throws NoSuchFileException
            // and the whole redo silently fails.
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            Files.writeString(target.toPath(), code);
            boolean wasAbsent = !repo.getClassCodeMap().containsKey(path);
            repo.getClassCodeMap().put(path, code);
            repo.getClassFileMap().put(path, target);
            repo.getDisabledClasses().remove(path);
            if (wasAbsent && panelAddCallback != null) {
                panelAddCallback.accept(path, target.getName());
            }
        }
    }
}

/**
 * Deletes dir and its ancestors while they are empty and contain no
 * project marker. The marker check is the safety net: any folder with
 * buildinfo.properties, .git, pom.xml, build.gradle, settings.gradle,
 * .project or Makefile is treated as user-meaningful and left alone.
 */
private void deleteEmptyAncestors(File dir) {
    int safety = 20;
    while (dir != null && safety-- > 0) {
        if (hasProjectMarker(dir)) return;
        File[] children = dir.listFiles();
        if (children == null || children.length > 0) return;
        try {
            Files.delete(dir.toPath());
            System.out.println("[Undo] deleted empty dir: " + dir);
        } catch (IOException e) {
            return;
        }
        dir = dir.getParentFile();
    }
}

// Structural folder names are never deleted even when empty — otherwise
// undoing a single new class in a fresh project would shred src/main/java
// and break Redo (which then has nowhere to put the file back).
private static final java.util.Set<String> PROTECTED_DIR_NAMES = java.util.Set.of(
        "src", "main", "java", "test", "resources", "target", "build",
        "dist", "out", "bin", "obj", "node_modules", "META-INF", "WEB-INF",
        ".git", ".idea", ".vscode");

private static boolean hasProjectMarker(File dir) {
    if (dir == null) return true;
    String name = dir.getName();
    if (name != null && PROTECTED_DIR_NAMES.contains(name)) return true;
    return new File(dir, "buildinfo.properties").exists()
        || new File(dir, ".git").exists()
        || new File(dir, ".project").exists()
        || new File(dir, "pom.xml").exists()
        || new File(dir, "build.gradle").exists()
        || new File(dir, "settings.gradle").exists()
        || new File(dir, "Makefile").exists();
}

public Entry peekUndo() {
    return undoStack.peekFirst();
}

}