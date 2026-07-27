package wv.codeclip.protocol.ui;

/** A single rendered line in a diff view, tagged with its change type. */
public final class DiffLine {
    public enum Type { UNCHANGED, ADDED, REMOVED }

    public final Type type;
    public final String text;

    public DiffLine(Type type, String text) {
        this.type = type;
        this.text = text;
    }
}