package wv.codeclip;

import wv.codeclip.io.SettingsManager;
import wv.codeclip.io.ClipboardService;
import wv.codeclip.io.FileDropHandler;
import wv.codeclip.model.ClassRepository;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import wv.codeclip.config.AiInstructions;
import wv.codeclip.ui.CheckpointDialog;
import wv.codeclip.ui.PasteClassHandler;
import wv.codeclip.ui.ClassActions;
import wv.codeclip.ui.SimpleDocumentListener;
import wv.codeclip.ui.SmartPasteSettings;
import wv.codeclip.ui.SmartPasteSettingsDialog;
import wv.codeclip.patch.PatchApplier;
import wv.codeclip.patch.PatchErrorDialog;

public class CodeClipFrame extends JFrame implements java.awt.event.FocusListener {

private final JTextArea classTextArea = new JTextArea(8, 50);

// Replaces JTextArea — supports colored segments
private final JTextPane notesTextPane = new JTextPane();

// Source of truth
private String notesBuffer = "";
private String logBuffer   = "";

// Tracks current sort mode, cycles 0-3
private int sortMode = 0;
private static final String[] SORT_LABELS = {
"Order: Added",
"Order: ABC",
"Order: Enabled↑ Added",
"Order: Enabled↑ ABC"
};

// Prevent programmatic UI updates from triggering the notes document listener
private boolean internalUpdate = false;
private boolean logClearLocked = false;

private final JPanel classPanel = new JPanel();
private JSplitPane split;

private final JCheckBox showMissingFileMessages =
new JCheckBox("Show missing file messages", true);
private final JCheckBox alwaysOnTopCheck =
new JCheckBox("Always on Top", true);
private final JCheckBox includeInstructionsCheck =
new JCheckBox("Include Instructions", false);
private final JCheckBox smartPasteCheck =
new JCheckBox("Smart Paste", false);

private final JLabel enabledCountLabel = new JLabel("Enabled Classes: 0");
private final JLabel charCountLabel    = new JLabel("Code Characters: 0");

private final ClassRepository repo    = new ClassRepository();
private final ClassActions actions;
private PasteClassHandler pasteHandler;
private wv.codeclip.patch.PatchUndoManager undoManager;
private final SettingsManager settings = new SettingsManager();
private CheckpointDialog checkpointDialog = null;
private PatchApplier.PatchResult lastPatchError = null;
private JButton lastErrorBtn;
private JMenuItem lastErrorMenuItem;
private Runnable syncUndoRedo;

private static final Color ENABLED_COLOR   = new Color(240, 240, 240);
private static final Color DISABLED_COLOR  = new Color(210, 210, 210);
private static final Color LOG_CLASS_COLOR = new Color(30, 120, 220);
private static final Color UNSYNCED_COLOR  = new Color(30, 100, 210);

private static final String BUILD_INFO_FILE = "buildinfo.properties";

public CodeClipFrame() {

undoManager = new wv.codeclip.patch.PatchUndoManager();
pasteHandler = new PasteClassHandler(
repo,
this,
this::refreshText,
this::appendTempLog,
this::addClassPanel,
this::onCodeChanged,
smartPasteCheck::isSelected,
undoManager
);
pasteHandler.setErrorCallback(this::setLastPatchError);
undoManager.setPanelRemovalCallback(this::removeClassPanel);
undoManager.setPanelAddCallback(this::addClassPanel);

actions = new ClassActions(
this,
classTextArea,
notesTextPane,
showMissingFileMessages,
repo,
includeInstructionsCheck::isSelected
);

setTitle("Code Clip");
setDefaultCloseOperation(EXIT_ON_CLOSE);
setBounds(settings.loadFrameBounds());
setLayout(new BorderLayout());

buildUI();
installDnD();

setAlwaysOnTop(alwaysOnTopCheck.isSelected());

// Load persisted state
notesBuffer = settings.loadNotes();
includeInstructionsCheck.setSelected(settings.loadIncludeInstructions());
smartPasteCheck.setSelected(settings.loadSmartPaste());
SmartPasteSettings.load(settings);
renderNotes();

for (String path : settings.loadClassPaths()) {
File f = new File(path);
if (f.exists()) addClass(f);
}
// Retry title restore until workers finish, up to 20 attempts x 150ms = 3s
int[] attempts = {0};
javax.swing.Timer titleTimer = new javax.swing.Timer(150, null);
titleTimer.addActionListener(e -> {
restoreBuildInfoTitle();
if (!getTitle().equals("Code Clip")) {
titleTimer.stop();
return;
}
restoreBuildInfoTitleFromDisk();
if (!getTitle().equals("Code Clip")) {
titleTimer.stop();
return;
}
attempts[0]++;
if (attempts[0] >= 20) titleTimer.stop();
});
titleTimer.start();

notesTextPane.addFocusListener(this);

notesTextPane.addMouseListener(new java.awt.event.MouseAdapter() {
@Override
public void mousePressed(java.awt.event.MouseEvent e) {
if (e.getButton() == java.awt.event.MouseEvent.BUTTON3) {
logClearLocked = true;
}
}

@Override
public void mouseClicked(java.awt.event.MouseEvent e) {
if (e.getClickCount() == 2 && e.getButton() == java.awt.event.MouseEvent.BUTTON1) {
logClearLocked = false;
clearTempLogs();
}
}
});

notesTextPane.getDocument().addDocumentListener(
new SimpleDocumentListener(() -> {
if (!internalUpdate) {
notesBuffer = extractNotesFromPane();
}
})
);

addWindowListener(new java.awt.event.WindowAdapter() {
@Override
public void windowClosing(java.awt.event.WindowEvent e) {
clearTempLogs();
settings.saveFrameBounds(getBounds());
settings.saveDividerPosition(split.getDividerLocation());
settings.saveNotes(notesBuffer);
settings.saveIncludeInstructions(includeInstructionsCheck.isSelected());
settings.saveSmartPaste(smartPasteCheck.isSelected());
SmartPasteSettings.save(settings);
settings.saveClassPaths(
repo.getClassCodeMap().keySet().toArray(new String[0])
);
settings.saveProperties();
}
});

setVisible(true);
}

// ------------------------------------------------------------------
// FocusListener
// ------------------------------------------------------------------



@Override
public void focusGained(java.awt.event.FocusEvent e) {
if (!logClearLocked) {
clearTempLogs();
}
}

@Override
public void focusLost(java.awt.event.FocusEvent e) {}

// ------------------------------------------------------------------
// UI
// ------------------------------------------------------------------

private void buildUI() {

classTextArea.setEditable(false);
classTextArea.setLineWrap(true);

// --- Menu bar ---
JMenuBar menuBar = new JMenuBar();

JMenu settingsMenu = new JMenu("Settings");
JCheckBoxMenuItem showMissingItem = new JCheckBoxMenuItem(
"Show missing file messages", showMissingFileMessages.isSelected());
showMissingItem.addActionListener(e ->
showMissingFileMessages.setSelected(showMissingItem.isSelected()));
settingsMenu.add(showMissingItem);
menuBar.add(settingsMenu);

JMenu extraMenu = new JMenu("Extra");
JMenuItem copyArchItem = new JMenuItem("Copy Architecture");
copyArchItem.addActionListener(e -> actions.copyArchitecture());
extraMenu.add(copyArchItem);
JMenuItem timestampItem = new JMenuItem("Timestamp…");
timestampItem.addActionListener(e -> openTimestampDialog());
extraMenu.add(timestampItem);
JMenuItem copyMetaItem = new JMenuItem("Copy Meta Instructions");
copyMetaItem.addActionListener(e -> {
new ClipboardService().write(wv.codeclip.config.MetaInstructions.TEXT);
});
extraMenu.add(copyMetaItem);
menuBar.add(extraMenu);

JMenu systemMenu = new JMenu("System");
JMenuItem checkpointItem = new JMenuItem("Checkpoint…");
checkpointItem.addActionListener(e -> openCheckpointDialog());
systemMenu.add(checkpointItem);
JMenuItem resetItem = new JMenuItem("Reset");
resetItem.addActionListener(e -> {
int confirm = JOptionPane.showConfirmDialog(
CodeClipFrame.this,
"Are you sure you want to reset?\nThis will remove all loaded classes and data.",
"Confirm Reset",
JOptionPane.YES_NO_OPTION,
JOptionPane.WARNING_MESSAGE
);
if (confirm == JOptionPane.YES_OPTION) {
actions.resetAll(classPanel);
undoManager.clear();
syncUndoRedo.run();
setTitle("Code Clip");
}
});
systemMenu.add(resetItem);
lastErrorMenuItem = new JMenuItem("Last Error");
lastErrorMenuItem.setEnabled(false);
lastErrorMenuItem.addActionListener(e -> {
if (lastPatchError != null) {
PatchErrorDialog.show(this, lastPatchError, repo);
}
});
systemMenu.add(lastErrorMenuItem);
menuBar.add(systemMenu);

setJMenuBar(menuBar);

// --- Top bar: undo/redo + stats ---
JButton undoBtn = new JButton("↩ Undo");
JButton redoBtn = new JButton("↪ Redo");
undoBtn.setEnabled(false);
redoBtn.setEnabled(false);

syncUndoRedo = () -> {
undoBtn.setEnabled(undoManager.canUndo());
redoBtn.setEnabled(undoManager.canRedo());
};

undoBtn.addActionListener(e -> {
try {
wv.codeclip.patch.PatchUndoManager.Entry entry = undoManager.undo(repo);
if (entry != null) {
refreshText();
refreshPanels();
appendTempLog("↩ Undo: " + describeEntry(entry));
restoreTimestampFromSnapshot(entry);
}
} catch (java.io.IOException ex) {
JOptionPane.showMessageDialog(this, "Undo failed:\n" + ex.getMessage(),
"Error", JOptionPane.ERROR_MESSAGE);
}
syncUndoRedo.run();
});

redoBtn.addActionListener(e -> {
try {
wv.codeclip.patch.PatchUndoManager.Entry entry = undoManager.redo(repo);
if (entry != null) {
refreshText();
refreshPanels();
appendTempLog("↪ Redo: " + describeEntry(entry));
restoreTimestampFromSnapshot(entry);
}
} catch (java.io.IOException ex) {
JOptionPane.showMessageDialog(this, "Redo failed:\n" + ex.getMessage(),
"Error", JOptionPane.ERROR_MESSAGE);
}
syncUndoRedo.run();
});

pasteHandler.setPostPasteCallback(() -> {
syncUndoRedo.run();
stampBuildInfo();
});

JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
statsPanel.add(undoBtn);
statsPanel.add(redoBtn);
statsPanel.add(enabledCountLabel);
statsPanel.add(charCountLabel);
add(statsPanel, BorderLayout.NORTH);

// --- Center: notes + class list ---
notesTextPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
JScrollPane notesScroll = new JScrollPane(notesTextPane);

JTextField classSearch = new JTextField();
classSearch.setToolTipText("Filter classes…");
classSearch.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
String q = classSearch.getText().trim().toLowerCase();
for (Component c : classPanel.getComponents()) {
if (c instanceof JPanel p) {
Object nameObj = p.getClientProperty("name");
String n = nameObj instanceof String s ? s.toLowerCase() : "";
c.setVisible(q.isEmpty() || n.contains(q));
}
}
classPanel.revalidate();
classPanel.repaint();
}));

