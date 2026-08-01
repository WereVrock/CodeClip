package wv.codeclip;

import wv.codeclip.mainFrame.CodeClipFrame;
import javax.swing.SwingUtilities;

public class CodeClip {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(CodeClipFrame::new);
    }
}
