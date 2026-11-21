package wv.codeclip.html;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Side-by-side diff view for the fuzzy-match confirmation dialogs.
 * Left = what @@FIND asked for, right = what was actually matched in the file.
 *
 * Lines are aligned with an LCS over whitespace-normalized lines, so lines that
 * differ only in indentation/spacing are shown as "whitespace only" (the matcher
 * ignores those) and real differences stand out. Inside changed lines the
 * differing characters get a darker highlight. Scrolling of both panes is linked.
 */
public final class FuzzyDiffView {

    private FuzzyDiffView() {}

    private enum Kind { SAME, WS_ONLY, CHANGED, ONLY }

    private static final class Mark {
        Kind kind = Kind.SAME;
        List<int[]> ranges = new ArrayList<>();
    }

    private record Pane(JPanel panel, JScrollPane scroll) {}

    private static final long MAX_LINE_LCS_CELLS = 4_000_000L;
    private static final long MAX_CHAR_LCS_CELLS = 160_000L;

    private static final Color ONLY_LEFT = new Color(255, 205, 205);
    private static final Color ONLY_RIGHT = new Color(200, 238, 200);
    private static final Color CHANGED_LEFT = new Color(255, 232, 232);
    private static final Color CHANGED_RIGHT = new Color(228, 246, 228);
    private static final Color CHARS_LEFT = new Color(255, 150, 150);
    private static final Color CHARS_RIGHT = new Color(130, 205, 130);
    private static final Color WS_LINE = new Color(228, 232, 250);
    private static final Color WS_CHARS = new Color(165, 180, 235);

    public static JPanel build(String requested, String actual, String pct) {
        String[] ll = normalizeEol(requested).split("\n", -1);
        String[] rl = normalizeEol(actual).split("\n", -1);
        Mark[] lm = newMarks(ll.length);
        Mark[] rm = newMarks(rl.length);
        diffLines(ll, rl, lm, rm);

        Pane left = buildPane("Requested (@@FIND)", ll, lm, true,
                new Color(244, 249, 255), new Color(0, 90, 180));
        Pane right = buildPane("Actually Matched (" + pct + "%)", rl, rm, false,
                new Color(255, 252, 242), new Color(180, 130, 0));
        link(left.scroll().getVerticalScrollBar(), right.scroll().getVerticalScrollBar());
        link(left.scroll().getHorizontalScrollBar(), right.scroll().getHorizontalScrollBar());

        JPanel split = new JPanel(new GridLayout(1, 2, 8, 0));
        split.setOpaque(false);
        split.add(left.panel());
        split.add(right.panel());

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setOpaque(false);
        root.add(buildLegend(lm, rm), BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    // ── UI pieces ──────────────────────────────────────────────────────────────

    private static Pane buildPane(String title, String[] lines, Mark[] marks, boolean isLeft,
                                  Color bg, Color accent) {
        String text = String.join("\n", lines);
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(bg);
        area.setLineWrap(false);
        applyHighlights(area, text, lines, marks, isLeft);
        area.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(accent, 1, true));

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        JLabel lbl = new JLabel(title);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        lbl.setForeground(accent);
        p.add(lbl, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return new Pane(p, scroll);
    }

    private static void applyHighlights(JTextArea area, String text, String[] lines,
                                        Mark[] marks, boolean isLeft) {
        Highlighter h = area.getHighlighter();
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            int lineStart = offset;
            int lineEnd = offset + lines[i].length();
            Mark mk = marks[i];
            if (mk.kind != Kind.SAME) {
                try {
                    int p1 = Math.min(lineEnd + 1, text.length());
                    h.addHighlight(lineStart, p1, new FullLinePainter(lineColor(mk.kind, isLeft)));
                    Color cc = charColor(mk.kind, isLeft);
                    for (int[] r : mk.ranges) {
                        h.addHighlight(lineStart + r[0], lineStart + r[1],
                                new DefaultHighlighter.DefaultHighlightPainter(cc));
                    }
                } catch (BadLocationException ignored) {
                }
            }
            offset = lineEnd + 1;
        }
    }

    private static Color lineColor(Kind k, boolean isLeft) {
        return switch (k) {
            case ONLY -> isLeft ? ONLY_LEFT : ONLY_RIGHT;
            case CHANGED -> isLeft ? CHANGED_LEFT : CHANGED_RIGHT;
            case WS_ONLY -> WS_LINE;
            default -> new Color(0, 0, 0, 0);
        };
    }

    private static Color charColor(Kind k, boolean isLeft) {
        return k == Kind.WS_ONLY ? WS_CHARS : (isLeft ? CHARS_LEFT : CHARS_RIGHT);
    }

    private static JPanel buildLegend(Mark[] lm, Mark[] rm) {
        int changed = count(lm, Kind.CHANGED);
        int onlyReq = count(lm, Kind.ONLY);
        int onlyFile = count(rm, Kind.ONLY);
        int ws = Math.max(count(lm, Kind.WS_ONLY), count(rm, Kind.WS_ONLY));

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setOpaque(false);
        p.add(new JLabel("Differences:"));
        p.add(chip("Changed: " + changed, CHANGED_LEFT, CHARS_LEFT,
                "Lines that exist on both sides but differ. Darker = the differing characters."));
        p.add(chip("Only in @@FIND: " + onlyReq, ONLY_LEFT, CHARS_LEFT,
                "Lines the patch asked for that are not in the file."));
        p.add(chip("Only in file: " + onlyFile, ONLY_RIGHT, CHARS_RIGHT,
                "Lines in the matched file region that the patch did not include."));
        p.add(chip("Whitespace only: " + ws, WS_LINE, WS_CHARS,
                "Indentation, spacing or blank-line differences. The matcher ignores these."));
        return p;
    }

    private static JLabel chip(String text, Color bg, Color border, String tip) {
        JLabel l = new JLabel(text);
        l.setOpaque(true);
        l.setBackground(bg);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
        l.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 1),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        l.setToolTipText(tip);
        return l;
    }

