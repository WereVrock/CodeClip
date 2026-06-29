package wv.codeclip.html;

import java.util.ArrayList;
import java.util.List;

/**
 * Locates a named structural unit inside HTML, CSS, or JS source text:
 *   - HTML: an element identified by id="name", extent = open tag through matching close tag
 *   - CSS:  a rule block identified by its selector text, extent = selector through closing }
 *   - JS:   a function identified by name, covering:
 *             function name(...) { ... }
 *             const name = (...) => { ... }   (and let/var)
 *             name(...) { ... }               (object/class method shorthand)
 *           extent = from the start of the declaration line through the matching closing }
 *
 * All matching is strict / exact — no whitespace tolerance. If zero or more
 * than one match is found, callers get an empty/multi list and must report
 * "not found" or "ambiguous" themselves; this class never guesses.
 */
public final class HtmlStructuralTarget {

    private HtmlStructuralTarget() {}

    public record Extent(int start, int end, String matchedSignature) {
        public String slice(String code) {
            return code.substring(start, end);
        }
    }

    /**
     * Dispatches to the correct strategy based on file extension.
     * Returns all matches found (0, 1, or more) — caller decides what
     * "ambiguous" or "not found" means for their directive.
     */
    public static List<Extent> findByName(String fileName, String code, String name) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return findHtmlElementById(code, name);
        }
        if (lower.endsWith(".css")) {
            return findCssRuleBySelector(code, name);
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return findJsFunctionByName(code, name);
        }
        return List.of();
    }

    /** True if this file type supports @@METHOD:/@@INSERT_METHOD: targeting at all. */
    public static boolean supportsStructuralTargeting(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith(".css")
                || lower.endsWith(".js") || lower.endsWith(".mjs");
    }

    /**
     * For standalone @@INSERT_METHOD: (no anchor) — where to insert by default.
     * HTML: just before </body> if present, else end of file.
     * CSS:  end of file.
     * JS:   end of file.
     */
    public static int defaultInsertionPoint(String fileName, String code) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            int idx = lastIndexOfIgnoreCase(code, "</body>");
            if (idx >= 0) return idx;
        }
        return code.length();
    }

    // ------------------------------------------------------------------
    // HTML: element by id
    // ------------------------------------------------------------------

    private static List<Extent> findHtmlElementById(String code, String id) {
        List<Extent> results = new ArrayList<>();
        // Match: <tag ... id="theId" ...> or <tag ... id='theId' ...>
        String needleDouble = "id=\"" + id + "\"";
        String needleSingle = "id='" + id + "'";

        int searchFrom = 0;
        while (true) {
            int idxD = code.indexOf(needleDouble, searchFrom);
            int idxS = code.indexOf(needleSingle, searchFrom);
            int idAttrIdx;
            if (idxD < 0 && idxS < 0) break;
            idAttrIdx = (idxD < 0) ? idxS : (idxS < 0 ? idxD : Math.min(idxD, idxS));

            // Walk back to find the start of this tag ('<')
            int tagStart = code.lastIndexOf('<', idAttrIdx);
            if (tagStart < 0) { searchFrom = idAttrIdx + 1; continue; }
            // Guard: make sure there's no '>' between tagStart and idAttrIdx (i.e. id= is inside this tag's attributes)
            int closeBeforeAttr = code.indexOf('>', tagStart);
            if (closeBeforeAttr >= 0 && closeBeforeAttr < idAttrIdx) { searchFrom = idAttrIdx + 1; continue; }

            // Extract the tag name
            int nameEnd = tagStart + 1;
            while (nameEnd < code.length() && (Character.isLetterOrDigit(code.charAt(nameEnd)) || code.charAt(nameEnd) == '-')) {
                nameEnd++;
            }
            String tagName = code.substring(tagStart + 1, nameEnd);
            if (tagName.isEmpty()) { searchFrom = idAttrIdx + 1; continue; }

            // Find end of opening tag '>'
            int openTagEnd = code.indexOf('>', nameEnd);
            if (openTagEnd < 0) { searchFrom = idAttrIdx + 1; continue; }

            boolean selfClosing = code.charAt(openTagEnd - 1) == '/';
            int extentEnd;
            if (selfClosing || isVoidElement(tagName)) {
                extentEnd = openTagEnd + 1;
            } else {
                int matchEnd = findMatchingCloseTag(code, openTagEnd + 1, tagName);
                if (matchEnd < 0) { searchFrom = idAttrIdx + 1; continue; }
                extentEnd = matchEnd;
            }

            results.add(new Extent(tagStart, extentEnd, "<" + tagName + " id=\"" + id + "\">"));
            searchFrom = extentEnd;
        }
        return results;
    }

    private static boolean isVoidElement(String tagName) {
        switch (tagName.toLowerCase()) {
            case "area": case "base": case "br": case "col": case "embed":
            case "hr": case "img": case "input": case "link": case "meta":
            case "param": case "source": case "track": case "wbr":
                return true;
            default:
                return false;
        }
    }

    /**
     * Given position just after a tag's opening '>', finds the index just after
     * the matching closing tag, accounting for nested tags of the same name.
     */
    private static int findMatchingCloseTag(String code, int from, String tagName) {
        String openNeedle = "<" + tagName;
        String closeNeedle = "</" + tagName;
        int depth = 1;
        int i = from;
        while (i < code.length()) {
            int nextOpen = indexOfTagIgnoreCase(code, openNeedle, i);
            int nextClose = indexOfTagIgnoreCase(code, closeNeedle, i);
            if (nextClose < 0) return -1;
            if (nextOpen >= 0 && nextOpen < nextClose) {
                // Confirm it's a real tag boundary (followed by space, >, or /)
                char after = code.charAt(nextOpen + openNeedle.length());
                boolean selfClosingNested = false;
                int tagEnd = code.indexOf('>', nextOpen);
                if (tagEnd > 0 && code.charAt(tagEnd - 1) == '/') selfClosingNested = true;
                if ((after == ' ' || after == '>' || after == '\t' || after == '\n' || after == '/')
                        && !isVoidElement(tagName) && !selfClosingNested) {
                    depth++;
                }
                i = nextOpen + openNeedle.length();
            } else {
                depth--;
                int closeTagEnd = code.indexOf('>', nextClose);
                if (closeTagEnd < 0) return -1;
                if (depth == 0) {
                    return closeTagEnd + 1;
                }
                i = closeTagEnd + 1;
            }
        }
        return -1;
    }

    private static int indexOfTagIgnoreCase(String code, String needle, int from) {
        // Tag names are case-insensitive in HTML; do a manual case-insensitive search
        String lowerCode = code.toLowerCase();
        String lowerNeedle = needle.toLowerCase();
        return lowerCode.indexOf(lowerNeedle, from);
    }

    private static int lastIndexOfIgnoreCase(String code, String needle) {
        return code.toLowerCase().lastIndexOf(needle.toLowerCase());
    }

    // ------------------------------------------------------------------
    // CSS: rule block by selector
    // ------------------------------------------------------------------

    private static List<Extent> findCssRuleBySelector(String code, String selector) {
        List<Extent> results = new ArrayList<>();
        String normalizedTarget = selector.trim().replaceAll("\\s+", " ");

        int i = 0;
        while (i < code.length()) {
            int braceIdx = code.indexOf('{', i);
            if (braceIdx < 0) break;

            int selectorStart = findSelectorStart(code, braceIdx);
            String rawSelector = code.substring(selectorStart, braceIdx).trim();
            String normalizedActual = rawSelector.replaceAll("\\s+", " ");

            int blockEnd = findMatchingCssBrace(code, braceIdx);
            if (blockEnd < 0) break;

            if (normalizedActual.equals(normalizedTarget)) {
                results.add(new Extent(selectorStart, blockEnd, rawSelector));
            }
            i = blockEnd;
        }
        return results;
    }

    /** Walks back from a '{' to the start of its selector (after the previous '}' or start of file). */
    private static int findSelectorStart(String code, int braceIdx) {
        int prevClose = code.lastIndexOf('}', braceIdx - 1);
        int start = (prevClose < 0) ? 0 : prevClose + 1;
        // Skip leading whitespace
        while (start < braceIdx && Character.isWhitespace(code.charAt(start))) start++;
        return start;
    }

    private static int findMatchingCssBrace(String code, int openBraceIdx) {
        int depth = 0;
        for (int i = openBraceIdx; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // JS: function by name
    // ------------------------------------------------------------------

    private static List<Extent> findJsFunctionByName(String code, String name) {
        List<Extent> results = new ArrayList<>();
        results.addAll(findJsFunctionDeclaration(code, name));
        results.addAll(findJsArrowConst(code, name));
        results.addAll(findJsMethodShorthand(code, name));
        return results;
    }

    /** function name(...) { ... } */
    private static List<Extent> findJsFunctionDeclaration(String code, String name) {
        List<Extent> results = new ArrayList<>();
        String needle = "function " + name + "(";
        String needleStar = "function* " + name + "(";
        int searchFrom = 0;
        while (true) {
            int idx = code.indexOf(needle, searchFrom);
            int idxStar = code.indexOf(needleStar, searchFrom);
            int matchIdx = (idx < 0) ? idxStar : (idxStar < 0 ? idx : Math.min(idx, idxStar));
            if (matchIdx < 0) break;

            int lineStart = lineStartOf(code, matchIdx);
            int braceOpen = code.indexOf('{', matchIdx);
            if (braceOpen < 0) { searchFrom = matchIdx + needle.length(); continue; }
            int braceClose = findMatchingJsBrace(code, braceOpen);
            if (braceClose < 0) { searchFrom = matchIdx + needle.length(); continue; }

            results.add(new Extent(lineStart, braceClose, code.substring(lineStart, Math.min(braceOpen + 1, code.length()))));
            searchFrom = braceClose;
        }
        return results;
    }

    /** const/let/var name = (...) => { ... }   — only the brace-body arrow form, not single-expression arrows */
    private static List<Extent> findJsArrowConst(String code, String name) {
        List<Extent> results = new ArrayList<>();
        for (String keyword : new String[]{"const ", "let ", "var "}) {
            String needle = keyword + name + " ";
            int searchFrom = 0;
            while (true) {
                int idx = code.indexOf(needle, searchFrom);
                if (idx < 0) break;
                // Must be at a statement boundary (start of line, ignoring leading whitespace)
                int lineStart = lineStartOf(code, idx);
                String prefix = code.substring(lineStart, idx).trim();
                if (!prefix.isEmpty()) { searchFrom = idx + needle.length(); continue; }

                int afterName = idx + needle.length();
                int eqIdx = code.indexOf('=', afterName);
                if (eqIdx < 0) { searchFrom = idx + needle.length(); continue; }
                String between = code.substring(afterName, eqIdx).trim();
                if (!between.isEmpty()) { searchFrom = idx + needle.length(); continue; }

                int arrowIdx = code.indexOf("=>", eqIdx);
                int semicolonBeforeArrow = code.indexOf(';', eqIdx);
                if (arrowIdx < 0 || (semicolonBeforeArrow >= 0 && semicolonBeforeArrow < arrowIdx)) {
                    searchFrom = idx + needle.length(); continue;
                }
                int braceOpen = code.indexOf('{', arrowIdx);
                int semicolonAfterArrow = code.indexOf(';', arrowIdx);
                if (braceOpen < 0 || (semicolonAfterArrow >= 0 && semicolonAfterArrow < braceOpen)) {
                    // Single-expression arrow (no brace body) — not a structural target we support
                    searchFrom = idx + needle.length(); continue;
                }
                int braceClose = findMatchingJsBrace(code, braceOpen);
                if (braceClose < 0) { searchFrom = idx + needle.length(); continue; }

                // Consume optional trailing semicolon into the extent
                int extentEnd = braceClose;
                int probe = extentEnd;
                while (probe < code.length() && Character.isWhitespace(code.charAt(probe))) probe++;
                if (probe < code.length() && code.charAt(probe) == ';') extentEnd = probe + 1;

                results.add(new Extent(lineStart, extentEnd, code.substring(lineStart, Math.min(braceOpen + 1, code.length()))));
                searchFrom = extentEnd;
            }
        }
        return results;
    }

    /** name(...) { ... } as an object/class method shorthand — name at start of trimmed line, not preceded by 'function'/keyword */
    private static List<Extent> findJsMethodShorthand(String code, String name) {
        List<Extent> results = new ArrayList<>();
        String needle = name + "(";
        int searchFrom = 0;
        while (true) {
            int idx = code.indexOf(needle, searchFrom);
            if (idx < 0) break;

            int lineStart = lineStartOf(code, idx);
            String prefix = code.substring(lineStart, idx);
            String trimmedPrefix = prefix.trim();

            // Must be the first token on the line (possibly with async keyword), not part of a call like foo.bar(
            boolean validPrefix = trimmedPrefix.isEmpty() || trimmedPrefix.equals("async");
            char before = idx > 0 ? code.charAt(idx - 1) : ' ';
            boolean wordBoundaryOk = !Character.isLetterOrDigit(before) && before != '_' && before != '.';

            if (!validPrefix || !wordBoundaryOk) { searchFrom = idx + needle.length(); continue; }

            int parenClose = matchingParen(code, idx + name.length());
            if (parenClose < 0) { searchFrom = idx + needle.length(); continue; }

            int braceOpen = code.indexOf('{', parenClose);
            // Reject if anything other than whitespace lies between ) and {
            if (braceOpen < 0) { searchFrom = idx + needle.length(); continue; }
            String between = code.substring(parenClose + 1, braceOpen).trim();
            if (!between.isEmpty()) { searchFrom = idx + needle.length(); continue; }

            int braceClose = findMatchingJsBrace(code, braceOpen);
            if (braceClose < 0) { searchFrom = idx + needle.length(); continue; }

            results.add(new Extent(lineStart, braceClose, code.substring(lineStart, Math.min(braceOpen + 1, code.length()))));
            searchFrom = braceClose;
        }
        return results;
    }

    private static int matchingParen(String code, int fromOpenParenSearch) {
        int open = code.indexOf('(', fromOpenParenSearch);
        if (open < 0) return -1;
        int depth = 1;
        for (int i = open + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int lineStartOf(String code, int idx) {
        int nl = code.lastIndexOf('\n', idx - 1);
        return nl < 0 ? 0 : nl + 1;
    }

    /**
     * Brace matcher aware of JS strings, template literals, and comments —
     * so braces inside `"{"`, `'{'`, `` `${x}` ``, `// {`, or `/* { *\/` don't
     * throw off the depth count.
     */
    private static int findMatchingJsBrace(String code, int openBraceIdx) {
        int depth = 0;
        boolean inString = false, inChar = false, inTemplate = false;
        boolean inLineComment = false, inBlockComment = false;
        boolean escape = false;
        int templateExprDepth = 0;

        for (int i = openBraceIdx; i < code.length(); i++) {
            char c = code.charAt(i);
            char next = (i + 1 < code.length()) ? code.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') { inBlockComment = false; i++; }
                continue;
            }
            if (inString) {
                if (c == '"' && !escape) inString = false;
                escape = c == '\\' && !escape;
                continue;
            }
            if (inChar) {
                if (c == '\'' && !escape) inChar = false;
                escape = c == '\\' && !escape;
                continue;
            }
            if (inTemplate && templateExprDepth == 0) {
                if (c == '`' && !escape) { inTemplate = false; }
                else if (c == '$' && next == '{') { templateExprDepth = 1; i++; }
                escape = c == '\\' && !escape;
                continue;
            }

            if (c == '/' && next == '/' && !inTemplate) { inLineComment = true; i++; continue; }
            if (c == '/' && next == '*' && !inTemplate) { inBlockComment = true; i++; continue; }
            if (c == '"' && !inTemplate) { inString = true; escape = false; continue; }
            if (c == '\'' && !inTemplate) { inChar = true; escape = false; continue; }
            if (c == '`') { inTemplate = true; escape = false; continue; }

            if (inTemplate && templateExprDepth > 0) {
                if (c == '{') templateExprDepth++;
                else if (c == '}') {
                    templateExprDepth--;
                    if (templateExprDepth == 0) continue;
                }
                continue;
            }

            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }
}