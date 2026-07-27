package wv.codeclip.protocol.model;

import java.util.List;

/**
 * A single parsed command from a @@protocol block. Every command is scoped
 * to exactly one file (the filename given on the @@protocol line).
 */
public final class Command {
    public static final String START_SENTINEL = "START";

    private final CommandType type;
    private final String targetFile;
    private final String id;
    private final String targetId; // nullable; for NEWAFTER / MOVE_AFTER
    private final List<String> contentLines; // nullable for DELETE / MOVE_AFTER
    private final int sourceLineNumber;

    public Command(CommandType type, String targetFile, String id, String targetId,
                    List<String> contentLines, int sourceLineNumber) {
        this.type = type;
        this.targetFile = targetFile;
        this.id = id;
        this.targetId = targetId;
        this.contentLines = contentLines;
        this.sourceLineNumber = sourceLineNumber;
    }

    public CommandType getType() { return type; }
    public String getTargetFile() { return targetFile; }
    public String getId() { return id; }
    public String getTargetId() { return targetId; }
    public List<String> getContentLines() { return contentLines; }
    public int getSourceLineNumber() { return sourceLineNumber; }

    public boolean targetsStart() {
        return targetId != null && targetId.equals(START_SENTINEL);
    }

    @Override
    public String toString() {
        return type + "[file=" + targetFile + ", id=" + id
            + (targetId != null ? ", target=" + targetId : "") + "]";
    }
}