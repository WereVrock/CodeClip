package wv.codeclip.protocol.validate;

import java.util.*;
import java.util.regex.*;
import static wv.codeclip.model.ClassRepository.ChangeKind.NEW;
import wv.codeclip.protocol.model.Command;
import static wv.codeclip.protocol.model.CommandType.APPENDTO;
import static wv.codeclip.protocol.model.CommandType.DELETE;
import static wv.codeclip.protocol.model.CommandType.MOVE_AFTER;
import static wv.codeclip.protocol.model.CommandType.NEWAFTER;
import static wv.codeclip.protocol.model.CommandType.UPDATE;
import wv.codeclip.protocol.model.ProtocolFile;
import wv.codeclip.protocol.model.ValidationError;
import wv.codeclip.protocol.model.ValidationResult;

/**
 * Runs BEFORE the dialog appears. Validates commands (already grouped per file)
 * against that file's original state, including master-lock and file-lock checks.
 */
public final class PatchValidator {

    private static final Pattern VALID_ID = Pattern.compile("^[a-z][a-z0-9_-]*$");
    private final CycleDetector cycleDetector = new CycleDetector();

    /**
     * @param masterLocked true if the global master lock is engaged; if so, ALL
     *                     commands for ALL files are rejected before anything else runs.
     */
    public ValidationResult validate(String fileName, ProtocolFile original,
                                      List<Command> commandsForFile, boolean masterLocked) {
        ValidationResult result = new ValidationResult();

        if (commandsForFile.isEmpty()) {
            return result;
        }

        if (masterLocked) {
            result.add(ValidationError.patchError(
                "Master lock is engaged; no protocol commands can be applied", null, fileName));
            return result;
        }

        if (original.isLocked()) {
            result.add(ValidationError.patchError(
                "File '" + fileName + "' is locked; AI commands cannot modify it", null, fileName));
            return result;
        }

        Set<String> idsCreatedInPatch = new HashSet<>();
        Set<String> idsDeletedInPatch = new HashSet<>();
        Map<String, Integer> newOrNewAfterCounts = new HashMap<>();

        for (Command c : commandsForFile) {
            if (!VALID_ID.matcher(c.getId()).matches()) {
                result.add(ValidationError.patchError(
                    "Invalid id format: '" + c.getId() + "' (must match [a-z][a-z0-9_-]*)", c.getId(), fileName));
            }
            if (c.getTargetId() != null && !c.targetsStart()
                    && !VALID_ID.matcher(c.getTargetId()).matches()) {
                result.add(ValidationError.patchError(
                    "Invalid target id format: '" + c.getTargetId() + "'", c.getTargetId(), fileName));
            }

            switch (c.getType()) {
                case NEW:
                case NEWAFTER:
                    idsCreatedInPatch.add(c.getId());
                    newOrNewAfterCounts.merge(c.getId(), 1, Integer::sum);
                    break;
                case DELETE:
                    idsDeletedInPatch.add(c.getId());
                    break;
                default:
                    break;
            }
        }

        for (Map.Entry<String, Integer> e : newOrNewAfterCounts.entrySet()) {
            if (e.getValue() > 1) {
                result.add(ValidationError.patchError(
                    "Duplicate NEW/NEWAFTER for id '" + e.getKey() + "' in the same patch", e.getKey(), fileName));
            }
            // NEW/NEWAFTER creating an id that already exists in this file is also an error.
            if (original.containsId(e.getKey())) {
                result.add(ValidationError.patchError(
                    "NEW/NEWAFTER id '" + e.getKey() + "' already exists in '" + fileName + "'", e.getKey(), fileName));
            }
        }

        for (Command c : commandsForFile) {
            switch (c.getType()) {
                case UPDATE:
                case APPENDTO:
                    if (!original.containsId(c.getId()) && !idsCreatedInPatch.contains(c.getId())) {
                        result.add(ValidationError.patchError(
                            c.getType() + " source id '" + c.getId() + "' does not exist in '" + fileName + "'",
                            c.getId(), fileName));
                    }
                    requireTerminated(c, result, fileName);
                    break;

                case DELETE:
                    if (!original.containsId(c.getId()) && !idsCreatedInPatch.contains(c.getId())) {
                        result.add(ValidationError.patchError(
                            "DELETE source id '" + c.getId() + "' does not exist in '" + fileName + "'", c.getId(), fileName));
                    }
                    break;

                case NEW:
                case NEWAFTER:
                    requireTerminated(c, result, fileName);
                    break;

                case MOVE_AFTER:
                    if (!original.containsId(c.getId()) && !idsCreatedInPatch.contains(c.getId())) {
                        result.add(ValidationError.patchError(
                            "MOVE_AFTER source id '" + c.getId() + "' does not exist in '" + fileName + "'", c.getId(), fileName));
                    }
                    if (!c.targetsStart()) {
                        boolean targetExistsInOriginal = original.containsId(c.getTargetId());
                        boolean targetCreatedInPatch = idsCreatedInPatch.contains(c.getTargetId());

                        if (!targetExistsInOriginal && !targetCreatedInPatch) {
                            result.add(ValidationError.patchError(
                                "MOVE_AFTER target '" + c.getTargetId() + "' does not exist in '" + fileName
                                    + "' and is not START (no fallback for missing target)", c.getTargetId(), fileName));
                        }
                    }
                    break;
            }
        }

        result = ValidationResult.merge(result, cycleDetector.detect(commandsForFile, fileName));

        return result;
    }

    private void requireTerminated(Command c, ValidationResult result, String fileName) {
        if (c.getContentLines() == null) {
            result.add(ValidationError.patchError(
                c.getType() + " block for id '" + c.getId() + "' is missing its content/terminator",
                c.getId(), fileName));
        }
    }
}