    private static void link(JScrollBar x, JScrollBar y) {
        x.addAdjustmentListener(e -> { if (y.getValue() != e.getValue()) y.setValue(e.getValue()); });
        y.addAdjustmentListener(e -> { if (x.getValue() != e.getValue()) x.setValue(e.getValue()); });
    }

    /** Paints a full-width background band for the line that starts at p0. */
    private static final class FullLinePainter implements Highlighter.HighlightPainter {
        private final Color color;

        FullLinePainter(Color color) {
            this.color = color;
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                Rectangle2D r = c.getUI().modelToView2D(c, p0, Position.Bias.Forward);
                if (r == null) return;
                g.setColor(color);
                g.fillRect(0, (int) r.getY(), c.getWidth(), (int) Math.ceil(r.getHeight()));
            } catch (BadLocationException ignored) {
            }
        }
    }

    // ── Diff logic ─────────────────────────────────────────────────────────────

    private static Mark[] newMarks(int n) {
        Mark[] m = new Mark[n];
        for (int i = 0; i < n; i++) m[i] = new Mark();
        return m;
    }

    private static int count(Mark[] marks, Kind k) {
        int c = 0;
        for (Mark m : marks) if (m.kind == k) c++;
        return c;
    }

    private static String normalizeEol(String s) {
        return (s == null ? "" : s).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String norm(String s) {
        return s.strip().replaceAll("\\s+", " ");
    }

    private static boolean isBlank(String s) {
        return s.strip().isEmpty();
    }

    private static void diffLines(String[] ll, String[] rl, Mark[] lm, Mark[] rm) {
        int n = ll.length;
        int m = rl.length;
        if ((long) n * m > MAX_LINE_LCS_CELLS) {
            pairGap(ll, rl, lm, rm, 0, n, 0, m);
            return;
        }
        String[] ln = new String[n];
        String[] rn = new String[m];
        for (int a = 0; a < n; a++) ln[a] = norm(ll[a]);
        for (int b = 0; b < m; b++) rn[b] = norm(rl[b]);

        int[][] dp = new int[n + 1][m + 1];
        for (int a = n - 1; a >= 0; a--) {
            for (int b = m - 1; b >= 0; b--) {
                dp[a][b] = ln[a].equals(rn[b])
                        ? dp[a + 1][b + 1] + 1
                        : Math.max(dp[a + 1][b], dp[a][b + 1]);
            }
        }

        int i = 0;
        int j = 0;
        int gi = 0;
        int gj = 0;
        while (i < n && j < m) {
            if (ln[i].equals(rn[j])) {
                pairGap(ll, rl, lm, rm, gi, i, gj, j);
                markPair(ll, rl, lm, rm, i, j);
                i++;
                j++;
                gi = i;
                gj = j;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        pairGap(ll, rl, lm, rm, gi, n, gj, m);
    }

    /** Lines between two aligned anchors: pair them in order, the surplus is "only on one side". */
    private static void pairGap(String[] ll, String[] rl, Mark[] lm, Mark[] rm,
                                int a, int b, int c, int d) {
        int pairs = Math.min(b - a, d - c);
        for (int k = 0; k < pairs; k++) {
            markPair(ll, rl, lm, rm, a + k, c + k);
        }
        for (int i = a + pairs; i < b; i++) {
            lm[i].kind = isBlank(ll[i]) ? Kind.WS_ONLY : Kind.ONLY;
        }
        for (int j = c + pairs; j < d; j++) {
            rm[j].kind = isBlank(rl[j]) ? Kind.WS_ONLY : Kind.ONLY;
        }
    }

    private static void markPair(String[] ll, String[] rl, Mark[] lm, Mark[] rm, int li, int ri) {
        String a = ll[li];
        String b = rl[ri];
        if (a.equals(b)) return;
        List<int[]> ra = new ArrayList<>();
        List<int[]> rb = new ArrayList<>();
        charDiff(a, b, ra, rb);
        Kind k = norm(a).equals(norm(b)) ? Kind.WS_ONLY : Kind.CHANGED;
        lm[li].kind = k;
        lm[li].ranges = ra;
        rm[ri].kind = k;
        rm[ri].ranges = rb;
    }

    /** Character-level diff of two lines; fills the unmatched [start,end) ranges for each side. */
    private static void charDiff(String a, String b, List<int[]> ra, List<int[]> rb) {
        int n = a.length();
        int m = b.length();
        if ((long) n * m > MAX_CHAR_LCS_CELLS) {
            int p = 0;
            while (p < n && p < m && a.charAt(p) == b.charAt(p)) p++;
            int s = 0;
            while (s < n - p && s < m - p && a.charAt(n - 1 - s) == b.charAt(m - 1 - s)) s++;
            if (n - s > p) ra.add(new int[]{p, n - s});
            if (m - s > p) rb.add(new int[]{p, m - s});
            return;
        }
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.charAt(i) == b.charAt(j)
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        boolean[] ua = new boolean[n];
        boolean[] ub = new boolean[m];
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ua[i++] = true;
            } else {
                ub[j++] = true;
            }
        }
        while (i < n) ua[i++] = true;
        while (j < m) ub[j++] = true;
        toRanges(ua, ra);
        toRanges(ub, rb);
    }

    private static void toRanges(boolean[] flags, List<int[]> out) {
        int i = 0;
        while (i < flags.length) {
            if (!flags[i]) {
                i++;
                continue;
            }
            int start = i;
            while (i < flags.length && flags[i]) i++;
            out.add(new int[]{start, i});
        }
    }
}