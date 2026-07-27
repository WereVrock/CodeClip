package wv.codeclip.protocol.engine;

import java.util.*;
import wv.codeclip.protocol.model.Command;
import wv.codeclip.protocol.model.CommandType;
import wv.codeclip.protocol.model.ProtocolEntry;
import wv.codeclip.protocol.model.ProtocolFile;
import wv.codeclip.protocol.model.ValidationError;
import wv.codeclip.protocol.model.ValidationResult;

/**
 * Applies the accepted subset of commands (all scoped to one file) to a
 * working copy of that file, in deterministic order:
 *   1. NEW / NEWAFTER
 *   2. UPDATE / APPENDTO
 *   3. Resolve MOVE_AFTER targets that depended on a deleted id
 *   4. MOVE_AFTER
 *   5. DELETE
 */
public final class ProtocolApplier {

    private final FallbackResolver fallbackResolver = new FallbackResolver();

    public static final class ApplyOutcome {
        public final ProtocolFile result;
        public final List<String> log;
        public final ValidationResult warnings;

        ApplyOutcome(ProtocolFile result, List<String> log, ValidationResult warnings) {
            this.result = result;
            this.log = log;
            this.warnings = warnings;
        }
    }

    public ApplyOutcome apply(ProtocolFile original, List<Command> commandsForFile, Set<String> acceptedKeys) {
        ProtocolFile working = original.deepCopy();
        List<String> log = new ArrayList<>();
        ValidationResult warnings = new ValidationResult();

        List<Command> accepted = new ArrayList<>();
        for (Command c : commandsForFile) {
            if (acceptedKeys.contains(commandKey(c))) accepted.add(c);
        }

        Set<String> acceptedDeletes = new HashSet<>();
        for (Command c : accepted) {
            if (c.getType() == CommandType.DELETE) acceptedDeletes.add(c.getId());
        }

        List<Command> newCommands = filterByType(accepted, CommandType.NEW);
        List<Command> newAfterCommands = filterByType(accepted, CommandType.NEWAFTER);

        for (Command c : newCommands) {
            ProtocolEntry entry = new ProtocolEntry(c.getId(), c.getContentLines(), -1);
            working.getEntries().add(entry);
            log.add("NEW '" + c.getId() + "' appended at end of " + working.getFileName());
        }

        Set<String> idsPresentSoFar = currentIds(working);

        for (Command c : newAfterCommands) {
            FallbackResolver.ResolvedTarget resolved =
                fallbackResolver.resolve(c, original, acceptedDeletes, idsPresentSoFar);

            ProtocolEntry entry = new ProtocolEntry(c.getId(), c.getContentLines(), -1);

            if (resolved.targetId == null) {
                working.getEntries().add(entry);
                log.add("NEWAFTER '" + c.getId() + "' " +
                    (resolved.isFallback ? "fell back to end-of-file" : "inserted at START") +
                    (resolved.shouldWarn ? " [WARNING: target not found]" : ""));
                if (resolved.shouldWarn) {
                    warnings.add(ValidationError.patchWarning(
                        "NEWAFTER target not found for '" + c.getId() + "', appended at end",
                        c.getId(), working.getFileName()));
                }
            } else {
                int idx = indexOfId(working, resolved.targetId);
                int insertAt = (idx >= 0) ? idx + 1 : working.getEntries().size();
                working.getEntries().add(insertAt, entry);
                log.add("NEWAFTER '" + c.getId() + "' inserted after '" + resolved.targetId + "'"
                    + (resolved.isFallback ? " (fallback target)" : ""));
            }
            idsPresentSoFar = currentIds(working);
        }

        for (Command c : filterByType(accepted, CommandType.UPDATE)) {
            working.findById(c.getId()).ifPresent(e -> {
                e.setContentLines(c.getContentLines());
                log.add("UPDATE applied to '" + c.getId() + "'");
            });
        }
        for (Command c : filterByType(accepted, CommandType.APPENDTO)) {
            working.findById(c.getId()).ifPresent(e -> {
                List<String> merged = new ArrayList<>(e.getContentLines());
                merged.addAll(c.getContentLines());
                e.setContentLines(merged);
                log.add("APPENDTO applied to '" + c.getId() + "'");
            });
        }

        idsPresentSoFar = currentIds(working);

        List<Command> moveCommands = filterByType(accepted, CommandType.MOVE_AFTER);
        Map<Command, FallbackResolver.ResolvedTarget> resolvedMoves = new LinkedHashMap<>();
        for (Command c : moveCommands) {
            FallbackResolver.ResolvedTarget resolved =
                fallbackResolver.resolve(c, original, acceptedDeletes, idsPresentSoFar);
            resolvedMoves.put(c, resolved);
            if (resolved.isFallback) {
                log.add("MOVE_AFTER '" + c.getId() + "' target '" + c.getTargetId()
                    + "' was deleted -> resolved to "
                    + (resolved.targetId == null ? "START" : "'" + resolved.targetId + "'"));
            }
        }

        for (Map.Entry<Command, FallbackResolver.ResolvedTarget> e : resolvedMoves.entrySet()) {
            Command c = e.getKey();
            FallbackResolver.ResolvedTarget resolved = e.getValue();

            int sourceIdx = indexOfId(working, c.getId());
            if (sourceIdx < 0) {
                log.add("MOVE_AFTER '" + c.getId() + "' skipped: source no longer present");
                continue;
            }
            ProtocolEntry movingEntry = working.getEntries().remove(sourceIdx);

            if (resolved.targetId == null) {
                working.getEntries().add(0, movingEntry);
                log.add("MOVE_AFTER '" + c.getId() + "' moved to START");
            } else {
                int targetIdx = indexOfId(working, resolved.targetId);
                int insertAt = (targetIdx >= 0) ? targetIdx + 1 : working.getEntries().size();
                working.getEntries().add(insertAt, movingEntry);
                log.add("MOVE_AFTER '" + c.getId() + "' moved after '" + resolved.targetId + "'");
            }
        }

        for (Command c : filterByType(accepted, CommandType.DELETE)) {
            int idx = indexOfId(working, c.getId());
            if (idx >= 0) {
                working.getEntries().remove(idx);
                log.add("DELETE applied to '" + c.getId() + "'");
            }
        }

        return new ApplyOutcome(working, log, warnings);
    }

    public static String commandKey(Command c) {
        return c.getType() + "|" + c.getTargetFile() + "|" + c.getId() + "|" + c.getSourceLineNumber();
    }

    private List<Command> filterByType(List<Command> commands, CommandType type) {
        List<Command> out = new ArrayList<>();
        for (Command c : commands) if (c.getType() == type) out.add(c);
        return out;
    }

    private Set<String> currentIds(ProtocolFile file) {
        Set<String> ids = new HashSet<>();
        for (ProtocolEntry e : file.getEntries()) ids.add(e.getId());
        return ids;
    }

    private int indexOfId(ProtocolFile file, String id) {
        return file.indexOf(id);
    }
}