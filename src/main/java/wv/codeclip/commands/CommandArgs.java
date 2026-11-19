package wv.codeclip.commands;

import wv.codeclip.model.ClassRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared, forgiving parsing for the @@Enable / @@Copy / @@Move / @@Delete
 * chat commands. Tolerates code fences, backticks, bullets, a colon after the
 * command, lists continued after a trailing comma, several command lines,
 * quotes, paths, a missing extension and case differences.
 */
public final class CommandArgs {

    private static final int MAX_MESSAGE_WITH_PROSE = 3000;

    private CommandArgs() {}

    /**
     * True if the text is a command message. A command on the first meaningful
     * line always counts. A command after other prose only counts when the
     * message is short and contains no patch / file-block markers.
     */
    public static boolean hasCommand(String text, String command) {
        if (text == null || text.isBlank()) return false;
        boolean seenOther = false;
        for (String line : text.split("\\R", -1)) {
            String c = cleanLine(line);
            if (c.isEmpty()) continue;
            if (isCommandLine(c, command)) {
                if (!seenOther) return true;
                return !containsWorkMarkers(text) && text.length() <= MAX_MESSAGE_WITH_PROSE;
            }
            seenOther = true;
        }
        return false;
    }

    /** Text from the first command line to the end, fences stripped; null if absent. */
    public static String sliceFromCommand(String text, String command) {
        if (text == null) return null;
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String c = cleanLine(lines[i]);
            if (isCommandLine(c, command)) {
                StringBuilder sb = new StringBuilder(c);
                for (int j = i + 1; j < lines.length; j++) {
                    sb.append('\n').append(lines[j]);
                }
                String r = sb.toString().strip();
                while (r.endsWith("`")) {
                    r = r.substring(0, r.length() - 1).stripTrailing();
                }
                return r;
            }
        }
        return null;
    }

    /** Argument text of every command line, joined by commas; null if no command line. */
    public static String argsOf(String text, String command) {
        if (text == null) return null;
        String[] lines = text.split("\\R", -1);
        int prefixLen = ("@@" + command).length();
        StringBuilder all = new StringBuilder();
        boolean found = false;
        for (int i = 0; i < lines.length; i++) {
            String c = cleanLine(lines[i]);
            if (!isCommandLine(c, command)) continue;
            found = true;
            String arg = trimArg(c.substring(prefixLen));
            // Names listed on the lines below a bare command.
            if (arg.isEmpty()) {
                while (i + 1 < lines.length) {
                    String next = cleanLine(lines[i + 1]);
                    if (next.isEmpty() || next.startsWith("@@")) break;
                    i++;
                    arg = arg.isEmpty() ? trimArg(next) : arg + ", " + trimArg(next);
                }
            }
            // List continued on the next line after a trailing comma.
            while ((arg.endsWith(",") || arg.endsWith(";")) && i + 1 < lines.length) {
                String next = cleanLine(lines[i + 1]);
                if (next.isEmpty() || next.startsWith("@@")) break;
                i++;
                arg = arg + " " + trimArg(next);
            }
            if (all.length() > 0) all.append(", ");
            all.append(arg);
        }
        return found ? all.toString() : null;
    }

    /** Splits an argument string into normalized (lowercase, forward-slash) file targets. */
    public static List<String> parseTargets(String args) {
        Set<String> out = new LinkedHashSet<>();
        if (args == null) return new ArrayList<>(out);
        String s = args.replaceAll("[`\"'()\\[\\]\u201c\u201d\u2018\u2019]", " ");
        for (String token : s.split("[,;\\s]+")) {
            String t = token.strip();
            while (t.endsWith(".") || t.endsWith(":")) {
                t = t.substring(0, t.length() - 1);
            }
            if (t.isEmpty()) continue;
            if (t.equalsIgnoreCase("and") || t.equals("&") || t.equals("+")) continue;
            t = t.replace('\\', '/');
            while (t.startsWith("./")) t = t.substring(2);
            if (t.isEmpty()) continue;
            out.add(t.toLowerCase());
        }
        return new ArrayList<>(out);
    }

    /**
     * Finds loaded files for one target. Matches the exact file name, or the name
     * without extension when the target has none, or (as a last resort) the name
     * ignoring '_', '-' and spaces. A relative path narrows down duplicate names.
     */
    public static List<Map.Entry<String, File>> resolve(ClassRepository repo, String target) {
        String t = target.replace('\\', '/').toLowerCase();
        while (t.startsWith("./")) t = t.substring(2);
        String bare = t.contains("/") ? t.substring(t.lastIndexOf('/') + 1) : t;

        List<Map.Entry<String, File>> matches = collectMatches(repo, bare, false);
        if (matches.isEmpty()) {
            matches = collectMatches(repo, bare, true);
        }
        if (t.contains("/") && matches.size() > 1) {
            String suffix = "/" + t;
            List<Map.Entry<String, File>> narrowed = new ArrayList<>();
            for (Map.Entry<String, File> e : matches) {
                if (e.getKey().replace('\\', '/').toLowerCase().endsWith(suffix)) {
                    narrowed.add(e);
                }
            }
            if (!narrowed.isEmpty()) matches = narrowed;
        }
        return matches;
    }

    private static List<Map.Entry<String, File>> collectMatches(ClassRepository repo, String bare, boolean fuzzy) {
        String wanted = fuzzy ? squash(bare) : bare;
        boolean noExt = bare.indexOf('.') < 0;
        List<Map.Entry<String, File>> out = new ArrayList<>();
        for (Map.Entry<String, File> e : repo.getClassFileMap().entrySet()) {
            File f = e.getValue();
            if (f == null) continue;
            String name = f.getName().toLowerCase();
            if (fuzzy) name = squash(name);
            if (name.equals(wanted) || (noExt && stripExt(name).equals(wanted))) {
                out.add(e);
            }
        }
        return out;
    }

    private static String cleanLine(String line) {
        String s = line.strip();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '`' || c == '>' || c == '*' || c == '-' || c == '\u2022' || Character.isWhitespace(c)) {
                i++;
            } else {
                break;
            }
        }
        return s.substring(i);
    }

    private static boolean isCommandLine(String cleaned, String command) {
        String marker = "@@" + command;
        if (!cleaned.regionMatches(true, 0, marker, 0, marker.length())) return false;
        if (cleaned.length() == marker.length()) return true;
        return !Character.isLetterOrDigit(cleaned.charAt(marker.length()));
    }

    private static String trimArg(String s) {
        String r = s.strip();
        while (!r.isEmpty() && (r.charAt(0) == ':' || r.charAt(0) == '=')) {
            r = r.substring(1).stripLeading();
        }
        while (!r.isEmpty() && r.charAt(r.length() - 1) == '`') {
            r = r.substring(0, r.length() - 1).stripTrailing();
        }
        return r;
    }

    private static boolean containsWorkMarkers(String text) {
        return text.contains("@@PATCH") || text.contains("#@FileStart")
                || text.contains("@@FILE:") || text.contains("@@newfile");
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String squash(String s) {
        return s.replaceAll("[_\\-\\s]", "");
    }
}