package wv.codeclip;

import wv.codeclip.mainFrame.CodeClipFrame;
import javax.swing.SwingUtilities;

public class CodeClip {

    /** Strong reference so the level override on the JDK shell logger is not garbage-collected. */
    private static final java.util.logging.Logger SHELL_LOGGER
            = java.util.logging.Logger.getLogger("sun.awt.shell");

public static void main(String[] args) {
        silenceShellFolderWarning();
        SwingUtilities.invokeLater(CodeClipFrame::new);
    }

/**
     * Windows logs a harmless "Cannot access 'Personal'" warning when the
     * Documents folder is redirected or unreachable. Must run before any
     * JFileChooser is created.
     */
    private static void silenceShellFolderWarning() {
        SHELL_LOGGER.setLevel(java.util.logging.Level.OFF);
        javax.swing.UIManager.put("FileChooser.useShellFolder", Boolean.FALSE);
    }

}
