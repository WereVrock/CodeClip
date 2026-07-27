package wv.codeclip.protocol.model;

import java.util.*;

public final class ProtocolPatchResult {
    public enum Status { EMPTY, CANCELLED, VALIDATION_FAILED, APPLIED, FILE_VALIDATION_FAILED }

    private final Status status;
    private final ValidationResult validation;
    private final Map<String, String> writtenFiles; // fileName -> final content
    private final List<String> log;

    public ProtocolPatchResult(Status status, ValidationResult validation,
                        Map<String, String> writtenFiles, List<String> log) {
        this.status = status;
        this.validation = validation;
        this.writtenFiles = writtenFiles;
        this.log = log;
    }

    public static ProtocolPatchResult empty() {
        return new ProtocolPatchResult(Status.EMPTY, new ValidationResult(), Map.of(), List.of());
    }

    public static ProtocolPatchResult cancelled() {
        return new ProtocolPatchResult(Status.CANCELLED, new ValidationResult(), Map.of(), List.of());
    }

    public static ProtocolPatchResult validationFailed(ValidationResult vr) {
        return new ProtocolPatchResult(Status.VALIDATION_FAILED, vr, Map.of(), List.of());
    }

    public static ProtocolPatchResult fileValidationFailed(ValidationResult vr, List<String> log) {
        return new ProtocolPatchResult(Status.FILE_VALIDATION_FAILED, vr, Map.of(), log);
    }

    public static ProtocolPatchResult applied(Map<String, String> writtenFiles, List<String> log, ValidationResult warnings) {
        return new ProtocolPatchResult(Status.APPLIED, warnings, writtenFiles, log);
    }

    public Status getStatus() { return status; }
    public ValidationResult getValidation() { return validation; }
    public Map<String, String> getWrittenFiles() { return writtenFiles; }
    public List<String> getLog() { return log; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("PatchResult[status=").append(status).append("]\n");
        sb.append(validation);
        for (String l : log) sb.append("  > ").append(l).append('\n');
        for (Map.Entry<String, String> e : writtenFiles.entrySet()) {
            sb.append("--- ").append(e.getKey()).append(" ---\n");
            sb.append(e.getValue());
        }
        return sb.toString();
    }
}