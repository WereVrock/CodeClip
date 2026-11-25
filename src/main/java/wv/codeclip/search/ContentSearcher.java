package wv.codeclip.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure search logic (no Swing): builds a Pattern from user options and finds
 * matches in a text, reporting line numbers and a short highlighted preview.
 */
public final class ContentSearcher {

    public static final int MAX_HITS_PER_FILE = 500;

    private static final int MAX_PREVIEW_CHARS = 220;
    private static final int CONTEXT_BEFORE_MATCH = 60;
    private static final String WORD_BEFORE = "(?<![\\p{L}\\p{N}_])";
    private static final String WORD_AFTER = "(?![\\p{L}\\p{N}_])";

    private ContentSearcher() {}

    public record Options(String query, boolean matchCase, boolean wholeWord,
                          boolean regex, boolean ignoreWhitespace) {}

    /** line/column are 1-based; previewStart/End are offsets inside preview. */
    public record Hit(int line, int column, String preview, int previewStart, int previewEnd) {}

    public record FileResult(String path, String name, List<Hit> hits, boolean truncated) {}

    public static final class SearchCancelledException extends RuntimeException {
        public SearchCancelledException() {
            super("Search cancelled", null, false, false);
        }
    }

    /**
     * Builds the pattern. Throws PatternSyntaxException for a bad regex and
     * IllegalArgumentException for an empty query.
     */
    public static Pattern compile(Options o) {
        String q = o.query();
        if (q == null || q.isEmpty()) {
            throw new IllegalArgumentException("Empty query");
        }
        String body;
        boolean startsWord;
        boolean endsWord;
        if (o.regex()) {
            body = q;
            startsWord = true;
            endsWord = true;
        } else if (o.ignoreWhitespace()) {
            String stripped = stripWhitespace(q);
            if (stripped.isEmpty()) {
                throw new IllegalArgumentException("Query contains only whitespace");
            }
            body = joinIgnoringWhitespace(stripped);
            startsWord = isWordChar(stripped.codePointAt(0));
            endsWord = isWordChar(stripped.codePointBefore(stripped.length()));
        } else {
            body = literal(q);
            startsWord = isWordChar(q.codePointAt(0));
            endsWord = isWordChar(q.codePointBefore(q.length()));
        }
        if (o.wholeWord()) {
            body = (startsWord ? WORD_BEFORE : "") + "(?:" + body + ")" + (endsWord ? WORD_AFTER : "");
        }
        int flags = Pattern.MULTILINE;
        if (!o.matchCase()) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        return Pattern.compile(body, flags);
    }

    /**
     * Filter on file names. Tokens separated by comma/space/semicolon:
     * ".java" or "*.java" = extension, anything else = part of the name.
     */
    public static boolean matchesNameFilter(String name, String filterText) {
        if (filterText == null || filterText.isBlank()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean anyToken = false;
        for (String raw : filterText.split("[,;\\s]+")) {
            String t = raw.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("*")) {
                t = t.substring(1);
            }
            if (t.isEmpty()) {
                continue;
            }
            anyToken = true;
            if (t.startsWith(".") ? lower.endsWith(t) : lower.contains(t)) {
                return true;
            }
        }
        return !anyToken;
    }

    public static FileResult searchFile(String path, String name, String text,
                                        Pattern pattern, BooleanSupplier cancelled) {
        List<Hit> hits = new ArrayList<>();
        boolean truncated = false;
        int[] lineStarts = null;
        Matcher m = pattern.matcher(new CancellableText(text, cancelled));
        while (m.find()) {
            if (m.end() == m.start()) {
                continue;
            }
            if (hits.size() >= MAX_HITS_PER_FILE) {
                truncated = true;
                break;
            }
            if (lineStarts == null) {
                lineStarts = computeLineStarts(text);
            }
            hits.add(buildHit(text, lineStarts, m.start(), m.end()));
        }
        return new FileResult(path, name, hits, truncated);
    }

    // ------------------------------------------------------------------

    private static Hit buildHit(String text, int[] lineStarts, int start, int end) {
        int lineIdx = lineIndexOf(lineStarts, start);
        int lineStart = lineStarts[lineIdx];
        int lineEnd = lineIdx + 1 < lineStarts.length ? lineStarts[lineIdx + 1] - 1 : text.length();
        if (lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r') {
            lineEnd--;
        }
        int lineLen = Math.max(0, lineEnd - lineStart);
        int mStart = Math.min(start - lineStart, lineLen);
        int mEnd = Math.min(Math.max(Math.min(end, lineEnd) - lineStart, mStart), lineLen);

        String line = text.substring(lineStart, lineStart + lineLen);

        int lead = 0;
        while (lead < mStart && Character.isWhitespace(line.charAt(lead))) {
            lead++;
        }
        line = line.substring(lead);
        mStart -= lead;
        mEnd -= lead;

        String preview = line;
        int ps = mStart;
        int pe = mEnd;
        if (preview.length() > MAX_PREVIEW_CHARS) {
            int from = Math.max(0, mStart - CONTEXT_BEFORE_MATCH);
            int to = Math.min(preview.length(), from + MAX_PREVIEW_CHARS);
            boolean cutLeft = from > 0;
            boolean cutRight = to < preview.length();
            String cut = preview.substring(from, to);
            ps = mStart - from;
            pe = Math.min(mEnd, to) - from;
            preview = (cutLeft ? "…" : "") + cut + (cutRight ? "…" : "");
            if (cutLeft) {
                ps++;
                pe++;
            }
        }
        return new Hit(lineIdx + 1, start - lineStart + 1, preview, ps, Math.max(ps, pe));
    }

    private static int[] computeLineStarts(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        int[] starts = new int[count];
        int idx = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[idx++] = i + 1;
            }
        }
        return starts;
    }

    private static int lineIndexOf(int[] starts, int offset) {
        int pos = Arrays.binarySearch(starts, offset);
        return pos >= 0 ? pos : -pos - 2;
    }

    private static String literal(String q) {
        String normalized = q.replace("\r\n", "\n");
        if (normalized.indexOf('\n') < 0) {
            return Pattern.quote(normalized);
        }
        String[] parts = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append("\\R");
            }
            if (!parts[i].isEmpty()) {
                sb.append(Pattern.quote(parts[i]));
            }
        }
        return sb.toString();
    }

    private static String stripWhitespace(String q) {
        StringBuilder sb = new StringBuilder(q.length());
        q.codePoints().filter(cp -> !Character.isWhitespace(cp)).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static String joinIgnoringWhitespace(String stripped) {
        int[] cps = stripped.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            if (i > 0) {
                sb.append("\\s*");
            }
            sb.append(Pattern.quote(new String(Character.toChars(cps[i]))));
        }
        return sb.toString();
    }

    private static boolean isWordChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '_';
    }

    /** Lets a runaway regex be interrupted: polls the cancel flag while matching. */
    private static final class CancellableText implements CharSequence {
        private final String text;
        private final BooleanSupplier cancelled;
        private int ticks;

        CancellableText(String text, BooleanSupplier cancelled) {
            this.text = text;
            this.cancelled = cancelled;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if ((++ticks & 0x3FF) == 0 && cancelled.getAsBoolean()) {
                throw new SearchCancelledException();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.substring(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}