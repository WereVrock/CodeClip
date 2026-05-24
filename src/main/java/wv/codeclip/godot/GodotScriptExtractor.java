package wv.codeclip.godot;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts GDScript blocks from text using #@FileStart: / #@FileEnd markers.
 */
public class GodotScriptExtractor {

    private static final String MARKER_START = "#@FileStart:";
    private static final String MARKER_END   = "#@FileEnd";

    private GodotScriptExtractor() {}

    public record ScriptEntry(String fileName, String code) {}

    public static boolean containsFileMarkers(String text) {
        return text != null && text.contains(MARKER_START);
    }

    /**
     * Extracts all #@FileStart: ... #@FileEnd blocks from the given text.
     * Strips the marker lines themselves; the returned code is clean GDScript.
     */
    public static List<ScriptEntry> extract(String text) {
        List<ScriptEntry> results = new ArrayList<>();
        if (text == null) return results;

        String[] lines = text.split("\n", -1);
        int i = 0;

        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith(MARKER_START)) {
                String fileName = trimmed.substring(MARKER_START.length()).trim();
                if (fileName.isBlank()) { i++; continue; }
                if (!fileName.endsWith(".gd")) fileName = fileName + ".gd";

                i++;
                StringBuilder sb = new StringBuilder();
                while (i < lines.length) {
                    String line = lines[i].trim();
                    if (line.equals(MARKER_END) || line.startsWith(MARKER_END)) {
                        i++;
                        break;
                    }
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(lines[i]);
                    i++;
                }

                String code = sb.toString().strip();
                if (!code.isEmpty()) {
                    results.add(new ScriptEntry(fileName, code));
                }
            } else {
                i++;
            }
        }

        return results;
    }
}