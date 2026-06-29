package wv.codeclip.html;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts #@FileStart: <relative/path> ... #@FileEnd blocks from text.
 * Unlike GodotScriptExtractor, the marker carries a full relative path
 * (with subfolders) instead of a bare filename, since HTML projects need
 * nested directories (css/, js/, etc.).
 */
public final class HtmlScriptExtractor {

    private static final String MARKER_START = "#@FileStart:";
    private static final String MARKER_END   = "#@FileEnd";

    private HtmlScriptExtractor() {}

    public record FileEntry(String relativePath, String code) {}

    public static boolean containsFileMarkers(String text) {
        return text != null && text.contains(MARKER_START);
    }

    /**
     * Extracts all #@FileStart: ... #@FileEnd blocks from the given text.
     * The relative path is normalized to forward slashes and stripped of
     * any leading "./" or "/".
     */
    public static List<FileEntry> extract(String text) {
        List<FileEntry> results = new ArrayList<>();
        if (text == null) return results;

        String[] lines = text.split("\n", -1);
        int i = 0;

        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith(MARKER_START)) {
                String rawPath = trimmed.substring(MARKER_START.length()).trim();
                String relPath = normalizePath(rawPath);
                if (relPath.isBlank()) { i++; continue; }

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
                    results.add(new FileEntry(relPath, code));
                }
            } else {
                i++;
            }
        }

        return results;
    }

    private static String normalizePath(String raw) {
        String p = raw.replace('\\', '/').trim();
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }
}