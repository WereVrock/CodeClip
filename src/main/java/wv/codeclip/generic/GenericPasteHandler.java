// ===== GenericPasteHandler.java =====
package wv.codeclip.generic;

import wv.codeclip.io.ClipboardService;
import wv.codeclip.model.ClassRepository;
import wv.codeclip.model.PatchChange;
import wv.codeclip.model.PatchException;
import wv.codeclip.patch.PatchDuplicateDetector;
import wv.codeclip.patch.PatchErrorDialog;
import wv.codeclip.patch.PatchParser;
import wv.codeclip.patch.PatchUndoManager;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Handles clipboard paste operations when in Generic mode (any file type).
 *
 * Mirrors wv.codeclip.html.HtmlPasteHandler exactly, with one deliberate
 * omission: @@METHOD:/@@AFTER_METHOD:/@@INSERT_METHOD: structural targeting
 * is not supported, since there is no per-file-type parser to find named
 * units (element ids, CSS selectors, function names) in an arbitrary file.
 * Those directives fail with a clear PatchException instead of silently
 * doing nothing or guessing.
 *
 * Two entry points, same semantics as HTML mode:
 *   - handlePasteFromClipboard(): single-block paste.
 *   - handleSmartPasteFromClipboard(): extracts and applies every @@PATCH
 *     and #@FileStart/#@FileEnd block found in the clipboard, in document
 *     order, ignoring surrounding chatter.
 */
public class GenericPasteHandler {

    private final ClassRepository repo;
    private final JFrame parent;
    private final Runnable refreshCallback;
    private final Consumer<String> statusLogger;
    private final BiConsumer<String, String> addPanelCallback;
    private final BiConsumer<String, String> codeChangedCallback;
    private final PatchUndoManager undoManager;
    private final ClipboardService clipboard = new ClipboardService();
    private final PatchDuplicateDetector duplicateDetector = new PatchDuplicateDetector();
    private final wv.codeclip.commands.CopierCommand copierCommand;
    private final wv.codeclip.commands.EnablerCommand enablerCommand;
    private final wv.codeclip.commands.MoverCommand moverCommand;
    private final wv.codeclip.commands.DeleterCommand deleterCommand;
    private Consumer<String> removePanelCallback;

    private Consumer<Boolean> postPasteCallback;

    public GenericPasteHandler(
            ClassRepository repo,
            JFrame parent,
            Runnable refreshCallback,
            Consumer<String> statusLogger,
            BiConsumer<String, String> addPanelCallback,
            BiConsumer<String, String> codeChangedCallback,
            PatchUndoManager undoManager
    ) {
        this.repo = repo;
        this.parent = parent;
        this.refreshCallback = refreshCallback;
        this.statusLogger = statusLogger;
        this.addPanelCallback = addPanelCallback;
        this.codeChangedCallback = codeChangedCallback;
        this.undoManager = undoManager;
        this.copierCommand = new wv.codeclip.commands.CopierCommand(repo, statusLogger);
        this.enablerCommand = new wv.codeclip.commands.EnablerCommand(repo, refreshCallback, statusLogger);
        this.moverCommand = new wv.codeclip.commands.MoverCommand(repo, refreshCallback, statusLogger,
                addPanelCallback, path -> { if (removePanelCallback != null) removePanelCallback.accept(path); },
                undoManager);
        this.deleterCommand = new wv.codeclip.commands.DeleterCommand(repo, refreshCallback, statusLogger,
                path -> { if (removePanelCallback != null) removePanelCallback.accept(path); },
                undoManager);
    }

    public void setRemovePanelCallback(Consumer<String> callback) {
        this.removePanelCallback = callback;
    }

    public void setPostPasteCallback(Consumer<Boolean> callback) {
        this.postPasteCallback = callback;
    }

    public void clearDuplicateHistory() {
        duplicateDetector.clearHistory();
    }

    // ------------------------------------------------------------------
    // Entry point 1: single-block paste (Smart Paste checkbox OFF)
    // ------------------------------------------------------------------

