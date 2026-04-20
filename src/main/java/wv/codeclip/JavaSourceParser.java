package wv.codeclip;

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
        code = code.replaceAll("(?s)/\\*.*?\\*/", " ");
        code = code.replaceAll("(?m)//.*?$", " ");
        code = code.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
        code = code.replaceAll("'(?:\\\\.|[^'\\\\])'", "''");
        return code;
    }
}