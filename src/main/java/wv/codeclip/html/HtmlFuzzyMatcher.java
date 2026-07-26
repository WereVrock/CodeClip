package wv.codeclip.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Approximate ("fuzzy") text matching used by HTML mode's surgical patching
 * when an @@FIND block has no exact match in the target file. Slides windows
 * of nearby line counts across the file and scores each window against the
 * target text using normalized Levenshtein similarity.
 *
 * Pure matching logic only — no UI, no logging, no file I/O. Progress
 * reporting and cancellation are supported via ProgressListener/CancelToken
 * so a caller can drive a live progress dialog on large files without this
 * class knowing anything about Swing.
 */
public final class HtmlFuzzyMatcher {

    private HtmlFuzzyMatcher() {}

    public record Candidate(int startOffset, int endOffset, String text, double similarityPercent) {}

    /** Result of a (possibly cancelled) progress-reporting scan. */
    public record ScanResult(List<Candidate> candidates, boolean cancelled) {}

    /** Reports scan progress. Called from whatever thread the scan runs on — implementations must marshal to the EDT themselves if updating UI. */
    public interface ProgressListener {
        void onProgress(int windowsScanned, int totalWindows, double bestSoFar);
    }

    /** Lets a long-running scan be aborted early. Checked periodically, not after every single window. */
    public interface CancelToken {
        boolean isCancelled();
    }

    private static final ProgressListener NO_OP_LISTENER = (scanned, total, best) -> {};
    private static final CancelToken NEVER_CANCEL = () -> false;

    private static final int WINDOW_SLACK_LINES = 2;
    private static final int PROGRESS_REPORT_INTERVAL = 100;

    public static List<Candidate> findCandidates(String code, String find, double minPercent) {
        return findCandidatesWithProgress(code, find, minPercent, NO_OP_LISTENER, NEVER_CANCEL).candidates();
    }

    public static ScanResult findCandidatesWithProgress(String code, String find, double minPercent,
                                                          ProgressListener listener, CancelToken cancelToken) {
        String[] codeLines = code.split("\n", -1);
        int findLineCount = countLines(find);

        int minWindow = Math.max(1, findLineCount - WINDOW_SLACK_LINES);
        int maxWindow = Math.min(codeLines.length, findLineCount + WINDOW_SLACK_LINES);

        int[] lineStart = new int[codeLines.length + 1];
        lineStart[0] = 0;
        for (int i = 0; i < codeLines.length; i++) {
            lineStart[i + 1] = lineStart[i] + codeLines[i].length() + 1;
        }

        int totalWindows = estimateWindowCount(codeLines.length, findLineCount);
        int scanned = 0;
        double bestSoFar = 0.0;

        List<Candidate> results = new ArrayList<>();
        int findLen = find.length();
        double allowedFraction = 1.0 - (minPercent / 100.0);

        for (int windowSize = minWindow; windowSize <= maxWindow; windowSize++) {
            for (int start = 0; start + windowSize <= codeLines.length; start++) {
                if (scanned % PROGRESS_REPORT_INTERVAL == 0) {
                    if (cancelToken.isCancelled()) {
                        return new ScanResult(results, true);
                    }
                    listener.onProgress(scanned, totalWindows, bestSoFar);
                }

                int end = start + windowSize;
                String windowText = joinLines(codeLines, start, end);

                int maxLen = Math.max(windowText.length(), findLen);
                scanned++;
                if (maxLen == 0) continue;
                int lenDiff = Math.abs(windowText.length() - findLen);
                if (lenDiff > maxLen * allowedFraction) continue;

                double sim = similarityPercent(windowText, find);
                if (sim > bestSoFar) bestSoFar = sim;
                if (sim >= minPercent) {
                    int startOffset = lineStart[start];
                    int endOffset = startOffset + windowText.length();
                    results.add(new Candidate(startOffset, endOffset, windowText, sim));
                }
            }
        }

        listener.onProgress(totalWindows, totalWindows, bestSoFar);
        return new ScanResult(results, false);
    }

    public static int estimateWindowCount(String code, String find) {
        return estimateWindowCount(countLines(code), countLines(find));
    }

    private static int estimateWindowCount(int codeLineCount, int findLineCount) {
        int minWindow = Math.max(1, findLineCount - WINDOW_SLACK_LINES);
        int maxWindow = Math.min(codeLineCount, findLineCount + WINDOW_SLACK_LINES);
        int total = 0;
        for (int windowSize = minWindow; windowSize <= maxWindow; windowSize++) {
            int windowsForSize = codeLineCount - windowSize + 1;
            if (windowsForSize > 0) total += windowsForSize;
        }
        return total;
    }

    public static Candidate best(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (best == null || c.similarityPercent() > best.similarityPercent()) best = c;
        }
        return best;
    }

    public static Candidate secondBest(List<Candidate> candidates, Candidate exclude) {
        Candidate second = null;
        for (Candidate c : candidates) {
            if (overlaps(c, exclude)) continue;
            if (second == null || c.similarityPercent() > second.similarityPercent()) second = c;
        }
        return second;
    }

    private static boolean overlaps(Candidate a, Candidate b) {
        return a.startOffset() < b.endOffset() && b.startOffset() < a.endOffset();
    }

public static double similarityPercent(String a, String b) {
    if (a.equals(b)) return 100.0;
    String an = collapseWhitespace(a);
    String bn = collapseWhitespace(b);
    if (an.equals(bn)) return 100.0;
    int dist = levenshtein(an, bn);
    int maxLen = Math.max(an.length(), bn.length());
    if (maxLen == 0) return 100.0;
    return Math.max(0.0, (1.0 - ((double) dist / maxLen)) * 100.0);
}

private static String collapseWhitespace(String s) {
    return s.replaceAll("[ \\t]+", " ");
}

public static String formatPercent(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    private static int countLines(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    private static String joinLines(String[] lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines[i]);
            if (i < end - 1) sb.append('\n');
        }
        return sb.toString();
    }
}