package wv.codeclip.ui;

/**
 * Global smart paste settings — loaded once at startup, saved on close.
 */
public class SmartPasteSettings {

    private static boolean allowClasses       = true;
    private static boolean skipCreateConfirm  = false;
    private static boolean skipOverwriteConfirm = false;

    private SmartPasteSettings() {}

    public static void load(wv.codeclip.io.SettingsManager s) {
        allowClasses         = s.loadSmartPasteAllowClasses();
        skipCreateConfirm    = s.loadSmartPasteSkipCreateConfirm();
        skipOverwriteConfirm = s.loadSmartPasteSkipOverwriteConfirm();
    }

    public static void save(wv.codeclip.io.SettingsManager s) {
        s.saveSmartPasteAllowClasses(allowClasses);
        s.saveSmartPasteSkipCreateConfirm(skipCreateConfirm);
        s.saveSmartPasteSkipOverwriteConfirm(skipOverwriteConfirm);
    }

    public static boolean isAllowClasses()         { return allowClasses; }
    public static boolean isSkipCreateConfirm()    { return skipCreateConfirm; }
    public static boolean isSkipOverwriteConfirm() { return skipOverwriteConfirm; }

    public static void setAllowClasses(boolean v)         { allowClasses = v; }
    public static void setSkipCreateConfirm(boolean v)    { skipCreateConfirm = v; }
    public static void setSkipOverwriteConfirm(boolean v) { skipOverwriteConfirm = v; }
}