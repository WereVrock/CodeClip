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
import java.util.Map;
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

    private static final Color ENABLED_COLOR   = new Color(240, 240, 240);
    private static final Color DISABLED_COLOR  = new Color(210, 210, 210);
    private static final Color LOG_CLASS_COLOR = new Color(30, 120, 220);
    private static final Color UNSYNCED_COLOR  = new Color(30, 100, 210);

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

    // --- Undo / Redo buttons (top-left corner) ---
    JButton undoBtn = new JButton("↩ Undo");
    JButton redoBtn = new JButton("↪ Redo");
    undoBtn.setEnabled(false);
    redoBtn.setEnabled(false);

    Runnable syncUndoRedo = () -> {
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
            }
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(this, "Redo failed:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
        syncUndoRedo.run();
    });

    // Sync undo/redo state after every paste (covers first paste)
    pasteHandler.setPostPasteCallback(syncUndoRedo);

    JPanel undoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    undoPanel.add(undoBtn);
    undoPanel.add(redoBtn);

    JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    statsPanel.add(undoBtn);
    statsPanel.add(redoBtn);
    statsPanel.add(enabledCountLabel);
    statsPanel.add(charCountLabel);
    add(statsPanel, BorderLayout.NORTH);

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

    JPanel classListPanel = new JPanel(new BorderLayout(0, 2));
    classListPanel.add(classSearch, BorderLayout.NORTH);
    classListPanel.add(classScroll, BorderLayout.CENTER);

    split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, notesScroll, classListPanel);
    split.setResizeWeight(0.7);

    SwingUtilities.invokeLater(() -> {
        int divider = settings.loadDividerPosition();
        if (divider > 0) split.setDividerLocation(divider);
    });

    add(split, BorderLayout.CENTER);

    JPanel buttons = new JPanel(new GridLayout(0, 4, 5, 5));

    JButton reset            = new JButton("Reset");
    JButton update           = new JButton("Update All");
    JButton copy             = new JButton("Copy All");
    JButton copyCode         = new JButton("Copy Code Only");
    JButton enableAll        = new JButton("Enable All");
    JButton disableAll       = new JButton("Disable All");
    JButton pasteClass       = new JButton("Paste Class");
    JButton copyInstructions = new JButton("Copy Instructions");
    JButton copyArch         = new JButton("Copy Architecture");
    JButton sortOrder        = new JButton(SORT_LABELS[sortMode]);
    JButton checkpoint       = new JButton("Checkpoint");
    updateCheckpointButtonColor(checkpoint);
    lastErrorBtn = new JButton("Last Error");
    lastErrorBtn.setEnabled(false);
    lastErrorBtn.addActionListener(e -> {
        if (lastPatchError != null) {
            PatchErrorDialog.show(this, lastPatchError, repo);
        }
    });

    reset.addActionListener(e -> actions.resetAll(classPanel));

    update.addActionListener(e ->
            actions.updateAll(this::refreshText, this::removeClassPanel)
    );

    copy.addActionListener(e ->
            actions.copyAll(this::clearTempLogs, notesBuffer)
    );

    copyCode.addActionListener(e -> actions.copyCodeOnly());

    alwaysOnTopCheck.addActionListener(e ->
            setAlwaysOnTop(alwaysOnTopCheck.isSelected()));

    enableAll.addActionListener(e -> {
        repo.getDisabledClasses().clear();
        refreshText();
        refreshPanels();
    });

    disableAll.addActionListener(e -> {
        repo.getDisabledClasses().addAll(repo.getClassCodeMap().keySet());
        refreshText();
        refreshPanels();
    });

    pasteClass.addActionListener(e -> {
        pasteHandler.handlePasteFromClipboard();
        syncUndoRedo.run();
    });

    copyInstructions.addActionListener(e ->
            new ClipboardService().write(AiInstructions.TEXT)
    );

    copyArch.addActionListener(e -> actions.copyArchitecture());

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

    buttons.add(reset);
    buttons.add(update);
    buttons.add(copy);
    buttons.add(copyCode);
    buttons.add(enableAll);
    buttons.add(disableAll);
    buttons.add(pasteClass);
    buttons.add(copyInstructions);
    buttons.add(showMissingFileMessages);
    buttons.add(alwaysOnTopCheck);
    buttons.add(includeInstructionsCheck);
    buttons.add(sortOrder);

    checkpoint.addActionListener(e -> openCheckpointDialog());
    buttons.add(copyArch);
    buttons.add(checkpoint);
    buttons.add(smartPasteCheck);
    buttons.add(lastErrorBtn);

    smartPasteCheck.addMouseListener(new java.awt.event.MouseAdapter() {
        @Override
        public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) {
                new SmartPasteSettingsDialog(CodeClipFrame.this, settings).setVisible(true);
            }
        }
    });

    add(buttons, BorderLayout.SOUTH);
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

private void openCheckpointDialog() {
        if (checkpointDialog == null || !checkpointDialog.isDisplayable()) {
            checkpointDialog = new CheckpointDialog(this, repo, () -> {
                refreshText();
                refreshPanels();
            });
        } else {
            checkpointDialog.setRefreshCallback(() -> {
                refreshText();
                refreshPanels();
            });
        }
        checkpointDialog.refresh();
        checkpointDialog.setVisible(true);
    }

public void setLastPatchError(PatchApplier.PatchResult result) {
        lastPatchError = result;
        lastErrorBtn.setEnabled(result != null);
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