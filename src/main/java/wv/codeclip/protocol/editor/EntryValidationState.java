package wv.codeclip.protocol.editor;

public final class EntryValidationState {
    public enum Level { OK, WARNING, ERROR }

    public final Level level;
    public final String message;

    public EntryValidationState(Level level, String message) {
        this.level = level;
        this.message = message;
    }

    public static EntryValidationState ok() {
        return new EntryValidationState(Level.OK, null);
    }

    public static EntryValidationState error(String message) {
        return new EntryValidationState(Level.ERROR, message);
    }

    public static EntryValidationState warning(String message) {
        return new EntryValidationState(Level.WARNING, message);
    }
}