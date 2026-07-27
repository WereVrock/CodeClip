package wv.codeclip.protocol.validate;

import java.util.*;
import wv.codeclip.protocol.model.Command;
import wv.codeclip.protocol.model.CommandType;
import wv.codeclip.protocol.model.ValidationError;
import wv.codeclip.protocol.model.ValidationResult;

/** Detects self-references and cycles among MOVE_AFTER / NEWAFTER commands, per file. */
public final class CycleDetector {

    public ValidationResult detect(List<Command> commandsForFile, String fileName) {
        ValidationResult result = new ValidationResult();

        Map<String, String> edges = new LinkedHashMap<>();
        for (Command c : commandsForFile) {
            if ((c.getType() == CommandType.MOVE_AFTER || c.getType() == CommandType.NEWAFTER)
                    && c.getTargetId() != null && !c.targetsStart()) {

                if (c.getId().equals(c.getTargetId())) {
                    result.add(ValidationError.patchError(
                        "Command " + c.getType() + " references itself as target: '" + c.getId() + "'",
                        c.getId(), fileName));
                    continue;
                }
                edges.put(c.getId(), c.getTargetId());
            }
        }

        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();

        for (String start : edges.keySet()) {
            if (!visited.contains(start)) {
                List<String> path = new ArrayList<>();
                if (hasCycle(start, edges, visited, inStack, path)) {
                    result.add(ValidationError.patchError(
                        "Cycle detected among MOVE_AFTER/NEWAFTER targets: " + String.join(" -> ", path),
                        start, fileName));
                }
            }
        }

        return result;
    }

    private boolean hasCycle(String node, Map<String, String> edges,
                              Set<String> visited, Set<String> inStack, List<String> path) {
        visited.add(node);
        inStack.add(node);
        path.add(node);

        String next = edges.get(node);
        if (next != null) {
            if (inStack.contains(next)) {
                path.add(next);
                return true;
            }
            if (!visited.contains(next) && edges.containsKey(next)) {
                if (hasCycle(next, edges, visited, inStack, path)) {
                    return true;
                }
            }
        }

        inStack.remove(node);
        path.remove(path.size() - 1);
        return false;
    }
}