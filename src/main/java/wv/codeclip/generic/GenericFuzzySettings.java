// ===== GenericFuzzySettings.java =====
package wv.codeclip.generic;

import java.util.prefs.Preferences;

/**
 * User-configurable thresholds for Generic mode's fuzzy @@FIND matching.
 * Mirrors wv.codeclip.html.HtmlFuzzySettings, but uses its own Preferences
 * keys so the two modes' thresholds are independent.
 */
public final class GenericFuzzySettings {

    private static final Preferences PREFS = Preferences.userNodeForPackage(GenericFuzzySettings.class);
    private static final String KEY_MIN_MATCH_PERCENT = "generic.fuzzy.minMatchPercent";
    private static final String KEY_CONFIRM_HIGH_CONFIDENCE = "generic.fuzzy.confirmHighConfidence";

    public static final double DEFAULT_MIN_MATCH_PERCENT = 30.0;
    public static final double MIN_ALLOWED_PERCENT = 1.0;
    public static final double MAX_ALLOWED_PERCENT = 94.0;

    private static double minMatchPercent = PREFS.getDouble(KEY_MIN_MATCH_PERCENT, DEFAULT_MIN_MATCH_PERCENT);
    private static boolean confirmHighConfidenceMatches = PREFS.getBoolean(KEY_CONFIRM_HIGH_CONFIDENCE, false);

    private GenericFuzzySettings() {}

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