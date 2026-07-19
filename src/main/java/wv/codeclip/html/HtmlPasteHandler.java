package wv.codeclip.html;

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
import java.util.function.Supplier;

/**
 * Handles clipboard paste operations when in HTML mode (HTML + CSS + JS).
 *
 * Two entry points:
 *   - handlePasteFromClipboard(): single-block paste (one @@PATCH or one
 *     #@FileStart/#@FileEnd block), same behavior as before.
 *   - handleSmartPasteFromClipboard(): HTML mode's own Smart Paste — extracts
 *     and applies every @@PATCH and #@FileStart/#@FileEnd block found in the
 *     clipboard, in document order, ignoring all surrounding chatter. This
 *     never delegates to wv.codeclip.io.PasteClassHandler; HTML mode is fully
 *     self-contained.
 *
 * Patch directives supported (all strict-match, no whitespace tolerance):
 *   @@FIND: / @@REPLACE:            — exact substring match
 *   @@METHOD: <name> / @@REPLACE:   — replaces a named structural unit:
 *                                      HTML: element with id="<name>"
 *                                      CSS:  rule with selector <name>
 *                                      JS:   function named <name>
 *   @@AFTER_METHOD: <name> / @@INSERT_METHOD: — inserts after a named unit
 *   @@INSERT_METHOD: (standalone)   — inserts at the file's default position
 *                                      (before </body> for HTML, end of file otherwise)
 */
public class HtmlPasteHandler {

    private final ClassRepository repo;
    private final JFrame parent;
    private final Runnable refreshCallback;
    private final Consumer<String> statusLogger;
    private final BiConsumer<String, String> addPanelCallback;
    private final BiConsumer<String, String> codeChangedCallback;
    private final PatchUndoManager undoManager;
    private final ClipboardService clipboard = new ClipboardService();
    private final PatchDuplicateDetector duplicateDetector = new PatchDuplicateDetector();

    private Consumer<Boolean> postPasteCallback;
    private Consumer<List<wv.codeclip.patch.PatchApplier.PatchResult>> batchErrorCallback;