classPanel.setLayout(new BoxLayout(classPanel, BoxLayout.Y_AXIS));
JScrollPane classScroll = new JScrollPane(classPanel);
classScroll.getVerticalScrollBar().setUnitIncrement(16);

JButton enableAllBtn  = new JButton("Enable All");
JButton disableAllBtn = new JButton("Disable All");
enableAllBtn.addActionListener(e -> {
repo.getDisabledClasses().clear();
refreshText();
refreshPanels();
});
disableAllBtn.addActionListener(e -> {
repo.getDisabledClasses().addAll(repo.getClassCodeMap().keySet());
refreshText();
refreshPanels();
});
JButton sortOrder = new JButton(SORT_LABELS[sortMode]);
sortOrder.addActionListener(e -> {
sortMode = (sortMode + 1) % SORT_LABELS.length;
sortOrder.setText(SORT_LABELS[sortMode]);
refreshPanels();
});
sortOrder.addMouseListener(new java.awt.event.MouseAdapter() {
@Override
public void mouseClicked(java.awt.event.MouseEvent e) {
if (e.getButton() == java.awt.event.MouseEvent.BUTTON3) {
sortMode = (sortMode - 1 + SORT_LABELS.length) % SORT_LABELS.length;
sortOrder.setText(SORT_LABELS[sortMode]);
refreshPanels();
}
}
});

