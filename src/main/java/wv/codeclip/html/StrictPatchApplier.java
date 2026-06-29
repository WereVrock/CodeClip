package wv.codeclip.html;

import wv.codeclip.model.PatchChange;
import wv.codeclip.model.PatchException;

/**
 * Strict-match-only find/replace, used by HTML mode instead of the
 * tolerant multi-stage matching in wv.codeclip.patch.PatchApplier.
 *
 * Per project notes: HTML mode should allow surgical changes "just like
 * the default Java mode but without the sophisticated tolerance steps —
 * just the perfect matches." This class is intentionally separate from
 * PatchApplier so the existing Java/Godot matching behavior is untouched.
 *
 * Only @@FIND/@@REPLACE (PatchChange.FindReplace) is supported here.
 * @@METHOD: and @@INSERT_METHOD: are not handled — HtmlPasteHandler
 * rejects those directives before reaching this class (see
 * HtmlInstructions: "not supported in HTML mode").
 */
public final class StrictPatchApplier {

    private StrictPatchApplier() {}

    /**
     * Applies a single strict find/replace to the given code.
     * @throws PatchException if @@FIND matches zero or more than one time.
     */
    public static String applyFindReplace(PatchChange.FindReplace fr, String code)
            throws PatchException {
        String find = fr.find();
        String replace = fr.replace();

        int count = countOccurrences(code, find);
        if (count == 0) {
            throw new PatchException(
                    "@@FIND block not found in " + fr.fileName()
                    + " (strict match — exact whitespace and line endings required).\n\n"
                    + "Searched for:\n" + find,
                    fr.fileName());
        }
        if (count > 1) {
            throw new PatchException(
                    "@@FIND block matches " + count + " locations in " + fr.fileName()
                    + " — must match exactly once.\n\nSearched for:\n" + find,
                    fr.fileName());
        }

        return code.replace(find, replace);
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