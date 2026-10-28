package wv.codeclip.io;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads / writes the project-scoped properties stored in this project's
 * buildinfo.properties, alongside LAST_UPDATED / BUILD_NO.
 *
 * Manual read/write (not java.util.Properties) so that:
 *   - Key order is preserved (LAST_UPDATED, BUILD_NO stay at top).
 *   - No Properties.store() Date comment, so content is byte-stable across
 *     writes.
 *   - UTF-8 characters (Turkish etc.) stay readable, no escape sequences.
 *
 * onSaved fires after every successful write so the caller can refresh any
 * in-memory cache it maintains for this file (CodeClip's repo tracks
 * buildinfo.properties in classCodeMap; without this the disk and the
 * in-memory view silently diverge and undo/checkpoint/versions lie).
 */
public final class ProjectSettings {

    // Same physical file is opened by multiple ProjectSettings instances
    // (BuildInfoStamper creates its own). A per-file monitor serializes
    // read-modify-write so a concurrent saveProjectName / saveBuildInfo
    // pair can't interleave and clobber one another.
    private static final java.util.concurrent.ConcurrentHashMap<String, Object>
            FILE_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private final File file;
    private final Runnable onSaved;

    private Object lock() {
        String key;
        try { key = file.getCanonicalPath().toLowerCase(); }
        catch (IOException e) { key = file.getAbsolutePath().toLowerCase(); }
        return FILE_LOCKS.computeIfAbsent(key, k -> new Object());
    }

    public ProjectSettings(File sourceRoot, Runnable onSaved) {
        this.file = new File(sourceRoot, "buildinfo.properties");
        this.onSaved = onSaved;
    }

    public File getFile() {
        return file;
    }

    public String loadProjectName() {
        return read().getOrDefault("projectName", "");
    }

    public long loadIconTintSeed() {
        String raw = read().getOrDefault("iconTintSeed", "0");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public String loadProtocolsDirectory() {
        return read().getOrDefault("protocols.directory", "");
    }

public void saveProjectName(String name) {
    String value = name == null ? "" : name;
    synchronized (lock()) {
        Map<String, String> m = read();
        m.put("projectName", value);
        System.out.println("[PS] saveProjectName existing=" + m.keySet()
                + " value='" + value + "'");
        write(m);
        String written = loadProjectName();
        System.out.println("[PS] saveProjectName round-trip='" + written + "'");
        if (!java.util.Objects.equals(written, value)) {
            System.err.println("CodeClip: saveProjectName ROUND-TRIP FAILED — "
                    + "wrote '" + value + "' read '" + written + "'");
        }
    }
}

public void saveIconTintSeed(long seed) {
    synchronized (lock()) {
        Map<String, String> m = read();
        m.put("iconTintSeed", Long.toString(seed));
        write(m);
    }
}

/**
 * Updates LAST_UPDATED and BUILD_NO while preserving every other property
 * already in the file. Uses the same read-modify-write path as
 * saveProjectName so Properties.store()'s escaping quirks and header
 * rewriting can't silently drop projectName / permanent.checkpoint.enabled
 * / backup.directory on the next stamp.
 */

/**
 * Updates LAST_UPDATED + BUILD_NO while preserving every other key.
 * Uses the same read-modify-write + lock as saveProjectName so it can't
 * collide with a concurrent save and drop projectName / backup.directory.
 */
public void saveBuildInfo(String timestamp, String buildNo) {
    synchronized (lock()) {
        Map<String, String> m = read();
        System.out.println("[PS] saveBuildInfo before keys=" + m.keySet()
                + " projectName='" + m.get("projectName") + "'");
        m.put("LAST_UPDATED", timestamp == null ? "" : timestamp);
        m.put("BUILD_NO", buildNo == null ? "" : buildNo);
        write(m);
        System.out.println("[PS] saveBuildInfo after projectName='"
                + read().get("projectName") + "'");
    }
}

public void saveProtocolsDirectory(String path) {
    synchronized (lock()) {
        Map<String, String> m = read();
        m.put("protocols.directory", path == null ? "" : path);
        write(m);
    }
}

public String loadBackupDirectory() {
        return read().getOrDefault("backup.directory", "");
    }

public void saveBackupDirectory(String path) {
    synchronized (lock()) {
        Map<String, String> m = read();
        m.put("backup.directory", path == null ? "" : path);
        write(m);
        System.out.println("[PS] saveBackupDirectory value='" + path
                + "' readback='" + loadBackupDirectory() + "'");
    }
}

public boolean loadPermanentCheckpointEnabled() {
        return Boolean.parseBoolean(read().getOrDefault("permanent.checkpoint.enabled", "false"));
    }

public void savePermanentCheckpointEnabled(boolean value) {
    synchronized (lock()) {
        Map<String, String> m = read();
        m.put("permanent.checkpoint.enabled", String.valueOf(value));
        write(m);
    }
}

/**
     * The folder where this project's backup checkpoints live:
     * &lt;backup.directory&gt;/&lt;projectName&gt;_checkpoint/. Returns null
     * when no base backup directory is configured.
     *
     * Centralized so every read and write path (CheckpointDialog's writer,
     * CodeClipFrame's restore-on-open) computes the same target — otherwise
     * a change to the naming rule would silently desync the two.
     */

public File resolveBackupFolder() {
    String base = loadBackupDirectory();
    if (base == null || base.isBlank()) {
        System.out.println("[PS] resolveBackupFolder base empty");
        return null;
    }
    File named  = new File(base, backupSubfolderName());
    File legacy = new File(base, "codeclip_checkpoint");
    int namedCount  = countFilesIn(named);
    int legacyCount = countFilesIn(legacy);
    System.out.println("[PS] resolveBackupFolder base=" + base
            + " named=" + named.getName() + "(files=" + namedCount + ")"
            + " legacy=" + legacy.getName() + "(files=" + legacyCount + ")");
    if (namedCount > 0)  return named;
    if (legacyCount > 0) return legacy;
    return named;
}

private static int countFilesIn(File dir) {
    if (dir == null || !dir.isDirectory()) return 0;
    File[] files = dir.listFiles(File::isFile);
    return files == null ? 0 : files.length;
}

private static boolean hasFiles(File dir) {
    if (dir == null || !dir.isDirectory()) return false;
    File[] files = dir.listFiles(File::isFile);
    return files != null && files.length > 0;
}

private String backupSubfolderName() {
        String name = loadProjectName();
        if (name == null || name.isBlank()) {
            return "codeclip_checkpoint";
        }
        String sanitized = name.replaceAll("[^a-zA-Z0-9._-]", "_").trim();
        if (sanitized.isEmpty()) return "codeclip_checkpoint";
        return sanitized + "_checkpoint";
    }

    public String loadCheckpointName() {
        return read().getOrDefault("checkpoint.name", "");
    }

public void saveCheckpointName(String name) {
    synchronized (lock()) {
        Map<String, String> m = read();
        m.put("checkpoint.name", name == null ? "" : name);
        write(m);
    }
}

private Map<String, String> read() {
    Map<String, String> map = new LinkedHashMap<>();
    if (!file.exists()) return map;
    try {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            map.put(line.substring(0, eq), line.substring(eq + 1));
        }
    } catch (IOException e) {
        e.printStackTrace();
        System.err.println("[PS] READ FAILED on " + file
                + " — returning empty map. Next write will CLOBBER the file.");
    }
    return map;
}

private void write(Map<String, String> map) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : map.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}