package wv.codeclip.protocol.ui;

import java.util.*;

/**
 * Minimal line-based diff (LCS-based) for rendering old vs new content in
 * the accept/reject dialog. Not meant to be a general-purpose diff library —
 * just enough to highlight added/removed lines clearly for review.
 */
public final class SimpleDiff {

    private SimpleDiff() {}

    public static List<DiffLine> diff(List<String> oldLines, List<String> newLines) {
        int n = oldLines.size();
        int m = newLines.size();
        int[][] lcs = new int[n + 1][m + 1];

        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (oldLines.get(i).equals(newLines.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<DiffLine> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                result.add(new DiffLine(DiffLine.Type.UNCHANGED, oldLines.get(i)));
                i++; j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(new DiffLine(DiffLine.Type.REMOVED, oldLines.get(i)));
                i++;
            } else {
                result.add(new DiffLine(DiffLine.Type.ADDED, newLines.get(j)));
                j++;
            }
        }
        while (i < n) { result.add(new DiffLine(DiffLine.Type.REMOVED, oldLines.get(i))); i++; }
        while (j < m) { result.add(new DiffLine(DiffLine.Type.ADDED, newLines.get(j))); j++; }

        return result;
    }
}