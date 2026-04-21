package wv.codeclip.parse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaSourceParser {

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("package\\s+([a-zA-Z0-9_.]+)\\s*;");

    // Matches: class / interface / enum / record with modifiers
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(?:^|\\s)" +
            "(?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp|\\s)*" +
            "(class|interface|enum|record)\\s+" +
            "([A-Za-z_][A-Za-z0-9_]*)",
            Pattern.MULTILINE
    );

    public String parsePackage(String code) {
        Matcher m = PACKAGE_PATTERN.matcher(code);
        return m.find() ? m.group(1) : null;
    }

    public String parseClassName(String code) {
        String clean = stripCommentsAndStrings(code);
        Matcher m = TYPE_PATTERN.matcher(clean);
        return m.find() ? m.group(2) : null;
    }

private String stripCommentsAndStrings(String code) {
        StringBuilder sb = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';

            // Line comment
            if (c == '/' && next == '/') {
                while (i < code.length() && code.charAt(i) != '\n') i++;
                continue;
            }
            // Block comment
            if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < code.length() &&
                       !(code.charAt(i) == '*' && code.charAt(i + 1) == '/')) i++;
                i += 2;
                continue;
            }
            // String literal
            if (c == '"') {
                sb.append('"');
                i++;
                while (i < code.length()) {
                    char sc = code.charAt(i);
                    if (sc == '\\') { i += 2; continue; }
                    if (sc == '"')  { i++; break; }
                    i++;
                }
                sb.append('"');
                continue;
            }
            // Char literal
            if (c == '\'') {
                i++;
                while (i < code.length()) {
                    char sc = code.charAt(i);
                    if (sc == '\\') { i += 2; continue; }
                    if (sc == '\'') { i++; break; }
                    i++;
                }
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

}