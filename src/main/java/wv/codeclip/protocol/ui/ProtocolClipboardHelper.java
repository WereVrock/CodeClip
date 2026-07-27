package wv.codeclip.protocol.ui;

import java.awt.*;
import java.awt.datatransfer.StringSelection;

public final class ProtocolClipboardHelper {

    private ProtocolClipboardHelper() {}

    public static void copyToClipboard(String text) {
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
    }

    public static String readFromClipboard() {
        try {
            return (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        } catch (Exception e) {
            return null;
        }
    }
}