package wv.codeclip.ui;

import wv.codeclip.parse.JavaSourceParser;
import wv.codeclip.parse.JavaBraceEndChecker;
import wv.codeclip.parse.MissingMethodDetector;
import wv.codeclip.parse.SourceRootDetector;
import wv.codeclip.io.ClipboardService;
import wv.codeclip.io.ClassFileWriter;
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

public class PasteClassHandler {

private final ClassRepository repo;
private final JFrame parent;
private java.util.function.Consumer<PatchApplier.PatchResult> errorCallback;
private final Runnable refreshCallback;
private final java.util.function.Consumer<String> statusLogger;
private final BiConsumer<String, String> addPanelCallback;

private final ClipboardService clipboard;
private final JavaSourceParser parser;
private final SourceRootDetector rootDetector;
private final ClassFileWriter fileWriter;
private final BiConsumer<String, String> codeChangedCallback;
private final Supplier<Boolean> multiPatchMode;
private final PatchDuplicateDetector duplicateDetector = new PatchDuplicateDetector();
private final wv.codeclip.patch.PatchUndoManager undoManager;
private Runnable postPasteCallback;

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

this.clipboard = new ClipboardService();
this.parser = new JavaSourceParser();
this.rootDetector = new SourceRootDetector(repo, parent, parser);
this.fileWriter = new ClassFileWriter(repo);
}

// ------------------------------------------------------------------
// Main entry point
// ------------------------------------------------------------------

public void handlePasteFromClipboard() {
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

if (Boolean.TRUE.equals(multiPatchMode.get()) &&
(PatchParser.containsPatch(text) || SmartPasteExtractor.containsClassBlock(text))) {
handleSmartPaste(text);
firePostPaste();
return;
}

if (PatchParser.isPatch(text)) {
handlePatch(text);
firePostPaste();
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
firePostPaste();
}

// ------------------------------------------------------------------
// Patch handling
// ------------------------------------------------------------------

public void setErrorCallback(java.util.function.Consumer<PatchApplier.PatchResult> errorCallback) {
this.errorCallback = errorCallback;
}

public void setPostPasteCallback(Runnable callback) {
this.postPasteCallback = callback;
}

private void firePostPaste() {
if (postPasteCallback != null) postPasteCallback.run();
}

private void reportError(PatchApplier.PatchResult result) {
if (errorCallback != null) errorCallback.accept(result);
PatchErrorDialog.show((JFrame) parent, result, repo);
}

private void handleSmartPaste(String text) {
SmartPasteExtractor extractor = new SmartPasteExtractor(text);
List<SmartPasteExtractor.Entry> entries = extractor.extract(
SmartPasteSettings.isAllowClasses()
);

if (entries.isEmpty()) {
JOptionPane.showMessageDialog(parent,
"Smart Paste: no patches or class blocks found.",
"Nothing Found", JOptionPane.INFORMATION_MESSAGE);
return;
}

List<String> logLines = new ArrayList<>();

for (SmartPasteExtractor.Entry entry : entries) {
if (entry instanceof SmartPasteExtractor.PatchEntry pe) {
handleSmartPatchEntry(pe.text(), logLines);
} else if (entry instanceof SmartPasteExtractor.ClassEntry ce) {
handlePasteInternal(ce.text(), logLines);
}
}

if (!logLines.isEmpty() && statusLogger != null) {
int patchCount = (int) entries.stream()
.filter(e -> e instanceof SmartPasteExtractor.PatchEntry).count();
int classCount = (int) entries.stream()
.filter(e -> e instanceof SmartPasteExtractor.ClassEntry).count();
String time = java.time.LocalTime.now()
.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
String footer = "─".repeat(32);
String header = "── Smart Paste [" + time + "]: "
+ (patchCount > 0 ? patchCount + " patch block" + (patchCount > 1 ? "s" : "") : "")
+ (patchCount > 0 && classCount > 0 ? ", " : "")
+ (classCount > 0 ? classCount + " class" + (classCount > 1 ? "es" : "") : "")
+ ", " + logLines.size() + " change" + (logLines.size() > 1 ? "s" : "") + " ──";
statusLogger.accept(footer);
for (int i = logLines.size() - 1; i >= 0; i--) {
statusLogger.accept(logLines.get(i));
}
statusLogger.accept(header);
}
}

private void handleSmartPatchEntry(String patchText, List<String> logLines) {
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
PatchErrorDialog.show(parent,
"Patch format error:\n\n" + e.getMessage(), null, null);
return;
}

PatchApplier.PatchResult result = new PatchApplier(repo).apply(changes);
if (result.hasFailures()) reportError(result);
if (result.hasSuccesses()) {
duplicateDetector.record(patchText);
undoManager.pushUndo(result.undoSnapshot(), title);
refreshCallback.run();
notifyCodeChangedForPatch(changes);

// Collect unique file names
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

private void handlePatch(String text) {
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
if (choice != JOptionPane.YES_OPTION) return;
}

PatchParser patchParser = new PatchParser();
List<PatchChange> changes;

try {
changes = patchParser.parse(text);
} catch (IllegalArgumentException e) {
PatchErrorDialog.show(parent, "Patch format error:\n\n" + e.getMessage(), null, null);
return;
}

PatchApplier applier = new PatchApplier(repo);
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

// Collect unique file names touched
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
}
}

// ------------------------------------------------------------------
// Class paste handling (unchanged)
// ------------------------------------------------------------------

private void handlePaste(String classCode) {
handlePasteInternal(classCode, null);
}

private void handlePasteInternal(String classCode, List<String> logCollector) {
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
    boolean isNewFile = existingFile == null;

    if (!isNewFile && !smartSkipOverwrite && !confirmOverwrite(className, existingFile, classCode)) {
        return;
    }

    try {
        File file;
        if (isNewFile) {
            if (!smartSkipCreate && !confirmCreate(className, packageName, sourceRoot)) return;
            file = fileWriter.createFile(packageName, className, classCode, sourceRoot);
            // Register first so classFileMap has the File reference for undo
            fileWriter.registerInRepo(file, classCode);
            // null sentinel = this file didn't exist, undo should delete it
            Map<String, String> snapshot = new java.util.LinkedHashMap<>();
            snapshot.put(file.getAbsolutePath(), null);
            undoManager.pushUndo(snapshot, "Class Created: " + className);
        } else {
            // Capture old code before overwriting
            String oldCode = repo.getClassCodeMap().get(existingFile.getAbsolutePath());
            Map<String, String> snapshot = new java.util.LinkedHashMap<>();
            snapshot.put(existingFile.getAbsolutePath(), oldCode != null ? oldCode : "");
            undoManager.pushUndo(snapshot, "Class: " + className);
            fileWriter.updateFile(existingFile, classCode);
            fileWriter.registerInRepo(existingFile, classCode);
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
            notifyCodeChanged(file.getAbsolutePath(), repo.getClassCodeMap().get(file.getAbsolutePath()));
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
}














