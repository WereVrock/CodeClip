package wv.codeclip.patch;

import wv.codeclip.model.PatchException;
import wv.codeclip.model.PatchChange;
import wv.codeclip.model.ClassRepository;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PatchApplier {

    private final ClassRepository repo;

    public PatchApplier(ClassRepository repo) {
        this.repo = repo;
    }

    public PatchResult apply(List<PatchChange> changes) {
        Map<String, String> workingCode = new HashMap<>();
        Map<String, List<String>> fileSuccesses = new LinkedHashMap<>();
        Map<String, List<FailedChange>> fileFailures = new LinkedHashMap<>();

        for (PatchChange change : changes) {
            String path = resolveFilePath(change.fileName());
            if (path == null) {
                fileFailures
                        .computeIfAbsent(change.fileName(), k -> new ArrayList<>())
                        .add(new FailedChange(change.fileName(),
                                "File not found in loaded classes: " + change.fileName()));
                continue;
            }

            String code = workingCode.getOrDefault(path, repo.getClassCodeMap().get(path));

            try {
                switch (change) {
                    case PatchChange.FindReplace fr -> {
                        String newCode = applyFindReplace(fr, code);
                        workingCode.put(path, newCode);
                        fileSuccesses
                                .computeIfAbsent(path, k -> new ArrayList<>())
                                .add("FindReplace in " + fr.fileName());
                    }
                    case PatchChange.MethodReplace mr -> {
                        String newCode = applyMethodReplace(mr, code);
                        workingCode.put(path, newCode);
                        fileSuccesses
                                .computeIfAbsent(path, k -> new ArrayList<>())
                                .add("MethodReplace '" + mr.methodName() + "' in " + mr.fileName());
                    }
                    case PatchChange.InsertMethod im -> {
                        String newCode = applyInsertMethod(im, code);
                        workingCode.put(path, newCode);
                        String desc = im.afterMethod() != null
                                ? "InsertMethod after '" + im.afterMethod() + "' in " + im.fileName()
                                : "InsertMethod (end of class) in " + im.fileName();
                        fileSuccesses
                                .computeIfAbsent(path, k -> new ArrayList<>())
                                .add(desc);
                    }
                }
            } catch (PatchException e) {
                fileFailures
                        .computeIfAbsent(change.fileName(), k -> new ArrayList<>())
                        .add(new FailedChange(change.fileName(), e.getMessage()));
            }
        }

        for (String failedFileName : fileFailures.keySet()) {
            String path = resolveFilePath(failedFileName);
            if (path != null) {
                workingCode.remove(path);
                fileSuccesses.remove(path);
            }
        }

        Map<String, String> undoSnapshot = new LinkedHashMap<>();
        for (String path : workingCode.keySet()) {
            String previous = repo.getClassCodeMap().get(path);
            if (previous != null) {
                undoSnapshot.put(path, previous);
            }
        }

        List<String> applied = new ArrayList<>();
        List<String> writeErrors = new ArrayList<>();

        for (Map.Entry<String, String> entry : workingCode.entrySet()) {
            String path = entry.getKey();
            String finalCode = entry.getValue();
            File file = repo.getClassFileMap().get(path);
            try {
                Files.writeString(file.toPath(), finalCode);
                repo.getClassCodeMap().put(path, finalCode);
                repo.getDisabledClasses().remove(path);
                applied.add(file.getName());
            } catch (IOException e) {
                writeErrors.add(file.getName() + ": " + e.getMessage());
            }
        }

        List<FailedChange> allFailures = new ArrayList<>();
        for (List<FailedChange> failures : fileFailures.values()) {
            allFailures.addAll(failures);
        }
        for (String writeError : writeErrors) {
            allFailures.add(new FailedChange(writeError, "Failed to write to disk: " + writeError));
        }

        List<String> summary = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : fileSuccesses.entrySet()) {
            for (String desc : entry.getValue()) {
                summary.add("✓ " + desc);
            }
        }

        // Collect all changes that did not succeed
        Set<String> appliedSet = new java.util.HashSet<>(applied);
        // A change failed if its file had any failure, or its file wasn't written
        Set<String> failedFileNames = new java.util.HashSet<>();
        for (FailedChange fc : allFailures) {
            failedFileNames.add(fc.fileName());
        }
        List<PatchChange> failedChanges = new ArrayList<>();
        for (PatchChange change : changes) {
            String path = resolveFilePath(change.fileName());
            boolean fileApplied = path != null && appliedSet.contains(
                repo.getClassFileMap().containsKey(path)
                    ? repo.getClassFileMap().get(path).getName()
                    : "");
            if (!fileApplied || failedFileNames.contains(change.fileName())) {
                failedChanges.add(change);
            }
        }

        return new PatchResult(summary, allFailures, applied, undoSnapshot, failedChanges);
    }

