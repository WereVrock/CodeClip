package wv.codeclip.godot;

import wv.codeclip.io.SettingsManager;
import java.io.File;

/**
 * Single source of truth for the Godot project directory.
 * Persisted via SettingsManager.
 */
public final class GodotDirectory {

    private static final String PROP_KEY = "godot.directory";

    private static File directory = null;

    private GodotDirectory() {}

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
        String path = settings.loadGodotDirectory();
        if (path != null && !path.isBlank()) {
            File f = new File(path);
            if (f.exists() && f.isDirectory()) {
                directory = f;
            }
        }
    }

    public static void save(SettingsManager settings) {
        settings.saveGodotDirectory(directory != null ? directory.getAbsolutePath() : "");
    }
}