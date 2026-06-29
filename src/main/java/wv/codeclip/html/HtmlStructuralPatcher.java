package wv.codeclip.html;

import wv.codeclip.model.PatchException;
import java.util.List;

/**
 * Implements @@METHOD:/@@REPLACE: and @@AFTER_METHOD:/@@INSERT_METHOD: (and
 * standalone @@INSERT_METHOD:) for HTML mode, using HtmlStructuralTarget to
 * find the named unit (element id / CSS selector / JS function) per file type.
 *
 * Strict matching throughout — exact text only, no whitespace tolerance,
 * mirroring StrictPatchApplier's philosophy for @@FIND/@@REPLACE.
 */
public final class HtmlStructuralPatcher {

    private HtmlStructuralPatcher() {}

    /**
     * Replaces the named structural unit (element/rule/function) with new content.
     * @param fileName  used to pick the strategy (html/css/js)
     * @param code      current full file content
     * @param name      the id / selector / function name from @@METHOD:
     * @param replacement the new content from @@REPLACE: (used verbatim)
     */
    public static String applyMethodReplace(String fileName, String code, String name, String replacement)
            throws PatchException {
        if (!HtmlStructuralTarget.supportsStructuralTargeting(fileName)) {
            throw new PatchException(
                    "@@METHOD: targeting is not supported for file: " + fileName,
                    fileName);
        }

        List<HtmlStructuralTarget.Extent> matches = HtmlStructuralTarget.findByName(fileName, code, name);

        if (matches.isEmpty()) {
            throw new PatchException(
                    "@@METHOD: target '" + name + "' not found in " + fileName
                    + " (" + targetKindLabel(fileName) + ").",
                    fileName);
        }
        if (matches.size() > 1) {
            throw new PatchException(
                    "@@METHOD: target '" + name + "' matches " + matches.size()
                    + " locations in " + fileName + " — must match exactly once. "
                    + "Use @@FIND/@@REPLACE with more surrounding context to target a specific one.",
                    fileName);
        }

        HtmlStructuralTarget.Extent extent = matches.get(0);
        String before = code.substring(0, extent.start());
        String after = code.substring(extent.end());
        return before + replacement.strip() + after;
    }

    /**
     * Inserts new content immediately after the named anchor unit.
     */
    public static String applyInsertAfter(String fileName, String code, String anchorName, String insertion)
            throws PatchException {
        if (!HtmlStructuralTarget.supportsStructuralTargeting(fileName)) {
            throw new PatchException(
                    "@@AFTER_METHOD: targeting is not supported for file: " + fileName,
                    fileName);
        }

        List<HtmlStructuralTarget.Extent> matches = HtmlStructuralTarget.findByName(fileName, code, anchorName);

        if (matches.isEmpty()) {
            throw new PatchException(
                    "@@AFTER_METHOD: anchor '" + anchorName + "' not found in " + fileName
                    + " (" + targetKindLabel(fileName) + ").",
                    fileName);
        }
        if (matches.size() > 1) {
            throw new PatchException(
                    "@@AFTER_METHOD: anchor '" + anchorName + "' matches " + matches.size()
                    + " locations in " + fileName + " — must match exactly once.",
                    fileName);
        }

        int insertAt = matches.get(0).end();
        String before = code.substring(0, insertAt);
        String after = code.substring(insertAt);
        return joinWithSpacing(before, insertion.strip(), after);
    }

    /**
     * Standalone @@INSERT_METHOD: with no anchor — inserts at the file-type's
     * default insertion point (before </body> for HTML, end of file otherwise).
     */
    public static String applyInsertDefault(String fileName, String code, String insertion)
            throws PatchException {
        if (!HtmlStructuralTarget.supportsStructuralTargeting(fileName)) {
            throw new PatchException(
                    "@@INSERT_METHOD: targeting is not supported for file: " + fileName,
                    fileName);
        }
        int insertAt = HtmlStructuralTarget.defaultInsertionPoint(fileName, code);
        String before = code.substring(0, insertAt);
        String after = code.substring(insertAt);
        return joinWithSpacing(before, insertion.strip(), after);
    }