private String applyFindReplace(PatchChange.FindReplace fr, String code)
        throws PatchException {
    String find = fr.find();
    String replace = normalize(fr.replace());
    String original = code;

    // Step 1: exact match
    int count = countOccurrences(code, find);
    if (count == 1) return code.replace(find, replace);
    if (count > 1) throw ambiguousException(fr.fileName(), count, find, "exact");

    // Step 2: normalize line endings — normCode is structurally close to original,
    // mapOffset can walk it back reliably
    String normCode = normalize(code);
    String normFind = normalize(find);
    count = countOccurrences(normCode, normFind);
    if (count == 1) return spliceInto(original, normCode, normFind, replace);
    if (count > 1) throw ambiguousException(fr.fileName(), count, find, "line-ending normalization");

    // Steps 3-5 build on normCode (character-count-preserving transforms),
    // so mapOffset from normCode -> original still works
    String trimCode = trimLines(normCode);
    String trimFind = trimLines(normFind);
    count = countOccurrences(trimCode, trimFind);
    if (count == 1) return spliceInto(original, normCode, trimCode, trimFind, replace);
    if (count > 1) throw ambiguousException(fr.fileName(), count, find, "trailing-whitespace normalization");

    String tabCode = normalizeTabs(trimCode);
    String tabFind = normalizeTabs(trimFind);
    count = countOccurrences(tabCode, tabFind);
    if (count == 1) return spliceInto(original, normCode, tabCode, tabFind, replace);
    if (count > 1) throw ambiguousException(fr.fileName(), count, find, "tab normalization");

    String blankCode = collapseBlankLines(tabCode);
    String blankFind = collapseBlankLines(tabFind);
    count = countOccurrences(blankCode, blankFind);
    if (count == 1) return spliceInto(original, normCode, blankCode, blankFind, replace);
    if (count > 1) throw ambiguousException(fr.fileName(), count, find, "blank-line normalization");

    // Steps 6-8 destroy structure (strip indent, collapse/remove whitespace).
    // We find the match in the transformed string, map back to normCode first
    // (still line-by-line intact), then map normCode -> original.
    String dedentCode = stripIndent(normCode);
    String dedentFind = stripIndent(normFind);
    count = countOccurrences(dedentCode, dedentFind);
    if (count == 1) return spliceInto(original, normCode, dedentCode, dedentFind, replace);
    if (count > 1) throw ambiguousException(fr.fileName(), count, find, "indent-stripping");

    String collapseCode = collapseWhitespace(normCode);
    String collapseFind = collapseWhitespace(normFind);
    count = countOccurrences(collapseCode, collapseFind);
    if (count == 1) return spliceInto(original, normCode, collapseCode, collapseFind, replace);
    if (count > 1) throw ambiguousException(fr.fileName(), count, find, "whitespace collapsing");

    String noSpaceCode = removeWhitespace(normCode);
    String noSpaceFind = removeWhitespace(normFind);
    count = countOccurrences(noSpaceCode, noSpaceFind);
    if (count == 1) return spliceInto(original, normCode, noSpaceCode, noSpaceFind, replace);
    if (count > 1) throw ambiguousException(fr.fileName(), count, find, "whitespace removal");

    throw new PatchException(
            "@@FIND block not found in " + fr.fileName()
            + " (tried exact, line-ending, trailing-whitespace, tab, blank-line, indent-stripped,"
            + " whitespace-collapsed, and whitespace-removed matching).\n\n"
            + "Searched for:\n" + find,
            fr.fileName());
}

