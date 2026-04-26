package wv.codeclip.patch;

import wv.codeclip.model.PatchChange;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans arbitrary text (e.g. a full AI message) for one or more
 * @@PATCH...@@END blocks and merges all their changes into a single list.
 */
public class MultiPatchExtractor {

    /**
     * Extracts all @@PATCH blocks from the given text and merges their changes.
     * Returns an empty list if no valid blocks are found.
     * Throws IllegalArgumentException if any block is malformed.
     */
    public List<PatchChange> extractAll(String text) {
        List<String> blocks = splitIntoBlocks(text);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("No @@PATCH blocks found in text.");
        }

        PatchParser parser = new PatchParser();
        List<PatchChange> all = new ArrayList<>();
        int blockIndex = 1;

        for (String block : blocks) {
            try {
                all.addAll(parser.parse(block));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Error in patch block #" + blockIndex + ":\n" + e.getMessage());
            }
            blockIndex++;
        }

        return all;
    }

    public int countBlocks(String text) {
        return splitIntoBlocks(text).size();
    }

    private List<String> splitIntoBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        String patch = PatchParser.PATCH_MARKER();
        String end   = PatchParser.END_MARKER();

        int searchFrom = 0;
        while (true) {
            int patchIdx = text.indexOf(patch, searchFrom);
            if (patchIdx < 0) break;

            int endIdx = text.indexOf(end, patchIdx);
            if (endIdx < 0) break;

            int blockEnd = endIdx + end.length();
            blocks.add(text.substring(patchIdx, blockEnd));
            searchFrom = blockEnd;
        }

        return blocks;
    }
}