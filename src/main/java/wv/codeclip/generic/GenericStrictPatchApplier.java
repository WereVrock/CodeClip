// ===== GenericStrictPatchApplier.java =====
package wv.codeclip.generic;

import wv.codeclip.html.FuzzyMatchProgressDialog;
import wv.codeclip.html.HtmlFuzzyMatcher;
import wv.codeclip.model.PatchChange;
import wv.codeclip.model.PatchException;

import javax.swing.JFrame;
import java.util.List;

/**
 * Find/replace for Generic mode's @@FIND/@@REPLACE directive. Identical
 * strategy to wv.codeclip.html.StrictPatchApplier: exact match first, then
 * a fuzzy fallback via the shared HtmlFuzzyMatcher/FuzzyMatchProgressDialog
 * (both file-type agnostic, so reused directly rather than duplicated).
 */
public final class GenericStrictPatchApplier {

    private GenericStrictPatchApplier() {}

    public enum MatchTier { EXACT, FUZZY_HIGH, FUZZY_LOW }

    public record FindReplaceResult(String newCode, MatchTier tier, double similarityPercent, String matchedText) {}

    private static final double FUZZY_HIGH_THRESHOLD = 95.0;
    private static final double AMBIGUITY_MARGIN = 5.0;

    public static FindReplaceResult applyFindReplace(JFrame parent, PatchChange.FindReplace fr, String code)
            throws PatchException {
        String find = fr.find();
        String replace = fr.replace();

        int exactCount = countOccurrences(code, find);
        if (exactCount == 1) {
            return new FindReplaceResult(code.replace(find, replace), MatchTier.EXACT, 100.0, find);
        }
        if (exactCount > 1) {
            throw new PatchException(
                    "@@FIND block matches " + exactCount + " locations in " + fr.fileName()
                    + " — must match exactly once.\n\nSearched for:\n" + find,
                    fr.fileName());
        }

        double floor = GenericFuzzySettings.getMinMatchPercent();
        HtmlFuzzyMatcher.ScanResult scan =
                FuzzyMatchProgressDialog.runScan(parent, fr.fileName(), code, find, floor);

        if (scan.cancelled()) {
            throw new PatchException(
                    "@@FIND fuzzy search for " + fr.fileName()
                    + " was terminated by the user before a match was found.",
                    fr.fileName());
        }

        List<HtmlFuzzyMatcher.Candidate> candidates = scan.candidates();
        HtmlFuzzyMatcher.Candidate best = HtmlFuzzyMatcher.best(candidates);
        if (best == null) {
            throw new PatchException(
                    "@@FIND block not found in " + fr.fileName()
                    + " (tried an exact match, then fuzzy matching down to "
                    + HtmlFuzzyMatcher.formatPercent(floor) + "% similarity).\n\nSearched for:\n" + find,
                    fr.fileName());
        }

        if (best.similarityPercent() >= FUZZY_HIGH_THRESHOLD) {
            String newCode = splice(code, best, replace);
            return new FindReplaceResult(newCode, MatchTier.FUZZY_HIGH, best.similarityPercent(), best.text());
        }

        HtmlFuzzyMatcher.Candidate second = HtmlFuzzyMatcher.secondBest(candidates, best);
        if (second != null && (best.similarityPercent() - second.similarityPercent()) < AMBIGUITY_MARGIN) {
            throw new PatchException(
                    "@@FIND block has ambiguous fuzzy matches in " + fr.fileName()
                    + " (best " + HtmlFuzzyMatcher.formatPercent(best.similarityPercent()) + "%, next "
                    + HtmlFuzzyMatcher.formatPercent(second.similarityPercent()) + "%, within "
                    + (int) AMBIGUITY_MARGIN + "% of each other). Add more surrounding context to @@FIND "
                    + "so it matches a single location.",
                    fr.fileName());
        }

        String newCode = splice(code, best, replace);
        return new FindReplaceResult(newCode, MatchTier.FUZZY_LOW, best.similarityPercent(), best.text());
    }

    private static String splice(String code, HtmlFuzzyMatcher.Candidate match, String replace) {
        return code.substring(0, match.startOffset()) + replace + code.substring(match.endOffset());
    }

    private static int countOccurrences(String text, String find) {
        if (find == null || find.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(find, idx)) != -1) {
            count++;
            idx += find.length();
        }
        return count;
    }
}