private PatchException ambiguousException(String fileName, int count, String find, String stage) {
        return new PatchException(
                "@@FIND block matches " + count + " locations in " + fileName
                + " at stage: " + stage + " — must match exactly once.\n\nSearched for:\n" + find,
                fileName);
    }

    private int countOccurrences(String text, String find) {
        if (find.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(find, idx)) != -1) {
            count++;
            idx += find.length();
        }
        return count;
    }

    private String normalize(String code) {
        return code.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String trimLines(String code) {
        String[] parts = code.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append(parts[i].stripTrailing());
            if (i < parts.length - 1) sb.append("\n");
        }
        if (code.endsWith("\n")) sb.append("\n");
        return sb.toString();
    }

    private String collapseBlankLines(String code) {
        return code.replaceAll("(\n\\s*){2,}\n", "\n\n");
    }

    private String normalizeTabs(String code) {
        String[] parts = code.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append(parts[i].replace("\t", "    "));
            if (i < parts.length - 1) sb.append("\n");
        }
        if (code.endsWith("\n")) sb.append("\n");
        return sb.toString();
    }

    private String stripIndent(String code) {
        String[] parts = code.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append(parts[i].stripLeading().stripTrailing());
            if (i < parts.length - 1) sb.append("\n");
        }
        if (code.endsWith("\n")) sb.append("\n");
        return sb.toString();
    }

/**
 * Steps 2 only: originalCode == normalizedCode, direct splice.
 */
private String spliceInto(String code, String find, String replacement) {
    int idx = code.indexOf(find);
    if (idx < 0) return code;
    return code.substring(0, idx) + replacement + code.substring(idx + find.length());
}

/**
 * Steps 2-5: transformedCode is structurally close to originalCode (only
 * line endings / trailing spaces / tabs altered). Map match offsets from
 * transformedCode directly back to originalCode via mapOffset.
 */
private String spliceInto(String originalCode, String normalizedCode,
                           String transformedFind, String replacement) {
    int transStart = normalizedCode.indexOf(transformedFind);
    if (transStart < 0) return originalCode;

    int origStart = mapOffset(originalCode, normalizedCode, transStart);
    int origEnd   = mapOffset(originalCode, normalizedCode, transStart + transformedFind.length());

    if (origStart < 0 || origEnd < 0 || origStart > origEnd) {
        return normalizedCode.substring(0, transStart) + replacement
                + normalizedCode.substring(transStart + transformedFind.length());
    }

    return originalCode.substring(0, origStart) + replacement + originalCode.substring(origEnd);
}

/**
 * Steps 6-8: transformedCode has severe whitespace destruction (indent strip,
 * collapse, remove). We cannot map directly to originalCode. Instead:
 * 1. Find match in transformedCode.
 * 2. Map those offsets back to normCode (line-ending normalized only, still
 *    structurally intact relative to original).
 * 3. Map normCode offsets back to originalCode.
 * Falls back to splicing into normCode if either mapping fails.
 */
private String spliceInto(String originalCode, String normCode,
                           String transformedCode, String transformedFind, String replacement) {
    int transStart = transformedCode.indexOf(transformedFind);
    if (transStart < 0) return originalCode;

    int normStart = mapOffset(normCode, transformedCode, transStart);
    int normEnd   = mapOffset(normCode, transformedCode, transStart + transformedFind.length());

    if (normStart < 0 || normEnd < 0 || normStart > normEnd) {
        // Can't map back to norm — last resort: splice into normCode
        int s = Math.max(0, transStart);
        int e = Math.min(normCode.length(), transStart + transformedFind.length());
        return normCode.substring(0, s) + replacement + normCode.substring(e);
    }

    int origStart = mapOffset(originalCode, normCode, normStart);
    int origEnd   = mapOffset(originalCode, normCode, normEnd);

    if (origStart < 0 || origEnd < 0 || origStart > origEnd) {
        // Can map to norm but not to original — splice into normCode
        return normCode.substring(0, normStart) + replacement + normCode.substring(normEnd);
    }

    return originalCode.substring(0, origStart) + replacement + originalCode.substring(origEnd);
}

