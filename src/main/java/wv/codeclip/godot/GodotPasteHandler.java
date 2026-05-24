package wv.codeclip.godot;

import wv.codeclip.io.ClipboardService;
import wv.codeclip.io.ClassFileWriter;
import wv.codeclip.model.ClassRepository;
import wv.codeclip.patch.PatchApplier;
import wv.codeclip.patch.PatchDuplicateDetector;
import wv.codeclip.patch.PatchErrorDialog;
import wv.codeclip.patch.PatchParser;
import wv.codeclip.patch.PatchUndoManager;
import wv.codeclip.model.PatchChange;
import wv.codeclip.ui.SmartPasteExtractor;
import wv.codeclip.ui.SmartPasteSettings;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handles clipboard paste operations when in Godot mode.
 * GDScript has no package system — scripts are written to GodotDirectory.
 */
public class GodotPasteHandler {

    private final ClassRepository repo;
    private final JFrame parent;
    private final Runnable refreshCallback;
    private final Consumer<String> statusLogger;
    private final BiConsumer<String, String> addPanelCallback;
    private final BiConsumer<String, String> codeChangedCallback;
    private final Supplier<Boolean> multiPatchMode;
    private final PatchUndoManager undoManager;
    private final ClipboardService clipboard = new ClipboardService();
    private final GodotSourceParser godotParser = new GodotSourceParser();
    private final PatchDuplicateDetector duplicateDetector = new PatchDuplicateDetector();
    private wv.codeclip.commands.CopierCommand copierCommand;

    private Consumer<PatchApplier.PatchResult> errorCallback;
    private Consumer<Boolean> postPasteCallback;

    public GodotPasteHandler(
            ClassRepository repo,
            JFrame parent,
            Runnable refreshCallback,
            Consumer<String> statusLogger,
            BiConsumer<String, String> addPanelCallback,
            BiConsumer<String, String> codeChangedCallback,
            Supplier<Boolean> multiPatchMode,
            PatchUndoManager undoManager
    ) {
        this.repo = repo;
        this.parent = parent;
        this.refreshCallback = refreshCallback;
        this.statusLogger = statusLogger;
        this.addPanelCallback = addPanelCallback;
        this.codeChangedCallback = codeChangedCallback;
        this.multiPatchMode = multiPatchMode;
        this.undoManager = undoManager;
        this.copierCommand = new wv.codeclip.commands.CopierCommand(repo, statusLogger);
    }

    public void setErrorCallback(Consumer<PatchApplier.PatchResult> errorCallback) {
        this.errorCallback = errorCallback;
    }

    public void setPostPasteCallback(Consumer<Boolean> callback) {
        this.postPasteCallback = callback;
    }