JPanel enableDisablePanel = new JPanel(new GridLayout(2, 1, 0, 2));
JPanel enableDisableRow = new JPanel(new GridLayout(1, 2, 4, 0));
enableDisableRow.add(enableAllBtn);
enableDisableRow.add(disableAllBtn);
enableDisablePanel.add(enableDisableRow);
enableDisablePanel.add(sortOrder);

JPanel classListPanel = new JPanel(new BorderLayout(0, 2));
classListPanel.add(classSearch, BorderLayout.NORTH);
classListPanel.add(classScroll, BorderLayout.CENTER);
classListPanel.add(enableDisablePanel, BorderLayout.SOUTH);

split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, notesScroll, classListPanel);
split.setResizeWeight(0.7);

SwingUtilities.invokeLater(() -> {
int divider = settings.loadDividerPosition();
if (divider > 0) split.setDividerLocation(divider);
});

add(split, BorderLayout.CENTER);

// --- Bottom bar ---
JButton pasteClass       = new JButton("Paste Class");
JButton update           = new JButton("Update All");
JButton copy             = new JButton("Copy All");
JButton copyCode         = new JButton("Copy Code Only");
JButton copyInstructions = new JButton("Copy Instructions");
lastErrorBtn = new JButton("Last Error");
lastErrorBtn.setEnabled(false);

