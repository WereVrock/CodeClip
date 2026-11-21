package wv.codeclip.io;

import wv.codeclip.parse.JavaSourceParser;
import wv.codeclip.parse.JavaBraceEndChecker;
import wv.codeclip.parse.MissingMethodDetector;
import wv.codeclip.parse.SourceRootDetector;
import wv.codeclip.patch.PatchErrorDialog;
import wv.codeclip.patch.PatchApplier;
import wv.codeclip.patch.PatchParser;
import wv.codeclip.model.PatchChange;
import wv.codeclip.model.ClassRepository;
import wv.codeclip.patch.MultiPatchExtractor;
import javax.swing.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import wv.codeclip.patch.PatchDuplicateDetector;
import wv.codeclip.ui.SmartPasteExtractor;
import wv.codeclip.ui.SmartPasteSettings;

public class PasteClassHandler {

private final ClassRepository repo;
private final JFrame parent;
private java.util.function.Consumer<List<PatchApplier.PatchResult>> errorCallback;
private final Runnable refreshCallback;
private final java.util.function.Consumer<String> statusLogger;
private final BiConsumer<String, String> addPanelCallback;

private final ClipboardService clipboard;
private final JavaSourceParser parser;
private final SourceRootDetector rootDetector;
private final ClassFileWriter fileWriter;
private final wv.codeclip.io.SettingsManager settingsManager;
private final BiConsumer<String, String> codeChangedCallback;
private final Supplier<Boolean> multiPatchMode;
private final PatchDuplicateDetector duplicateDetector = new PatchDuplicateDetector();
private final java.util.concurrent.atomic.AtomicBoolean pasting = new java.util.concurrent.atomic.AtomicBoolean(false);
private final wv.codeclip.patch.PatchUndoManager undoManager;
private final wv.codeclip.commands.CopierCommand copierCommand;
private final wv.codeclip.commands.EnablerCommand enablerCommand;
private java.util.function.Consumer<Boolean> postPasteCallback;
private PatchApplier.InsertConflictResolver conflictResolver;

private static final int CLASS_NAME_WRAP_LENGTH = 40;

public PasteClassHandler(
ClassRepository repo,
JFrame parent,
Runnable refreshCallback,
java.util.function.Consumer<String> statusLogger,
BiConsumer<String, String> addPanelCallback
) {
this(repo, parent, refreshCallback, statusLogger, addPanelCallback, null, () -> false, new wv.codeclip.patch.PatchUndoManager());
}

public PasteClassHandler(
ClassRepository repo,
JFrame parent,
Runnable refreshCallback,
java.util.function.Consumer<String> statusLogger,
BiConsumer<String, String> addPanelCallback,
BiConsumer<String, String> codeChangedCallback
) {
this(repo, parent, refreshCallback, statusLogger, addPanelCallback, codeChangedCallback, () -> false, new wv.codeclip.patch.PatchUndoManager());
}

public PasteClassHandler(
ClassRepository repo,
JFrame parent,
Runnable refreshCallback,
java.util.function.Consumer<String> statusLogger,
BiConsumer<String, String> addPanelCallback,
BiConsumer<String, String> codeChangedCallback,
Supplier<Boolean> multiPatchMode,
wv.codeclip.patch.PatchUndoManager undoManager
) {
this.repo = repo;
this.parent = parent;
this.refreshCallback = refreshCallback;
this.statusLogger = statusLogger;
this.addPanelCallback = addPanelCallback;
this.codeChangedCallback = codeChangedCallback;
this.multiPatchMode = multiPatchMode;
this.errorCallback = null;
this.undoManager = undoManager;
this.copierCommand = new wv.codeclip.commands.CopierCommand(repo, statusLogger);
this.enablerCommand = new wv.codeclip.commands.EnablerCommand(repo, refreshCallback, statusLogger);
this.settingsManager = new wv.codeclip.io.SettingsManager();

this.clipboard = new ClipboardService();
this.parser = new JavaSourceParser();
this.rootDetector = new SourceRootDetector(repo, parent, parser, settingsManager);
this.fileWriter = new ClassFileWriter(repo);
}

// ------------------------------------------------------------------
// Main entry point
// ------------------------------------------------------------------

public void handlePasteFromClipboard() {
if (!pasting.compareAndSet(false, true)) {
// A paste is already in progress — ignore the duplicate invocation.
return;
}
try {
handlePasteFromClipboardInternal();
} finally {
pasting.set(false);
}
}

private void handlePasteFromClipboardInternal() {
    String text = clipboard.read();
    if (text == null || text.isBlank()) {
        JOptionPane.showMessageDialog(
            parent,
            "Clipboard is empty or does not contain text.",
            "Error",
            JOptionPane.ERROR_MESSAGE
        );
        return;
    }

    try {
        if (wv.codeclip.commands.CommandArgs.hasCommand(text, "Enable")) {
            handleEnable(wv.codeclip.commands.CommandArgs.sliceFromCommand(text, "Enable"));
            firePostPaste(false);
            return;
        }

        if (wv.codeclip.commands.CommandArgs.hasCommand(text, "Copy")) {
            copierCommand.handle(wv.codeclip.commands.CommandArgs.sliceFromCommand(text, "Copy"));
            return;
        }

        // New-file blocks always go through the smart path, even when the
        // Smart Paste checkbox is off: they are extracted first and the
        // remaining text (patches, class blocks) is handled as usual.
        boolean hasNewFile = wv.codeclip.ui.NewFileExtractor.containsNewFile(text);

        if (hasNewFile || (Boolean.TRUE.equals(multiPatchMode.get()) &&
            (PatchParser.containsPatch(text) || looksLikePatch(text) || SmartPasteExtractor.containsClassBlock(text)))) {
            boolean changed = handleSmartPaste(text);
            firePostPaste(changed);
            return;
        }

        if (PatchParser.containsPatch(text) || looksLikePatch(text)) {
            boolean changed = handlePatch(text);
            firePostPaste(changed);
            return;
        }

        if (!looksLikeJavaSource(text)) {
            JOptionPane.showMessageDialog(
                parent,
                "Clipboard does not appear to contain Java source code.",
                "Invalid Input",
                JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        handlePaste(text);
        firePostPaste(true);
    } catch (Exception ex) {
        ex.printStackTrace();
        JOptionPane.showMessageDialog(
            parent,
            "Paste failed unexpectedly:\n\n" + ex.getClass().getSimpleName()
                + (ex.getMessage() != null ? ": " + ex.getMessage() : "")
                + "\n\nNothing was applied from this paste. Check the console/log for a stack trace.",
            "Paste Failed",
            JOptionPane.ERROR_MESSAGE
        );
    }
}

// ------------------------------------------------------------------
// Patch handling
// ------------------------------------------------------------------

public void setErrorCallback(java.util.function.Consumer<List<PatchApplier.PatchResult>> errorCallback) {
this.errorCallback = errorCallback;
}

public void setConflictResolver(PatchApplier.InsertConflictResolver resolver) {
this.conflictResolver = resolver;
}

public void setPostPasteCallback(java.util.function.Consumer<Boolean> callback) {
this.postPasteCallback = callback;
}

private void firePostPaste(boolean changed) {
if (postPasteCallback != null) postPasteCallback.accept(changed);
if (changed) {
wv.codeclip.patch.PostPatchVerifier.verify(repo, parent, statusLogger);
}
}

private void reportError(PatchApplier.PatchResult result) {
reportBatchErrors(List.of(result));
}

private void reportBatchErrors(List<PatchApplier.PatchResult> results) {
if (results.isEmpty()) return;
if (errorCallback != null) errorCallback.accept(results);
PatchErrorDialog.show((JFrame) parent, results, repo);
}

private boolean handleSmartPaste(String text) {
wv.codeclip.ui.NewFileExtractor.Result nfResult = wv.codeclip.ui.NewFileExtractor.extract(text);
List<wv.codeclip.ui.NewFileExtractor.NewFile> newFiles = nfResult.files();
String body = nfResult.remaining();

if (!nfResult.errors().isEmpty()) {
    StringBuilder esb = new StringBuilder("These new file blocks are incomplete and were skipped:\n\n");
    for (String er : nfResult.errors()) {
        esb.append("• ").append(er).append("\n");
        if (statusLogger != null) {
            statusLogger.accept("✗ New file block skipped: " + er);
        }
    }
    JOptionPane.showMessageDialog(parent, esb.toString(),
        "New File Block Error", JOptionPane.WARNING_MESSAGE);
}

SmartPasteExtractor extractor = new SmartPasteExtractor(body);
List<SmartPasteExtractor.Entry> entries = extractor.extract(
SmartPasteSettings.isAllowClasses()
);

if (entries.isEmpty() && newFiles.isEmpty()) {
if (!nfResult.errors().isEmpty()) {
    return false;
}
JOptionPane.showMessageDialog(parent,
(SmartPasteExtractor.containsClassBlock(body) && !SmartPasteSettings.isAllowClasses())
? "Smart Paste found class block(s), but \"Allow classes\" is turned off in the Smart Paste settings\n(double-click the Smart Paste checkbox). Nothing was applied."
: "Smart Paste: no patches or class blocks found.",
"Nothing Found", JOptionPane.INFORMATION_MESSAGE);
return false;
}

List<String> logLines = new ArrayList<>();
Map<String, String> combinedSnapshot = new java.util.LinkedHashMap<>();
List<String> titles = new ArrayList<>();
List<PatchApplier.PatchResult> failedResults = new ArrayList<>();
List<String> crashedEntries = new ArrayList<>();

// New files first, so patches later in the same paste can target them.
int newFileCount = 0;
try {
    newFileCount = handleNewFileEntries(newFiles, logLines, combinedSnapshot, titles);
} catch (Exception ex) {
    crashedEntries.add("(new file block) — " + ex.getClass().getSimpleName()
        + (ex.getMessage() != null ? ": " + ex.getMessage() : ""));
    ex.printStackTrace();
}

for (SmartPasteExtractor.Entry entry : entries) {
    try {
        if (entry instanceof SmartPasteExtractor.PatchEntry pe) {
            handleSmartPatchEntry(pe.text(), logLines, combinedSnapshot, titles, failedResults);
        } else if (entry instanceof SmartPasteExtractor.ClassEntry ce) {
            handlePasteInternal(ce.text(), logLines, combinedSnapshot, titles);
        }
    } catch (Exception ex) {
        // One block throwing must NOT abort the whole batch.
        String label;
        if (entry instanceof SmartPasteExtractor.PatchEntry pe) {
            String t = PatchParser.extractTitle(pe.text());
            label = t != null ? t : "(untitled patch block)";
        } else {
            label = "(class block)";
        }
        crashedEntries.add(label + " — " + ex.getClass().getSimpleName()
            + (ex.getMessage() != null ? ": " + ex.getMessage() : ""));
        ex.printStackTrace();
    }
}

reportBatchErrors(failedResults);

if (SmartPasteExtractor.containsClassBlock(body) && !SmartPasteSettings.isAllowClasses()
        && entries.stream().noneMatch(e -> e instanceof SmartPasteExtractor.ClassEntry)) {
    JOptionPane.showMessageDialog(parent,
        "This paste also contained class block(s) that were skipped because\n"
        + "\"Allow classes\" is off in the Smart Paste settings.",
        "Class Blocks Skipped", JOptionPane.WARNING_MESSAGE);
    if (statusLogger != null) {
        statusLogger.accept("✗ Class block(s) skipped: Allow classes is off in Smart Paste settings");
    }
}

if (!crashedEntries.isEmpty()) {
    StringBuilder sb = new StringBuilder(
        "The following block(s) failed unexpectedly and were skipped.\n"
        + "Everything else in this paste was still applied normally:\n\n");
    for (String c : crashedEntries) {
        sb.append("• ").append(c).append("\n");
    }
    JOptionPane.showMessageDialog(parent, sb.toString(),
        "Some Blocks Failed", JOptionPane.WARNING_MESSAGE);
    if (statusLogger != null) {
        for (String c : crashedEntries) {
            statusLogger.accept("✗ Block failed unexpectedly: " + c);
        }
    }
}

if (!combinedSnapshot.isEmpty()) {
String combinedTitle = titles.isEmpty() ? "Smart Paste"
: titles.size() == 1 ? titles.get(0)
: titles.get(0) + " (+" + (titles.size() - 1) + " more)";
undoManager.pushUndo(combinedSnapshot, combinedTitle, new ArrayList<>(titles));
}

if (!logLines.isEmpty() && statusLogger != null) {
int patchCount = (int) entries.stream()
.filter(e -> e instanceof SmartPasteExtractor.PatchEntry).count();
int classCount = (int) entries.stream()
.filter(e -> e instanceof SmartPasteExtractor.ClassEntry).count();
String time = java.time.LocalTime.now()
.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
String footer = "─".repeat(32);
List<String> parts = new ArrayList<>();
if (patchCount > 0) parts.add(patchCount + " patch block" + (patchCount > 1 ? "s" : ""));
if (classCount > 0) parts.add(classCount + " class" + (classCount > 1 ? "es" : ""));
if (newFileCount > 0) parts.add(newFileCount + " new file" + (newFileCount > 1 ? "s" : ""));
parts.add(logLines.size() + " change" + (logLines.size() > 1 ? "s" : ""));
String header = "── Smart Paste [" + time + "]: " + String.join(", ", parts) + " ──";
statusLogger.accept(footer);
for (int i = logLines.size() - 1; i >= 0; i--) {
statusLogger.accept(logLines.get(i));
}
statusLogger.accept(header);
}

return !combinedSnapshot.isEmpty();
}

private void handleSmartPatchEntry(String patchText, List<String> logLines,
Map<String, String> combinedSnapshot, List<String> titles,
List<PatchApplier.PatchResult> failedResults) {
if (duplicateDetector.check(patchText) == PatchDuplicateDetector.Result.DUPLICATE) {
String t = PatchParser.extractTitle(patchText);
String d = PatchParser.extractDesc(patchText);
int choice = JOptionPane.showConfirmDialog(
parent,
"This patch looks identical to a recently applied one:\n\n" +
(t != null ? "Title: " + t + "\n" : "") +
(d != null ? "Desc:  " + d + "\n" : "") +
"\nApply it again?",
"Duplicate Patch Detected",
JOptionPane.YES_NO_OPTION,
JOptionPane.WARNING_MESSAGE
);
if (choice != JOptionPane.YES_OPTION) return;
}

String title = PatchParser.extractTitle(patchText);
String desc  = PatchParser.extractDesc(patchText);
List<PatchChange> changes;
try {
changes = new PatchParser().parse(patchText);
} catch (IllegalArgumentException e) {
String[] parts = PatchParser.splitRawBlock(e.getMessage());
PatchErrorDialog.show(parent,
"Patch format error:\n\n" + parts[0], null, null, parts[1]);
return;
}

PatchApplier applier = new PatchApplier(repo);
applier.setConflictResolver(conflictResolver);
PatchApplier.PatchResult result = applier.apply(changes);
if (result.hasFailures()) failedResults.add(result);
if (result.hasSuccesses()) {
duplicateDetector.record(patchText);
// Merge into combined snapshot — don't overwrite earlier entries for same path
for (Map.Entry<String, String> e : result.undoSnapshot().entrySet()) {
combinedSnapshot.putIfAbsent(e.getKey(), e.getValue());
}
if (title != null) titles.add(title);
refreshCallback.run();
notifyCodeChangedForPatch(changes);

List<String> files = changes.stream()
.map(PatchChange::fileName)
.distinct()
.toList();

String titleLine = (title != null ? "── " + title + " ──" : "── patch ──")
+ (desc != null ? " " + desc : "");
logLines.add(titleLine);
logLines.add("  Files: " + String.join(", ", files));
for (String s : result.successSummary()) logLines.add(s);
}
}

private void handleMultiPatch(String text) {
MultiPatchExtractor extractor = new MultiPatchExtractor();
List<PatchChange> changes;
int blockCount = extractor.countBlocks(text);

try {
changes = extractor.extractAll(text);
} catch (IllegalArgumentException e) {
PatchErrorDialog.show(parent, "Multi-patch format error:\n\n" + e.getMessage(), null, null);
return;
}

        PatchApplier applier = new PatchApplier(repo);
        applier.setConflictResolver(conflictResolver);
        PatchApplier.PatchResult result = applier.apply(changes);

        if (result.hasFailures()) {
            reportError(result);
        }

        if (result.hasSuccesses()) {
            refreshCallback.run();
            notifyCodeChangedForPatch(changes);
            if (statusLogger != null) {
List<String> summary = result.successSummary();
String footer = "─".repeat(32);
String time = java.time.LocalTime.now()
.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
String header = "── Smart Paste [" + time + "]: " + blockCount + " block" + (blockCount > 1 ? "s" : "") +
", " + summary.size() + " change" + (summary.size() > 1 ? "s" : "") + " ──";
statusLogger.accept(footer);
for (int i = summary.size() - 1; i >= 0; i--) {
statusLogger.accept(summary.get(i));
}
statusLogger.accept(header);
}
}
}

private boolean handlePatch(String text) {
String title = PatchParser.extractTitle(text);
String desc  = PatchParser.extractDesc(text);

if (duplicateDetector.check(text) == PatchDuplicateDetector.Result.DUPLICATE) {
int choice = JOptionPane.showConfirmDialog(
parent,
"This patch looks identical to a recently applied one:\n\n" +
(title != null ? "Title: " + title + "\n" : "") +
(desc  != null ? "Desc:  " + desc  + "\n" : "") +
"\nApply it again?",
"Duplicate Patch Detected",
JOptionPane.YES_NO_OPTION,
JOptionPane.WARNING_MESSAGE
);
if (choice != JOptionPane.YES_OPTION) return false;
}

PatchParser patchParser = new PatchParser();
List<PatchChange> changes;

try {
changes = patchParser.parse(text);
} catch (IllegalArgumentException e) {
String[] parts = PatchParser.splitRawBlock(e.getMessage());
PatchErrorDialog.show(parent, "Patch format error:\n\n" + parts[0], null, null, parts[1]);
return false;
}

        PatchApplier applier = new PatchApplier(repo);
        applier.setConflictResolver(conflictResolver);
        PatchApplier.PatchResult result = applier.apply(changes);

        if (result.hasFailures()) {
        reportError(result);
        }

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
List<String> files = changes.stream()
.map(PatchChange::fileName)
.distinct()
.toList();
String filesLine = "  Files: " + String.join(", ", files);
String header = "── Patch [" + time + "] (" + summary.size()
+ " change" + (summary.size() > 1 ? "s" : "") + ")"
+ (title != null ? ": " + title : "") + " ──";
statusLogger.accept(footer);
for (int i = summary.size() - 1; i >= 0; i--) {
statusLogger.accept(summary.get(i));
}
statusLogger.accept(filesLine);
if (desc != null) statusLogger.accept("  " + desc);
statusLogger.accept(header);
}
return true;
}

return false;
}

// ------------------------------------------------------------------
// Class paste handling (unchanged)
// ------------------------------------------------------------------

private void handlePaste(String classCode) {
handlePasteInternal(classCode, null);
}

private void handlePasteInternal(String classCode, List<String> logCollector) {
handlePasteInternal(classCode, logCollector, null, null);
}

private void handlePasteInternal(String classCode, List<String> logCollector,
Map<String, String> combinedSnapshot, List<String> titles) {
String packageName = parser.parsePackage(classCode);
String className = parser.parseClassName(classCode);

if (className == null) {
JOptionPane.showMessageDialog(
parent,
"Could not determine the class/interface/enum name from the pasted code.",
"Invalid Source",
JOptionPane.ERROR_MESSAGE
);
return;
}

if (!JavaBraceEndChecker.hasCompleteEnd(classCode)) {
int choice = JOptionPane.showConfirmDialog(
parent,
classLabel(className) +
"The pasted source appears to have incomplete or unbalanced braces.\n\n" +
"Do you want to continue anyway?",
"Brace Validation Failed",
JOptionPane.OK_CANCEL_OPTION,
JOptionPane.WARNING_MESSAGE
);
if (choice != JOptionPane.OK_OPTION) return;
}

boolean smartSkipCreate    = logCollector != null && SmartPasteSettings.isSkipCreateConfirm();
boolean smartSkipOverwrite = logCollector != null && SmartPasteSettings.isSkipOverwriteConfirm();

File sourceRoot = rootDetector.detect(packageName);
File existingFile = fileWriter.findExistingFile(packageName, className, sourceRoot);

if (existingFile == null) {
List<File> repoMatches = findAllExistingFilesInRepo(className);
if (repoMatches.size() == 1) {
existingFile = repoMatches.get(0);
} else if (repoMatches.size() > 1) {
List<String> repoMatchPaths = new ArrayList<>();
for (File f : repoMatches) repoMatchPaths.add(f.getAbsolutePath());
repoMatchPaths.sort(null);
StringBuilder dupMsg = new StringBuilder();
dupMsg.append("\"").append(className)
.append("\" already exists at ").append(repoMatches.size())
.append(" different locations in this project:\n\n");
appendDuplicatePathList(dupMsg, repoMatchPaths, 8);
dupMsg.append("\nCodeClip can't safely tell which one to update, ")
.append("so this paste was cancelled to avoid creating another copy.\n")
.append("Remove or consolidate the duplicates and try again.");
JOptionPane.showMessageDialog(parent, dupMsg.toString(),
"Duplicate Class Detected", JOptionPane.WARNING_MESSAGE);
return;
}
}

boolean isNewFile = existingFile == null;

if (!isNewFile && !smartSkipOverwrite && !confirmOverwrite(className, existingFile, classCode)) {
return;
}

try {
File file;
if (isNewFile) {
if (!smartSkipCreate && !confirmCreate(className, packageName, sourceRoot)) return;
file = fileWriter.createFile(packageName, className, classCode, sourceRoot);
fileWriter.registerInRepo(file, classCode);
repo.recordChange(file.getAbsolutePath(), ClassRepository.ChangeKind.NEW);
// null sentinel = file didn't exist, undo should delete it
Map<String, String> snapshot = new java.util.LinkedHashMap<>();
snapshot.put(file.getAbsolutePath(), null);
if (combinedSnapshot != null) {
combinedSnapshot.putIfAbsent(file.getAbsolutePath(), null);
} else {
undoManager.pushUndo(snapshot, "Class Created: " + className);
}
if (titles != null) titles.add("Class Created: " + className);
} else {
String oldCode = repo.getClassCodeMap().get(existingFile.getAbsolutePath());
Map<String, String> snapshot = new java.util.LinkedHashMap<>();
snapshot.put(existingFile.getAbsolutePath(), oldCode != null ? oldCode : "");
if (combinedSnapshot != null) {
combinedSnapshot.putIfAbsent(existingFile.getAbsolutePath(),
oldCode != null ? oldCode : "");
} else {
undoManager.pushUndo(snapshot, "Class: " + className);
}
if (titles != null) titles.add("Class: " + className);
fileWriter.updateFile(existingFile, classCode);
fileWriter.registerInRepo(existingFile, classCode);
repo.recordChange(existingFile.getAbsolutePath(), ClassRepository.ChangeKind.WHOLE_UPDATE);
file = existingFile;
}

refreshCallback.run();

if (isNewFile) {
addPanelCallback.accept(file.getAbsolutePath(), file.getName());
}

String logMsg = (isNewFile ? "Class Created: " : "Class Updated: ")
+ className + " (" + file.getAbsolutePath() + ")";

if (logCollector != null) {
logCollector.add(logMsg);
} else if (statusLogger != null) {
statusLogger.accept(logMsg);
}

if (!isNewFile) {
notifyCodeChanged(file.getAbsolutePath(),
repo.getClassCodeMap().get(file.getAbsolutePath()));
}

} catch (IOException e) {
JOptionPane.showMessageDialog(
parent,
classLabel(className) +
"Failed to create/update file:\n" + e.getMessage(),
"Error",
JOptionPane.ERROR_MESSAGE
);
}
}

private boolean confirmOverwrite(String className, File existingFile, String newCode) {
String oldCode;
try {
oldCode = Files.readString(existingFile.toPath());
} catch (IOException e) {
JOptionPane.showMessageDialog(
parent,
classLabel(className) + "Failed to read existing file:\n" + e.getMessage(),
"Error",
JOptionPane.ERROR_MESSAGE
);
return false;
}

List<String> missingMethods = MissingMethodDetector.findMissingMethods(oldCode, newCode);
if (missingMethods.isEmpty()) return true;

StringBuilder errorText = new StringBuilder();
errorText.append("Warning: The new code for class ")
.append(className)
.append(" has these methods missing:\n");
for (String m : missingMethods) {
errorText.append("• ").append(m).append("\n");
}
errorText.append("\n\nMake sure you are not missing functionality.")
.append("\nDon't keep them for the sake of compatibility");

while (true) {
Object[] options = {"Overwrite", "Copy Error", "Cancel"};
int choice = JOptionPane.showOptionDialog(
parent,
errorText.toString(),
"Missing Methods Detected",
JOptionPane.DEFAULT_OPTION,
JOptionPane.WARNING_MESSAGE,
null,
options,
options[0]
);
if (choice == 0) return true;
if (choice == 1) {
clipboard.write(errorText.toString());
continue;
}
return false;
}
}

private boolean confirmCreate(String className, String packageName, File sourceRoot) {
String pkgPath = (packageName != null) ? packageName.replace('.', File.separatorChar) : "";
String path = new File(sourceRoot, pkgPath).getAbsolutePath();

JTextArea body = new JTextArea(
"Class: " + className + "\n\n" +
"File does not exist.\n\n" +
"Target directory:\n" + path + "\n\n" +
"Create new file?"
);
body.setEditable(false);
body.setLineWrap(true);
body.setWrapStyleWord(false);
body.setFont(UIManager.getFont("Label.font"));
body.setBackground(UIManager.getColor("Panel.background"));
body.setFocusable(false);
body.setRows(8);
body.setColumns(50);

int choice = JOptionPane.showConfirmDialog(
parent,
body,
"Create Class",
JOptionPane.OK_CANCEL_OPTION,
JOptionPane.QUESTION_MESSAGE
);
return choice == JOptionPane.OK_OPTION;
}

// ------------------------------------------------------------------
// Helpers
// ------------------------------------------------------------------

private boolean handleEnable(String text) {
    return enablerCommand.handle(text);
}

private boolean looksLikeJavaSource(String text) {
// Skip leading comments and blank lines before checking
for (String line : text.split("\n")) {
String trimmed = line.trim();
if (trimmed.isEmpty()
|| trimmed.startsWith("//")
|| trimmed.startsWith("*")
|| trimmed.startsWith("/*")) {
continue;
}
return trimmed.startsWith("package ")
|| trimmed.startsWith("import ")
|| trimmed.startsWith("public class")
|| trimmed.startsWith("public interface")
|| trimmed.startsWith("public enum")
|| trimmed.startsWith("public record")
|| trimmed.startsWith("class ")
|| trimmed.startsWith("interface ")
|| trimmed.startsWith("enum ");
}
return false;
}

private String classLabel(String className) {
if (className.length() <= CLASS_NAME_WRAP_LENGTH) {
return "Class: " + className + "\n\n";
}
return "Class:\n" + className + "\n\n";
}

private void notifyCodeChangedForPatch(List<PatchChange> changes) {
for (PatchChange change : changes) {
for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
if (entry.getValue().getName().equalsIgnoreCase(change.fileName())) {
notifyCodeChanged(entry.getKey(), repo.getClassCodeMap().get(entry.getKey()));
break;
}
}
}
}

