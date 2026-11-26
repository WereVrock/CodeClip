package wv.codeclip.patch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reverts one recorded patch against the CURRENT file contents, even when
 * later patches changed the same files. Works on line hunks: the diff between
 * the patch's before/after is computed, and every hunk is located in the
 * current text (using a few lines of context) and swapped back. If any hunk
 * can't be located uniquely, that file is reported as a conflict and nothing
 * is produced for it.
 */
public final class PatchReverter {

    private static final long MAX_LCS_CELLS = 4_000_000L;
    private static final int MAX_CONTEXT = 3;

    private PatchReverter() {
    }

    /**
     * newContents: path -> reverted content (only files that can be reverted).
     * conflicts: paths that cannot be reverted cleanly.
     * alreadyReverted: paths whose current content already equals the state before the patch.
     */
    public record Result(Map<String, String> newContents, List<String> conflicts,
                         List<String> alreadyReverted) {
    }

    public static Result revert(PatchRecord rec, Map<String, String> current) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        List<String> already = new ArrayList<>();

        for (String path : rec.after().keySet()) {
            String before = rec.before().get(path);
            String after = rec.after().get(path);
            String now = current.get(path);

            if (now == null || before == null || after == null) {
                conflicts.add(path);
            } else if (now.equals(before)) {
                already.add(path);
            } else if (now.equals(after)) {
                out.put(path, before);
            } else {
                String merged = revertHunks(before, after, now);
                if (merged == null) {
                    conflicts.add(path);
                } else {
                    out.put(path, merged);
                }
            }
        }
        return new Result(out, conflicts, already);
    }

    private static String revertHunks(String before, String after, String now) {
        List<String> b = lines(before);
        List<String> a = lines(after);
        List<String> n = lines(now);

        List<int[]> hunks = diffHunks(b, a);
        if (hunks.isEmpty()) {
            return null;
        }

        // Locate every hunk in the untouched current text first, then apply bottom-up.
        int[] starts = new int[hunks.size()];
        int prevEnd = 0;
        for (int h = 0; h < hunks.size(); h++) {
            int[] hk = hunks.get(h);
            int pos = locate(a, n, hk[2], hk[3]);
            if (pos < 0 || pos < prevEnd) {
                return null;
            }
            starts[h] = pos;
            prevEnd = pos + (hk[3] - hk[2]);
        }
        for (int h = hunks.size() - 1; h >= 0; h--) {
            int[] hk = hunks.get(h);
            int pos = starts[h];
            n.subList(pos, pos + (hk[3] - hk[2])).clear();
            n.addAll(pos, new ArrayList<>(b.subList(hk[0], hk[1])));
        }
        return String.join("\n", n);
    }

    /**
     * Finds where the "after" lines [aStart, aEnd) live in the current lines.
     * Uses up to MAX_CONTEXT surrounding lines to make the spot unique, shrinking
     * the context when later edits changed the neighbours. Returns the index in n
     * where the hunk's core starts, or -1 when missing/ambiguous.
     */
    private static int locate(List<String> a, List<String> n, int aStart, int aEnd) {
        for (int ctx = MAX_CONTEXT; ctx >= 0; ctx--) {
            int from = Math.max(0, aStart - ctx);
            int to = Math.min(a.size(), aEnd + ctx);
            List<String> pattern = a.subList(from, to);
            if (pattern.isEmpty()) {
                continue;
            }
            int found = -1;
            int count = 0;
            for (int i = 0; i + pattern.size() <= n.size(); i++) {
                if (matchesAt(n, i, pattern)) {
                    count++;
                    found = i;
                    if (count > 1) {
                        break;
                    }
                }
            }
            if (count == 1) {
                return found + (aStart - from);
            }
            if (count > 1) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean matchesAt(List<String> n, int at, List<String> pattern) {
        for (int k = 0; k < pattern.size(); k++) {
            if (!n.get(at + k).equals(pattern.get(k))) {
                return false;
            }
        }
        return true;
    }

    /** Hunks as {bStart, bEnd, aStart, aEnd}, half-open line ranges. */
    private static List<int[]> diffHunks(List<String> b, List<String> a) {
        List<int[]> hunks = new ArrayList<>();
        int max = Math.min(b.size(), a.size());
        int pre = 0;
        while (pre < max && b.get(pre).equals(a.get(pre))) {
            pre++;
        }
        int suf = 0;
        while (suf < max - pre && b.get(b.size() - 1 - suf).equals(a.get(a.size() - 1 - suf))) {
            suf++;
        }
        int bEnd = b.size() - suf;
        int aEnd = a.size() - suf;
        if (pre == bEnd && pre == aEnd) {
            return hunks;
        }
        int m = bEnd - pre;
        int k = aEnd - pre;
        if ((long) m * k > MAX_LCS_CELLS) {
            hunks.add(new int[]{pre, bEnd, pre, aEnd});
            return hunks;
        }

        int[][] lcs = new int[m + 1][k + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = k - 1; j >= 0; j--) {
                lcs[i][j] = b.get(pre + i).equals(a.get(pre + j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        int i = 0;
        int j = 0;
        int hb = -1;
        int ha = -1;
        while (i < m || j < k) {
            if (i < m && j < k && b.get(pre + i).equals(a.get(pre + j))) {
                if (hb >= 0) {
                    hunks.add(new int[]{pre + hb, pre + i, pre + ha, pre + j});
                    hb = -1;
                }
                i++;
                j++;
            } else {
                if (hb < 0) {
                    hb = i;
                    ha = j;
                }
                if (j < k && (i >= m || lcs[i][j + 1] >= lcs[i + 1][j])) {
                    j++;
                } else {
                    i++;
                }
            }
        }
        if (hb >= 0) {
            hunks.add(new int[]{pre + hb, pre + m, pre + ha, pre + k});
        }
        return hunks;
    }

    private static List<String> lines(String s) {
        return new ArrayList<>(Arrays.asList(s.split("\n", -1)));
    }
}