alwaysOnTopCheck.addActionListener(e -> setAlwaysOnTop(alwaysOnTopCheck.isSelected()));

pasteClass.addActionListener(e -> {
pasteHandler.handlePasteFromClipboard();
syncUndoRedo.run();
});

update.addActionListener(e -> actions.updateAll(this::refreshText, this::removeClassPanel));
copy.addActionListener(e -> actions.copyAll(this::clearTempLogs, notesBuffer));
copyCode.addActionListener(e -> actions.copyCodeOnly());
copyInstructions.addActionListener(e -> new ClipboardService().write(AiInstructions.TEXT));

smartPasteCheck.addMouseListener(new java.awt.event.MouseAdapter() {
@Override
public void mouseClicked(java.awt.event.MouseEvent e) {
if (e.getClickCount() == 2) {
new SmartPasteSettingsDialog(CodeClipFrame.this, settings).setVisible(true);
}
}
});

// Paste + Smart Paste pinned to left
JPanel pasteGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
pasteGroup.add(pasteClass);
pasteGroup.add(smartPasteCheck);

// Right side grid
JPanel rightButtons = new JPanel(new GridLayout(0, 3, 5, 5));
rightButtons.add(update);
rightButtons.add(copy);
rightButtons.add(copyCode);
rightButtons.add(copyInstructions);
rightButtons.add(alwaysOnTopCheck);
rightButtons.add(includeInstructionsCheck);

JPanel bottomBar = new JPanel(new BorderLayout(4, 0));
bottomBar.add(pasteGroup, BorderLayout.WEST);
bottomBar.add(rightButtons, BorderLayout.CENTER);

add(bottomBar, BorderLayout.SOUTH);
}

// ------------------------------------------------------------------
// Logs & Notes
// ------------------------------------------------------------------

public void appendTempLog(String message) {
logBuffer = message + "\n" + logBuffer;
renderNotes();
// Scroll to top so the new message is immediately visible
SwingUtilities.invokeLater(() ->
notesTextPane.setCaretPosition(0)
);
}

public void clearTempLogs() {
if (!logBuffer.isEmpty()) {
logBuffer = "";
renderNotes();
}
}

private void renderNotes() {
internalUpdate = true;
try {
StyledDocument doc = notesTextPane.getStyledDocument();
doc.remove(0, doc.getLength());

Style base = notesTextPane.addStyle("base", null);
StyleConstants.setFontFamily(base, Font.MONOSPACED);
StyleConstants.setFontSize(base, 12);
StyleConstants.setForeground(base,
UIManager.getColor("TextArea.foreground") != null
? UIManager.getColor("TextArea.foreground")
: Color.BLACK);

Style highlight = notesTextPane.addStyle("highlight", base);
StyleConstants.setBold(highlight, true);
StyleConstants.setForeground(highlight, LOG_CLASS_COLOR);

if (!logBuffer.isEmpty()) {
for (String line : logBuffer.split("\n", -1)) {
if (line.isEmpty()) continue;
appendLogLine(doc, line, base, highlight);
doc.insertString(doc.getLength(), "\n", base);
}
}

doc.insertString(doc.getLength(), notesBuffer, base);

} catch (BadLocationException ignored) {
} finally {
internalUpdate = false;
}
}

/**
* Parses a log line and inserts it with the class name (or patch target) highlighted.
*
* Handles two formats:
*   "Class Created: Foo (path)"   → prefix | Foo | path
*   "✓ FindReplace in Foo.java"   → prefix | Foo.java
*/

private void appendLogLine(StyledDocument doc, String line,
Style base, Style highlight)
throws BadLocationException {

if (line.startsWith("──") && !line.contains("Smart Paste") && !line.contains("Patch [")) {
SimpleAttributeSet title = new SimpleAttributeSet();
StyleConstants.setFontFamily(title, Font.MONOSPACED);
StyleConstants.setFontSize(title, 12);
StyleConstants.setForeground(title, new Color(180, 30, 30));
doc.insertString(doc.getLength(), line, title);
return;
}

int colonSpace = line.indexOf(": ");
if (colonSpace < 0) {
doc.insertString(doc.getLength(), line, base);
return;
}

String prefix = line.substring(0, colonSpace + 2);
String rest   = line.substring(colonSpace + 2);

int parenIdx = rest.indexOf(" (");
if (parenIdx < 0) {
doc.insertString(doc.getLength(), prefix, base);
doc.insertString(doc.getLength(), rest, highlight);
return;
}

String name     = rest.substring(0, parenIdx);
String pathPart = rest.substring(parenIdx);

doc.insertString(doc.getLength(), prefix, base);
doc.insertString(doc.getLength(), name, highlight);
doc.insertString(doc.getLength(), pathPart, base);
}

