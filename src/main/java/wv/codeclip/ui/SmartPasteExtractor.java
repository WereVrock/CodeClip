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

    // Normalize ```@@PATCH ... ``` into bare @@PATCH blocks before scanning
    String text = this.text.replaceAll("(?m)^```@@", "@@")
                           .replaceAll("(?m)^```\\s*$", "");

    String patchMarker = PatchParser.PATCH_MARKER();
    String endMarker   = PatchParser.END_MARKER();

    // Collect @@PATCH block positions
    int searchFrom = 0;
    while (true) {
        int patchIdx = text.indexOf(patchMarker, searchFrom);
        if (patchIdx < 0) break;
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

    // Collect ```java fenced class block positions
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

            // Find the end of the opening fence line, then the closing ```
            int lineEnd = text.indexOf('\n', fenceIdx);
            if (lineEnd < 0) break;
            int closeIdx = text.indexOf(FENCE_END, lineEnd + 1);
            if (closeIdx < 0) break;
            int blockEnd = closeIdx + FENCE_END.length();

            if (!isInsideAnyBlock(fenceIdx, patchPositions)) {
                // Skip fenced blocks that are actually patch blocks
                int lineEndIdx = text.indexOf('\n', fenceIdx);
                String inner = lineEndIdx >= 0 ? text.substring(lineEndIdx + 1, closeIdx) : "";
                if (!inner.contains(patchMarker)) {
                    classPositions.add(new int[]{fenceIdx, blockEnd});
                }
            }
            searchFrom = blockEnd;
        }
    }

    // Merge in document order
    List<int[]> all = new ArrayList<>();
    for (int[] p : patchPositions) all.add(new int[]{p[0], p[1], 0});
    for (int[] c : classPositions) all.add(new int[]{c[0], c[1], 1});
    all.sort((a, b) -> Integer.compare(a[0], b[0]));

    for (int[] pos : all) {
        String block = text.substring(pos[0], pos[1]);
        if (pos[2] == 0) {
            entries.add(new PatchEntry(block));
        } else {
            // Strip the opening fence line and closing fence
            int lineEnd = block.indexOf('\n');
            String inner = lineEnd >= 0 ? block.substring(lineEnd + 1) : block;
            if (inner.endsWith(FENCE_END)) {
                inner = inner.substring(0, inner.length() - FENCE_END.length());
            }
            inner = inner.strip();
            if (!inner.isEmpty()) {
                entries.add(new ClassEntry(inner));
            }
        }
    }

    return entries;
}

}