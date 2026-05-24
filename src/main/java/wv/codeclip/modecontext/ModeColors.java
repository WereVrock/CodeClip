package wv.codeclip.modecontext;

import java.awt.Color;

/**
 * Provides mode-specific UI color tints.
 */
public final class ModeColors {

    private ModeColors() {}

    // Very light reddish tint for Java enabled/disabled rows
    private static final Color JAVA_ENABLED  = new Color(255, 240, 240);
    private static final Color JAVA_DISABLED = new Color(220, 200, 200);

    // Very light bluish tint for Godot enabled/disabled rows
    private static final Color GODOT_ENABLED  = new Color(240, 240, 255);
    private static final Color GODOT_DISABLED = new Color(200, 200, 220);

    /**
     * Background color for an enabled class row in the list.
     */
    public static Color getEnabledBackground() {
        return switch (ModeContext.getMode()) {
            case GODOT -> GODOT_ENABLED;
            default    -> JAVA_ENABLED;
        };
    }

    /**
     * Background color for a disabled class row in the list.
     */
    public static Color getDisabledBackground() {
        return switch (ModeContext.getMode()) {
            case GODOT -> GODOT_DISABLED;
            default    -> JAVA_DISABLED;
        };
    }
}