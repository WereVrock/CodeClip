package wv.codeclip;

import java.util.*;
import java.util.regex.*;

public class MissingMethodDetector {

    // Matches method declarations (not constructors)
    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "(?m)^[ \\t]*" +
        "(public|protected|private)\\s+" +          // access modifier
        "(static\\s+)?(final\\s+)?(abstract\\s+)?" + // optional modifiers
        "([\\w<>\\[\\]]+)\\s+" +                     // return type
        "(\\w+)\\s*" +                               // method name
        "\\(([^)]*)\\)"                              // parameters
    );

    /**
     * Returns method signatures that exist in oldCode but not in newCode.
     */
    public static List<String> findMissingMethods(String oldCode, String newCode) {
        Set<String> oldMethods = extractMethodSignatures(oldCode);
        Set<String> newMethods = extractMethodSignatures(newCode);

        List<String> missing = new ArrayList<>();
        for (String method : oldMethods) {
            if (!newMethods.contains(method)) {
                missing.add(method);
            }
        }

        return missing;
    }
    

    private static Set<String> extractMethodSignatures(String code) {
        code = stripComments(code);

        Set<String> methods = new HashSet<>();
        Matcher matcher = METHOD_PATTERN.matcher(code);

        while (matcher.find()) {
            String returnType = matcher.group(5);
            String name = matcher.group(6);
            String params = normalizeParams(matcher.group(7));

            // signature = returnType name(params)
            String signature = returnType + " " + name + "(" + params + ")";
            methods.add(signature);
        }

        return methods;
    }

    private static String normalizeParams(String params) {
        if (params.trim().isEmpty()) return "";

        String[] parts = params.split(",");
        List<String> normalized = new ArrayList<>();

        for (String p : parts) {
            // remove parameter names, keep types
            String[] tokens = p.trim().split("\\s+");
            if (tokens.length >= 1) {
                normalized.add(tokens[0]);
            }
        }

        return String.join(",", normalized);
    }

    private static String stripComments(String code) {
        // remove block comments
        code = code.replaceAll("(?s)/\\*.*?\\*/", "");
        // remove line comments
        code = code.replaceAll("//.*", "");
        return code;
    }
}
