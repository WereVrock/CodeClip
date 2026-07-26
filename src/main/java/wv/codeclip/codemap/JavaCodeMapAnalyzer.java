// JavaCodeMapAnalyzer.java
package wv.codeclip.codemap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codemap analyzer for .java files. Extracts the package, imports, the
 * primary type (class/interface/enum/record) and its public/protected
 * members as "exports".
 */
public final class JavaCodeMapAnalyzer implements CodeMapAnalyzer {

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");

    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(?:^|\\s)(?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp|\\s)*"
            + "(class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)",
            Pattern.MULTILINE);

    private static final Pattern PUBLIC_MEMBER_PATTERN = Pattern.compile(
            "(?m)^[ \\t]*public\\s+(?:static\\s+)?(?:final\\s+)?(?:abstract\\s+)?"
            + "(?:[\\w<>\\[\\],\\s]+?\\s+)?(\\w+)\\s*\\(");

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".java");
    }

    @Override
    public FileSummary analyze(String fileName, String code) {
        String safe = code != null ? code : "";
        String stripped = stripCommentsAndStrings(safe);

        List<String> imports = new ArrayList<>();
        Matcher im = IMPORT_PATTERN.matcher(safe);
        while (im.find()) imports.add(im.group(1));

        String typeKind = null;
        String typeName = null;
        Matcher tm = TYPE_PATTERN.matcher(stripped);
        if (tm.find()) {
            typeKind = tm.group(1);
            typeName = tm.group(2);
        }

        LinkedHashSet<String> exports = new LinkedHashSet<>();
        if (typeName != null) {
            exports.add((typeKind != null ? typeKind + " " : "") + typeName);
        }
        Matcher pm = PUBLIC_MEMBER_PATTERN.matcher(stripped);
        while (pm.find()) {
            String member = pm.group(1);
            if (member.equals(typeName)) continue; // constructor
            exports.add(typeName != null ? typeName + "." + member + "()" : member + "()");
        }

        String summary = (typeName != null)
                ? (typeKind != null ? capitalize(typeKind) : "Type") + " " + typeName
                  + (imports.isEmpty() ? "" : ", depends on " + imports.size() + " import(s)")
                : "Java source (no top-level type detected)";

        return new FileSummary(fileName, new ArrayList<>(exports), imports, summary);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String stripCommentsAndStrings(String code) {
        StringBuilder sb = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') { while (i < code.length() && code.charAt(i) != '\n') i++; continue; }
            if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < code.length() && !(code.charAt(i) == '*' && code.charAt(i + 1) == '/')) i++;
                i += 2;
                continue;
            }
            if (c == '"') {
                sb.append('"'); i++;
                while (i < code.length()) {
                    char sc = code.charAt(i);
                    if (sc == '\\') { i += 2; continue; }
                    if (sc == '"') { i++; break; }
                    i++;
                }
                sb.append('"');
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}