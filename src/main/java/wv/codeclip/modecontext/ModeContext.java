package wv.codeclip.modecontext;

import wv.codeclip.AppMode;

/**
 * Holds mode-specific behaviour that doesn't belong in AppMode itself
 * or in a mode's own package.
 */
public final class ModeContext {

    private static AppMode currentMode = AppMode.JAVA;

    private ModeContext() {}

    public static void setMode(AppMode mode) {
        currentMode = mode;
    }

    public static AppMode getMode() {
        return currentMode;
    }

    /**
     * Returns the single-line comment prefix for the current mode.
     * Java → "//"   GDScript → "#"
     */
    public static String getCommentPrefix() {
        return switch (currentMode) {
            case GODOT -> "#";
            default    -> "//";
        };
    }
}