/**
 * Maps a character offset in a normalised string back to the corresponding
 * offset in the original string by walking both in parallel.
 * Returns -1 if the strings diverge unexpectedly.
 */
private int mapOffset(String original, String normalized, int normOffset) {
    int o = 0, n = 0;
    while (n < normOffset && o < original.length()) {
        char oc = original.charAt(o);
        if (n < normalized.length() && normalized.charAt(n) == oc) {
            o++; n++;
        } else {
            // original has a character that was removed/collapsed in normalization — skip it
            o++;
        }
    }
    return (n == normOffset) ? o : -1;
}

private String collapseWhitespace(String code) {
    return code.replaceAll("\\s+", " ");
}

private String removeWhitespace(String code) {
    return code.replaceAll("\\s+", "");
}

private String applyMethodReplace(PatchChange.MethodReplace mr, String code)
            throws PatchException {
        List<int[]> matches = findMethodExtents(code, mr.methodName(), mr.paramTypes());

        if (matches.isEmpty()) {
            throw new PatchException(
                    "Method '" + mr.methodName() + "' not found in " + mr.fileName(),
                    mr.fileName());
        }
        if (matches.size() > 1) {
            if (mr.paramTypes() == null) {
                String overloads = buildOverloadList(code, mr.methodName());
                throw new PatchException(
                        "Method '" + mr.methodName() + "' is overloaded (" + matches.size()
                        + " matches) in " + mr.fileName() + ".\n"
                        + "Specify parameter types to target one overload, e.g.:\n"
                        + overloads,
                        mr.fileName());
            }
            throw new PatchException(
                    "Method '" + mr.methodName() + "' still ambiguous after param-type filtering ("
                    + matches.size() + " matches) in " + mr.fileName()
                    + " — use @@FIND/@@REPLACE to target the specific overload.",
                    mr.fileName());
        }

        int[] extent = matches.get(0);
        String before = code.substring(0, extent[0]).stripTrailing();
        String replacement = mr.replace().strip();
        String after = code.substring(extent[1]).stripLeading();

        return before + "\n\n" + replacement + "\n\n" + after;
    }

/**
     * Builds a human-readable list of all overloads found for methodName,
     * formatted as @@METHOD: name(Type, Type) hints for the AI to use.
     */
    private String buildOverloadList(String code, String methodName) {
        String[] lines = code.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!isFuzzyMethodLine(trimmed, methodName, false)) continue;
            int open  = trimmed.indexOf(methodName + "(");
            if (open < 0) continue;
            int parenOpen  = open + methodName.length();
            int parenClose = trimmed.indexOf(')', parenOpen);
            if (parenClose < 0) continue;
            String paramSection = trimmed.substring(parenOpen + 1, parenClose).trim();
            List<String> types = parseTypeList(paramSection);
            sb.append("  @@METHOD: ").append(methodName)
              .append("(").append(String.join(", ", types)).append(")\n");
        }
        return sb.isEmpty() ? "  (could not parse overload signatures)\n" : sb.toString();
    }

private String applyInsertMethod(PatchChange.InsertMethod im, String code)
            throws PatchException {

        int insertAfterPos;

        if (im.afterMethod() != null) {
            List<int[]> matches = findMethodExtents(code, im.afterMethod(), null);
            if (matches.isEmpty()) {
                throw new PatchException(
                        "@@AFTER_METHOD: anchor '" + im.afterMethod() + "' not found in " + im.fileName(),
                        im.fileName());
            }
            if (matches.size() > 1) {
                throw new PatchException(
                        "@@AFTER_METHOD: anchor '" + im.afterMethod() + "' is ambiguous ("
                        + matches.size() + " matches) in " + im.fileName()
                        + " — use the explicit overload name to target one.",
                        im.fileName());
            }
            insertAfterPos = matches.get(0)[1];
        } else {
            insertAfterPos = findLastClassBrace(code);
            if (insertAfterPos < 0) {
                throw new PatchException(
                        "Could not locate the closing brace of the class in " + im.fileName(),
                        im.fileName());
            }
        }

        String before = code.substring(0, insertAfterPos).stripTrailing();
        String newMethod = im.code().strip();
        String after = code.substring(insertAfterPos).stripLeading();

        return before + "\n\n" + newMethod + "\n\n" + after;
    }