private void notifyCodeChanged(String path, String code) {
if (codeChangedCallback != null) {
codeChangedCallback.accept(path, code);
}
}

private static String escapeHtml(String text) {
return text.replace("&", "&amp;")
.replace("<", "&lt;")
.replace(">", "&gt;");
}

private boolean looksLikePatch(String text) {
if (looksLikeJavaSource(text)) return false;
boolean hasFile = false;
boolean hasDirective = false;
for (String line : text.split("\n")) {
String trimmed = line.trim();
if (trimmed.startsWith("@@FILE:"))           hasFile = true;
if (trimmed.startsWith("@@METHOD:")
|| trimmed.startsWith("@@FIND:")
|| trimmed.startsWith("@@AFTER_METHOD:")
|| trimmed.startsWith("@@INSERT_METHOD:")) hasDirective = true;
if (hasFile && hasDirective) return true;
}
return false;
}

public void clearDuplicateHistory() {
    duplicateDetector.clearHistory();
}

private static boolean isFencedCopyCommand(String text) {
    String lower = text.toLowerCase();
    if (!lower.startsWith("```")) return false;
    // strip opening fence line
    int nl = text.indexOf('\n');
    if (nl < 0) return false;
    String body = text.substring(nl + 1).stripLeading();
    return body.startsWith("@@Copy");
}

