package wv.codeclip.ui;

import wv.codeclip.patch.PatchParser;
import java.util.ArrayList;
import java.util.List;

/**
* Scans arbitrary text for @@PATCH blocks and //@CLASS blocks in document order.
*/
public class SmartPasteExtractor {

private static final String FENCE_START_LOWER = "```java";
private static final String FENCE_START_UPPER = "```Java";
private static final String FENCE_END         = "```";

public sealed interface Entry permits PatchEntry, ClassEntry {}
public record PatchEntry(String text) implements Entry {}
public record ClassEntry(String text) implements Entry {}

private final String text;

public SmartPasteExtractor(String text) {
this.text = text;
}

public static boolean containsClassBlock(String text) {
return text != null && (text.contains(FENCE_START_LOWER) || text.contains(FENCE_START_UPPER));
}

private boolean isInsideAnyBlock(int idx, List<int[]> blocks) {
for (int[] block : blocks) {
if (idx >= block[0] && idx < block[1]) return true;
}
return false;
}

public List<Entry> extract(boolean includeClasses) {
    List<Entry> entries = new ArrayList<>();
    List<int[]> patchPositions = new ArrayList<>();
    List<int[]> classPositions = new ArrayList<>();

    String text = this.text.replaceAll("(?m)^```@@", "@@");
    text = text.replaceAll("(?s)", "");
    text = text.replaceAll("(?m)^!@@", "//escaped@@");

    String patchMarker = PatchParser.PATCH_MARKER();
    String endMarker   = PatchParser.END_MARKER();

    int searchFrom = 0;
    while (true) {
        int patchIdx = text.indexOf(patchMarker, searchFrom);
        int loosePatchIdx = findLoosePatchBlock(text, searchFrom, patchMarker);
        if (patchIdx < 0 && loosePatchIdx < 0) break;
        boolean useLoose = (patchIdx < 0) || (loosePatchIdx >= 0 && loosePatchIdx < patchIdx);

        if (useLoose) {
            int endIdx = text.indexOf(endMarker, loosePatchIdx);
            if (endIdx < 0) break;
            int blockEnd = endIdx + endMarker.length();
            patchPositions.add(new int[]{loosePatchIdx, blockEnd});
            searchFrom = blockEnd;
        } else {
            int lineStart = text.lastIndexOf('\n', patchIdx);
            String before = text.substring(lineStart + 1, patchIdx);
            if (!before.isBlank()) { searchFrom = patchIdx + 1; continue; }
            int endIdx = text.indexOf(endMarker, patchIdx);
            if (endIdx < 0) break;
            int endLineStart = text.lastIndexOf('\n', endIdx);
            String endBefore = text.substring(endLineStart + 1, endIdx);
            if (!endBefore.isBlank()) { searchFrom = patchIdx + 1; continue; }
            int blockEnd = endIdx + endMarker.length();
            patchPositions.add(new int[]{patchIdx, blockEnd});
            searchFrom = blockEnd;
        }
    }

    if (includeClasses) {
        searchFrom = 0;
        while (true) {
            int fenceIdx = -1;
            int lowerIdx = text.indexOf(FENCE_START_LOWER, searchFrom);
            int upperIdx = text.indexOf(FENCE_START_UPPER, searchFrom);
            if (lowerIdx < 0 && upperIdx < 0) break;
            if (lowerIdx < 0) fenceIdx = upperIdx;
            else if (upperIdx < 0) fenceIdx = lowerIdx;
            else fenceIdx = Math.min(lowerIdx, upperIdx);

            int lineEnd = text.indexOf('\n', fenceIdx);
            if (lineEnd < 0) break;
            int closeIdx = text.indexOf(FENCE_END, lineEnd + 1);
            if (closeIdx < 0) break;
            int blockEnd = closeIdx + FENCE_END.length();

            if (!isInsideAnyBlock(fenceIdx, patchPositions)) {
                int lineEndIdx = text.indexOf('\n', fenceIdx);
                String inner = lineEndIdx >= 0 ? text.substring(lineEndIdx + 1, closeIdx) : "";
                if (!inner.contains(patchMarker)) {
                    classPositions.add(new int[]{fenceIdx, blockEnd});
                }
            }
            searchFrom = blockEnd;
        }
    }

    List<int[]> all = new ArrayList<>();
    for (int[] p : patchPositions) all.add(new int[]{p[0], p[1], 0});
    for (int[] c : classPositions) all.add(new int[]{c[0], c[1], 1});
    all.sort((a, b) -> Integer.compare(a[0], b[0]));

    for (int[] pos : all) {
        String block = text.substring(pos[0], pos[1]);
        if (pos[2] == 0) {
            entries.add(new PatchEntry(block));
        } else {
            int lineEnd = block.indexOf('\n');
            String inner = lineEnd >= 0 ? block.substring(lineEnd + 1) : block;
            if (inner.endsWith(FENCE_END)) {
                inner = inner.substring(0, inner.length() - FENCE_END.length());
            }
            inner = inner.strip();
            if (!inner.isEmpty()) {
                for (String singleClass : splitTopLevelTypes(inner)) {
                    entries.add(new ClassEntry(singleClass));
                }
            }
        }
    }

    return entries;
}

private List<String> splitTopLevelTypes(String code) {
    List<int[]> typeStarts = findTopLevelTypeStarts(code);
    if (typeStarts.size() <= 1) {
        return List.of(code);
    }
    int firstTypeLineStart = typeStarts.get(0)[0];
    String header = code.substring(0, firstTypeLineStart).strip();
    List<String> results = new ArrayList<>();
    for (int i = 0; i < typeStarts.size(); i++) {
        int blockStart = typeStarts.get(i)[0];
        int braceOpen = typeStarts.get(i)[1];
        int blockEnd = findMatchingBrace(code, braceOpen);
        if (blockEnd < 0) {
            return List.of(code);
        }
        String body = code.substring(blockStart, blockEnd + 1).strip();
        String combined = header.isEmpty() ? body : header + "\n\n" + body;
        results.add(combined);
    }
    return results;
}

private int findLoosePatchBlock(String text, int searchFrom, String patchMarker) {
    String[] lines = text.substring(searchFrom).split("\n", -1);
    int offset = searchFrom;
    for (int i = 0; i < lines.length; i++) {
        String trimmed = lines[i].trim();
        if (trimmed.equals(patchMarker)) {
            offset += lines[i].length() + 1;
            continue;
        }
        if (trimmed.startsWith("@@TITLE:") || trimmed.startsWith("@@DESC:") || trimmed.startsWith("@@FILE:")) {
            int remaining = text.indexOf("@@END", offset);
            if (remaining >= 0) {
                int nextPatch = text.indexOf(patchMarker, offset);
                if (nextPatch < 0 || nextPatch > remaining) {
                    return offset;
                }
            }
        }
        offset += lines[i].length() + 1;
    }
    return -1;
}

private List<int[]> findTopLevelTypeStarts(String code) {
    java.util.regex.Pattern typePattern = java.util.regex.Pattern.compile(
        "(?m)^[ \\t]*(?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp|\\s)*"
        + "(?:class|interface|enum|record)\\s+[A-Za-z_][A-Za-z0-9_]*"
    );
    java.util.regex.Matcher m = typePattern.matcher(code);
    List<int[]> results = new ArrayList<>();
    int searchFrom = 0;
    while (m.find(searchFrom)) {
        int matchStart = m.start();
        if (countBraceDepthUpTo(code, matchStart) == 0) {
            int lineStart = code.lastIndexOf('\n', matchStart);
            lineStart = (lineStart < 0) ? 0 : lineStart + 1;
            int braceOpen = code.indexOf('{', m.end());
            if (braceOpen >= 0) {
                results.add(new int[]{lineStart, braceOpen});
            }
        }
        searchFrom = m.end();
    }
    return results;
}

private int countBraceDepthUpTo(String code, int upTo) {
    int depth = 0;
    boolean inLineComment = false, inBlockComment = false, inString = false, inChar = false, escape = false;
    for (int i = 0; i < upTo && i < code.length(); i++) {
        char c = code.charAt(i);
        char next = (i + 1 < code.length()) ? code.charAt(i + 1) : '\0';
        if (inLineComment) { if (c == '\n') inLineComment = false; continue; }
        if (inBlockComment) { if (c == '*' && next == '/') { inBlockComment = false; i++; } continue; }
        if (inString) { if (c == '"' && !escape) inString = false; escape = c == '\\' && !escape; continue; }
        if (inChar) { if (c == '\'' && !escape) inChar = false; escape = c == '\\' && !escape; continue; }
        if (c == '/' && next == '/') { inLineComment = true; i++; }
        else if (c == '/' && next == '*') { inBlockComment = true; i++; }
        else if (c == '"') { inString = true; escape = false; }
        else if (c == '\'') { inChar = true; escape = false; }
        else if (c == '{') depth++;
        else if (c == '}') depth--;
    }
    return depth;
}

private int findMatchingBrace(String code, int openBraceIdx) {
    int depth = 0;
    boolean inLineComment = false, inBlockComment = false, inString = false, inChar = false, escape = false;
    for (int i = openBraceIdx; i < code.length(); i++) {
        char c = code.charAt(i);
        char next = (i + 1 < code.length()) ? code.charAt(i + 1) : '\0';
        if (inLineComment) { if (c == '\n') inLineComment = false; continue; }
        if (inBlockComment) { if (c == '*' && next == '/') { inBlockComment = false; i++; } continue; }
        if (inString) { if (c == '"' && !escape) inString = false; escape = c == '\\' && !escape; continue; }
        if (inChar) { if (c == '\'' && !escape) inChar = false; escape = c == '\\' && !escape; continue; }
        if (c == '/' && next == '/') { inLineComment = true; i++; }
        else if (c == '/' && next == '*') { inBlockComment = true; i++; }
        else if (c == '"') { inString = true; escape = false; }
        else if (c == '\'') { inChar = true; escape = false; }
        else if (c == '{') depth++;
        else if (c == '}') { depth--; if (depth == 0) return i; }
    }
    return -1;
}

}