/**
     * Finds the position of the last top-level closing brace in the source,
     * which is the class closing brace. Returns the index of that '}'.
     */
    private int findLastClassBrace(String code) {
        // Walk backwards from end, skip whitespace/blank lines
        for (int i = code.length() - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == '}') {
                return i;
            }
            if (!Character.isWhitespace(c)) {
                break;
            }
        }
        return -1;
    }

    private List<int[]> findMethodExtents(String code, String methodName, String paramTypes) {
        String[] lines = code.split("\n", -1);

        int[] lineStart = new int[lines.length + 1];
        lineStart[0] = 0;
        for (int i = 0; i < lines.length; i++) {
            lineStart[i + 1] = lineStart[i] + lines[i].length() + 1;
        }

        // Step 1: exact signature match
        List<int[]> results = collectMethodExtents(lines, lineStart, code, methodName, false, false);
        if (!results.isEmpty()) {
            return filterByParamTypes(results, lines, lineStart, methodName, paramTypes);
        }

        // Step 2: fuzzy — ignore modifiers/return type, just find methodName( on a line
        results = collectMethodExtents(lines, lineStart, code, methodName, true, false);
        List<int[]> filtered = filterByParamTypes(results, lines, lineStart, methodName, paramTypes);
        if (filtered.size() == 1) {
            return filtered;
        }
        if (filtered.size() > 1) {
            return filtered;
        }

        // Step 3: case-insensitive fuzzy
        results = collectMethodExtents(lines, lineStart, code, methodName, true, true);
        filtered = filterByParamTypes(results, lines, lineStart, methodName, paramTypes);
        if (filtered.size() == 1) {
            return filtered;
        }

        return filtered;
    }

    /**
     * If paramTypes is null, returns matches unchanged.
     * If paramTypes is non-null (including empty string for zero-arg),
     * filters to only those methods whose parameter list matches.
     * Matching is type-name only (no package, no generics), comma-separated,
     * case-insensitive, whitespace-tolerant.
     */
    private List<int[]> filterByParamTypes(List<int[]> matches, String[] lines,
                                            int[] lineStart, String methodName, String paramTypes) {
        if (paramTypes == null || matches.size() <= 1) return matches;

        List<String> expectedTypes = parseTypeList(paramTypes);

        List<int[]> filtered = new ArrayList<>();
        for (int[] extent : matches) {
            // Find the signature line(s) for this extent
            int startOffset = extent[0];
            String sigLine = findSignatureLineForOffset(lines, lineStart, startOffset, methodName);
            if (sigLine == null) {
                filtered.add(extent); // can't parse, keep it
                continue;
            }
            List<String> actualTypes = extractParamTypesFromSignature(sigLine, methodName);
            if (actualTypes == null) {
                filtered.add(extent);
                continue;
            }
            if (paramTypesMatch(expectedTypes, actualTypes)) {
                filtered.add(extent);
            }
        }
        return filtered.isEmpty() ? matches : filtered;
    }

    private String findSignatureLineForOffset(String[] lines, int[] lineStart, int startOffset, String methodName) {
        // Find which line this extent starts on, then look for the method name within a few lines
        int sigLineIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lineStart[i] == startOffset) {
                sigLineIdx = i;
                break;
            }
        }
        if (sigLineIdx < 0) return null;
        // The signature may span multiple lines (params on next lines), collect up to opening brace
        StringBuilder sb = new StringBuilder();
        for (int i = sigLineIdx; i < Math.min(sigLineIdx + 10, lines.length); i++) {
            sb.append(lines[i]).append(" ");
            if (lines[i].contains("{")) break;
        }
        return sb.toString();
    }

    private List<String> extractParamTypesFromSignature(String sigLine, String methodName) {
        // Find methodName( ... ) and extract the param list
        String search = methodName + "(";
        int idx = sigLine.indexOf(search);
        if (idx < 0) {
            // case-insensitive fallback
            idx = sigLine.toLowerCase().indexOf(search.toLowerCase());
        }
        if (idx < 0) return null;
        int open = idx + search.length() - 1;
        // find matching close paren
        int depth = 1;
        int i = open + 1;
        while (i < sigLine.length() && depth > 0) {
            char c = sigLine.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            i++;
        }
        String paramSection = sigLine.substring(open + 1, i - 1).trim();
        return parseTypeList(paramSection);
    }

    /**
     * Parses a comma-separated list of param declarations or type names.
     * Handles both "String foo, int bar" (declarations) and "String, int" (types only).
     * Strips generics and array notation for simple matching.
     */
    private List<String> parseTypeList(String raw) {
        List<String> types = new ArrayList<>();
        if (raw == null || raw.isBlank()) return types; // zero-arg
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            // Strip generics: List<String> -> List
            int angle = trimmed.indexOf('<');
            if (angle >= 0) trimmed = trimmed.substring(0, angle).trim();
            // Strip array: String[] -> String
            int bracket = trimmed.indexOf('[');
            if (bracket >= 0) trimmed = trimmed.substring(0, bracket).trim();
            // If it looks like "Type varName", keep only the type token
            String[] tokens = trimmed.split("\\s+");
            types.add(tokens[0].toLowerCase());
        }
        return types;
    }

    private boolean paramTypesMatch(List<String> expected, List<String> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            String exp = expected.get(i);
            String act = actual.get(i);
            // Strip simple package prefix for comparison: com.foo.Bar -> bar
            exp = exp.substring(exp.lastIndexOf('.') + 1);
            act = act.substring(act.lastIndexOf('.') + 1);
            if (!exp.equalsIgnoreCase(act)) return false;
        }
        return true;
    }

    private List<int[]> collectMethodExtents(String[] lines, int[] lineStart, String code,
            String methodName, boolean fuzzy, boolean ignoreCase) {
        List<int[]> results = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            boolean matches = fuzzy
                    ? isFuzzyMethodLine(trimmed, methodName, ignoreCase)
                    : isMethodSignatureLine(trimmed, methodName);
            if (!matches) {
                continue;
            }

            int braceStart = findOpeningBrace(lines, i, lineStart);
            if (braceStart < 0) {
                continue;
            }

            int methodStart = lineStart[i];
            int end = traceToClosingBrace(code, braceStart);
            if (end < 0) {
                continue;
            }

            results.add(new int[]{methodStart, end});
        }
        return results;
    }

    private boolean isFuzzyMethodLine(String trimmed, String methodName, boolean ignoreCase) {
        if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
            return false;
        }
        // Must look like a definition, not a call site.
        // A call site has no declaration keyword and would cause silent corruption.
        boolean hasDeclarationKeyword
                = trimmed.contains("public ") || trimmed.contains("private ")
                || trimmed.contains("protected ") || trimmed.contains("static ")
                || trimmed.contains("void ") || trimmed.contains("@Override");
        if (!hasDeclarationKeyword) {
            return false;
        }
        String haystack = ignoreCase ? trimmed.toLowerCase() : trimmed;
        String needle = ignoreCase ? methodName.toLowerCase() : methodName;
        String token = needle + "(";
        int idx = 0;
        while ((idx = haystack.indexOf(token, idx)) >= 0) {
            char before = idx > 0 ? haystack.charAt(idx - 1) : ' ';
            if (!Character.isLetterOrDigit(before) && before != '_') {
                return true;
            }
            idx += token.length();
        }
        return false;
    }

    private boolean isMethodSignatureLine(String trimmed, String methodName) {
        if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
            return false;
        }