private String extractNotesFromPane() {
try {
String full = notesTextPane.getDocument()
.getText(0, notesTextPane.getDocument().getLength());
int logLen = logBuffer.length();
if (full.length() >= logLen) {
return full.substring(logLen);
}
return full;
} catch (BadLocationException e) {
return notesBuffer;
}
}

// ------------------------------------------------------------------
// Classes
// ------------------------------------------------------------------

private void installDnD() {
new FileDropHandler(this::addClass).install(this);
}

private void addClass(File file) {
String path = file.getAbsolutePath();

if (repo.getClassCodeMap().containsKey(path)) {
if (repo.getDisabledClasses().remove(path)) {
refreshText();
refreshPanels();
}
return;
}

SwingWorker<String, Void> worker = new SwingWorker<>() {
@Override
protected String doInBackground() throws Exception {
return Files.readString(file.toPath());
}

@Override
protected void done() {
try {
String code = get();
repo.getClassCodeMap().put(path, code);
repo.getClassFileMap().put(path, file);
repo.setCheckpoint(path, code);
addClassPanel(path, file.getName());
refreshText();
refreshPanels();
if (file.getName().equals(BUILD_INFO_FILE)) {
restoreBuildInfoTitle();
}
} catch (Exception ignored) {}
}
};
worker.execute();
}

// ------------------------------------------------------------------
// Class panels
// ------------------------------------------------------------------

public void addClassPanel(String path, String name) {
JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
panel.setOpaque(true);
panel.setBackground(ENABLED_COLOR);
panel.putClientProperty("path", path);
panel.putClientProperty("name", name);

JLabel label   = new JLabel(name);
label.setToolTipText("In sync with checkpoint");
JButton toggle = new JButton("Disable");
JButton copy   = new JButton("Copy");
JButton delete = new JButton("Delete");
panel.putClientProperty("label", label);

toggle.addActionListener(e -> {
if (repo.getDisabledClasses().remove(path)) {
toggle.setText("Disable");
panel.setBackground(ENABLED_COLOR);
} else {
repo.getDisabledClasses().add(path);
toggle.setText("Enable");
panel.setBackground(DISABLED_COLOR);
}
refreshText();
});

copy.addActionListener(e -> {
String code = repo.getClassCodeMap().get(path);
if (code != null) {
String text = "// ===== " + name + " =====\n" + code + "\n";
Toolkit.getDefaultToolkit()
.getSystemClipboard()
.setContents(new java.awt.datatransfer.StringSelection(text), null);
}
});

delete.addActionListener(e -> {
repo.getClassCodeMap().remove(path);
repo.getClassFileMap().remove(path);
repo.getDisabledClasses().remove(path);
classPanel.remove(panel);
refreshText();
refreshPanels();
});

panel.add(label);
panel.add(toggle);
panel.add(copy);
panel.add(delete);

classPanel.add(panel);
classPanel.revalidate();
classPanel.repaint();
}

private void removeClassPanel(String path) {
for (Component c : classPanel.getComponents()) {
if (c instanceof JPanel panel) {
Object storedPath = panel.getClientProperty("path");
if (path.equals(storedPath)) {
classPanel.remove(panel);
break;
}
}
}
classPanel.revalidate();
classPanel.repaint();
}

// ------------------------------------------------------------------
// Refresh
// ------------------------------------------------------------------

private void refreshText() {
StringBuilder sb = new StringBuilder();
repo.getClassCodeMap().forEach((path, code) -> {
if (!repo.getDisabledClasses().contains(path)) {
sb.append(code).append("\n\n");
}
});
classTextArea.setText(sb.toString());
refreshStats();
}

private void refreshStats() {
long enabled = repo.getClassCodeMap().size() - repo.getDisabledClasses().size();
enabledCountLabel.setText("Enabled Classes: " + enabled);
int totalChars = repo.getClassCodeMap().entrySet().stream()
.filter(e -> !repo.getDisabledClasses().contains(e.getKey()))
.mapToInt(e -> e.getValue().length())
.sum();
charCountLabel.setText("Code Characters: " + totalChars);
}

