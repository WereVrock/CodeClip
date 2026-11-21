package wv.codeclip.html;

/**
 * Adapts the AI's @@REPLACE text to the region that a FUZZY @@FIND actually
 * matched in the file: indentation is shifted from the indentation used in
 * @@FIND to the indentation found in the file, tabs/spaces are converted to
 * the file's style, and line endings follow the matched region (CRLF vs LF).
 * Exact matches are never passed through here.
 */
public final class IndentAdapter {

    private static final int SPACES_PER_TAB = 4;

    private IndentAdapter() {}

    public static String adapt(String matched, String find, String replace) {
        if (replace == null || replace.isEmpty()) return replace;
        String result = replace.replace("\r\n", "\n").replace('\r', '\n');

        String fileIndent = firstIndent(matched);
        String findIndent = firstIndent(find);
        if (fileIndent != null && findIndent != null && !fileIndent.equals(findIndent)) {
            result = reindent(result, findIndent, fileIndent, usesTabs(matched));
        }

        boolean crlf = matched.contains("\r\n") || matched.endsWith("\r");
        if (crlf) {
            result = result.replace("\n", "\r\n");
            if (matched.endsWith("\r") && !result.endsWith("\r")) {
                result += "\r";
            }
        }
        return result;
    }

    private static String reindent(String text, String findIndent, String fileIndent, boolean fileUsesTabs) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String line = lines[i];
            if (line.strip().isEmpty()) {
                sb.append(line);
                continue;
            }
            String lead = leadingWhitespace(line);
            if (!lead.startsWith(findIndent)) {
                sb.append(line);
                continue;
            }
            String rest = lead.substring(findIndent.length());
            rest = convertStyle(rest, fileUsesTabs);
            sb.append(fileIndent).append(rest).append(line.substring(lead.length()));
        }
        return sb.toString();
    }

    /** Converts the extra indentation to the file's tab/space style when it is unambiguous. */
    private static String convertStyle(String rest, boolean fileUsesTabs) {
        if (rest.isEmpty()) return rest;
        if (fileUsesTabs && allChars(rest, ' ') && rest.length() % SPACES_PER_TAB == 0) {
            return "\t".repeat(rest.length() / SPACES_PER_TAB);
        }
        if (!fileUsesTabs && allChars(rest, '\t')) {
            return " ".repeat(rest.length() * SPACES_PER_TAB);
        }
        return rest;
    }

    private static boolean allChars(String s, char c) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != c) return false;
        }
        return true;
    }

    private static boolean usesTabs(String text) {
        for (String line : text.split("\n", -1)) {
            if (!line.strip().isEmpty() && line.startsWith("\t")) return true;
        }
        return false;
    }

    /** Leading whitespace of the first non-blank line, or null if there is none. */
    private static String firstIndent(String text) {
        for (String line : text.split("\n", -1)) {
            if (!line.strip().isEmpty()) return leadingWhitespace(line);
        }
        return null;
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') i++;
            else break;
        }
        return line.substring(0, i);
    }
}