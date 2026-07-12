package wv.codeclip.patch;

import java.util.prefs.Preferences;

/**
 * User toggle for PostPatchVerifier's compile check. Defaults OFF: compiling
 * only the files loaded in CodeClip (no external classpath) is exact and
 * reliable for arity/type/syntax errors within your own code, but will also
 * report "cannot find symbol" for any type that lives in a library or module
 * not loaded into CodeClip — expected, not a real bug, for most real-world
 * projects. Turning this on is most useful for projects (like CodeClip
 * itself) with few or no external dependencies.
 */
public final class PostPatchVerifierSettings {

    private static final Preferences PREFS = Preferences.userNodeForPackage(PostPatchVerifierSettings.class);
    private static final String KEY_COMPILE_CHECK = "postpatch.compileCheck.enabled";

    private static boolean compileCheckEnabled = PREFS.getBoolean(KEY_COMPILE_CHECK, false);

    private PostPatchVerifierSettings() {}

    public static boolean isCompileCheckEnabled() {
        return compileCheckEnabled;
    }

    public static void setCompileCheckEnabled(boolean value) {
        compileCheckEnabled = value;
        PREFS.putBoolean(KEY_COMPILE_CHECK, value);
    }
}