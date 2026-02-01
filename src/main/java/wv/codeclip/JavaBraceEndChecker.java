package wv.codeclip;

public final class JavaBraceEndChecker {

    private JavaBraceEndChecker() {}

    
    
    /**
     * @param source Java source code
     * @return true if all structural braces are balanced (file not cut off)
     */
    public static boolean hasCompleteEnd(String source) {
        if (source == null || source.isEmpty()) {
            return false;
        }

        int balance = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean escape = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = (i + 1 < source.length()) ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            if (inString) {
                if (c == '"' && !escape) inString = false;
                escape = (c == '\\' && !escape);
                continue;
            }

            if (inChar) {
                if (c == '\'' && !escape) inChar = false;
                escape = (c == '\\' && !escape);
                continue;
            }

            // normal code
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else if (c == '"') {
                inString = true;
                escape = false;
            } else if (c == '\'') {
                inChar = true;
                escape = false;
            } else if (c == '{') {
                balance++;
            } else if (c == '}') {
                balance--;
            }
        }

        return balance == 0;
    }
}
