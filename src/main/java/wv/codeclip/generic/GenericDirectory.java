// ===== GenericDirectory.java =====
package wv.codeclip.generic;

import wv.codeclip.io.SettingsManager;
import java.io.File;

/**
 * Single source of truth for the Generic-mode project root directory.
 * Mirrors wv.codeclip.html.HtmlDirectory exactly.
 */
public final class GenericDirectory {

    private static File directory = null;

    private GenericDirectory() {}

    public static File get() {
        return directory;
    }

    public static boolean isSet() {
        return directory != null;
    }

    public static void set(File dir) {
        directory = dir;
    }

    public static void load(SettingsManager settings) {
        String path = settings.loadGenericDirectory();
        if (path != null && !path.isBlank()) {
            File f = new File(path);
            if (f.exists() && f.isDirectory()) {
                directory = f;
            }
        }
    }

    public static void save(SettingsManager settings) {
        settings.saveGenericDirectory(directory != null ? directory.getAbsolutePath() : "");
    }
}