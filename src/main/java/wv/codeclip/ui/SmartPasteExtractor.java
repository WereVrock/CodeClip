package wv.codeclip.ui;

import wv.codeclip.patch.PatchParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans arbitrary text for @@PATCH blocks and //@CLASS blocks in document order.
 */
public class SmartPasteExtractor {

    private static final String CLASS_START = "//@@CLASS";
    private static final String CLASS_END   = "//@@CLASSEND";

    public sealed interface Entry permits PatchEntry, ClassEntry {}
    public record PatchEntry(String text) implements Entry {}
    public record ClassEntry(String text) implements Entry {}

    private final String text;

    public SmartPasteExtractor(String text) {
        this.text = text;
    }

    public static boolean containsClassBlock(String text) {
        return text != null && text.contains(CLASS_START);
    }

    private boolean isInsideAnyBlock(int idx, List<int[]> blocks) {
        for (int[] block : blocks) {
            if (idx >= block[0] && idx < block[1]) return true;
        }
        return false;
    }

    private String stripCodeFences(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) continue;
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

public List<Entry> extract(boolean includeClasses) {
        List<Entry> entries = new ArrayList<>();
        List<int[]> patchPositions = new ArrayList<>();
        List<int[]> classPositions = new ArrayList<>();

        String patchMarker = PatchParser.PATCH_MARKER();
        String endMarker   = PatchParser.END_MARKER();
        int searchFrom = 0;
        while (true) {
            int patchIdx = text.indexOf(patchMarker, searchFrom);
            if (patchIdx < 0) break;
            int endIdx = text.indexOf(endMarker, patchIdx);
            if (endIdx < 0) break;
            int blockEnd = endIdx + endMarker.length();
            patchPositions.add(new int[]{patchIdx, blockEnd});
            searchFrom = blockEnd;
        }

        if (includeClasses) {
            searchFrom = 0;
            while (true) {
                int classIdx = text.indexOf(CLASS_START, searchFrom);
                if (classIdx < 0) break;
                int classEnd = text.indexOf(CLASS_END, classIdx);
                if (classEnd < 0) break;
                int blockEnd = classEnd + CLASS_END.length();

                if (!isInsideAnyBlock(classIdx, patchPositions)) {
                    classPositions.add(new int[]{classIdx, blockEnd});
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
                String inner = block
                        .substring(CLASS_START.length(), block.length() - CLASS_END.length());
                inner = stripCodeFences(inner).strip();
                entries.add(new ClassEntry(inner));
            }
        }

        return entries;
    }

}