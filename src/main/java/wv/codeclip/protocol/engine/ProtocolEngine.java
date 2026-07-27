package wv.codeclip.protocol.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wv.codeclip.protocol.library.ProtocolLibrary;

import wv.codeclip.protocol.model.Command;
import wv.codeclip.protocol.model.ProtocolPatchResult;
import wv.codeclip.protocol.model.ProtocolFile;
import wv.codeclip.protocol.model.ValidationResult;
import wv.codeclip.protocol.parser.AiOutputParser;
import wv.codeclip.protocol.parser.ProtocolFileParser;
import wv.codeclip.protocol.validate.FileValidator;
import wv.codeclip.protocol.validate.PatchValidator;

/**
 * Public facade. Commands from AI output are already file-scoped via the
 * @@protocol filename line, so no external id->file mapping is needed —
 * the engine resolves everything against the ProtocolLibrary directly.
 */
public final class ProtocolEngine {

    private final AiOutputParser aiOutputParser = new AiOutputParser();
    private final PatchValidator patchValidator = new PatchValidator();
    private final FileValidator fileValidator = new FileValidator();
    private final ProtocolApplier applier = new ProtocolApplier();

    private List<Command> recordedCommands = new ArrayList<>();

    public interface AcceptanceResolver {
        /** Returns accepted command keys (ProtocolApplier.commandKey) for this file. */
        Set<String> resolveAccepted(String fileName, ProtocolFile original, List<Command> commandsForFile);
    }

    public void recordPatch(String aiOutput) {
        recordedCommands = aiOutputParser.parse(aiOutput);
    }

    public List<Command> getRecordedCommands() {
        return recordedCommands;
    }

    public ProtocolPatchResult processRecorded(ProtocolLibrary library, AcceptanceResolver acceptanceResolver) {
        if (recordedCommands.isEmpty()) {
            return ProtocolPatchResult.empty();
        }

        Map<String, List<Command>> byFile = new LinkedHashMap<>();
        for (Command c : recordedCommands) {
            byFile.computeIfAbsent(c.getTargetFile(), k -> new ArrayList<>()).add(c);
        }

        boolean masterLocked = library.isMasterLocked();

        Map<String, ProtocolFile> originals = new LinkedHashMap<>();
        ValidationResult combinedPatchValidation = new ValidationResult();

        for (Map.Entry<String, List<Command>> entry : byFile.entrySet()) {
            String fileName = entry.getKey();
            ProtocolFile original = library.loadOrCreate(fileName);
            originals.put(fileName, original);

            ValidationResult vr = patchValidator.validate(fileName, original, entry.getValue(), masterLocked);
            combinedPatchValidation = ValidationResult.merge(combinedPatchValidation, vr);
        }

        if (!combinedPatchValidation.isValid()) {
            return ProtocolPatchResult.validationFailed(combinedPatchValidation);
        }

        Map<String, Set<String>> acceptedByFile = new LinkedHashMap<>();
        for (Map.Entry<String, List<Command>> entry : byFile.entrySet()) {
            String fileName = entry.getKey();
            Set<String> accepted = acceptanceResolver.resolveAccepted(
                fileName, originals.get(fileName), entry.getValue());
            acceptedByFile.put(fileName, accepted);
        }

        boolean anyAccepted = acceptedByFile.values().stream().anyMatch(s -> !s.isEmpty());
        if (!anyAccepted) {
            return ProtocolPatchResult.cancelled();
        }

        Map<String, String> writtenFiles = new LinkedHashMap<>();
        List<String> combinedLog = new ArrayList<>();
        ValidationResult combinedWarnings = new ValidationResult();
        ValidationResult combinedFileValidation = new ValidationResult();

        Map<String, ProtocolFile> appliedResults = new LinkedHashMap<>();

        for (Map.Entry<String, List<Command>> entry : byFile.entrySet()) {
            String fileName = entry.getKey();
            ProtocolFile original = originals.get(fileName);
            Set<String> accepted = acceptedByFile.get(fileName);

            ProtocolApplier.ApplyOutcome outcome = applier.apply(original, entry.getValue(), accepted);
            appliedResults.put(fileName, outcome.result);
            combinedLog.addAll(outcome.log);
            combinedWarnings = ValidationResult.merge(combinedWarnings, outcome.warnings);

            ValidationResult fv = fileValidator.validate(outcome.result);
            combinedFileValidation = ValidationResult.merge(combinedFileValidation, fv);
        }

        if (!combinedFileValidation.isValid()) {
            return ProtocolPatchResult.fileValidationFailed(combinedFileValidation, combinedLog);
        }

        for (Map.Entry<String, ProtocolFile> entry : appliedResults.entrySet()) {
            library.save(entry.getValue());
            writtenFiles.put(entry.getKey(), entry.getValue().render());
        }

        return ProtocolPatchResult.applied(writtenFiles, combinedLog, combinedWarnings);
    }

    /** Standalone validator for a file's raw text — used after hand-edits in the UI. */
    public ValidationResult validateFileContent(String fileName, String fileContent) {
        ProtocolFileParser parser = new ProtocolFileParser();
        ProtocolFile parsed = parser.parse(fileName, fileContent);
        return fileValidator.validate(parsed);
    }
}