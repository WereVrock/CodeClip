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

    private final File file;
    private final Runnable onSaved;

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
        Map<String, String> m = read();
        m.put("projectName", name == null ? "" : name);
        write(m);
    }

    public void saveIconTintSeed(long seed) {
        Map<String, String> m = read();
        m.put("iconTintSeed", Long.toString(seed));
        write(m);
    }

    public void saveProtocolsDirectory(String path) {
        Map<String, String> m = read();
        m.put("protocols.directory", path == null ? "" : path);
        write(m);
    }

    public String loadBackupDirectory() {
        return read().getOrDefault("backup.directory", "");
    }

    public void saveBackupDirectory(String path) {
        Map<String, String> m = read();
        m.put("backup.directory", path == null ? "" : path);
        write(m);
    }

    public boolean loadPermanentCheckpointEnabled() {
        return Boolean.parseBoolean(read().getOrDefault("permanent.checkpoint.enabled", "false"));
    }

    public void savePermanentCheckpointEnabled(boolean value) {
        Map<String, String> m = read();
        m.put("permanent.checkpoint.enabled", String.valueOf(value));
        write(m);
    }

    private Map<String, String> read() {
        Map<String, String> map = new LinkedHashMap<>();
        if (!file.exists()) {
            return map;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                map.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (IOException e) {
            e.printStackTrace();
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