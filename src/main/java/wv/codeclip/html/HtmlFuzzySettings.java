package wv.codeclip.html;

import java.util.prefs.Preferences;

/**
 * User-configurable thresholds for HTML mode's fuzzy @@FIND matching.
 * Self-contained (uses java.util.prefs.Preferences directly) rather than
 * going through SettingsManager, since SettingsManager's storage format
 * wasn't available to pattern-match against — happy to migrate this to
 * SettingsManager once that file is shared, if that's preferred.
 *
 * HTML-mode-only; Java/Godot patching doesn't use fuzzy matching at all.
 */
public final class HtmlFuzzySettings {

    private static final Preferences PREFS = Preferences.userNodeForPackage(HtmlFuzzySettings.class);
    private static final String KEY_MIN_MATCH_PERCENT = "html.fuzzy.minMatchPercent";
    private static final String KEY_CONFIRM_HIGH_CONFIDENCE = "html.fuzzy.confirmHighConfidence";

    public static final double DEFAULT_MIN_MATCH_PERCENT = 30.0;
    public static final double MIN_ALLOWED_PERCENT = 1.0;
    public static final double MAX_ALLOWED_PERCENT = 94.0; // must stay below the 95% high-confidence tier

    private static double minMatchPercent = PREFS.getDouble(KEY_MIN_MATCH_PERCENT, DEFAULT_MIN_MATCH_PERCENT);
    private static boolean confirmHighConfidenceMatches = PREFS.getBoolean(KEY_CONFIRM_HIGH_CONFIDENCE, false);

    private HtmlFuzzySettings() {}

    public static double getMinMatchPercent() {
        return minMatchPercent;
    }

    public static void setMinMatchPercent(double percent) {
        double clamped = Math.max(MIN_ALLOWED_PERCENT, Math.min(MAX_ALLOWED_PERCENT, percent));
        minMatchPercent = clamped;
        PREFS.putDouble(KEY_MIN_MATCH_PERCENT, clamped);
    }

    public static boolean isConfirmHighConfidenceMatches() {
        return confirmHighConfidenceMatches;
    }

    public static void setConfirmHighConfidenceMatches(boolean value) {
        confirmHighConfidenceMatches = value;
        PREFS.putBoolean(KEY_CONFIRM_HIGH_CONFIDENCE, value);
    }
}