private void refreshPanels() {
List<PanelEntry> entries = new ArrayList<>();
List<String> insertionOrder = new ArrayList<>(repo.getClassCodeMap().keySet());

for (Component c : classPanel.getComponents()) {
if (c instanceof JPanel panel) {
Object storedPath = panel.getClientProperty("path");
if (storedPath instanceof String path) {
boolean disabled = repo.getDisabledClasses().contains(path);
File file = repo.getClassFileMap().get(path);
String name = (file != null) ? file.getName() : path;
int insertionIdx = insertionOrder.indexOf(path);
entries.add(new PanelEntry(panel, path, name, disabled, insertionIdx));

panel.setBackground(disabled ? DISABLED_COLOR : ENABLED_COLOR);
boolean unsynced = isUnsynced(path);
Object labelObj = panel.getClientProperty("label");
if (labelObj instanceof JLabel lbl) {
lbl.setForeground(unsynced ? UNSYNCED_COLOR : UIManager.getColor("Label.foreground"));
lbl.setToolTipText(unsynced ? "Modified since last checkpoint" : "In sync with checkpoint");
}
for (Component child : panel.getComponents()) {
if (child instanceof JButton btn
&& (btn.getText().equals("Enable") || btn.getText().equals("Disable"))) {
btn.setText(disabled ? "Enable" : "Disable");
}
}
}
}
}

Comparator<PanelEntry> comparator = switch (sortMode) {
case 0 -> Comparator.comparingInt(PanelEntry::insertionIdx);
case 1 -> Comparator.comparing(e -> e.name().toLowerCase());
case 2 -> Comparator
.comparingInt((PanelEntry e) -> e.disabled() ? 1 : 0)
.thenComparingInt(PanelEntry::insertionIdx);
case 3 -> Comparator
.comparingInt((PanelEntry e) -> e.disabled() ? 1 : 0)
.thenComparing(e -> e.name().toLowerCase());
default -> Comparator.comparingInt(PanelEntry::insertionIdx);
};
entries.sort(comparator);

classPanel.removeAll();
for (PanelEntry entry : entries) {
classPanel.add(entry.panel());
}
classPanel.revalidate();
classPanel.repaint();
}

private record PanelEntry(JPanel panel, String path, String name,
boolean disabled, int insertionIdx) {}

private String describeEntry(wv.codeclip.patch.PatchUndoManager.Entry entry) {
List<String> names = new ArrayList<>();
for (String path : entry.snapshot().keySet()) {
File f = repo.getClassFileMap().get(path);
names.add(f != null ? f.getName() : new File(path).getName());
}
String files = String.join(", ", names);
return entry.title() != null ? entry.title() + " (" + files + ")" : files;
}

private void updateCheckpointButtonColor(JButton btn) {
if (btn == null) {
for (Component c : ((JPanel) getContentPane().getComponent(2)).getComponents()) {
if (c instanceof JButton b && (b.getText().equals("Checkpoint") || b.getText().equals("Checkpoint ✓"))) {
btn = b;
break;
}
}
}
if (btn == null) return;
boolean allInSync = !repo.hasPendingRestores();
btn.setText(allInSync ? "Checkpoint ✓" : "Checkpoint");
btn.setForeground(UIManager.getColor("Button.foreground"));
}

private boolean isUnsynced(String path) {
String current    = repo.getClassCodeMap().get(path);
String checkpoint = repo.getCheckpointCodeMap().get(path);
if (current == null || checkpoint == null) return false;
return !current.equals(checkpoint);
}

private void restoreTimestampFromSnapshot(wv.codeclip.patch.PatchUndoManager.Entry entry) {
if (entry == null) return;
for (java.util.Map.Entry<String, String> e : entry.snapshot().entrySet()) {
java.io.File f = repo.getClassFileMap().get(e.getKey());
if (f != null && f.getName().equals(BUILD_INFO_FILE)) {
String oldContent = e.getValue();
String ts = oldContent != null ? extractTimestampFromContent(oldContent) : null;
if (ts != null) {
stampBuildInfoWithContent(oldContent);
} else {
stampBuildInfo();
}
return;
}
}
stampBuildInfo();
}

private void stampBuildInfoWithContent(String content) {
java.io.File sourceRoot = detectSourceRoot();
if (sourceRoot == null) return;
java.io.File file = new java.io.File(sourceRoot, BUILD_INFO_FILE);
try {
java.nio.file.Files.writeString(file.toPath(), content);
String path = file.getAbsolutePath();
repo.getClassCodeMap().put(path, content);
repo.getClassFileMap().put(path, file);
repo.setCheckpoint(path, content);
refreshText();
} catch (java.io.IOException ex) {
ex.printStackTrace();
}
}

