package wv.codeclip;

import javax.swing.SwingUtilities;

public class CodeClip {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(CodeClipFrame::new);
    }
}