    public void handlePasteFromClipboard() {
        String text = clipboard.read();
        if (text == null || text.isBlank()) {
            JOptionPane.showMessageDialog(parent,
                    "Clipboard is empty or does not contain text.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String trimmed = text.trim();
        if (trimmed.startsWith("@@Enable")) {
            enablerCommand.handle(trimmed);
            firePostPaste(false);
            return;
        }
        if (trimmed.startsWith("@@Copy")) {
            copierCommand.handle(trimmed);
            return;
        }
        if (trimmed.startsWith("@@Move")) {
            boolean changed = moverCommand.handle(trimmed, GenericDirectory.isSet() ? GenericDirectory.get() : null);
            firePostPaste(changed);
            return;
        }
        if (trimmed.startsWith("@@Delete")) {
            boolean changed = deleterCommand.handle(trimmed);
            firePostPaste(changed);
            return;
        }

        boolean hasPatch = PatchParser.containsPatch(text);
        boolean hasFileMarkers = GenericScriptExtractor.containsFileMarkers(text);

        if (!hasPatch && !hasFileMarkers) {
            JOptionPane.showMessageDialog(parent,
                    "Clipboard does not appear to contain Generic-mode file markers"
                    + " (#@FileStart:) or a @@PATCH block.",
                    "Invalid Input", JOptionPane.ERROR_MESSAGE);
            return;
        }

        PasteOutcome outcome = new PasteOutcome();

        if (hasPatch) {
            applyPatchBlock(text, outcome);
        }
        if (hasFileMarkers) {
            boolean allowNewFiles = confirmRootForNewFilesIfNeeded();
            for (GenericScriptExtractor.FileEntry entry : GenericScriptExtractor.extract(text)) {
                applyFileEntry(entry, outcome, allowNewFiles);
            }
        }

        finishOutcome(outcome, "Generic Paste");
        firePostPaste(outcome.anySuccess());
    }

    // ------------------------------------------------------------------
    // Entry point 2: Generic mode's own Smart Paste (Smart Paste checkbox ON)
    // ------------------------------------------------------------------

    public void handleSmartPasteFromClipboard() {
        String text = clipboard.read();
        if (text == null || text.isBlank()) {
            JOptionPane.showMessageDialog(parent,
                    "Clipboard is empty or does not contain text.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String trimmed = text.trim();
        if (trimmed.startsWith("@@Enable")) {
            enablerCommand.handle(trimmed);
            firePostPaste(false);
            return;
        }
        if (trimmed.startsWith("@@Copy")) {
            copierCommand.handle(trimmed);
            return;
        }
        if (trimmed.startsWith("@@Move")) {
            boolean changed = moverCommand.handle(trimmed, GenericDirectory.isSet() ? GenericDirectory.get() : null);
            firePostPaste(changed);
            return;
        }
        if (trimmed.startsWith("@@Delete")) {
            boolean changed = deleterCommand.handle(trimmed);
            firePostPaste(changed);
            return;
        }

        List<GenericSmartPasteExtractor.Entry> entries = new GenericSmartPasteExtractor(text).extract();

        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "Smart Paste: no @@PATCH blocks or #@FileStart:/#@FileEnd blocks found.",
                    "Nothing Found", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        PasteOutcome outcome = new PasteOutcome();
        boolean hasFileEntries = entries.stream().anyMatch(e -> e instanceof GenericSmartPasteExtractor.FileEntry);
        boolean allowNewFiles = hasFileEntries && confirmRootForNewFilesIfNeeded();

        for (GenericSmartPasteExtractor.Entry entry : entries) {
            if (entry instanceof GenericSmartPasteExtractor.PatchEntry pe) {
                applyPatchBlock(pe.text(), outcome);
            } else if (entry instanceof GenericSmartPasteExtractor.FileEntry fe) {
                applyFileEntry(new GenericScriptExtractor.FileEntry(fe.relativePath(), fe.code()), outcome, allowNewFiles);
            }
        }

        int patchCount = (int) entries.stream().filter(e -> e instanceof GenericSmartPasteExtractor.PatchEntry).count();
        int fileCount = (int) entries.stream().filter(e -> e instanceof GenericSmartPasteExtractor.FileEntry).count();
        finishOutcome(outcome, "Smart Paste"
                + (patchCount > 0 ? ", " + patchCount + " patch block" + (patchCount > 1 ? "s" : "") : "")
                + (fileCount > 0 ? ", " + fileCount + " file" + (fileCount > 1 ? "s" : "") : ""));

        firePostPaste(outcome.anySuccess());
    }

    // ------------------------------------------------------------------
    // Shared outcome bookkeeping
    // ------------------------------------------------------------------

    private static final class PasteOutcome {
        final List<String> logLines = new ArrayList<>();
        final Map<String, String> combinedSnapshot = new LinkedHashMap<>();
        final List<String> titles = new ArrayList<>();

        boolean anySuccess() {
            return !combinedSnapshot.isEmpty();
        }
    }

    private void finishOutcome(PasteOutcome outcome, String headerLabel) {
        if (!outcome.combinedSnapshot.isEmpty()) {
            String combinedTitle = outcome.titles.isEmpty() ? headerLabel
                    : outcome.titles.size() == 1 ? outcome.titles.get(0)
                    : outcome.titles.get(0) + " (+" + (outcome.titles.size() - 1) + " more)";
            undoManager.pushUndo(outcome.combinedSnapshot, combinedTitle, new ArrayList<>(outcome.titles));
        }

        if (!outcome.logLines.isEmpty() && statusLogger != null) {
            String time = java.time.LocalTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            String footer = "─".repeat(32);
            String header = "── " + headerLabel + " [" + time + "]: " + outcome.logLines.size()
                    + " change" + (outcome.logLines.size() > 1 ? "s" : "") + " ──";
            statusLogger.accept(footer);
            for (int i = outcome.logLines.size() - 1; i >= 0; i--) statusLogger.accept(outcome.logLines.get(i));
            statusLogger.accept(header);
        }
    }

    // ------------------------------------------------------------------
    // File creation / update (whole-file #@FileStart/#@FileEnd format)
    // ------------------------------------------------------------------

    private void applyFileEntry(GenericScriptExtractor.FileEntry entry, PasteOutcome outcome, boolean allowNewFiles) {
        if (!GenericDirectory.isSet()) {
            JOptionPane.showMessageDialog(parent,
                    "No Generic project directory set.\nUse the directory button to set one.",
                    "No Directory", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File root = GenericDirectory.get();
        File targetFile = resolveTargetFile(root, entry.relativePath(), allowNewFiles, outcome);
        if (targetFile == null) {
            return;
        }
        File parentDir = targetFile.getParentFile();
        boolean isNew = !targetFile.exists();

        try {
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    JOptionPane.showMessageDialog(parent,
                            "Failed to create directory:\n" + parentDir.getAbsolutePath(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }

            String path = targetFile.getAbsolutePath();
            String oldCode = repo.getClassCodeMap().get(path);

            Files.writeString(targetFile.toPath(), entry.code());

            outcome.combinedSnapshot.putIfAbsent(path, isNew ? null : (oldCode != null ? oldCode : ""));
            outcome.titles.add((isNew ? "File Created: " : "File Updated: ") + entry.relativePath());
            outcome.logLines.add((isNew ? "File Created: " : "File Updated: ") + entry.relativePath());

            boolean wasAbsent = !repo.getClassCodeMap().containsKey(path);
            repo.getClassCodeMap().put(path, entry.code());
            repo.getClassFileMap().put(path, targetFile);
            repo.getDisabledClasses().remove(path);
            if (!repo.hasCheckpoint(path)) {
                repo.setCheckpoint(path, entry.code());
            }
            repo.recordChange(path,
                    isNew ? ClassRepository.ChangeKind.NEW : ClassRepository.ChangeKind.WHOLE_UPDATE);

            refreshCallback.run();

            if (wasAbsent) {
                addPanelCallback.accept(path, entry.relativePath());
            }
            if (codeChangedCallback != null) {
                codeChangedCallback.accept(path, entry.code());
            }

        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent,
                    "Failed to write file:\n" + entry.relativePath() + "\n\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------
    // Patch handling — @@FIND/@@REPLACE only. @@METHOD:/@@AFTER_METHOD:/
    // @@INSERT_METHOD: are explicitly rejected — Generic mode has no
    // per-file-type structural parser to target a named unit.
    // ------------------------------------------------------------------

    private void applyPatchBlock(String text, PasteOutcome outcome) {

        if (duplicateDetector.check(text) == PatchDuplicateDetector.Result.DUPLICATE) {
            String t = PatchParser.extractTitle(text);
            String d = PatchParser.extractDesc(text);
            int choice = JOptionPane.showConfirmDialog(parent,
                    "This patch looks identical to a recently applied one:\n\n" +
                    (t != null ? "Title: " + t + "\n" : "") +
                    (d != null ? "Desc:  " + d + "\n" : "") +
                    "\nApply it again?",
                    "Duplicate Patch Detected",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        String title = PatchParser.extractTitle(text);
        List<PatchChange> changes;
        try {
            changes = new PatchParser().parse(text);
        } catch (IllegalArgumentException e) {
            String[] parts = PatchParser.splitRawBlock(e.getMessage());
            PatchErrorDialog.show(parent, "Patch format error:\n\n" + parts[0], null, null,
                    parts[1] != null ? parts[1] : text);
            return;
        }

        Map<String, String> workingCode = new HashMap<>();
        Map<String, String> errorsByFile = new LinkedHashMap<>();
        List<String> appliedDescs = new ArrayList<>();
        Map<String, String> resolvedPathByRequestedName = new LinkedHashMap<>();

        for (PatchChange change : changes) {
            String requestedName = change.fileName();
            String path = resolveRelativePath(requestedName);
            if (path == null) {
                errorsByFile.put(requestedName, ambiguityAwareNotFoundMessage(requestedName));
                continue;
            }
            resolvedPathByRequestedName.put(requestedName, path);
            String code = workingCode.getOrDefault(path, repo.getClassCodeMap().get(path));
            File file = repo.getClassFileMap().get(path);
            String actualFileName = file != null ? file.getName() : requestedName;

            try {
                String newCode = applyOneChange(actualFileName, change, code);
                workingCode.put(path, newCode);
                appliedDescs.add(describeChange(change) + " in " + requestedName);
            } catch (PatchException e) {
                errorsByFile.put(requestedName, e.getMessage());
            }
        }

        Set<String> failedPaths = new HashSet<>();
        for (String failedFileName : errorsByFile.keySet()) {
            String path = resolvedPathByRequestedName.get(failedFileName);
            if (path != null) failedPaths.add(path);
        }
        for (String failedPath : failedPaths) {
            workingCode.remove(failedPath);
        }

        if (!errorsByFile.isEmpty()) {
            StringBuilder sb = new StringBuilder("PATCH FAILURES\n==============\n\n");
            for (Map.Entry<String, String> e : errorsByFile.entrySet()) {
                sb.append("File: ").append(e.getKey()).append("\n  ✗ ").append(e.getValue()).append("\n\n");
            }
            if (!workingCode.isEmpty()) {
                sb.append("──────────────────────────────\n");
                sb.append("Successfully applied to:\n");
                for (String path : workingCode.keySet()) {
                    File f = repo.getClassFileMap().get(path);
                    sb.append("  ✓ ").append(f != null ? f.getName() : path).append("\n");
                }
            }
            PatchErrorDialog.show(parent, sb.toString(), errorsByFile, repo, text);
        }

        for (Map.Entry<String, String> entry : workingCode.entrySet()) {
            String path = entry.getKey();
            String finalCode = entry.getValue();
            File file = repo.getClassFileMap().get(path);
            try {
                Files.writeString(file.toPath(), finalCode);
                String previous = repo.getClassCodeMap().get(path);
                outcome.combinedSnapshot.putIfAbsent(path, previous);
                repo.getClassCodeMap().put(path, finalCode);
                repo.getDisabledClasses().remove(path);
                repo.recordChange(path, ClassRepository.ChangeKind.PATCH_UPDATE);
                refreshCallback.run();
                if (codeChangedCallback != null) {
                    codeChangedCallback.accept(path, finalCode);
                }
                outcome.logLines.add("Patched: " + file.getName());
            } catch (IOException e) {
                JOptionPane.showMessageDialog(parent,
                        "Failed to write patched file:\n" + file.getName() + "\n\n" + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        if (!appliedDescs.isEmpty() && errorsByFile.isEmpty()) {
            duplicateDetector.record(text);
        }
        if (title != null && !workingCode.isEmpty()) {
            outcome.titles.add(title);
        }
    }

    /**
     * Dispatches a single PatchChange. Only @@FIND/@@REPLACE is supported in
     * Generic mode — @@METHOD:/@@AFTER_METHOD:/@@INSERT_METHOD: fail with a
     * clear explanation instead of silently doing nothing, since there is no
     * per-file-type structural parser (element id / selector / function name)
     * to fall back on for an arbitrary file type.
     */
    private String applyOneChange(String actualFileName, PatchChange change, String code) throws PatchException {
        return switch (change) {
            case PatchChange.FindReplace fr ->
                applyFindReplaceWithFuzzyFeedback(actualFileName, fr, code);

            case PatchChange.MethodReplace mr -> throw new PatchException(
                    "@@METHOD: is not supported in Generic mode (no structural parser for arbitrary "
                    + "file types). Use @@FIND/@@REPLACE for '" + mr.methodName() + "' in " + actualFileName
                    + ", or resend the whole file.",
                    actualFileName);

            case PatchChange.InsertMethod im -> throw new PatchException(
                    "@@INSERT_METHOD:/@@AFTER_METHOD: is not supported in Generic mode (no structural "
                    + "parser for arbitrary file types). Use @@FIND/@@REPLACE to insert content in "
                    + actualFileName + ", or resend the whole file.",
                    actualFileName);
        };
    }

    private String applyFindReplaceWithFuzzyFeedback(String actualFileName, PatchChange.FindReplace fr, String code)
            throws PatchException {
        GenericStrictPatchApplier.FindReplaceResult result =
                GenericStrictPatchApplier.applyFindReplace(parent, fr, code);

        switch (result.tier()) {
            case FUZZY_HIGH -> {
                if (GenericFuzzySettings.isConfirmHighConfidenceMatches()) {
                    FuzzyMatchDialogGeneric.Decision decision = FuzzyMatchDialogGeneric.show(
                            parent, actualFileName, result.similarityPercent(), fr.find(), result.matchedText(), true);
                    if (decision == FuzzyMatchDialogGeneric.Decision.REJECT) {
                        throw new PatchException(
                                "@@FIND fuzzy match in " + actualFileName + " ("
                                + wv.codeclip.html.HtmlFuzzyMatcher.formatPercent(result.similarityPercent())
                                + "%) was rejected by the user.",
                                actualFileName);
                    }
                    if (statusLogger != null) {
                        statusLogger.accept("Fuzzy matched @@FIND in " + actualFileName + " at "
                                + wv.codeclip.html.HtmlFuzzyMatcher.formatPercent(result.similarityPercent()) + "% — accepted by user");
                    }
                } else if (statusLogger != null) {
                    statusLogger.accept("Fuzzy matched @@FIND in " + actualFileName
                            + " at " + wv.codeclip.html.HtmlFuzzyMatcher.formatPercent(result.similarityPercent()) + "% (no exact match)");
                }
            }
            case FUZZY_LOW -> {
                FuzzyMatchDialogGeneric.Decision decision = FuzzyMatchDialogGeneric.show(
                        parent, actualFileName, result.similarityPercent(), fr.find(), result.matchedText(), false);
                if (decision == FuzzyMatchDialogGeneric.Decision.REJECT) {
                    throw new PatchException(
                            "@@FIND fuzzy match in " + actualFileName + " ("
                            + wv.codeclip.html.HtmlFuzzyMatcher.formatPercent(result.similarityPercent())
                            + "%) was rejected by the user.",
                            actualFileName);
                }
                if (statusLogger != null) {
                    statusLogger.accept("Fuzzy matched @@FIND in " + actualFileName + " at "
                            + wv.codeclip.html.HtmlFuzzyMatcher.formatPercent(result.similarityPercent()) + "% — accepted by user");
                }
            }
            case EXACT -> {
                // nothing extra to report
            }
        }

        return result.newCode();
    }

    private String describeChange(PatchChange change) {
        return switch (change) {
            case PatchChange.FindReplace fr -> "FindReplace";
            case PatchChange.MethodReplace mr -> "MethodReplace (unsupported)";
            case PatchChange.InsertMethod im -> "InsertMethod (unsupported)";
        };
    }

    // ------------------------------------------------------------------
    // File path resolution — identical logic to HtmlPasteHandler
    // ------------------------------------------------------------------

    private String resolveRelativePath(String fileName) {
        String normalized = fileName.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);

        String bareName = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1)
                : normalized;

        List<Map.Entry<String, File>> bareMatches = new ArrayList<>();
        for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
            File f = entry.getValue();
            if (f == null) continue;
            if (f.getName().equalsIgnoreCase(bareName)) {
                bareMatches.add(entry);
            }
        }

        if (bareMatches.isEmpty()) return null;
        if (bareMatches.size() == 1) return bareMatches.get(0).getKey();

        String normalizedSlashed = "/" + normalized;
        List<Map.Entry<String, File>> suffixMatches = new ArrayList<>();
        for (Map.Entry<String, File> entry : bareMatches) {
            String absPath = entry.getKey().replace('\\', '/');
            if (absPath.toLowerCase().endsWith(normalizedSlashed.toLowerCase())) {
                suffixMatches.add(entry);
            }
        }

        if (suffixMatches.size() == 1) return suffixMatches.get(0).getKey();
        return null;
    }

    private String ambiguityAwareNotFoundMessage(String requestedName) {
        String bareName = requestedName.replace('\\', '/');
        bareName = bareName.contains("/") ? bareName.substring(bareName.lastIndexOf('/') + 1) : bareName;

        int sameBareNameCount = 0;
        for (File f : repo.getClassFileMap().values()) {
            if (f != null && f.getName().equalsIgnoreCase(bareName)) sameBareNameCount++;
        }
        if (sameBareNameCount > 1) {
            return "Ambiguous filename — " + sameBareNameCount + " loaded files are named \""
                    + bareName + "\". Use a path that includes enough parent folders to be unique, e.g. \"src/"
                    + bareName + "\".";
        }
        return "File not found in loaded Generic-mode classes: " + requestedName;
    }

    private void firePostPaste(boolean changed) {
        if (postPasteCallback != null) postPasteCallback.accept(changed);
        // Note: PostPatchVerifier's compile/overload checks are Java-source
        // specific (parses method declarations) and are intentionally not
        // run for Generic mode, which may hold non-Java files.
    }

    /**
     * Resolves the on-disk target for a #@FileStart:/#@FileEnd write.
     * Identical strategy to HtmlPasteHandler.resolveTargetFile.
     */
    private File resolveTargetFile(File root, String relativePath, boolean allowNewFiles, PasteOutcome outcome) {
        String bareName = relativePath.contains("/")
                ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
                : relativePath;

        List<Map.Entry<String, File>> bareMatches = new ArrayList<>();
        for (Map.Entry<String, File> e : repo.getClassFileMap().entrySet()) {
            File f = e.getValue();
            if (f != null && f.getName().equalsIgnoreCase(bareName)) {
                bareMatches.add(e);
            }
        }

        if (bareMatches.isEmpty()) {
            if (!allowNewFiles) {
                String msg = "Skipped Creating (project directory unconfirmed): " + relativePath;
                outcome.logLines.add(msg);
                if (statusLogger != null) statusLogger.accept(msg);
                return null;
            }
            return new File(root, relativePath);
        }

        if (bareMatches.size() == 1) {
            return bareMatches.get(0).getValue();
        }

        String suffix = "/" + relativePath;
        List<Map.Entry<String, File>> suffixMatches = new ArrayList<>();
        for (Map.Entry<String, File> e : bareMatches) {
            String abs = e.getKey().replace('\\', '/');
            if (abs.toLowerCase().endsWith(suffix.toLowerCase())) {
                suffixMatches.add(e);
            }
        }

        if (suffixMatches.size() == 1) {
            return suffixMatches.get(0).getValue();
        }

        StringBuilder msg = new StringBuilder();
        msg.append("\"").append(relativePath)
           .append("\" matches ").append(bareMatches.size())
           .append(" already-loaded files at different locations:\n\n");
        for (Map.Entry<String, File> e : bareMatches) {
            msg.append("  \u2022 ").append(e.getKey()).append("\n");
        }
        msg.append("\nCodeClip can't safely tell which one to update, ")
           .append("so this file write was skipped to avoid creating another copy.\n")
           .append("Remove or consolidate the duplicates and try again.");
        JOptionPane.showMessageDialog(parent, msg.toString(),
                "Duplicate File Detected", JOptionPane.WARNING_MESSAGE);
        return null;
    }

    private boolean confirmRootForNewFilesIfNeeded() {
        if (!GenericDirectory.isSet()) return true;
        if (repo.getClassFileMap().isEmpty()) return true;

        File root = GenericDirectory.get();
        String rootPath = root.getAbsolutePath();
        for (File f : repo.getClassFileMap().values()) {
            if (f == null) continue;
            File parentDir = f.getParentFile();
            String parentPath = parentDir != null ? parentDir.getAbsolutePath() : null;
            if (parentPath != null && (parentPath.equals(rootPath) || parentPath.startsWith(rootPath + File.separator))) {
                return true;
            }
        }

        int choice = JOptionPane.showConfirmDialog(parent,
                "The current Generic project directory is:\n" + rootPath + "\n\n" +
                "None of the files already loaded in this session live under that directory — " +
                "it may be stale (set from a different project, or changed since these files were loaded).\n\n" +
                "If you continue, any brand-new files in this paste will be written under that directory, " +
                "which may not be where you expect.\n\n" +
                "Continue and create new files there anyway?",
                "Project Directory May Be Stale",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }
}