// Must contain at least one declaration keyword to be a method signature
        boolean hasDeclarationKeyword
                = trimmed.contains("public ") || trimmed.contains("private ")
                || trimmed.contains("protected ") || trimmed.contains("static ")
                || trimmed.contains("void ") || trimmed.contains("@Override");
        if (!hasDeclarationKeyword) {
            return false;
        }

        String token = methodName + "(";
        int idx = 0;
        while ((idx = trimmed.indexOf(token, idx)) >= 0) {
            char before = idx > 0 ? trimmed.charAt(idx - 1) : ' ';
            if (!Character.isLetterOrDigit(before) && before != '_') {
                return true;
            }
            idx += token.length();
        }
        return false;
    }

    private int findOpeningBrace(String[] lines, int sigLine, int[] lineStart) {
        for (int i = sigLine; i < Math.min(sigLine + 20, lines.length); i++) {
            int idx = lines[i].indexOf('{');
            if (idx >= 0) {
                return lineStart[i] + idx;
            }
        }
        return -1;
    }

    private int traceToClosingBrace(String code, int openBrace) {
        int balance = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escape = false;

        for (int i = openBrace; i < code.length(); i++) {
            char c = code.charAt(i);
            char next = (i + 1 < code.length()) ? code.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
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
                if (c == '"' && !escape) {
                    inString = false;
                }
                escape = c == '\\' && !escape;
                continue;
            }
            if (inChar) {
                if (c == '\'' && !escape) {
                    inChar = false;
                }
                escape = c == '\\' && !escape;
                continue;
            }

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
                if (balance == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private String resolveFilePath(String fileName) {
// Normalize: accept slash-separated or dot-separated package prefix
// e.g. "wv/codeclip/patch/NobleArmy.java" or "wv.codeclip.patch.NobleArmy.java"
        String bareName = fileName;

// Strip slash-based path prefix
        int lastSlash = fileName.lastIndexOf('/');
        if (lastSlash < 0) {
            lastSlash = fileName.lastIndexOf('\\');
        }
        if (lastSlash >= 0) {
            bareName = fileName.substring(lastSlash + 1);
        } else if (fileName.contains(".") && fileName.endsWith(".java")) {
// Dot-separated: wv.codeclip.patch.NobleArmy.java
// Split on dots, last two tokens are ClassName.java
            String[] parts = fileName.split("\\.");
// last part is "java", second-to-last is the class name
            if (parts.length >= 2) {
                bareName = parts[parts.length - 2] + ".java";
            }
        }

        for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
            if (entry.getValue().getName().equalsIgnoreCase(bareName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private record ResolvedChange(String path, String newCode, String description) {

    }

    public record FailedChange(String fileName, String message) {

    }

    public record PatchResult(
            List<String> successSummary,
            List<FailedChange> failures,
            List<String> appliedFiles,
            Map<String, String> undoSnapshot,
            List<PatchChange> failedChanges) {

        public boolean hasFailures() {
            return !failures.isEmpty();
        }

        public boolean hasSuccesses() {
            return !appliedFiles.isEmpty();
        }

        public String buildErrorReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("PATCH FAILURES\n");
            sb.append("==============\n\n");
            Map<String, List<String>> byFile = new LinkedHashMap<>();
            for (FailedChange fc : failures) {
                byFile.computeIfAbsent(fc.fileName(), k -> new ArrayList<>()).add(fc.message());
            }
            for (Map.Entry<String, List<String>> entry : byFile.entrySet()) {
                sb.append("File: ").append(entry.getKey()).append("\n");
                for (String msg : entry.getValue()) {
                    sb.append("  ✗ ").append(msg).append("\n");
                }
                sb.append("\n");
            }
            if (!appliedFiles.isEmpty()) {
                sb.append("──────────────────────────────\n");
                sb.append("Successfully applied to:\n");
                for (String name : appliedFiles) {
                    sb.append("  ✓ ").append(name).append("\n");
                }
            }
            return sb.toString();
        }

        public Map<String, String> errorsByFile() {
            Map<String, List<String>> byFile = new LinkedHashMap<>();
            for (FailedChange fc : failures) {
                byFile.computeIfAbsent(fc.fileName(), k -> new ArrayList<>()).add(fc.message());
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : byFile.entrySet()) {
                StringBuilder sb = new StringBuilder();
                for (String msg : entry.getValue()) {
                    sb.append(msg).append("\n");
                }
                result.put(entry.getKey(), sb.toString().stripTrailing());
            }
            return result;
        }
    }
}
