package wv.codeclip.model;

/**
 * Thrown when a patch cannot be applied cleanly.
 * The message is always human-readable and suitable for display in a dialog.
 */
public class PatchException extends Exception {
    public PatchException(String message) {
        super(message);
    }
}