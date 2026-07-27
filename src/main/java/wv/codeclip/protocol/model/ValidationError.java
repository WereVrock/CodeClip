package wv.codeclip.protocol.model;

public final class ValidationError {
    public enum Stage { PATCH, FILE }
    public enum Severity { ERROR, WARNING }

    private final Stage stage;
    private final Severity severity;
    private final String message;
    private final String relatedId; // nullable
    private final String relatedFile; // nullable

    public ValidationError(Stage stage, Severity severity, String message, String relatedId, String relatedFile) {
        this.stage = stage;
        this.severity = severity;
        this.message = message;
        this.relatedId = relatedId;
        this.relatedFile = relatedFile;
    }

    public static ValidationError patchError(String message, String relatedId, String relatedFile) {
        return new ValidationError(Stage.PATCH, Severity.ERROR, message, relatedId, relatedFile);
    }

    public static ValidationError patchWarning(String message, String relatedId, String relatedFile) {
        return new ValidationError(Stage.PATCH, Severity.WARNING, message, relatedId, relatedFile);
    }

    public static ValidationError fileError(String message, String relatedId, String relatedFile) {
        return new ValidationError(Stage.FILE, Severity.ERROR, message, relatedId, relatedFile);
    }

    public Stage getStage() { return stage; }
    public Severity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public String getRelatedId() { return relatedId; }
    public String getRelatedFile() { return relatedFile; }

    @Override
    public String toString() {
        return "[" + stage + "/" + severity + "] " + message
            + (relatedFile != null ? " (file=" + relatedFile + ")" : "")
            + (relatedId != null ? " (id=" + relatedId + ")" : "");
    }
}