    // ------------------------------------------------------------------
    // Main entry point
    // ------------------------------------------------------------------

public void handlePasteFromClipboard() {
    String text = clipboard.read();
    if (text == null || text.isBlank()) {
        JOptionPane.showMessageDialog(parent,
                "Clipboard is empty or does not contain text.",
                "Error", JOptionPane.ERROR_MESSAGE);
        return;
    }

    if (text.trim().startsWith("@@Enable")) {
        boolean changed = handleEnable(text.trim());
        firePostPaste(changed);
        return;
    }

    if (text.trim().startsWith("@@Copy")) {
        copierCommand.handle(text.trim());
        return;
    }

    boolean hasPatches     = PatchParser.containsPatch(text);
    boolean hasFileMarkers = GodotScriptExtractor.containsFileMarkers(text);
    boolean hasClassBlocks = SmartPasteExtractor.containsClassBlock(text);

    if (hasPatches || hasFileMarkers || hasClassBlocks) {
        boolean changed = handleSmartPaste(text);
        firePostPaste(changed);
        return;
    }

    if (!looksLikeGdScript(text)) {
        JOptionPane.showMessageDialog(parent,
                "Clipboard does not appear to contain GDScript source code.",
                "Invalid Input", JOptionPane.ERROR_MESSAGE);
        return;
    }

    // Bare GDScript with no markers — prompt for name as last resort
    boolean changed = handleGdScriptPaste(text, null);
    firePostPaste(changed);
}

// ------------------------------------------------------------------
    // GDScript paste
    // ------------------------------------------------------------------

private boolean handleGdScriptPaste(String scriptCode, String knownFileName) {
    if (!GodotDirectory.isSet()) {
        JOptionPane.showMessageDialog(parent,
                "No Godot project directory set.\nUse the directory button to set one.",
                "No Directory", JOptionPane.WARNING_MESSAGE);
        return false;
    }

    String fileName;
    if (knownFileName != null && !knownFileName.isBlank()) {
        fileName = knownFileName.endsWith(".gd") ? knownFileName : knownFileName + ".gd";
    } else {
        String scriptName = parseScriptName(scriptCode);
        if (scriptName == null) return false;
        fileName = scriptName.endsWith(".gd") ? scriptName : scriptName + ".gd";
    }

    File targetDir  = GodotDirectory.get();
    File targetFile = new File(targetDir, fileName);
    boolean isNew   = !targetFile.exists();

    if (!isNew && !SmartPasteSettings.isSkipOverwriteConfirm()) {
        int choice = JOptionPane.showConfirmDialog(parent,
                "Script: " + fileName + "\n\nFile already exists. Overwrite?",
                "Overwrite Script",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return false;
    }

    if (isNew && !SmartPasteSettings.isSkipCreateConfirm()) {
        int choice = JOptionPane.showConfirmDialog(parent,
                "Script: " + fileName + "\n\nTarget directory:\n" +
                targetDir.getAbsolutePath() + "\n\nCreate new file?",
                "Create Script",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return false;
    }

    try {
        Files.writeString(targetFile.toPath(), scriptCode);
    } catch (IOException e) {
        JOptionPane.showMessageDialog(parent,
                "Failed to write script:\n" + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        return false;
    }

    String path = targetFile.getAbsolutePath();
    String oldCode = repo.getClassCodeMap().get(path);
    Map<String, String> snapshot = new LinkedHashMap<>();
    snapshot.put(path, isNew ? null : (oldCode != null ? oldCode : ""));
    undoManager.pushUndo(snapshot, (isNew ? "Script Created: " : "Script Updated: ") + fileName);

    repo.getClassCodeMap().put(path, scriptCode);
    repo.getClassFileMap().put(path, targetFile);
    repo.getDisabledClasses().remove(path);

    refreshCallback.run();

    if (isNew) {
        addPanelCallback.accept(path, fileName);
    }

    if (codeChangedCallback != null) {
        codeChangedCallback.accept(path, scriptCode);
    }

    String logMsg = (isNew ? "Script Created: " : "Script Updated: ") + fileName +
            " (" + targetDir.getAbsolutePath() + ")";
    if (statusLogger != null) statusLogger.accept(logMsg);

    return true;
}

// ------------------------------------------------------------------
    // Patch handling (delegates to shared PatchApplier)
    // ------------------------------------------------------------------

    private boolean handlePatch(String text) {
        String title = PatchParser.extractTitle(text);
        String desc  = PatchParser.extractDesc(text);

        if (duplicateDetector.check(text) == PatchDuplicateDetector.Result.DUPLICATE) {
            int choice = JOptionPane.showConfirmDialog(parent,
                    "This patch looks identical to a recently applied one:\n\n" +
                    (title != null ? "Title: " + title + "\n" : "") +
                    (desc  != null ? "Desc:  " + desc  + "\n" : "") +
                    "\nApply it again?",
                    "Duplicate Patch Detected",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return false;
        }

        List<PatchChange> changes;
        try {
            changes = new wv.codeclip.patch.PatchParser().parse(text);
        } catch (IllegalArgumentException e) {
            PatchErrorDialog.show(parent, "Patch format error:\n\n" + e.getMessage(), null, null);
            return false;
        }

        PatchApplier.PatchResult result = new PatchApplier(repo).apply(changes);
        if (result.hasFailures()) reportError(result);

        if (result.hasSuccesses()) {
            duplicateDetector.record(text);
            undoManager.pushUndo(result.undoSnapshot(), title);
            refreshCallback.run();
            notifyCodeChangedForPatch(changes);

            if (statusLogger != null) {
                List<String> summary = result.successSummary();
                String time = java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                String footer = "─".repeat(32);
                List<String> files = changes.stream().map(PatchChange::fileName).distinct().toList();
                String header = "── Patch [" + time + "] (" + summary.size() +
                        " change" + (summary.size() > 1 ? "s" : "") + ")" +
                        (title != null ? ": " + title : "") + " ──";
                statusLogger.accept(footer);
                for (int i = summary.size() - 1; i >= 0; i--) statusLogger.accept(summary.get(i));
                statusLogger.accept("  Files: " + String.join(", ", files));
                if (desc != null) statusLogger.accept("  " + desc);
                statusLogger.accept(header);
            }
            return true;
        }

        return false;
    }

private boolean handleSmartPaste(String text) {
    List<SmartPasteExtractor.Entry> patchEntries = new SmartPasteExtractor(text).extract(false);
    List<GodotScriptExtractor.ScriptEntry> scriptEntries = GodotScriptExtractor.extract(text);

    List<String> logLines = new ArrayList<>();
    Map<String, String> combinedSnapshot = new LinkedHashMap<>();
    List<String> titles = new ArrayList<>();

    for (SmartPasteExtractor.Entry entry : patchEntries) {
        if (entry instanceof SmartPasteExtractor.PatchEntry pe) {
            handleSmartPatchEntry(pe.text(), logLines, combinedSnapshot, titles);
        }
    }

    for (GodotScriptExtractor.ScriptEntry script : scriptEntries) {
        boolean ok = handleGdScriptPaste(script.code(), script.fileName());
        if (ok) logLines.add("Script pasted: " + script.fileName());
    }

    if (!combinedSnapshot.isEmpty()) {
        String combinedTitle = titles.isEmpty() ? "Smart Paste"
                : titles.size() == 1 ? titles.get(0)
                : titles.get(0) + " (+" + (titles.size() - 1) + " more)";
        undoManager.pushUndo(combinedSnapshot, combinedTitle);
    }

    if (!logLines.isEmpty() && statusLogger != null) {
        String time = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        String footer = "─".repeat(32);
        String header = "── Smart Paste [" + time + "]: " + logLines.size() +
                " change" + (logLines.size() > 1 ? "s" : "") + " ──";
        statusLogger.accept(footer);
        for (int i = logLines.size() - 1; i >= 0; i--) statusLogger.accept(logLines.get(i));
        statusLogger.accept(header);
    }

    return !logLines.isEmpty();
}

private void handleSmartPatchEntry(String patchText, List<String> logLines,
            Map<String, String> combinedSnapshot, List<String> titles) {
        if (duplicateDetector.check(patchText) == PatchDuplicateDetector.Result.DUPLICATE) {
            String t = PatchParser.extractTitle(patchText);
            String d = PatchParser.extractDesc(patchText);
            int choice = JOptionPane.showConfirmDialog(parent,
                    "Duplicate patch detected:\n\n" +
                    (t != null ? "Title: " + t + "\n" : "") +
                    (d != null ? "Desc:  " + d + "\n" : "") +
                    "\nApply it again?",
                    "Duplicate Patch Detected",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        String title = PatchParser.extractTitle(patchText);
        List<PatchChange> changes;
        try {
            changes = new wv.codeclip.patch.PatchParser().parse(patchText);
        } catch (IllegalArgumentException e) {
            PatchErrorDialog.show(parent, "Patch format error:\n\n" + e.getMessage(), null, null);
            return;
        }

        PatchApplier.PatchResult result = new PatchApplier(repo).apply(changes);
        if (result.hasFailures()) reportError(result);
        if (result.hasSuccesses()) {
            duplicateDetector.record(patchText);
            for (Map.Entry<String, String> e : result.undoSnapshot().entrySet()) {
                combinedSnapshot.putIfAbsent(e.getKey(), e.getValue());
            }
            if (title != null) titles.add(title);
            refreshCallback.run();
            notifyCodeChangedForPatch(changes);

            List<String> files = changes.stream().map(PatchChange::fileName).distinct().toList();
            String titleLine = (title != null ? "── " + title + " ──" : "── patch ──");
            logLines.add(titleLine);
            logLines.add("  Files: " + String.join(", ", files));
            for (String s : result.successSummary()) logLines.add(s);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Tries to determine the script name from GDScript source.
     * Looks for: class_name declaration, or an extends line to use as hint,
     * then falls back to prompting the user.
     */
    private String parseScriptName(String code) {
        for (String line : code.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("class_name ")) {
                String name = trimmed.substring("class_name ".length()).trim();
                int space = name.indexOf(' ');
                if (space > 0) name = name.substring(0, space);
                if (!name.isBlank()) return name;
            }
        }
        // Prompt user
        String entered = JOptionPane.showInputDialog(parent,
                "Could not detect class_name in script.\nEnter the script file name (without .gd):",
                "Script Name", JOptionPane.QUESTION_MESSAGE);
        if (entered != null && !entered.isBlank()) {
            return entered.trim().replace(".gd", "");
        }
        return null;
    }

    private boolean looksLikeGdScript(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            return trimmed.startsWith("extends ")
                    || trimmed.startsWith("class_name ")
                    || trimmed.startsWith("func ")
                    || trimmed.startsWith("var ")
                    || trimmed.startsWith("const ")
                    || trimmed.startsWith("signal ")
                    || trimmed.startsWith("@");
        }
        return false;
    }

    private void reportError(PatchApplier.PatchResult result) {
        if (errorCallback != null) errorCallback.accept(result);
        PatchErrorDialog.show(parent, result, repo);
    }

    private void firePostPaste(boolean changed) {
        if (postPasteCallback != null) postPasteCallback.accept(changed);
    }

    private void notifyCodeChangedForPatch(List<PatchChange> changes) {
        for (PatchChange change : changes) {
            for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
                if (entry.getValue().getName().equalsIgnoreCase(change.fileName())) {
                    if (codeChangedCallback != null) {
                        codeChangedCallback.accept(entry.getKey(),
                                repo.getClassCodeMap().get(entry.getKey()));
                    }
                    break;
                }
            }
        }
    }

private boolean handleFileMarkerPaste(String text) {
    List<GodotScriptExtractor.ScriptEntry> scripts = GodotScriptExtractor.extract(text);
    if (scripts.isEmpty()) return false;
    boolean anyChanged = false;
    for (GodotScriptExtractor.ScriptEntry entry : scripts) {
        if (handleGdScriptPaste(entry.code(), entry.fileName())) {
            anyChanged = true;
        }
    }
    return anyChanged;
}

private boolean handleEnable(String text) {
    String arg = text.substring("@@Enable".length()).trim();
    if (arg.isEmpty()) return false;
    String[] parts = arg.split("[,\\s]+");
    java.util.List<String> targets = new java.util.ArrayList<>();
    for (String p : parts) {
        String trimmed = p.trim();
        if (!trimmed.isEmpty()) targets.add(trimmed.toLowerCase());
    }
    if (targets.isEmpty()) return false;
    repo.getDisabledClasses().addAll(repo.getClassCodeMap().keySet());
    java.util.List<String> enabled = new java.util.ArrayList<>();
    for (java.util.Map.Entry<String, java.io.File> entry : repo.getClassFileMap().entrySet()) {
        if (entry.getValue() == null) continue;
        String name = entry.getValue().getName().toLowerCase();
        for (String target : targets) {
            if (name.equals(target) || name.equals(target + ".gd")) {
                repo.getDisabledClasses().remove(entry.getKey());
                enabled.add(entry.getValue().getName());
                break;
            }
        }
    }
    refreshCallback.run();
    if (statusLogger != null) statusLogger.accept("@@Enable: " + String.join(", ", enabled));
    return true;
}

}