    public HtmlPasteHandler(
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

        boolean hasPatch = PatchParser.containsPatch(text);
        boolean hasFileMarkers = HtmlScriptExtractor.containsFileMarkers(text);

        if (!hasPatch && !hasFileMarkers) {
            JOptionPane.showMessageDialog(parent,
                    "Clipboard does not appear to contain HTML-mode file markers"
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
            for (HtmlScriptExtractor.FileEntry entry : HtmlScriptExtractor.extract(text)) {
                applyFileEntry(entry, outcome, allowNewFiles);
            }
        }

        finishOutcome(outcome, "HTML Paste");
        firePostPaste(outcome.anySuccess());
    }

// ------------------------------------------------------------------
    // Entry point 2: HTML mode's own Smart Paste (Smart Paste checkbox ON)
    // ------------------------------------------------------------------

public void handleSmartPasteFromClipboard() {
        String text = clipboard.read();
        if (text == null || text.isBlank()) {
            JOptionPane.showMessageDialog(parent,
                    "Clipboard is empty or does not contain text.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<HtmlSmartPasteExtractor.Entry> entries = new HtmlSmartPasteExtractor(text).extract();

        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "Smart Paste: no @@PATCH blocks or #@FileStart:/#@FileEnd blocks found.",
                    "Nothing Found", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        PasteOutcome outcome = new PasteOutcome();
        boolean hasFileEntries = entries.stream().anyMatch(e -> e instanceof HtmlSmartPasteExtractor.FileEntry);
        boolean allowNewFiles = hasFileEntries && confirmRootForNewFilesIfNeeded();

        for (HtmlSmartPasteExtractor.Entry entry : entries) {
            if (entry instanceof HtmlSmartPasteExtractor.PatchEntry pe) {
                applyPatchBlock(pe.text(), outcome);
            } else if (entry instanceof HtmlSmartPasteExtractor.FileEntry fe) {
                applyFileEntry(new HtmlScriptExtractor.FileEntry(fe.relativePath(), fe.code()), outcome, allowNewFiles);
            }
        }

        int patchCount = (int) entries.stream().filter(e -> e instanceof HtmlSmartPasteExtractor.PatchEntry).count();
        int fileCount = (int) entries.stream().filter(e -> e instanceof HtmlSmartPasteExtractor.FileEntry).count();
        finishOutcome(outcome, "Smart Paste"
                + (patchCount > 0 ? ", " + patchCount + " patch block" + (patchCount > 1 ? "s" : "") : "")
                + (fileCount > 0 ? ", " + fileCount + " file" + (fileCount > 1 ? "s" : "") : ""));

        firePostPaste(outcome.anySuccess());
    }

// ------------------------------------------------------------------
    // Shared outcome bookkeeping — collects per-block results so multiple
    // independent blocks (and independent files within one @@PATCH) never
    // get conflated, and every failure surfaces while every success still
    // applies.
    // ------------------------------------------------------------------

    private static final class PasteOutcome {
        final List<String> logLines = new ArrayList<>();
        final Map<String, String> combinedSnapshot = new LinkedHashMap<>();
        final List<String> titles = new ArrayList<>();
        final List<wv.codeclip.patch.PatchApplier.PatchResult> failedResults = new ArrayList<>();

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

    private boolean isNewFile(String relativePath) {
        if (!HtmlDirectory.isSet()) return true;
        File target = new File(HtmlDirectory.get(), relativePath);
        return !target.exists();
    }

private void applyFileEntry(HtmlScriptExtractor.FileEntry entry, PasteOutcome outcome, boolean allowNewFiles) {
        if (!HtmlDirectory.isSet()) {
            JOptionPane.showMessageDialog(parent,
                    "No HTML project directory set.\nUse the directory button to set one.",
                    "No Directory", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File root = HtmlDirectory.get();
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
    // Patch handling — @@FIND/@@REPLACE (strict) and @@METHOD:/@@AFTER_METHOD:/
    // @@INSERT_METHOD: (structural, strict).
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
            PatchErrorDialog.show(parent, "Patch format error:\n\n" + e.getMessage(), null, null);
            return;
        }

        // Each entry keyed by the requested @@FILE: name (not resolved path) so
        // every change — including unresolved/ambiguous ones — keeps its own
        // distinct error and nothing gets silently merged or dropped.
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

        // Don't write any file whose resolved path had at least one failed change.
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
            PatchErrorDialog.show(parent, sb.toString(), errorsByFile, repo);
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
     * Dispatches a single PatchChange to the correct strict handler based on
     * its directive type. @@FIND/@@REPLACE goes through StrictPatchApplier;
     * @@METHOD:/@@AFTER_METHOD:/@@INSERT_METHOD: go through HtmlStructuralPatcher.
     */

private String applyOneChange(String actualFileName, PatchChange change, String code) throws PatchException {
        return switch (change) {
            case PatchChange.FindReplace fr ->
                applyFindReplaceWithFuzzyFeedback(actualFileName, fr, code);

            case PatchChange.MethodReplace mr ->
                HtmlStructuralPatcher.applyMethodReplace(actualFileName, code, mr.methodName(), mr.replace());

            case PatchChange.InsertMethod im -> {
                if (im.afterMethod() != null) {
                    yield HtmlStructuralPatcher.applyInsertAfter(actualFileName, code, im.afterMethod(), im.code());
                }
                // Standalone insert — check for an existing unit with the same
                // name first, mirroring Java mode's duplicate-detection behavior.
                HtmlStructuralPatcher.ExistingMatch existing =
                        HtmlStructuralPatcher.findExistingForInsert(actualFileName, code, im.code());
                if (existing != null) {
                    if (existing.identicalToIncoming()) {
                        yield code; // identical — silently no-op
                    }
                    throw new PatchException(
                            "@@INSERT_METHOD: target named '" + existing.name() + "' already exists in "
                            + actualFileName + " with different content. Use @@METHOD: " + existing.name()
                            + " / @@REPLACE: to overwrite it, or rename the new one.",
                            actualFileName);
                }
                yield HtmlStructuralPatcher.applyInsertDefault(actualFileName, code, im.code());
            }
        };
    }

/**
     * Applies a strict-then-fuzzy @@FIND/@@REPLACE and surfaces the outcome:
     * a high-confidence fuzzy match (>=95%) is just logged, while a
     * lower-confidence one (30%-95%) also pops an informational dialog so
     * the user can double-check what was actually matched before trusting it.
     */

private String applyFindReplaceWithFuzzyFeedback(String actualFileName, PatchChange.FindReplace fr, String code)
            throws PatchException {
        StrictPatchApplier.FindReplaceResult result = StrictPatchApplier.applyFindReplace(parent, fr, code);

        switch (result.tier()) {
            case FUZZY_HIGH -> {
                if (HtmlFuzzySettings.isConfirmHighConfidenceMatches()) {
                    FuzzyMatchDialog.Decision decision = FuzzyMatchDialog.show(
                            parent, actualFileName, result.similarityPercent(), fr.find(), result.matchedText(), true);
                    if (decision == FuzzyMatchDialog.Decision.REJECT) {
                        throw new PatchException(
                                "@@FIND fuzzy match in " + actualFileName + " ("
                                + HtmlFuzzyMatcher.formatPercent(result.similarityPercent())
                                + "%) was rejected by the user.",
                                actualFileName);
                    }
                    if (statusLogger != null) {
                        statusLogger.accept("Fuzzy matched @@FIND in " + actualFileName + " at "
                                + HtmlFuzzyMatcher.formatPercent(result.similarityPercent()) + "% — accepted by user");
                    }
                } else if (statusLogger != null) {
                    statusLogger.accept("Fuzzy matched @@FIND in " + actualFileName
                            + " at " + HtmlFuzzyMatcher.formatPercent(result.similarityPercent()) + "% (no exact match)");
                }
            }
            case FUZZY_LOW -> {
                FuzzyMatchDialog.Decision decision = FuzzyMatchDialog.show(
                        parent, actualFileName, result.similarityPercent(), fr.find(), result.matchedText(), false);
                if (decision == FuzzyMatchDialog.Decision.REJECT) {
                    throw new PatchException(
                            "@@FIND fuzzy match in " + actualFileName + " ("
                            + HtmlFuzzyMatcher.formatPercent(result.similarityPercent())
                            + "%) was rejected by the user.",
                            actualFileName);
                }
                if (statusLogger != null) {
                    statusLogger.accept("Fuzzy matched @@FIND in " + actualFileName + " at "
                            + HtmlFuzzyMatcher.formatPercent(result.similarityPercent()) + "% — accepted by user");
                }
            }
            case EXACT -> {
                // Exact match — nothing extra to report.
            }
        }

        return result.newCode();
    }

private String describeChange(PatchChange change) {
        return switch (change) {
            case PatchChange.FindReplace fr -> "FindReplace";
            case PatchChange.MethodReplace mr -> "MethodReplace '" + mr.methodName() + "'";
            case PatchChange.InsertMethod im -> im.afterMethod() != null
                    ? "InsertMethod after '" + im.afterMethod() + "'"
                    : "InsertMethod (default position)";
        };
    }

    // ------------------------------------------------------------------
    // File path resolution — fixed to disambiguate basename collisions by
    // path suffix instead of matching the first same-named file found.
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
                    + bareName + "\". Use a path that includes enough parent folders to be unique, e.g. \"css/"
                    + bareName + "\".";
        }
        return "File not found in loaded HTML classes: " + requestedName;
    }

    private void firePostPaste(boolean changed) {
        if (postPasteCallback != null) postPasteCallback.accept(changed);
        if (changed) {
            wv.codeclip.patch.PostPatchVerifier.verify(repo, parent, statusLogger);
        }
    }

/**
     * Resolves the on-disk target for a #@FileStart:/#@FileEnd write.
     * Prefers a file that is already tracked in the repo and whose path ends
     * with this relative path (so an existing file gets updated in place even
     * if HtmlDirectory's root has drifted from where the project was actually
     * loaded), falling back to root + relativePath only when no such file is
     * already tracked. Returns null (after showing a warning) if the relative
     * path matches more than one already-loaded file and can't be disambiguated —
     * mirrors resolveRelativePath's logic but is also usable for genuinely-new files.
     */
    private File resolveTargetFile(File root, String relativePath) {
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

/**
     * If the HTML project directory doesn't contain any file already loaded
     * in this session, that's a strong signal the directory has drifted from
     * the project that's actually open — creating a brand-new file there
     * would silently write it to the wrong place while still reporting
     * success. Asked once per paste operation; has no effect on updates to
     * already-tracked files, which resolve by tracked path regardless of
     * root correctness.
     */
    private boolean confirmRootForNewFilesIfNeeded() {
        if (!HtmlDirectory.isSet()) return true;
        if (repo.getClassFileMap().isEmpty()) return true;

        File root = HtmlDirectory.get();
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
                "The current HTML project directory is:\n" + rootPath + "\n\n" +
                "None of the files already loaded in this session live under that directory — " +
                "it may be stale (set from a different project, or changed since these files were loaded).\n\n" +
                "If you continue, any brand-new files in this paste will be written under that directory, " +
                "which may not be where you expect.\n\n" +
                "Continue and create new files there anyway?",
                "Project Directory May Be Stale",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    /**
     * Resolves the on-disk target for a #@FileStart:/#@FileEnd write.
     *
     * Prefers a file already tracked in the repo whose path ends with this
     * relative path, so an existing file gets updated in place even if
     * HtmlDirectory's root has drifted. Falls back to root + relativePath
     * only for a genuinely new file — and when falling back, honors
     * allowNewFiles so a stale/unconfirmed root can't silently create files
     * in the wrong place while still reporting success.
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

}