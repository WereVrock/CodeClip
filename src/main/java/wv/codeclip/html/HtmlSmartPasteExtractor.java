package wv.codeclip.html;

import java.util.ArrayList;
import java.util.List;

/**
 * HTML mode's own Smart Paste extractor — scans arbitrary clipboard text
 * (which may contain prose/chatter from an AI response) and pulls out, in
 * document order:
 *   - @@PATCH ... @@END blocks
 *   - #@FileStart: <path> ... #@FileEnd blocks (whole-file replacements)
 * Everything else (explanations, headers, code-fence wrappers around these
 * blocks) is discarded. This intentionally does NOT extract bare ```html/css/js
 * fenced code blocks the way Java mode's SmartPasteExtractor extracts ```java
 * blocks — HTML mode requires either an explicit @@PATCH or an explicit
 * #@FileStart:/#@FileEnd marker so the target path is always unambiguous
 * (a bare code fence has no filename to write to).
 */
public final class HtmlSmartPasteExtractor {

    public sealed interface Entry permits PatchEntry, FileEntry {}
    public record PatchEntry(String text) implements Entry {}
    public record FileEntry(String relativePath, String code) implements Entry {}

    private static final String PATCH_MARKER = "@@PATCH";
    private static final String END_MARKER = "@@END";
    private static final String FILE_START_MARKER = "#@FileStart:";
    private static final String FILE_END_MARKER = "#@FileEnd";

    private final String text;

    public HtmlSmartPasteExtractor(String text) {
        this.text = text == null ? "" : text;
    }

    public List<Entry> extract() {
        List<int[]> patchPositions = collectPatchPositions();
        List<Object[]> filePositions = collectFilePositions(patchPositions);

        List<Object[]> all = new ArrayList<>();
        for (int[] p : patchPositions) all.add(new Object[]{p[0], p[1], "PATCH", null});
        for (Object[] f : filePositions) all.add(f);
        all.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));

        List<Entry> entries = new ArrayList<>();
        for (Object[] pos : all) {
            int start = (Integer) pos[0];
            int end = (Integer) pos[1];
            String kind = (String) pos[2];
            if (kind.equals("PATCH")) {
                entries.add(new PatchEntry(text.substring(start, end)));
            } else {
                String relPath = (String) pos[3];
                String inner = extractFileBody(start, end);
                if (!inner.isBlank()) {
                    entries.add(new FileEntry(relPath, inner));
                }
            }
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // @@PATCH ... @@END block positions (loose-start tolerant, same rules
    // as Java mode's SmartPasteExtractor: an explicit @@PATCH line, or a
    // @@TITLE:/@@DESC:/@@FILE: line followed eventually by @@END with no
    // other @@PATCH in between).
    // ------------------------------------------------------------------

    private List<int[]> collectPatchPositions() {
        List<int[]> positions = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int patchIdx = text.indexOf(PATCH_MARKER, searchFrom);
            int looseIdx = findLoosePatchBlock(searchFrom);

            if (patchIdx < 0 && looseIdx < 0) break;
            boolean useLoose = (patchIdx < 0) || (looseIdx >= 0 && looseIdx < patchIdx);

            if (useLoose) {
                int endIdx = text.indexOf(END_MARKER, looseIdx);
                if (endIdx < 0) break;
                int blockEnd = endIdx + END_MARKER.length();
                positions.add(new int[]{looseIdx, blockEnd});
                searchFrom = blockEnd;
            } else {
                int lineStart = text.lastIndexOf('\n', patchIdx);
                String before = text.substring(lineStart + 1, patchIdx);
                if (!before.isBlank()) { searchFrom = patchIdx + 1; continue; }
                int endIdx = text.indexOf(END_MARKER, patchIdx);
                if (endIdx < 0) break;
                int endLineStart = text.lastIndexOf('\n', endIdx);
                String endBefore = text.substring(endLineStart + 1, endIdx);
                if (!endBefore.isBlank()) { searchFrom = patchIdx + 1; continue; }
                int blockEnd = endIdx + END_MARKER.length();
                positions.add(new int[]{patchIdx, blockEnd});
                searchFrom = blockEnd;
            }
        }
        return positions;
    }

    private int findLoosePatchBlock(int searchFrom) {
        String[] lines = text.substring(searchFrom).split("\n", -1);
        int offset = searchFrom;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals(PATCH_MARKER)) {
                offset += line.length() + 1;
                continue;
            }
            if (trimmed.startsWith("@@TITLE:") || trimmed.startsWith("@@DESC:") || trimmed.startsWith("@@FILE:")) {
                int remaining = text.indexOf(END_MARKER, offset);
                if (remaining >= 0) {
                    int nextPatch = text.indexOf(PATCH_MARKER, offset);
                    if (nextPatch < 0 || nextPatch > remaining) {
                        return offset;
                    }
                }
            }
            offset += line.length() + 1;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // #@FileStart: <path> ... #@FileEnd block positions, skipping anything
    // already claimed by a @@PATCH block.
    // ------------------------------------------------------------------

    private List<Object[]> collectFilePositions(List<int[]> patchPositions) {
        List<Object[]> results = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int startIdx = text.indexOf(FILE_START_MARKER, searchFrom);
            if (startIdx < 0) break;

            if (isInsideAny(startIdx, patchPositions)) {
                searchFrom = startIdx + FILE_START_MARKER.length();
                continue;
            }

            int lineEnd = text.indexOf('\n', startIdx);
            if (lineEnd < 0) break;
            String rawPath = text.substring(startIdx + FILE_START_MARKER.length(), lineEnd).trim();
            String relPath = normalizePath(rawPath);

            int endIdx = text.indexOf(FILE_END_MARKER, lineEnd);
            if (endIdx < 0) break;
            int afterEndMarker = endIdx + FILE_END_MARKER.length();

            if (!relPath.isBlank()) {
                results.add(new Object[]{startIdx, afterEndMarker, "FILE", relPath});
            }
            searchFrom = afterEndMarker;
        }
        return results;
    }

    private String extractFileBody(int blockStart, int blockEnd) {
        String block = text.substring(blockStart, blockEnd);
        int lineEnd = block.indexOf('\n');
        if (lineEnd < 0) return "";
        String afterFirstLine = block.substring(lineEnd + 1);
        int endMarkerIdx = afterFirstLine.lastIndexOf(FILE_END_MARKER);
        if (endMarkerIdx < 0) return afterFirstLine.strip();
        return afterFirstLine.substring(0, endMarkerIdx).strip();
    }

    private boolean isInsideAny(int idx, List<int[]> ranges) {
        for (int[] r : ranges) {
            if (idx >= r[0] && idx < r[1]) return true;
        }
        return false;
    }

    private static String normalizePath(String raw) {
        String p = raw.replace('\\', '/').trim();
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }
}