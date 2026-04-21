package wv.codeclip.model;

/**
 * Thrown when a patch cannot be applied cleanly.
 * The message is always human-readable and suitable for display in a dialog.
 */
public class PatchException extends Exception {

    private final String fileName;

    public PatchException(String message, String fileName) {
        super(message);
        this.fileName = fileName;
    }

    public PatchException(String message) {
        this(message, null);
    }

    public String getFileName() {
        return fileName;
    }
}