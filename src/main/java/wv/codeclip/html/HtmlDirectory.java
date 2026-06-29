package wv.codeclip.html;

import wv.codeclip.io.SettingsManager;
import java.io.File;

/**
 * Single source of truth for the HTML project root directory.
 * Mirrors wv.codeclip.godot.GodotDirectory exactly.
 */
public final class HtmlDirectory {

    private static File directory = null;

    private HtmlDirectory() {}

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
        String path = settings.loadHtmlDirectory();
        if (path != null && !path.isBlank()) {
            File f = new File(path);
            if (f.exists() && f.isDirectory()) {
                directory = f;
            }
        }
    }

    public static void save(SettingsManager settings) {
        settings.saveHtmlDirectory(directory != null ? directory.getAbsolutePath() : "");
    }
}