    /**
     * Checks whether inserting `incoming` would duplicate an existing unit with
     * the same name and identical content (mirrors Java mode's silent-skip-if-identical
     * behavior for @@INSERT_METHOD:). Returns the existing extent if a same-named
     * unit exists (regardless of whether content matches), so callers can decide
     * whether to skip, error, or delegate to a conflict resolver. Returns null if
     * no unit with this name exists yet, or if no name could be parsed from `incoming`.
     */
    public static ExistingMatch findExistingForInsert(String fileName, String code, String incoming) {
        String name = parseNameForInsert(fileName, incoming);
        if (name == null) return null;
        List<HtmlStructuralTarget.Extent> matches = HtmlStructuralTarget.findByName(fileName, code, name);
        if (matches.size() != 1) return null;
        HtmlStructuralTarget.Extent ext = matches.get(0);
        String existingCode = ext.slice(code);
        boolean identical = normalizeForComparison(existingCode).equals(normalizeForComparison(incoming.strip()));
        return new ExistingMatch(name, existingCode, ext, identical);
    }

    public record ExistingMatch(String name, String existingCode,
                                 HtmlStructuralTarget.Extent extent, boolean identicalToIncoming) {}

    /** Best-effort extraction of the unit name from a block of incoming content, per file type. */
    private static String parseNameForInsert(String fileName, String incoming) {
        String lower = fileName.toLowerCase();
        String trimmed = incoming.strip();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            int idIdx = trimmed.indexOf("id=\"");
            int quoteStyle = 1;
            if (idIdx < 0) { idIdx = trimmed.indexOf("id='"); quoteStyle = 2; }
            if (idIdx < 0) return null;
            int valueStart = idIdx + 4;
            char quoteChar = quoteStyle == 1 ? '"' : '\'';
            int valueEnd = trimmed.indexOf(quoteChar, valueStart);
            if (valueEnd < 0) return null;
            return trimmed.substring(valueStart, valueEnd);
        }
        if (lower.endsWith(".css")) {
            int braceIdx = trimmed.indexOf('{');
            if (braceIdx < 0) return null;
            return trimmed.substring(0, braceIdx).trim().replaceAll("\\s+", " ");
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            // Reuse the same heuristics as PatchApplier's Java method-name extraction,
            // adapted for JS: look for "function name(" or "name(" at line start.
            for (String line : trimmed.lines().toList()) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("//") || t.startsWith("*")) continue;
                if (t.startsWith("function ") || t.startsWith("function* ")) {
                    int afterKeyword = t.startsWith("function* ") ? "function* ".length() : "function ".length();
                    int paren = t.indexOf('(', afterKeyword);
                    if (paren > afterKeyword) return t.substring(afterKeyword, paren).trim();
                }
                for (String kw : new String[]{"const ", "let ", "var "}) {
                    if (t.startsWith(kw)) {
                        int eq = t.indexOf('=', kw.length());
                        if (eq > 0) return t.substring(kw.length(), eq).trim();
                    }
                }
                int paren = t.indexOf('(');
                if (paren > 0) {
                    String candidate = t.substring(0, paren).trim();
                    if (candidate.equals("async")) continue;
                    if (candidate.matches("[a-zA-Z_$][a-zA-Z0-9_$]*")) return candidate;
                }
            }
        }
        return null;
    }

    private static String normalizeForComparison(String code) {
        if (code == null) return "";
        return code.replaceAll("\\r\\n|\\r", "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^[ \\t]+", "")
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("\n{2,}", "\n")
                .strip();
    }

    private static String joinWithSpacing(String before, String middle, String after) {
        String trimmedBefore = before.stripTrailing();
        String trimmedAfter = after.stripLeading();
        return trimmedBefore + "\n\n" + middle + "\n\n" + trimmedAfter;
    }

    private static String targetKindLabel(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "looked for an element with this id";
        if (lower.endsWith(".css")) return "looked for a rule with this selector";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "looked for a function with this name";
        return "unknown target kind";
    }
}