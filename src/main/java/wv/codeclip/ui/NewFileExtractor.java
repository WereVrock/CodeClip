package wv.codeclip.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts new-file blocks (start line with a relative path, end line alone)
 * from pasted text. The blocks are removed from the returned remaining text,
 * so the normal patch/class extraction never sees them.
 */
public final class NewFileExtractor {

    public record NewFile(String path, String content) {}

    public record Result(List<NewFile> files, String remaining, List<String> errors) {}

    private static final String AT = "@@";
    private static final String START = AT + "newfile";
    private static final String END = AT + "endfile";

    private NewFileExtractor() {}

    public static boolean containsNewFile(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String line : text.split("\n", -1)) {
            if (parseStart(line) != null) return true;
        }
        return false;
    }

    public static Result extract(String text) {
        List<NewFile> files = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return new Result(files, "", errors);
        }

        String[] lines = text.split("\n", -1);
        boolean[] consumed = new boolean[lines.length];

        int i = 0;
        while (i < lines.length) {
            String path = parseStart(lines[i]);
            if (path == null) {
                i++;
                continue;
            }
            int end = -1;
            for (int j = i + 1; j < lines.length; j++) {
                if (isEnd(lines[j])) {
                    end = j;
                    break;
                }
                if (parseStart(lines[j]) != null) break;
            }
            if (end < 0) {
                errors.add(path + " (missing end line)");
                i++;
                continue;
            }
            files.add(new NewFile(path, buildContent(lines, i + 1, end)));
            for (int k = i; k <= end; k++) consumed[k] = true;

            // Drop a markdown fence that wraps exactly this block.
            if (i > 0 && !consumed[i - 1]
                    && lines[i - 1].trim().startsWith("```")
                    && end + 1 < lines.length
                    && lines[end + 1].trim().equals("```")) {
                consumed[i - 1] = true;
                consumed[end + 1] = true;
            }
            i = end + 1;
        }

        StringBuilder rest = new StringBuilder();
        boolean first = true;
        for (int k = 0; k < lines.length; k++) {
            if (consumed[k]) continue;
            if (!first) rest.append('\n');
            rest.append(lines[k]);
            first = false;
        }
        return new Result(files, rest.toString(), errors);
    }

    private static String buildContent(String[] lines, int from, int to) {
        List<String> body = new ArrayList<>();
        for (int k = from; k < to; k++) {
            String l = lines[k];
            if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
            body.add(l);
        }
        while (!body.isEmpty() && body.get(0).isBlank()) body.remove(0);
        while (!body.isEmpty() && body.get(body.size() - 1).isBlank()) body.remove(body.size() - 1);
        // Tolerate a markdown fence wrapped around the content itself.
        if (body.size() >= 2
                && body.get(0).trim().startsWith("```")
                && body.get(body.size() - 1).trim().equals("```")) {
            body.remove(body.size() - 1);
            body.remove(0);
        }
        return body.isEmpty() ? "" : String.join("\n", body) + "\n";
    }

    private static String parseStart(String line) {
        String t = line.trim();
        if (t.startsWith("```" + AT)) t = t.substring(3);
        if (t.length() < START.length() || !t.regionMatches(true, 0, START, 0, START.length())) {
            return null;
        }
        String rest = t.substring(START.length());
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0)) && rest.charAt(0) != ':') {
            return null;
        }
        rest = rest.trim();
        if (rest.startsWith(":")) rest = rest.substring(1).trim();
        if (rest.length() >= 2) {
            char f = rest.charAt(0);
            char l = rest.charAt(rest.length() - 1);
            if ((f == '"' || f == '\'' || f == '`') && f == l) {
                rest = rest.substring(1, rest.length() - 1).trim();
            }
        }
        return rest.isEmpty() ? null : rest;
    }

    private static boolean isEnd(String line) {
        return line.trim().equalsIgnoreCase(END);
    }
}