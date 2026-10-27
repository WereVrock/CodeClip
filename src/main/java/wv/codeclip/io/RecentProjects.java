package wv.codeclip.io;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the last N projects opened in this CodeClip install. A "project" is
 * identified by its source root folder — two folders with the same files but
 * different roots are two separate projects.
 *
 * Storage layout in ~/codeclip.properties:
 *   recent.projects.count          = number of entries
 *   recent.projects.<i>.sourceRoot = absolute path
 *   recent.projects.<i>.name       = project name (may be empty)
 *   recent.projects.<i>.files      = path1|path2|... (same delimiter as loadClassPaths)
 *
 * Index 0 is most recent.
 */
public final class RecentProjects {

    public static final int MAX_ENTRIES = 5;
    private static final String KEY_PREFIX = "recent.projects.";
    private static final String FILE_SEPARATOR = "|";

    public record Entry(String sourceRoot, String projectName, List<String> filePaths) {
        public String folderName() {
            if (sourceRoot == null || sourceRoot.isBlank()) return "";
            java.io.File f = new java.io.File(sourceRoot);
            String name = f.getName();
            return name.isEmpty() ? sourceRoot : name;
        }

        public String menuLabel() {
            String folder = folderName();
            if (projectName == null || projectName.isBlank()) return folder;
            if (projectName.equalsIgnoreCase(folder)) return folder;
            return projectName + " \u2014 " + folder;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public List<Entry> getEntries() {
        return new ArrayList<>(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void add(String sourceRoot, String projectName, List<String> filePaths) {
        if (sourceRoot == null || sourceRoot.isBlank()) return;
        entries.removeIf(e -> e.sourceRoot().equalsIgnoreCase(sourceRoot));
        entries.add(0, new Entry(sourceRoot, projectName == null ? "" : projectName,
                filePaths == null ? List.of() : new ArrayList<>(filePaths)));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }

    public void remove(String sourceRoot) {
        if (sourceRoot == null) return;
        entries.removeIf(e -> e.sourceRoot().equalsIgnoreCase(sourceRoot));
    }

    public void load(SettingsManager settings) {
        entries.clear();
        int count;
        try {
            count = Integer.parseInt(settings.getRawProperty(KEY_PREFIX + "count", "0"));
        } catch (NumberFormatException e) {
            count = 0;
        }
        for (int i = 0; i < count && entries.size() < MAX_ENTRIES; i++) {
            String root = settings.getRawProperty(KEY_PREFIX + i + ".sourceRoot", "");
            if (root.isBlank()) continue;
            String name = settings.getRawProperty(KEY_PREFIX + i + ".name", "");
            String filesStr = settings.getRawProperty(KEY_PREFIX + i + ".files", "");
            List<String> files = new ArrayList<>();
            if (!filesStr.isEmpty()) {
                for (String p : filesStr.split("\\|")) {
                    if (!p.isEmpty()) files.add(p);
                }
            }
            entries.add(new Entry(root, name, files));
        }
    }

    public void save(SettingsManager settings) {
        settings.clearRawProperties(KEY_PREFIX);
        settings.setRawProperty(KEY_PREFIX + "count", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String prefix = KEY_PREFIX + i + ".";
            settings.setRawProperty(prefix + "sourceRoot", e.sourceRoot());
            settings.setRawProperty(prefix + "name", e.projectName() == null ? "" : e.projectName());
            settings.setRawProperty(prefix + "files", String.join(FILE_SEPARATOR, e.filePaths()));
        }
    }
}