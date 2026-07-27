package wv.codeclip.protocol.model;

import java.util.*;

public final class ValidationResult {
    private final List<ValidationError> errors = new ArrayList<>();
    private final List<ValidationError> warnings = new ArrayList<>();

    public void add(ValidationError e) {
        if (e.getSeverity() == ValidationError.Severity.ERROR) errors.add(e);
        else warnings.add(e);
    }

    public boolean isValid() { return errors.isEmpty(); }
    public List<ValidationError> getErrors() { return errors; }
    public List<ValidationError> getWarnings() { return warnings; }

    public static ValidationResult merge(ValidationResult a, ValidationResult b) {
        ValidationResult r = new ValidationResult();
        a.errors.forEach(r::add);
        a.warnings.forEach(r::add);
        b.errors.forEach(r::add);
        b.warnings.forEach(r::add);
        return r;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Valid: ").append(isValid()).append('\n');
        for (ValidationError e : errors) sb.append("  ").append(e).append('\n');
        for (ValidationError w : warnings) sb.append("  ").append(w).append('\n');
        return sb.toString();
    }
}