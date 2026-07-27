package wv.codeclip.protocol.engine;

import wv.codeclip.protocol.library.ProtocolLibrary;
import wv.codeclip.protocol.model.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * Decides whether clipboard text is protocol-tagged content and, if so,
 * routes it through ProtocolEngine end to end. Pure logic — no Swing,
 * no dialogs. The caller supplies an AcceptanceResolver (real UI or
 * accept-all) and callbacks for logging/undo, so this class stays testable
 * and UI-agnostic per project convention.
 */
public final class ProtocolPasteRouter {

    private final ProtocolEngine engine = new ProtocolEngine();
    private final ProtocolLibrary library;
    private final ProtocolUndoManager undoManager;

    public ProtocolPasteRouter(ProtocolLibrary library, ProtocolUndoManager undoManager) {
        this.library = library;
        this.undoManager = undoManager;
    }

    /** True if the text contains at least one @@protocol ... @@protocolEnd block. */
    public static boolean containsProtocolBlock(String text) {
        return text != null && text.contains("@@protocol");
    }

    public static final class RouteOutcome {
        public final ProtocolPatchResult result;
        public final List<String> logLines;
        public final boolean changed;

        RouteOutcome(ProtocolPatchResult result, List<String> logLines, boolean changed) {
            this.result = result;
            this.logLines = logLines;
            this.changed = changed;
        }
    }

    /**
     * Runs the full protocol patch pipeline: parse, validate, resolve
     * acceptance, apply, snapshot for undo, and produce log lines the
     * caller can send wherever it wants (temp log, persistent log, both).
     */
    public RouteOutcome route(String clipboardText, ProtocolEngine.AcceptanceResolver resolver) {
        List<String> logLines = new ArrayList<>();

        try {
            engine.recordPatch(clipboardText);
        } catch (RuntimeException parseError) {
            logLines.add("Protocol paste rejected: " + parseError.getMessage());
            return new RouteOutcome(ProtocolPatchResult.validationFailed(new ValidationResult()), logLines, false);
        }

        List<Command> commands = engine.getRecordedCommands();
        if (commands.isEmpty()) {
            return new RouteOutcome(ProtocolPatchResult.empty(), logLines, false);
        }

        // Snapshot every targeted file's CURRENT raw content before applying,
        // for the separate protocol undo stack.
        Set<String> targetFiles = new LinkedHashSet<>();
        for (Command c : commands) targetFiles.add(c.getTargetFile());

        Map<String, String> preSnapshot = new LinkedHashMap<>();
        for (String fileName : targetFiles) {
            preSnapshot.put(fileName, library.exists(fileName) ? library.load(fileName).render() : null);
        }

        ProtocolPatchResult result = engine.processRecorded(library, resolver);

        boolean changed = result.getStatus() == ProtocolPatchResult.Status.APPLIED;
        if (changed) {
            String title = "Protocol Patch (" + result.getWrittenFiles().size() + " file"
                + (result.getWrittenFiles().size() > 1 ? "s" : "") + ")";
            undoManager.pushUndo(preSnapshot, title);
            logLines.add("── Protocol Patch: " + result.getWrittenFiles().size()
                + " file(s) updated ──");
            for (String line : result.getLog()) logLines.add("  " + line);
        } else {
            logLines.add("Protocol patch not applied (" + result.getStatus() + ")");
            for (ValidationError err : result.getValidation().getErrors()) {
                logLines.add("  ✗ " + err.getMessage());
            }
        }

        return new RouteOutcome(result, logLines, changed);
    }
}