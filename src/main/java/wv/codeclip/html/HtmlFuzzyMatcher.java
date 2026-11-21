package wv.codeclip.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Approximate ("fuzzy") text matching used by HTML/Generic mode's surgical
 * patching when an @@FIND block has no exact match in the target file. Slides
 * windows of nearby line counts across the file and scores each window against
 * the target text using normalized Levenshtein similarity.
 *
 * Whitespace is ignored when comparing: leading/trailing whitespace of every
 * line is trimmed, inner whitespace runs are collapsed, and blank lines are
 * dropped. A block that differs from the file only in indentation, trailing
 * spaces, CRLF vs LF or blank lines therefore scores 100%. Candidate offsets
 * and text still refer to the ORIGINAL file content.
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

        // lineStart[i] = offset of line i in the ORIGINAL code; normLines[i] = whitespace-normalized line i.
        int[] lineStart = new int[codeLines.length + 1];
        String[] normLines = new String[codeLines.length];
        lineStart[0] = 0;
        for (int i = 0; i < codeLines.length; i++) {
            lineStart[i + 1] = lineStart[i] + codeLines[i].length() + 1;
            normLines[i] = normalizeLine(codeLines[i]);
        }

        String normFind = normalizeText(find);
        int findLen = normFind.length();

        int totalWindows = estimateWindowCount(codeLines.length, findLineCount);
        int scanned = 0;
        double bestSoFar = 0.0;

        List<Candidate> results = new ArrayList<>();
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
                String normWindow = joinNormalized(normLines, start, end);

                int maxLen = Math.max(normWindow.length(), findLen);
                scanned++;
                if (maxLen == 0) continue;
                int lenDiff = Math.abs(normWindow.length() - findLen);
                if (lenDiff > maxLen * allowedFraction) continue;

                double sim = similarityNormalized(normWindow, normFind);
                if (sim > bestSoFar) bestSoFar = sim;
                if (sim >= minPercent) {
                    int startOffset = lineStart[start];
                    int endOffset = lineStart[end] - 1; // exclude the newline after the last window line
                    results.add(new Candidate(startOffset, endOffset,
                            code.substring(startOffset, endOffset), sim));
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

    /** Whitespace-insensitive similarity of two texts, 0..100. */
    public static double similarityPercent(String a, String b) {
        if (a.equals(b)) return 100.0;
        return similarityNormalized(normalizeText(a), normalizeText(b));
    }

    private static double similarityNormalized(String an, String bn) {
        if (an.equals(bn)) return 100.0;
        int maxLen = Math.max(an.length(), bn.length());
        if (maxLen == 0) return 100.0;
        int dist = levenshtein(an, bn);
        return Math.max(0.0, (1.0 - ((double) dist / maxLen)) * 100.0);
    }

    /** Trims every line, collapses inner whitespace runs, drops blank lines. */
    private static String normalizeText(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder(s.length());
        for (String line : lines) {
            String n = normalizeLine(line);
            if (n.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(n);
        }
        return sb.toString();
    }

    private static String normalizeLine(String line) {
        String t = line.strip(); // also removes a trailing '\r'
        if (t.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(t.length());
        boolean inWhitespace = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!inWhitespace) sb.append(' ');
                inWhitespace = true;
            } else {
                sb.append(c);
                inWhitespace = false;
            }
        }
        return sb.toString();
    }

    private static String joinNormalized(String[] normLines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            String n = normLines[i];
            if (n.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(n);
        }
        return sb.toString();
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
}