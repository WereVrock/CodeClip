package wv.codeclip.html;

/**
 * Central list of file extensions accepted by HTML mode.
 * Mirrors the role AppMode.extensions plays for Java/Godot, but HTML mode
 * needs more than one extension, so it lives in its own small class.
 */
public final class HtmlExtensions {

    private HtmlExtensions() {}

    public static final String[] EXTENSIONS = {
        ".html", ".htm", ".css", ".js", ".mjs", ".json", ".svg"
    };

    public static boolean accepts(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }
}