private static String stripFence(String text) {
    if (!text.startsWith("```")) return text;
    int nl = text.indexOf('\n');
    if (nl < 0) return text;
    String body = text.substring(nl + 1);
    // strip trailing ```
    if (body.endsWith("```")) body = body.substring(0, body.length() - 3);
    return body.strip();
}

private List<File> findAllExistingFilesInRepo(String className) {
String target = (className + ".java").toLowerCase();
List<File> matches = new ArrayList<>();
for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
File f = entry.getValue();
if (f == null) continue;
if (f.getName().toLowerCase().equals(target)) {
boolean already = false;
for (File m : matches) {
if (m.getAbsolutePath().equals(f.getAbsolutePath())) { already = true; break; }
}
if (!already) matches.add(f);
}
}
return matches;
}

private static void appendDuplicatePathList(StringBuilder sb, List<String> paths, int max) {
int shown = Math.min(paths.size(), max);
for (int i = 0; i < shown; i++) {
sb.append("  • ").append(paths.get(i)).append("\n");
}
int remaining = paths.size() - shown;
if (remaining > 0) {
sb.append("  ... and ").append(remaining).append(" more\n");
}
}

private int handleNewFileEntries(List<wv.codeclip.ui.NewFileExtractor.NewFile> newFiles,
            List<String> logLines, Map<String, String> combinedSnapshot, List<String> titles) {
        if (newFiles == null || newFiles.isEmpty()) return 0;

        File root = null;
        try {
            root = resolveProjectRoot();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        if (root == null) {
            JOptionPane.showMessageDialog(parent,
                    "Could not determine the project root, so the new file block(s) were not applied.\n"
                    + "Load at least one project class first.",
                    "New File", JOptionPane.WARNING_MESSAGE);
            if (statusLogger != null) {
                statusLogger.accept("✗ New file block(s) skipped: project root unknown");
            }
            return 0;
        }

        java.nio.file.Path rootPath = root.toPath().toAbsolutePath().normalize();
        boolean smart = Boolean.TRUE.equals(multiPatchMode.get());
        boolean skipCreate = smart && SmartPasteSettings.isSkipCreateConfirm();
        boolean skipOverwrite = smart && SmartPasteSettings.isSkipOverwriteConfirm();
        int applied = 0;

        for (wv.codeclip.ui.NewFileExtractor.NewFile nf : newFiles) {
            String rel = nf.path().trim().replace('\\', '/');
            while (rel.startsWith("./")) rel = rel.substring(2);

            java.nio.file.Path target = null;
            String problem = null;
            if (rel.isEmpty() || rel.startsWith("/") || rel.matches("^[A-Za-z]:.*")) {
                problem = "path must be relative to the project root";
            } else {
                try {
                    target = rootPath.resolve(rel).normalize();
                    if (!target.startsWith(rootPath) || target.equals(rootPath)) {
                        problem = "path escapes the project root";
                    }
                } catch (java.nio.file.InvalidPathException ex) {
                    problem = "invalid path";
                }
            }
            if (problem == null && target.toFile().isDirectory()) {
                problem = "path is an existing folder";
            }
            if (problem != null) {
                if (statusLogger != null) {
                    statusLogger.accept("✗ New file skipped: " + nf.path() + " — " + problem);
                }
                JOptionPane.showMessageDialog(parent,
                        "New file skipped:\n" + nf.path() + "\n\nReason: " + problem
                        + "\nProject root: " + rootPath,
                        "New File", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            File file = target.toFile();
            boolean exists = file.isFile();

            String existingKey = null;
            for (Map.Entry<String, File> e : repo.getClassFileMap().entrySet()) {
                File f = e.getValue();
                if (f != null && f.toPath().toAbsolutePath().normalize().equals(target)) {
                    existingKey = e.getKey();
                    break;
                }
            }

            if (exists && !skipOverwrite) {
                int c = JOptionPane.showConfirmDialog(parent,
                        "File already exists:\n" + file.getAbsolutePath()
                        + "\n\nOverwrite it with the new content?",
                        "Overwrite File", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (c != JOptionPane.YES_OPTION) continue;
            } else if (!exists && !skipCreate) {
                int c = JOptionPane.showConfirmDialog(parent,
                        "Create new file?\n\n" + file.getAbsolutePath(),
                        "Create File", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (c != JOptionPane.OK_OPTION) continue;
            }

            String oldCode = null;
            if (exists) {
                oldCode = existingKey != null ? repo.getClassCodeMap().get(existingKey) : null;
                if (oldCode == null) {
                    try {
                        oldCode = Files.readString(file.toPath());
                    } catch (IOException ex) {
                        oldCode = "";
                    }
                }
            }

            try {
                fileWriter.writeFile(file, nf.content(), existingKey == null);
                File repoFile = existingKey != null ? repo.getClassFileMap().get(existingKey) : file;
                fileWriter.registerInRepo(repoFile, nf.content());
                String key = repoFile.getAbsolutePath();
                repo.recordChange(key, exists
                        ? ClassRepository.ChangeKind.WHOLE_UPDATE
                        : ClassRepository.ChangeKind.NEW);
                combinedSnapshot.putIfAbsent(key, exists ? oldCode : null);

                String name = file.getName();
                String prefix = exists ? "File Updated: " : "File Created: ";
                titles.add(prefix + name);
                logLines.add(prefix + name + " (" + file.getAbsolutePath() + ")");
                if (existingKey == null) {
                    addPanelCallback.accept(key, name);
                }
                if (exists) {
                    notifyCodeChanged(key, nf.content());
                }
                applied++;
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(parent,
                        "Failed to write file:\n" + file.getAbsolutePath() + "\n\n" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                if (statusLogger != null) {
                    statusLogger.accept("✗ New file failed: " + nf.path() + " — " + ex.getMessage());
                }
            }
        }

        if (applied > 0) {
            refreshCallback.run();
        }
        return applied;
    }

private File resolveProjectRoot() {
        File src = rootDetector.detect(null);
        if (src == null) return null;

        // 1) Nearest folder (going up) that holds a build file.
        File dir = src;
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "pom.xml").isFile()
                    || new File(dir, "build.gradle").isFile()
                    || new File(dir, "build.gradle.kts").isFile()
                    || new File(dir, "settings.gradle").isFile()
                    || new File(dir, "settings.gradle.kts").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
        }

        // 2) Standard layout (src/main/java): the project root is above "src".
        File cur = src;
        while (cur != null) {
            if (cur.getName().equals("src") && cur.getParentFile() != null) {
                return cur.getParentFile();
            }
            cur = cur.getParentFile();
        }

        // 3) Fallback: the source root itself.
        return src;
    }

}
