private String extractTimestampFromContent(String content) {
if (content == null) return null;
for (String line : content.split("\n")) {
if (line.startsWith("LAST_UPDATED=")) {
return line.substring("LAST_UPDATED=".length()).trim();
}
}
return null;
}

private void openTimestampDialog() {
java.io.File sourceRoot = detectSourceRoot();
boolean hasTimestamp = false;
String timestampPath = "";
String foundTimestamp = "";

if (sourceRoot != null) {
java.io.File file = new java.io.File(sourceRoot, BUILD_INFO_FILE);
hasTimestamp = file.exists();
timestampPath = file.getAbsolutePath();
if (hasTimestamp) {
String content = repo.getClassCodeMap().get(timestampPath);
if (content == null) {
try { content = java.nio.file.Files.readString(file.toPath()); }
catch (java.io.IOException ignored) {}
}
if (content != null) {
foundTimestamp = extractTimestampFromContent(content);
if (foundTimestamp == null) foundTimestamp = "";
}
}
}

final boolean tsExists = hasTimestamp;
final String tsPath    = timestampPath;
final String tsValue   = foundTimestamp;

String instructions =
"buildinfo.properties is auto-generated by CodeClip.\n" +
"It is placed directly in your source root.\n\n" +
"Format:\n" +
"  LAST_UPDATED=Wed-14:32:05\n\n" +
"The format is: day-of-week abbreviation, hour, minute, second.\n\n" +
"IMPORTANT: Read this value once at startup and store it.\n" +
"Do NOT poll it at runtime — it only changes when CodeClip makes a change.\n\n" +
"Suggested usage:\n" +
"  Properties p = new Properties();\n" +
"  p.load(new FileReader(\"buildinfo.properties\"));\n" +
"  String buildTime = p.getProperty(\"LAST_UPDATED\");\n\n" +
"Or if it is on the classpath:\n" +
"  InputStream is = MyClass.class.getResourceAsStream(\"/buildinfo.properties\");\n" +
"  p.load(is);\n\n" +
"CodeClip updates this file automatically whenever it applies\n" +
"a patch, pastes a class, or performs an undo/redo.\n" +
"Undo/redo will revert the timestamp to what it was before that change.";

JDialog dialog = new JDialog(this, "Timestamp Info", true);
dialog.setLayout(new BorderLayout(10, 10));
dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

JTextArea info = new JTextArea(instructions);
info.setEditable(false);
info.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
info.setBackground(UIManager.getColor("Panel.background"));
info.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
dialog.add(new JScrollPane(info), BorderLayout.CENTER);

if (tsExists && !tsValue.isEmpty()) {
JLabel tsLabel = new JLabel("Last recorded timestamp: " + tsValue);
tsLabel.setFont(tsLabel.getFont().deriveFont(Font.ITALIC));
tsLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
dialog.add(tsLabel, BorderLayout.NORTH);
}

JButton copyInstrBtn = new JButton("Copy Instructions");
JButton copyPathBtn  = new JButton("Copy File Path");
JButton copyBothBtn  = new JButton("Copy Both");
JButton closeBtn     = new JButton("Close");

copyPathBtn.setEnabled(tsExists);
ClipboardService cb = new ClipboardService();

copyInstrBtn.addActionListener(e -> {
cb.write(instructions);
copyInstrBtn.setText("✓ Copied Instructions");
copyInstrBtn.setForeground(new Color(30, 120, 30));
});
copyPathBtn.addActionListener(e -> {
cb.write("buildinfo.properties location:\n" + tsPath);
copyPathBtn.setText("✓ Copied File Location");
copyPathBtn.setForeground(new Color(30, 120, 30));
});
copyBothBtn.addActionListener(e -> {
cb.write(instructions + "\n\nbuildinfo.properties location:\n" + tsPath);
copyBothBtn.setText("✓ Copied Both");
copyBothBtn.setForeground(new Color(30, 120, 30));
});
closeBtn.addActionListener(e -> dialog.dispose());

JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
btnPanel.add(copyInstrBtn);
btnPanel.add(copyPathBtn);
btnPanel.add(copyBothBtn);
btnPanel.add(closeBtn);
dialog.add(btnPanel, BorderLayout.SOUTH);

dialog.pack();
dialog.setMinimumSize(new Dimension(520, 380));
dialog.setLocationRelativeTo(this);
dialog.setVisible(true);
}

private void restoreBuildInfoTitle() {
for (java.util.Map.Entry<String, String> entry : repo.getClassCodeMap().entrySet()) {
String content = entry.getValue();
if (content == null) continue;
java.io.File f = repo.getClassFileMap().get(entry.getKey());
if (f == null || !f.getName().equals(BUILD_INFO_FILE)) continue;
String timestamp = extractTimestampFromContent(content);
if (timestamp != null) {
setTitle("Code Clip — " + timestamp);
return;
}
}
}

