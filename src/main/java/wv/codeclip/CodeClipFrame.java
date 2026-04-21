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
import wv.codeclip.ui.PasteClassHandler;
import wv.codeclip.ui.ClassActions;
import wv.codeclip.ui.SimpleDocumentListener;

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

    private final JPanel classPanel = new JPanel();

    private final JCheckBox showMissingFileMessages =
            new JCheckBox("Show missing file messages", true);
    private final JCheckBox alwaysOnTopCheck =
            new JCheckBox("Always on Top", true);
    private final JCheckBox includeInstructionsCheck =
            new JCheckBox("Include Instructions", false);

    private final JLabel enabledCountLabel = new JLabel("Enabled Classes: 0");
    private final JLabel charCountLabel    = new JLabel("Code Characters: 0");

    private final ClassRepository repo    = new ClassRepository();
    private final ClassActions actions;
    private final SettingsManager settings = new SettingsManager();

    private static final Color ENABLED_COLOR  = new Color(240, 240, 240);
    private static final Color DISABLED_COLOR = new Color(210, 210, 210);
    private static final Color LOG_CLASS_COLOR = new Color(30, 120, 220);

    public CodeClipFrame() {

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
        renderNotes();

        for (String path : settings.loadClassPaths()) {
            File f = new File(path);
            if (f.exists()) addClass(f);
        }

        notesTextPane.addFocusListener(this);

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
                settings.saveNotes(notesBuffer);
                settings.saveIncludeInstructions(includeInstructionsCheck.isSelected());
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
        clearTempLogs();
    }

    @Override
    public void focusLost(java.awt.event.FocusEvent e) {}

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUI() {

        classTextArea.setEditable(false);
        classTextArea.setLineWrap(true);

        JPanel codePanel = new JPanel(new BorderLayout());
        codePanel.add(new JScrollPane(classTextArea), BorderLayout.CENTER);

        JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statsPanel.add(enabledCountLabel);
        statsPanel.add(charCountLabel);
        codePanel.add(statsPanel, BorderLayout.SOUTH);

        add(codePanel, BorderLayout.NORTH);

        notesTextPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane notesScroll = new JScrollPane(notesTextPane);

        classPanel.setLayout(new BoxLayout(classPanel, BoxLayout.Y_AXIS));
        JScrollPane classScroll = new JScrollPane(classPanel);
        classScroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane split =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, notesScroll, classScroll);
        split.setResizeWeight(0.7);

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
        JButton sortOrder        = new JButton(SORT_LABELS[sortMode]);

        reset.addActionListener(e -> actions.resetAll(classPanel));

        update.addActionListener(e ->
                actions.updateAll(this::refreshText, this::removeClassPanel)
        );

        copy.addActionListener(e ->
                actions.copyAll(this::clearTempLogs)
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
            new PasteClassHandler(
                    repo,
                    this,
                    this::refreshText,
                    this::appendTempLog,
                    this::addClassPanel
            ).handlePasteFromClipboard();
        });

        copyInstructions.addActionListener(e ->
                new ClipboardService().write(AiInstructions.TEXT)
        );

        sortOrder.addActionListener(e -> {
            sortMode = (sortMode + 1) % SORT_LABELS.length;
            sortOrder.setText(SORT_LABELS[sortMode]);
            refreshPanels();
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

        int colonSpace = line.indexOf(": ");
        if (colonSpace < 0) {
            doc.insertString(doc.getLength(), line, base);
            return;
        }

        String prefix = line.substring(0, colonSpace + 2);
        String rest   = line.substring(colonSpace + 2);

        int parenIdx = rest.indexOf(" (");
        if (parenIdx < 0) {
            // No path suffix — highlight the whole remainder
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
        if (repo.getClassCodeMap().containsKey(path)) return;

        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return Files.readString(file.toPath());
            }

            @Override
            protected void done() {
                try {
                    repo.getClassCodeMap().put(path, get());
                    repo.getClassFileMap().put(path, file);
                    addClassPanel(path, file.getName());
                    refreshText();
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

        JLabel label   = new JLabel(name);
        JButton toggle = new JButton("Disable");
        JButton copy   = new JButton("Copy");
        JButton delete = new JButton("Delete");

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
        long enabled =
                repo.getClassCodeMap().size() - repo.getDisabledClasses().size();
        enabledCountLabel.setText("Enabled Classes: " + enabled);
        charCountLabel.setText(
                "Code Characters: " + classTextArea.getText().length()
        );
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
}