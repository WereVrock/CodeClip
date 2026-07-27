package wv.codeclip.protocol.engine;

import wv.codeclip.protocol.library.ProtocolLibrary;
import wv.codeclip.protocol.model.ProtocolFile;

import java.util.*;

/**
 * Separate undo/redo stack for protocol file changes, independent of the
 * code-file undo system (PatchUndoManager). Snapshots are full file
 * contents per .prtcl file, keyed by file name.
 */
public final class ProtocolUndoManager {

    public static final class Entry {
        private final Map<String, String> snapshot; // fileName -> content before change (null = file didn't exist)
        private final String title;

        public Entry(Map<String, String> snapshot, String title) {
            this.snapshot = snapshot;
            this.title = title;
        }

        public Map<String, String> snapshot() { return snapshot; }
        public String title() { return title; }
    }

    private final Deque<Entry> undoStack = new ArrayDeque<>();
    private final Deque<Entry> redoStack = new ArrayDeque<>();

    public void pushUndo(Map<String, String> snapshot, String title) {
        if (snapshot.isEmpty()) return;
        undoStack.push(new Entry(new LinkedHashMap<>(snapshot), title));
        redoStack.clear();
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    /**
     * Reverts the top entry: writes back each file's prior content (or
     * deletes it if it didn't exist before), and pushes the current state
     * onto the redo stack for the same file set.
     */
    public Entry undo(ProtocolLibrary library) {
        if (undoStack.isEmpty()) return null;
        Entry entry = undoStack.pop();

        Map<String, String> redoSnapshot = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entry.snapshot().entrySet()) {
            String fileName = e.getKey();
            String priorContent = e.getValue();

            StringBuilder err = new StringBuilder();
            ProtocolFile current = library.loadSafely(fileName, err);
            redoSnapshot.put(fileName, err.length() > 0 ? null : current.render());

            if (priorContent == null) {
                library.delete(fileName);
            } else {
                writeRaw(library, fileName, priorContent);
            }
        }

        redoStack.push(new Entry(redoSnapshot, entry.title()));
        return entry;
    }

    public Entry redo(ProtocolLibrary library) {
        if (redoStack.isEmpty()) return null;
        Entry entry = redoStack.pop();

        Map<String, String> undoSnapshot = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entry.snapshot().entrySet()) {
            String fileName = e.getKey();
            String priorContent = e.getValue();

            StringBuilder err = new StringBuilder();
            ProtocolFile current = library.loadSafely(fileName, err);
            undoSnapshot.put(fileName, err.length() > 0 ? null : current.render());

            if (priorContent == null) {
                library.delete(fileName);
            } else {
                writeRaw(library, fileName, priorContent);
            }
        }

        undoStack.push(new Entry(undoSnapshot, entry.title()));
        return entry;
    }

    private void writeRaw(ProtocolLibrary library, String fileName, String rawContent) {
        // Write the exact prior text back verbatim rather than re-rendering
        // through ProtocolFile, so undo restores byte-for-byte what was there.
        try {
            java.nio.file.Path path = library.getProtocolsDir().resolve(
                fileName.endsWith(".prtcl") ? fileName : fileName + ".prtcl");
            java.nio.file.Files.writeString(path, rawContent);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}