private void restoreBuildInfoTitleFromDisk() {
// Walk loaded file locations to find source root candidates
for (java.io.File file : repo.getClassFileMap().values()) {
if (file == null) continue;
// Try parent, grandparent, great-grandparent (covers deep package paths)
java.io.File dir = file.getParentFile();
for (int depth = 0; depth < 6 && dir != null; depth++) {
java.io.File candidate = new java.io.File(dir, BUILD_INFO_FILE);
if (candidate.exists()) {
try {
String content = java.nio.file.Files.readString(candidate.toPath());
String timestamp = extractTimestampFromContent(content);
if (timestamp != null) {
// Register in repo so future lookups work
String path = candidate.getAbsolutePath();
repo.getClassCodeMap().put(path, content);
repo.getClassFileMap().put(path, candidate);
repo.setCheckpoint(path, content);
setTitle("Code Clip — " + timestamp);
return;
}
} catch (java.io.IOException ignored) {}
}
dir = dir.getParentFile();
}
}
}

private void stampBuildInfo() {
String timestamp = java.time.LocalDateTime.now()
.format(java.time.format.DateTimeFormatter.ofPattern("EEE-HH:mm:ss"));

java.io.File sourceRoot = detectSourceRoot();
if (sourceRoot == null) return;

java.io.File file = new java.io.File(sourceRoot, BUILD_INFO_FILE);
String content = "LAST_UPDATED=" + timestamp + "\n";

// Capture old content for undo snapshot before overwriting
String path = file.getAbsolutePath();
String oldContent = repo.getClassCodeMap().get(path);
if (oldContent == null && file.exists()) {
try { oldContent = java.nio.file.Files.readString(file.toPath()); }
catch (java.io.IOException ignored) {}
}

try {
java.nio.file.Files.writeString(file.toPath(), content);

repo.getClassCodeMap().put(path, content);
repo.getClassFileMap().put(path, file);
repo.setCheckpoint(path, content);

// Push old content into undo snapshot so undo can revert it
if (oldContent != null) {
undoManager.mergeTimestampSnapshot(path, oldContent);
}

boolean panelExists = false;
for (java.awt.Component c : classPanel.getComponents()) {
if (c instanceof JPanel p &&
file.getAbsolutePath().equals(p.getClientProperty("path"))) {
panelExists = true;
break;
}
}
if (!panelExists) {
addClassPanel(path, file.getName());
}

// Title intentionally NOT updated here — startup only.
refreshText();

} catch (java.io.IOException ex) {
ex.printStackTrace();
}
}

private java.io.File detectSourceRoot() {
for (java.util.Map.Entry<String, java.io.File> entry : repo.getClassFileMap().entrySet()) {
java.io.File file = entry.getValue();
if (file == null) continue;
if (file.getName().equals(BUILD_INFO_FILE)) continue;
String code = repo.getClassCodeMap().get(entry.getKey());
if (code == null) continue;
wv.codeclip.parse.JavaSourceParser p = new wv.codeclip.parse.JavaSourceParser();
String pkg = p.parsePackage(code);
if (pkg == null || pkg.isEmpty()) continue;
String pkgPath = pkg.replace('.', java.io.File.separatorChar);
java.io.File dir = file.getParentFile();
if (dir == null) continue;
String abs = dir.getAbsolutePath();
if (abs.endsWith(java.io.File.separator + pkgPath)) {
return new java.io.File(abs.substring(0, abs.length() - pkgPath.length() - 1));
}
}
return null;
}

private void openCheckpointDialog() {
if (checkpointDialog == null || !checkpointDialog.isDisplayable()) {
checkpointDialog = new CheckpointDialog(this, repo, () -> {
refreshText();
refreshPanels();
stampBuildInfo();
});
} else {
checkpointDialog.setRefreshCallback(() -> {
refreshText();
refreshPanels();
stampBuildInfo();
});
}
checkpointDialog.refresh();
checkpointDialog.setVisible(true);
}

public void setLastPatchError(PatchApplier.PatchResult result) {
lastPatchError = result;
lastErrorBtn.setEnabled(result != null);
if (lastErrorMenuItem != null) {
lastErrorMenuItem.setEnabled(result != null);
}
}

public void onCodeChanged(String path, String code) {
if (!repo.getCheckpointCodeMap().containsKey(path)) {
repo.setCheckpoint(path, code);
}
if (checkpointDialog != null && checkpointDialog.isDisplayable()) {
checkpointDialog.refresh();
}
refreshPanels();
updateCheckpointButtonColor(null);
}

}















