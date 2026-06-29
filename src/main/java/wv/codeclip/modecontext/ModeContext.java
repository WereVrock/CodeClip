package wv.codeclip.modecontext;

import wv.codeclip.AppMode;
import wv.codeclip.godot.GodotPasteHandler;
import wv.codeclip.io.PasteClassHandler;

/**
 * Holds mode-specific behaviour that doesn't belong in AppMode itself
 * or in a mode's own package. Central place for multi-mode decisions.
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
     * Single-line comment prefix for the current mode.
     * Java → "//"   GDScript → "#"
     */
    public static String getCommentPrefix() {
        return switch (currentMode) {
            case GODOT -> "#";
            default    -> "//";
        };
    }

    public static boolean isGodotMode() {
        return currentMode == AppMode.GODOT;
    }

    public static boolean isHtmlMode() {
        return currentMode == AppMode.HTML;
    }
}