package wv.codeclip.protocol.engine;

import java.util.*;
import wv.codeclip.protocol.model.Command;
import wv.codeclip.protocol.model.CommandType;
import wv.codeclip.protocol.model.ProtocolFile;

/** Section 3.1 fallback resolution, re-resolved after the dialog closes, per file. */
public final class FallbackResolver {

    public static final class ResolvedTarget {
        public final String targetId;
        public final boolean isFallback;
        public final boolean shouldWarn;

        ResolvedTarget(String targetId, boolean isFallback, boolean shouldWarn) {
            this.targetId = targetId;
            this.isFallback = isFallback;
            this.shouldWarn = shouldWarn;
        }
    }

    public ResolvedTarget resolve(Command command, ProtocolFile original,
                                   Set<String> acceptedDeletes, Set<String> idsPresentSoFar) {

        String target = command.getTargetId();

        if (target == null || command.targetsStart()) {
            return new ResolvedTarget(null, false, false);
        }

        boolean targetExistsInOriginal = original.containsId(target);
        boolean targetWasDeleted = acceptedDeletes.contains(target);
        boolean targetStillPresent = idsPresentSoFar.contains(target) && !targetWasDeleted;

        if (targetStillPresent) {
            return new ResolvedTarget(target, false, false);
        }

        if (command.getType() == CommandType.NEWAFTER && !targetExistsInOriginal) {
            return new ResolvedTarget(null, true, true);
        }

        if (targetExistsInOriginal && targetWasDeleted) {
            String fallback = findNearestUndeletedPredecessor(original, target, idsPresentSoFar, acceptedDeletes);
            return new ResolvedTarget(fallback, true, false);
        }

        return new ResolvedTarget(null, true, true);
    }

    private String findNearestUndeletedPredecessor(ProtocolFile original, String deletedTargetId,
                                                     Set<String> idsPresentSoFar, Set<String> acceptedDeletes) {
        int idx = original.indexOf(deletedTargetId);
        if (idx < 0) return null;

        for (int i = idx - 1; i >= 0; i--) {
            String candidateId = original.getEntries().get(i).getId();
            boolean candidateDeleted = acceptedDeletes.contains(candidateId);
            boolean candidateStillPresent = idsPresentSoFar.contains(candidateId) && !candidateDeleted;
            if (candidateStillPresent) {
                return candidateId;
            }
        }
        return null;
    }
}