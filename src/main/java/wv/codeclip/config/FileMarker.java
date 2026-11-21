package wv.codeclip.config;

/**
 * Header line that CodeClip itself inserts before each file's content when
 * copying code. It is NOT part of the code, and says so.
 */
public final class FileMarker {

    public static final String NOT_IN_CODE = "(this line is not in the code)";

    private FileMarker() {}

    /** Returns e.g. "==SceneSetup.js== (this line is not in the code)\n". */
    public static String header(String fileName) {
        return "==" + fileName + "== " + NOT_IN_CODE + "\n";
    }
}