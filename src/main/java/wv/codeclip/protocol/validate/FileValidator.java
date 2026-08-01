package wv.codeclip.protocol.validate;

import java.util.*;
import java.util.regex.*;
import wv.codeclip.protocol.model.ProtocolEntry;
import wv.codeclip.protocol.model.ProtocolFile;
import wv.codeclip.protocol.model.ValidationError;
import wv.codeclip.protocol.model.ValidationResult;

/** Runs AFTER the accepted subset is applied. Also used after hand-edits in the UI. */
public final class FileValidator {

    private static final Pattern VALID_ID = Pattern.compile("^[a-z][a-z0-9_-]*$");

    public ValidationResult validate(ProtocolFile file) {
        ValidationResult result = new ValidationResult();
        String fileName = file.getFileName();

        Map<String, Integer> idCounts = new HashMap<>();
        for (ProtocolEntry entry : file.getEntries()) {
            idCounts.merge(entry.getId(), 1, Integer::sum);

            if (!VALID_ID.matcher(entry.getId()).matches()) {
                result.add(ValidationError.fileError(
                    "Malformed !id line: '" + entry.getId() + "'", entry.getId(), fileName));
            }

            boolean allBlank = entry.getContentLines().stream().allMatch(String::isEmpty);
            if (entry.getContentLines().isEmpty() || allBlank) {
                result.add(ValidationError.fileError(
                    "Empty content block for id '" + entry.getId() + "'", entry.getId(), fileName));
            }
        }

        for (Map.Entry<String, Integer> e : idCounts.entrySet()) {
            if (e.getValue() > 1) {
                result.add(ValidationError.fileError(
                    "Duplicate !id declaration: '" + e.getKey() + "' appears " + e.getValue() + " times",
                    e.getKey(), fileName));
            }
        }

        return result;
    }
}