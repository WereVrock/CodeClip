package wv.codeclip.mainFrame;

import wv.codeclip.io.SettingsManager;
import wv.codeclip.io.ClipboardService;
import wv.codeclip.io.FileDropHandler;
import wv.codeclip.model.ClassRepository;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wv.codeclip.AppMode;
import wv.codeclip.ui.CheckpointDialog;
import wv.codeclip.io.PasteClassHandler;
import wv.codeclip.modecontext.ModeColors;
import wv.codeclip.patch.InsertMethodConflictDialog;
import wv.codeclip.ui.ClassActions;
import wv.codeclip.ui.SimpleDocumentListener;
import wv.codeclip.ui.SmartPasteSettings;
import wv.codeclip.ui.SmartPasteSettingsDialog;
import wv.codeclip.patch.PatchApplier;
import wv.codeclip.patch.PatchErrorDialog;
import wv.codeclip.ui.ClassTreePanel;

public class CodeClipFrame extends JFrame implements java.awt.event.FocusListener {

    private final JTextArea classTextArea = new JTextArea(8, 50);

// Replaces JTextArea — supports colored segments
    private final JTextPane notesTextPane = new JTextPane();

// Source of truth
    private String notesBuffer = "";
    private String logBuffer = "";

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
    private ClassTreePanel classTreePanel;
    private boolean treeViewActive = false;
    private JSplitPane split;

    private final JCheckBox showMissingFileMessages
            = new JCheckBox("Show missing file messages", true);
    private final JCheckBox alwaysOnTopCheck
            = new JCheckBox("Always on Top", true);
    private final JCheckBox includeInstructionsCheck
            = new JCheckBox("Include Instructions", false);
    private final JCheckBox smartPasteCheck
            = new JCheckBox("Smart Paste", false);
    private final JCheckBox includeProtocolCheck
            = new JCheckBox("Include Protocol", true);

    private final JLabel enabledCountLabel = new JLabel("Enabled Classes: 0");
    private JLabel modeLabel;
    private final JLabel charCountLabel = new JLabel("Code Characters: 0");

    private AppMode currentMode = AppMode.JAVA;
    private FileDropHandler fileDropHandler;
    private final ClassRepository repo = new ClassRepository();
    private final ClassActions actions;
    private PasteClassHandler pasteHandler;
    private wv.codeclip.godot.GodotPasteHandler godotPasteHandler;
    private wv.codeclip.html.HtmlPasteHandler htmlPasteHandler;
    private wv.codeclip.generic.GenericPasteHandler genericPasteHandler;
    private wv.codeclip.patch.PatchUndoManager undoManager;
    private wv.codeclip.protocol.library.ProtocolLibrary protocolLibrary;
    private wv.codeclip.protocol.engine.ProtocolUndoManager protocolUndoManager;
    private wv.codeclip.protocol.engine.ProtocolPasteRouter protocolPasteRouter;
    private Runnable syncProtocolUndoRedo;
    private wv.codeclip.mainFrame.ProjectNameManager projectNameManager;
    private wv.codeclip.protocol.library.ProtocolDirectoryManager protocolDirectoryManager;
    private JMenuItem godotDirMenuItem;
    private JMenuItem htmlDirMenuItem;
    private JMenuItem genericDirMenuItem;
    private JMenuItem fuzzySettingsMenuItem;
    private JMenuItem genericFuzzySettingsMenuItem;
    private JMenuItem copyMetaItem;
    private final SettingsManager settings = new SettingsManager();
    private JCheckBoxMenuItem autoReplaceInsertConflictItem;
    private JCheckBoxMenuItem compileCheckItem;
    private CheckpointDialog checkpointDialog = null;
    private List<PatchApplier.PatchResult> lastPatchErrors = null;
    private JButton lastErrorBtn;
    private JMenuItem lastErrorMenuItem;
    private Runnable syncUndoRedo;

    private JTabbedPane notesTabs;
    private JTextPane persistentLogPane;

    private JPanel versionPanel;
    private final java.util.List<VersionEvent> versionHistory = new java.util.ArrayList<>();
    private int versionCurrentIdx = -1;

// Background colors for class rows are now provided by ModeColors.
// See ModeColors.getEnabledBackground() and getDisabledBackground().
    private static final Color LOG_CLASS_COLOR = new Color(30, 120, 220);
    private static final Color UNSYNCED_COLOR = new Color(30, 100, 210);
    private static final Color LOG_ERROR_COLOR = new Color(200, 30, 30);
    private static final Color LOG_TITLE_COLOR = new Color(180, 30, 30);
    private static final Color LOG_SEP_COLOR = new Color(120, 120, 120);

    /**
     * Insertion positions (in log-line index space, top = 0) where separators
     * live.
     */
    private final java.util.List<Integer> logSeparatorPositions = new java.util.ArrayList<>();
    private boolean logSeparatorsVisible = true;
    /**
     * Running count of log lines ever prepended (used to shift separator
     * indices).
     */
    private int logLineCount = 0;

    private static final String BUILD_INFO_FILE = "buildinfo.properties";
    private static final int ICON_SIZE = 64;
    private boolean titleFrozen = false;
    private wv.codeclip.mainFrame.BuildInfoStamper buildInfoStamper;

    private JWindow loadBarWindow;
    private JProgressBar loadProgressBar;
    private JLabel loadProgressLabel;

    public CodeClipFrame() {

        wv.codeclip.config.CodeClipBuildInfo.getBuildInfo();
        wv.codeclip.godot.GodotDirectory.load(settings);
        wv.codeclip.html.HtmlDirectory.load(settings);
        wv.codeclip.generic.GenericDirectory.load(settings);
        undoManager = new wv.codeclip.patch.PatchUndoManager();
        projectNameManager = new wv.codeclip.mainFrame.ProjectNameManager(settings);
        protocolDirectoryManager = new wv.codeclip.protocol.library.ProtocolDirectoryManager(
                settings, this::detectSourceRootForProtocols);
        protocolLibrary = new wv.codeclip.protocol.library.ProtocolLibrary(
                protocolDirectoryManager.getProtocolsBaseDir());
        protocolUndoManager = new wv.codeclip.protocol.engine.ProtocolUndoManager();
        protocolPasteRouter = new wv.codeclip.protocol.engine.ProtocolPasteRouter(
                protocolLibrary, protocolUndoManager);
        pasteHandler = new PasteClassHandler(
                repo,
                this,
                () -> {
                    refreshText();
                    refreshPanels();
                },
                this::appendTempLog,
                this::addClassPanel,
                this::onCodeChanged,
                smartPasteCheck::isSelected,
                undoManager
        );
        pasteHandler.setErrorCallback(this::setLastPatchError);
        godotPasteHandler = new wv.codeclip.godot.GodotPasteHandler(
                repo, this,
                () -> {
                    refreshText();
                    refreshPanels();
                },
                this::appendTempLog,
                this::addClassPanel,
                this::onCodeChanged,
                smartPasteCheck::isSelected,
                undoManager
        );
        godotPasteHandler.setErrorCallback(this::setLastPatchError);
        htmlPasteHandler = new wv.codeclip.html.HtmlPasteHandler(
                repo, this,
                () -> {
                    refreshText();
                    refreshPanels();
                },
                this::appendTempLog,
                this::addClassPanel,
                this::onCodeChanged,
                undoManager
        );
        htmlPasteHandler.setRemovePanelCallback(this::removeClassPanel);
        genericPasteHandler = new wv.codeclip.generic.GenericPasteHandler(
                repo, this,
                () -> {
                    refreshText();
                    refreshPanels();
                },
                this::appendTempLog,
                this::addClassPanel,
                this::onCodeChanged,
                undoManager
        );
        genericPasteHandler.setRemovePanelCallback(this::removeClassPanel);
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
        setIcon();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setBounds(settings.loadFrameBounds());
        setLayout(new BorderLayout());

        buildUI();
        installDnD();

// Load persisted state
        currentMode = AppMode.valueOf(settings.loadMode());
        wv.codeclip.modecontext.ModeContext.setMode(currentMode);
        if (fileDropHandler != null) {
            fileDropHandler.setMode(currentMode);
        }
        updateDirectoryButton();
        updateModeLabel();
        notesBuffer = settings.loadNotes();
        includeInstructionsCheck.setSelected(settings.loadIncludeInstructions());
        smartPasteCheck.setSelected(settings.loadSmartPaste());
        autoReplaceInsertConflictItem.setSelected(settings.loadAutoReplaceOnInsertConflict());
        wireConflictResolver();
        SmartPasteSettings.load(settings);
        alwaysOnTopCheck.setSelected(settings.loadAlwaysOnTop());
        renderNotes();

        String[] savedPaths = settings.loadClassPaths();
        if (savedPaths.length == 0) {
// Nothing to load — don't show or leave a stuck load bar
        } else {
            showLoadBar();
            loadClassPathsBatched(savedPaths);
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
            if (attempts[0] >= 20) {
                titleTimer.stop();
            }
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
                settings.saveAutoReplaceOnInsertConflict(autoReplaceInsertConflictItem.isSelected());
                settings.saveAlwaysOnTop(alwaysOnTopCheck.isSelected());
                SmartPasteSettings.save(settings);
                wv.codeclip.godot.GodotDirectory.save(settings);
                wv.codeclip.html.HtmlDirectory.save(settings);
                wv.codeclip.generic.GenericDirectory.save(settings);
                settings.saveMode(currentMode.name());
                settings.saveClassPaths(
                        repo.getClassCodeMap().keySet().toArray(new String[0])
                );
                settings.saveProperties();
            }
        });

        setVisible(true);
        // Must be applied after setVisible — some window managers (notably
        // several Linux WMs, and Windows in certain focus states) silently
        // ignore setAlwaysOnTop calls made before the peer is realized, which
        // is why it previously only "worked" by accident depending on timing.
        SwingUtilities.invokeLater(() -> setAlwaysOnTop(alwaysOnTopCheck.isSelected()));
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
    public void focusLost(java.awt.event.FocusEvent e) {
    }

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
        showMissingItem.addActionListener(e
                -> showMissingFileMessages.setSelected(showMissingItem.isSelected()));
settingsMenu.add(showMissingItem);
        compileCheckItem = new JCheckBoxMenuItem(
"Verify compilation after patch (Java, requires JDK)",
wv.codeclip.patch.PostPatchVerifierSettings.isCompileCheckEnabled());
compileCheckItem.setToolTipText(
"Compiles only the files currently loaded in CodeClip after each patch/paste. "
+ "Reliable for catching mistakes in your own code (wrong argument counts, syntax errors); "
+ "may show false positives for types from libraries not loaded into CodeClip.");
compileCheckItem.addActionListener(e ->
wv.codeclip.patch.PostPatchVerifierSettings.setCompileCheckEnabled(compileCheckItem.isSelected()));
settingsMenu.add(compileCheckItem);
        JMenuItem languageItem = new JMenuItem("Language…");
        languageItem.addActionListener(e -> openLanguageDialog());
        settingsMenu.add(languageItem);
        JMenuItem mainClassItem = new JMenuItem("Change Main Class…");
        mainClassItem.setToolTipText("Java mode: change which class's folder new pasted classes default to.");
        mainClassItem.addActionListener(e -> openChangeMainClassDialog());
        settingsMenu.add(mainClassItem);
        autoReplaceInsertConflictItem = new JCheckBoxMenuItem("Auto-Replace Duplicate Methods");
        autoReplaceInsertConflictItem.setToolTipText(
                "Java mode only: when INSERT_METHOD finds an existing method with a different body, "
                + "automatically replace it and log a warning instead of showing a dialog.");
        autoReplaceInsertConflictItem.setVisible(currentMode == AppMode.JAVA);
        settingsMenu.add(autoReplaceInsertConflictItem);
        godotDirMenuItem = new JMenuItem("Godot Directory…");
        godotDirMenuItem.addActionListener(e -> openGodotDirectoryDialog());
        godotDirMenuItem.setVisible(false);
        settingsMenu.add(godotDirMenuItem);
        htmlDirMenuItem = new JMenuItem("HTML Directory…");
        htmlDirMenuItem.addActionListener(e -> openHtmlDirectoryDialog());
        htmlDirMenuItem.setVisible(false);
        settingsMenu.add(htmlDirMenuItem);
        fuzzySettingsMenuItem = new JMenuItem("Fuzzy Match Settings…");
        fuzzySettingsMenuItem.addActionListener(e ->
                new wv.codeclip.html.HtmlFuzzySettingsDialog(this).setVisible(true));
        fuzzySettingsMenuItem.setVisible(false);
        settingsMenu.add(fuzzySettingsMenuItem);
        genericDirMenuItem = new JMenuItem("Generic Directory…");
        genericDirMenuItem.addActionListener(e -> openGenericDirectoryDialog());
        genericDirMenuItem.setVisible(false);
        settingsMenu.add(genericDirMenuItem);
        genericFuzzySettingsMenuItem = new JMenuItem("Generic Fuzzy Match Settings…");
        genericFuzzySettingsMenuItem.addActionListener(e ->
                new wv.codeclip.generic.GenericFuzzySettingsDialog(this).setVisible(true));
        genericFuzzySettingsMenuItem.setVisible(false);
        settingsMenu.add(genericFuzzySettingsMenuItem);
        menuBar.add(settingsMenu);

        JMenu extraMenu = new JMenu("Extra");
        JMenuItem copyArchItem = new JMenuItem("Copy Architecture");
        copyArchItem.addActionListener(e -> actions.copyArchitecture());
        extraMenu.add(copyArchItem);
        JMenuItem timestampItem = new JMenuItem("Version Display…");
        timestampItem.addActionListener(e -> openTimestampDialog());
        extraMenu.add(timestampItem);
        JMenuItem copyEnablerItem = new JMenuItem("Copy Enable Instructions");
        copyEnablerItem.addActionListener(e -> {
            new ClipboardService().write(
                    "Use @@Enable to enable the classes you want\n\n"
                    + "@@Enable ClassName1, ClassName2, ClassName3"
            );
        });
        extraMenu.add(copyEnablerItem);
        JMenuItem copyCopierItem = new JMenuItem("Copy Copier Instructions");
        copyCopierItem.addActionListener(e -> {
            new ClipboardService().write(
                    "Use @@Copy to copy the classes you want see\n\n"
                    + "@@Copy ClassName1, ClassName2, ClassName3"
            );
        });
        extraMenu.add(copyCopierItem);
        JMenuItem copyMoveItem = new JMenuItem("Copy Move Instructions");
        copyMoveItem.addActionListener(e -> {
            new ClipboardService().write(
                    "Use @@Move to move/rename a loaded file\n\n"
                    + "@@Move OldName.ext -> new/relative/path.ext"
            );
        });
        extraMenu.add(copyMoveItem);
        JMenuItem copyDeleteItem = new JMenuItem("Copy Delete Instructions");
        copyDeleteItem.addActionListener(e -> {
            new ClipboardService().write(
                    "Use @@Delete to delete loaded files from disk\n\n"
                    + "@@Delete FileName1.ext, FileName2.ext"
            );
        });
        extraMenu.add(copyDeleteItem);
        JMenuItem copyCodemapItem = new JMenuItem("Copy Codemap");
        copyCodemapItem.addActionListener(e -> {
            String map = new wv.codeclip.codemap.CodeMapBuilder(repo).build(repo.getDisabledClasses());
            new ClipboardService().write(map);
        });
        extraMenu.add(copyCodemapItem);
        copyMetaItem = new JMenuItem("Copy Meta Instructions");
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
                versionHistory.clear();
                versionCurrentIdx = -1;
                refreshVersionPanel();
            }
        });
        systemMenu.add(resetItem);
        lastErrorMenuItem = new JMenuItem("Last Error");
        lastErrorMenuItem.setEnabled(false);
        lastErrorMenuItem.addActionListener(e -> {
            if (lastPatchErrors != null) {
                PatchErrorDialog.show(this, lastPatchErrors, repo);
            }
        });
        systemMenu.add(lastErrorMenuItem);

        JMenuItem renameProjectItem = new JMenuItem("Rename Project…");
        renameProjectItem.addActionListener(e -> {
            if (projectNameManager.promptForRename(this)) {
                titleFrozen = false;
                refreshTitle();
                setIcon();
            }
        });
        systemMenu.add(renameProjectItem);

        JMenuItem rerollColorItem = new JMenuItem("New Random Icon Color");
        rerollColorItem.addActionListener(e -> {
            projectNameManager.rerollIconTint();
            setIcon();
        });
        systemMenu.add(rerollColorItem);

        menuBar.add(systemMenu);

        JMenu protocolMenu = new JMenu("Protocol");
        JMenuItem protocolManagerItem = new JMenuItem("Protocol Manager…");
        protocolManagerItem.addActionListener(e -> openProtocolManagerDialog());
        protocolMenu.add(protocolManagerItem);
        menuBar.add(protocolMenu);

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

        syncProtocolUndoRedo = () -> {};

        undoBtn.addActionListener(e -> {
            try {
                wv.codeclip.patch.PatchUndoManager.Entry entry = undoManager.undo(repo);
                if (entry != null) {
                    refreshText();
                    refreshPanels();
                    restoreTimestampFromSnapshot(entry);
                    versionUndo();
                    pasteHandler.clearDuplicateHistory();
                    godotPasteHandler.clearDuplicateHistory();
                    htmlPasteHandler.clearDuplicateHistory();
                    genericPasteHandler.clearDuplicateHistory();
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
                    restoreTimestampFromSnapshot(entry);
                    versionRedo();
                }
            } catch (java.io.IOException ex) {
                JOptionPane.showMessageDialog(this, "Redo failed:\n" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
            syncUndoRedo.run();
        });

        pasteHandler.setPostPasteCallback((changed) -> {
            syncUndoRedo.run();
            if (changed) {
                stampBuildInfo();
                addVersionEventFromUndoTop();
            }
        });
        godotPasteHandler.setPostPasteCallback((changed) -> {
            syncUndoRedo.run();
            if (changed) {
                stampBuildInfo();
                addVersionEventFromUndoTop();
            }
        });
        htmlPasteHandler.setPostPasteCallback((changed) -> {
            syncUndoRedo.run();
            if (changed) {
                stampBuildInfo();
                addVersionEventFromUndoTop();
            }
        });
        genericPasteHandler.setPostPasteCallback((changed) -> {
            syncUndoRedo.run();
            if (changed) {
                stampBuildInfo();
                addVersionEventFromUndoTop();
            }
        });

        JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statsPanel.add(undoBtn);
        statsPanel.add(redoBtn);
        statsPanel.add(enabledCountLabel);
        statsPanel.add(charCountLabel);

        modeLabel = new JLabel(currentMode.toString());
        modeLabel.setFont(modeLabel.getFont().deriveFont(Font.BOLD));
        modeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        modePanel.add(modeLabel);

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.add(statsPanel, BorderLayout.WEST);
        topBar.add(modePanel, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

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
// Clear + refresh helper and key bindings (Esc / Enter)
        Runnable clearAndRefresh = () -> {
            classSearch.setText("");
            classSearch.requestFocus();
        };
        InputMap im = classSearch.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = classSearch.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearSearch");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "clearSearch");
        am.put("clearSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                clearAndRefresh.run();
            }
        });

        classPanel.setLayout(new BoxLayout(classPanel, BoxLayout.Y_AXIS));
        JScrollPane classScroll = new JScrollPane(classPanel);
        classScroll.getVerticalScrollBar().setUnitIncrement(16);

        classTreePanel = new ClassTreePanel(
                repo,
                () -> {
                    refreshText();
                    classTreePanel.refresh();
                },
                () -> classSearch.getText()
        );
        JScrollPane treeScroll = new JScrollPane(classTreePanel);
        treeScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel classViewStack = new JPanel(new CardLayout());
        classViewStack.add(classScroll, "list");
        classViewStack.add(treeScroll, "tree");

        JToggleButton treeToggleBtn = new JToggleButton("☰ List");
        treeToggleBtn.setFont(treeToggleBtn.getFont().deriveFont(Font.PLAIN, 12f));
        treeToggleBtn.setFocusable(false);
        treeToggleBtn.setToolTipText("Switch between list view and tree view");
        treeToggleBtn.addActionListener(e -> {
            treeViewActive = treeToggleBtn.isSelected();
            CardLayout cl = (CardLayout) classViewStack.getLayout();
            if (treeViewActive) {
                classTreePanel.refresh();
                cl.show(classViewStack, "tree");
                treeToggleBtn.setText("⊞ Tree");
            } else {
                cl.show(classViewStack, "list");
                treeToggleBtn.setText("☰ List");
            }
        });

        JButton enableAllBtn = new JButton("Enable All");
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

        JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
        JButton clearButton = new JButton("✕");
        clearButton.setFocusable(false);
        clearButton.setMargin(new Insets(0, 0, 0, 0));
        clearButton.setPreferredSize(new Dimension(28, 26));
        clearButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        clearButton.addActionListener(e -> classSearch.setText(""));
        searchPanel.add(clearButton, BorderLayout.WEST);
        searchPanel.add(classSearch, BorderLayout.CENTER);

        JPanel searchAndToggleRow = new JPanel(new BorderLayout(4, 0));
        searchAndToggleRow.add(treeToggleBtn, BorderLayout.WEST);
        searchAndToggleRow.add(searchPanel, BorderLayout.CENTER);

        JPanel classListPanel = new JPanel(new BorderLayout(0, 2));
        classListPanel.add(searchAndToggleRow, BorderLayout.NORTH);
        classListPanel.add(classViewStack, BorderLayout.CENTER);
        classListPanel.add(enableDisablePanel, BorderLayout.SOUTH);

        persistentLogPane = new JTextPane();
        persistentLogPane.setEditable(false);
        persistentLogPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane persistentLogScroll = new JScrollPane(persistentLogPane);

        JPanel logTabPanel = new JPanel(new BorderLayout());
        JPanel logToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JToggleButton sepToggleBtn = new JToggleButton("Separators: ON", true);
        sepToggleBtn.setFont(sepToggleBtn.getFont().deriveFont(Font.PLAIN, 11f));
        sepToggleBtn.setFocusable(false);
        sepToggleBtn.addActionListener(e -> {
            logSeparatorsVisible = sepToggleBtn.isSelected();
            sepToggleBtn.setText(logSeparatorsVisible ? "Separators: ON" : "Separators: OFF");
            rebuildPersistentLog();
        });
        logToolbar.add(sepToggleBtn);
        logTabPanel.add(logToolbar, BorderLayout.NORTH);
        logTabPanel.add(persistentLogScroll, BorderLayout.CENTER);

        versionPanel = new JPanel();
        versionPanel.setLayout(new BoxLayout(versionPanel, BoxLayout.Y_AXIS));
        JScrollPane versionScroll = new JScrollPane(versionPanel);
        versionScroll.getVerticalScrollBar().setUnitIncrement(16);

        notesTabs = new JTabbedPane();
        notesTabs.addTab("Notes", notesScroll);
        notesTabs.addTab("Log", logTabPanel);
        notesTabs.addTab("Versions", versionScroll);

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, notesTabs, classListPanel);
        split.setResizeWeight(0.7);

        SwingUtilities.invokeLater(() -> {
            int divider = settings.loadDividerPosition();
            if (divider > 0) {
                split.setDividerLocation(divider);
            }
        });

        add(split, BorderLayout.CENTER);

// --- Bottom bar ---
        JButton pasteClass = new JButton("Paste");
        JButton update = new JButton("Update All");
        JButton copy = new JButton("Copy All");
        JButton copyCode = new JButton("Copy Code Only");
        JButton copyInstructions = new JButton("Copy Instructions");
        lastErrorBtn = new JButton("Last Error");
        lastErrorBtn.setEnabled(false);

        alwaysOnTopCheck.addActionListener(e -> {
            setAlwaysOnTop(alwaysOnTopCheck.isSelected());
            settings.saveAlwaysOnTop(alwaysOnTopCheck.isSelected());
        });

        pasteClass.addActionListener(e -> {
            // Protocol commands in the clipboard are always extracted and
            // routed to review, regardless of the "Include Protocol"
            // checkbox (that checkbox only controls Copy All's output).
            // This never blocks or consumes the rest of the clipboard —
            // handleProtocolPasteIfPresent() only reacts to @@protocol
            // blocks and leaves everything else untouched, so normal paste
            // handling below always still runs against the same text.
            handleProtocolPasteIfPresent();
            syncUndoRedo.run();
            syncProtocolUndoRedo.run();
            showPasteBusyBar();
            try {
                if (wv.codeclip.modecontext.ModeContext.isGodotMode()) {
                    // Godot mode has no Smart Paste — always single-block.
                    godotPasteHandler.handlePasteFromClipboard();
                } else if (wv.codeclip.modecontext.ModeContext.isHtmlMode()) {
                    // HTML mode has its own Smart Paste, fully separate from
                    // PasteClassHandler's Java-oriented Smart Paste.
                    if (smartPasteCheck.isSelected()) {
                        htmlPasteHandler.handleSmartPasteFromClipboard();
                    } else {
                        htmlPasteHandler.handlePasteFromClipboard();
                    }
                } else if (wv.codeclip.modecontext.ModeContext.isGenericMode()) {
                    if (smartPasteCheck.isSelected()) {
                        genericPasteHandler.handleSmartPasteFromClipboard();
                    } else {
                        genericPasteHandler.handlePasteFromClipboard();
                    }
                } else {
                    pasteHandler.handlePasteFromClipboard();
                }
            } finally {
                hidePasteBusyBar();
            }
            syncUndoRedo.run();
        });

        update.addActionListener(e -> actions.updateAll(this::refreshText, this::removeClassPanel));
        copy.addActionListener(e -> {
            String protocolAppendix = includeProtocolCheck.isSelected() ? buildProtocolAppendixForCopy() : null;
            actions.copyAll(this::clearTempLogs, notesBuffer, protocolAppendix);
        });
        copyCode.addActionListener(e -> actions.copyCodeOnly());
        copyInstructions.addActionListener(e -> new ClipboardService().write(currentMode.getInstructions()));

        smartPasteCheck.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    new SmartPasteSettingsDialog(CodeClipFrame.this, settings).setVisible(true);
                }
            }
        });

// Paste + Smart Paste + Always On Top stacked, pinned to left
        JPanel pasteCheckStack = new JPanel();
        pasteCheckStack.setLayout(new BoxLayout(pasteCheckStack, BoxLayout.Y_AXIS));
        smartPasteCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        alwaysOnTopCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        pasteCheckStack.add(smartPasteCheck);
        pasteCheckStack.add(alwaysOnTopCheck);

        JPanel pasteGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        pasteGroup.add(pasteClass);
        pasteGroup.add(pasteCheckStack);

// Right side grid
        JPanel rightButtons = new JPanel(new GridLayout(0, 3, 5, 5));
        rightButtons.add(update);
        rightButtons.add(copy);
        rightButtons.add(copyCode);
        rightButtons.add(copyInstructions);
        rightButtons.add(includeInstructionsCheck);
        rightButtons.add(includeProtocolCheck);

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
        addRawLogLine(message, false);
        insertPersistentLogLine(message, false);
        logLineCount++;
        SwingUtilities.invokeLater(() -> notesTextPane.setCaretPosition(0));
    }

    public void clearTempLogs() {
        if (!logBuffer.isEmpty()) {
            logBuffer = "";
            renderNotes();
            // Insert a separator into the persistent log
            String sep = "── cleared ─────────────────────────";
            addRawLogLine(sep, true);
            if (logSeparatorsVisible) {
                insertPersistentLogLine(sep, true);
            }
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
                    if (line.isEmpty()) {
                        continue;
                    }
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
     * Parses a log line and inserts it with the class name (or patch target)
     * highlighted.
     *
     * Handles two formats: "Class Created: Foo (path)" → prefix | Foo | path "✓
     * FindReplace in Foo.java" → prefix | Foo.java
     */
    private void appendLogLine(StyledDocument doc, String line,
            Style base, Style highlight)
            throws BadLocationException {

        if (line.startsWith("Copy ERROR:")) {
            SimpleAttributeSet err = new SimpleAttributeSet();
            StyleConstants.setFontFamily(err, Font.MONOSPACED);
            StyleConstants.setFontSize(err, 12);
            StyleConstants.setBold(err, true);
            StyleConstants.setForeground(err, LOG_ERROR_COLOR);
            doc.insertString(doc.getLength(), line, err);
            return;
        }

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
        String rest = line.substring(colonSpace + 2);

        int parenIdx = rest.indexOf(" (");
        if (parenIdx < 0) {
            doc.insertString(doc.getLength(), prefix, base);
            doc.insertString(doc.getLength(), rest, highlight);
            return;
        }

        String name = rest.substring(0, parenIdx);
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

private void setIcon() {
        setIconImage(wv.codeclip.mainFrame.AppIconFactory.build(projectNameManager.getIconTintSeed()));
    }

private void showLoadBar() {
        loadBarWindow = new JWindow(this);
        loadProgressBar = new JProgressBar(0, 100);
        loadProgressBar.setValue(0);
        loadProgressBar.setStringPainted(false);
        loadProgressBar.setPreferredSize(new java.awt.Dimension(280, 7));
        loadProgressBar.setBorderPainted(false);

        loadProgressLabel = new JLabel("Loading…");
        loadProgressLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        loadProgressLabel.setForeground(UIManager.getColor("Label.foreground"));

        JLabel subLabel = new JLabel("Reading class files");
        subLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        subLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        subLabel.setName("subLabel");

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground"), 1),
                BorderFactory.createEmptyBorder(12, 16, 14, 16)
        ));
        inner.setBackground(UIManager.getColor("Panel.background"));
        inner.add(loadProgressLabel);
        inner.add(Box.createVerticalStrut(3));
        inner.add(subLabel);
        inner.add(Box.createVerticalStrut(10));
        inner.add(loadProgressBar);

        loadBarWindow.setContentPane(inner);
        loadBarWindow.pack();

// Position bottom-right of frame
        Rectangle b = getBounds();
        int wx = b.x + b.width - loadBarWindow.getWidth() - 16;
        int wy = b.y + b.height - loadBarWindow.getHeight() - 16;
        loadBarWindow.setLocation(wx, wy);
        loadBarWindow.setVisible(true);
    }

    private void updateLoadBar(int done, int total) {
        if (loadBarWindow == null || !loadBarWindow.isVisible()) {
            return;
        }
        int pct = (int) Math.round(done * 100.0 / total);
        loadProgressBar.setValue(pct);
        loadProgressLabel.setText("Loading " + done + " of " + total + "…");
    }

    private void hideLoadBar() {
        if (loadBarWindow != null) {
            loadBarWindow.setVisible(false);
            loadBarWindow.dispose();
            loadBarWindow = null;
        }
    }

/**
     * Reuses the same JWindow-based bar as class loading, but in indeterminate
     * mode: paste is a single synchronous call with no discrete step count to
     * report, so a moving/pulsing bar is the honest signal — it tells the
     * person something is happening without inventing a fake percentage.
     */
    private void showPasteBusyBar() {
        loadBarWindow = new JWindow(this);
        loadProgressBar = new JProgressBar();
        loadProgressBar.setIndeterminate(true);
        loadProgressBar.setPreferredSize(new java.awt.Dimension(280, 7));
        loadProgressBar.setBorderPainted(false);

        loadProgressLabel = new JLabel("Pasting…");
        loadProgressLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        loadProgressLabel.setForeground(UIManager.getColor("Label.foreground"));

        JLabel subLabel = new JLabel("Parsing clipboard content");
        subLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        subLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground"), 1),
                BorderFactory.createEmptyBorder(12, 16, 14, 16)
        ));
        inner.setBackground(UIManager.getColor("Panel.background"));
        inner.add(loadProgressLabel);
        inner.add(Box.createVerticalStrut(3));
        inner.add(subLabel);
        inner.add(Box.createVerticalStrut(10));
        inner.add(loadProgressBar);

        loadBarWindow.setContentPane(inner);
        loadBarWindow.pack();

        Rectangle b = getBounds();
        int wx = b.x + b.width - loadBarWindow.getWidth() - 16;
        int wy = b.y + b.height - loadBarWindow.getHeight() - 16;
        loadBarWindow.setLocation(wx, wy);
        loadBarWindow.setVisible(true);
        // Paint immediately — the paste call below runs synchronously on the
        // EDT and would otherwise block before this window ever gets drawn.
        loadBarWindow.paint(loadBarWindow.getGraphics());
    }

    private void hidePasteBusyBar() {
        hideLoadBar();
    }

private void installDnD() {
        fileDropHandler = new FileDropHandler(this::addFilesBatched, true);
        fileDropHandler.setMode(currentMode);
        fileDropHandler.install(this);
    }

    private void openLanguageDialog() {
        AppMode[] modes = AppMode.values();
        AppMode selected = (AppMode) JOptionPane.showInputDialog(
                this,
                "Select language mode:",
                "Language",
                JOptionPane.QUESTION_MESSAGE,
                null,
                modes,
                currentMode
        );
        if (selected != null && selected != currentMode) {
            currentMode = selected;
            if (fileDropHandler != null) {
                fileDropHandler.setMode(currentMode);
            }
            wv.codeclip.modecontext.ModeContext.setMode(currentMode);
            updateDirectoryButton();
            updateModeLabel();
            refreshText();
            refreshPanels();
        }
    }

/**
     * Lets the user clear or directly retype the saved "preferred main class"
     * used by SourceRootDetector to decide which folder new pasted classes
     * land in, when the project has multiple classes containing main().
     */
    private void openChangeMainClassDialog() {
        String current = settings.loadPreferredMainClass();

        JTextField field = new JTextField(current != null ? current : "");
        field.setFont(field.getFont().deriveFont(13f));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JLabel label = new JLabel(
                (current == null || current.isEmpty())
                        ? "No main class saved yet — it will be asked for on the next ambiguous paste."
                        : "Currently saved: " + current);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(label, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(420, 60));

        Object[] options = {"Save", "Clear (ask again next time)", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                this, panel, "Change Main Class",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, options, options[0]);

        if (choice == 0) {
            String newValue = field.getText().trim();
            settings.savePreferredMainClass(newValue);
            settings.saveProperties();
        } else if (choice == 1) {
            settings.savePreferredMainClass("");
            settings.saveProperties();
        }
    }

/**
     * Keeps the top-right mode label in sync with currentMode. Must be called
     * any time currentMode changes (startup load and the Language… dialog) —
     * modeLabel is otherwise only initialized once in buildUI() and silently
     * goes stale.
     */
    private void updateModeLabel() {
        if (modeLabel != null) {
            modeLabel.setText(currentMode.toString());
        }
    }

    private void addClass(File file) {
        addFilesBatched(List.of(file));
    }

    private void addFilesBatched(List<File> files) {
        if (files.isEmpty()) {
            return;
        }

        List<File> toLoad = new ArrayList<>();
        for (File file : files) {
            String path = file.getAbsolutePath();
            if (repo.getClassCodeMap().containsKey(path)) {
                repo.getDisabledClasses().remove(path);
            } else {
                toLoad.add(file);
            }
        }

        if (toLoad.isEmpty()) {
            refreshText();
            refreshPanels();
            return;
        }

        int total = toLoad.size();
        if (loadBarWindow == null || !loadBarWindow.isVisible()) {
            showLoadBar();
        }

        java.util.concurrent.atomic.AtomicInteger remaining
                = new java.util.concurrent.atomic.AtomicInteger(total);
        java.util.concurrent.atomic.AtomicInteger loaded
                = new java.util.concurrent.atomic.AtomicInteger(0);

        for (File file : toLoad) {
            String path = file.getAbsolutePath();
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
                        if (file.getName().equals(BUILD_INFO_FILE)) {
                            refreshTitle();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        int done = loaded.incrementAndGet();
                        SwingUtilities.invokeLater(() -> updateLoadBar(done, total));
                        if (remaining.decrementAndGet() == 0) {
                            SwingUtilities.invokeLater(() -> {
                                if (wv.codeclip.modecontext.ModeContext.isGodotMode()) {
                                    autoSetGodotDirectoryFromRepo(toLoad);
                                }
                                refreshText();
                                refreshPanels();
                                hideLoadBar();
                            });
                        }
                    }
                }
            };
            worker.execute();
        }
    }

    private void addClassInternal(File file, boolean doRefresh) {
        String path = file.getAbsolutePath();

        if (repo.getClassCodeMap().containsKey(path)) {
            if (repo.getDisabledClasses().remove(path)) {
                if (doRefresh) {
                    refreshText();
                    refreshPanels();
                }
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
                    if (doRefresh) {
                        refreshText();
                        refreshPanels();
                    }
                    if (file.getName().equals(BUILD_INFO_FILE)) {
                        refreshTitle();
                    }
                } catch (Exception ignored) {
                }
            }
        };
        worker.execute();
    }

    private void loadClassPathsBatched(String[] paths) {
        if (paths.length == 0) {
            return;
        }

        java.util.concurrent.atomic.AtomicInteger remaining
                = new java.util.concurrent.atomic.AtomicInteger(paths.length);
        java.util.concurrent.atomic.AtomicInteger loaded
                = new java.util.concurrent.atomic.AtomicInteger(0);
        int total = paths.length;

        for (String path : paths) {
            File f = new File(path);
            if (!f.exists()) {
                int done = loaded.incrementAndGet();
                SwingUtilities.invokeLater(() -> updateLoadBar(done, total));
                if (remaining.decrementAndGet() == 0) {
                    SwingUtilities.invokeLater(() -> {
                        if (wv.codeclip.modecontext.ModeContext.isGodotMode()) {
                            autoSetGodotDirectoryFromRepo(null);
                        }
                        refreshText();
                        refreshPanels();
                        hideLoadBar();
                    });
                }
                continue;
            }

            String absPath = f.getAbsolutePath();
            if (repo.getClassCodeMap().containsKey(absPath)) {
                repo.getDisabledClasses().remove(absPath);
                int done = loaded.incrementAndGet();
                SwingUtilities.invokeLater(() -> updateLoadBar(done, total));
                if (remaining.decrementAndGet() == 0) {
                    SwingUtilities.invokeLater(() -> {
                        if (wv.codeclip.modecontext.ModeContext.isGodotMode()) {
                            autoSetGodotDirectoryFromRepo(null);
                        }
                        refreshText();
                        refreshPanels();
                        hideLoadBar();
                    });
                }
                continue;
            }

            SwingWorker<String, Void> worker = new SwingWorker<>() {
                @Override
                protected String doInBackground() throws Exception {
                    return Files.readString(f.toPath());
                }

                @Override
                protected void done() {
                    try {
                        String code = get();
                        repo.getClassCodeMap().put(absPath, code);
                        repo.getClassFileMap().put(absPath, f);
                        repo.setCheckpoint(absPath, code);
                        addClassPanel(absPath, f.getName());
                        if (f.getName().equals(BUILD_INFO_FILE)) {
                            refreshTitle();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        int done = loaded.incrementAndGet();
                        SwingUtilities.invokeLater(() -> updateLoadBar(done, total));
                        if (remaining.decrementAndGet() == 0) {
                            SwingUtilities.invokeLater(() -> {
                                if (wv.codeclip.modecontext.ModeContext.isGodotMode()) {
                                    autoSetGodotDirectoryFromRepo(null);
                                }
                                refreshText();
                                refreshPanels();
                                hideLoadBar();
                            });
                        }
                    }
                }
            };
            worker.execute();
        }
    }

// ------------------------------------------------------------------
// Class panels
// ------------------------------------------------------------------

public void addClassPanel(String path, String name) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setOpaque(true);
        panel.setBackground(wv.codeclip.modecontext.ModeColors.getEnabledBackground());
        panel.putClientProperty("path", path);
        panel.putClientProperty("name", name);

        JLabel label = new JLabel(name);
        label.setToolTipText("In sync with checkpoint");
        JButton toggle = new JButton("Disable");
        JButton copy = new JButton("Copy");
        JButton more = new JButton("...");
        panel.putClientProperty("label", label);

        toggle.addActionListener(e -> {
            if (repo.getDisabledClasses().remove(path)) {
                toggle.setText("Disable");
                panel.setBackground(wv.codeclip.modecontext.ModeColors.getEnabledBackground());
            } else {
                repo.getDisabledClasses().add(path);
                toggle.setText("Enable");
                panel.setBackground(wv.codeclip.modecontext.ModeColors.getDisabledBackground());
            }
            refreshText();
        });

        copy.addActionListener(e -> {
            String code = repo.getClassCodeMap().get(path);
            if (code != null) {
                String prefix = wv.codeclip.modecontext.ModeContext.getCommentPrefix();
                String text = prefix + " ===== " + name + " =====\n" + code + "\n";
                Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(text), null);
            }
        });

        more.setToolTipText("Directory, edit, play, open file location, delete");
        more.addActionListener(e -> {
            File f = repo.getClassFileMap().get(path);
            wv.codeclip.ui.FileActionsDialog.show(CodeClipFrame.this, f,
                    deletedPath -> {
                        repo.getClassCodeMap().remove(deletedPath);
                        repo.getClassFileMap().remove(deletedPath);
                        repo.getDisabledClasses().remove(deletedPath);
                        removeClassPanel(deletedPath);
                        File onDisk = new File(deletedPath);
                        if (onDisk.exists()) {
                            onDisk.delete();
                        }
                        refreshText();
                        refreshPanels();
                    },
                    removedPath -> {
                        repo.getClassCodeMap().remove(removedPath);
                        repo.getClassFileMap().remove(removedPath);
                        repo.getDisabledClasses().remove(removedPath);
                        removeClassPanel(removedPath);
                        refreshText();
                        refreshPanels();
                    });
        });

        panel.add(label);
        panel.add(toggle);
        panel.add(copy);
        panel.add(more);

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
        String prefix = wv.codeclip.modecontext.ModeContext.getCommentPrefix();
        StringBuilder sb = new StringBuilder();
        repo.getClassCodeMap().forEach((path, code) -> {
            if (!repo.getDisabledClasses().contains(path)) {
                File file = repo.getClassFileMap().get(path);
                String name = (file != null) ? file.getName() : path;
                sb.append(prefix).append(" ===== ").append(name).append(" =====\n");
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

                    panel.setBackground(disabled
                            ? ModeColors.getDisabledBackground()
                            : ModeColors.getEnabledBackground());
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
            case 0 ->
                Comparator.comparingInt(PanelEntry::insertionIdx);
            case 1 ->
                Comparator.comparing(e -> e.name().toLowerCase());
            case 2 ->
                Comparator
                .comparingInt((PanelEntry e) -> e.disabled() ? 1 : 0)
                .thenComparingInt(PanelEntry::insertionIdx);
            case 3 ->
                Comparator
                .comparingInt((PanelEntry e) -> e.disabled() ? 1 : 0)
                .thenComparing(e -> e.name().toLowerCase());
            default ->
                Comparator.comparingInt(PanelEntry::insertionIdx);
        };
        entries.sort(comparator);

        classPanel.removeAll();
        for (PanelEntry entry : entries) {
            classPanel.add(entry.panel());
        }
        classPanel.revalidate();
        classPanel.repaint();

        if (treeViewActive && classTreePanel != null) {
            classTreePanel.refresh();
        }
    }

    private record PanelEntry(JPanel panel, String path, String name,
            boolean disabled, int insertionIdx) {

    }

    // Converted from record to mutable class so the title can be renamed
    private static class VersionEvent {
        private String title;
        private final String files;
        private final String timestamp;
        private final String targetBuild;      // BUILD_NO at the time of this version
        private final java.util.List<String> allTitles; // every @@TITLE: in a batch, in order
        private boolean isCheckpoint;

        VersionEvent(String title, String files, String timestamp, String targetBuild,
                     java.util.List<String> allTitles, boolean isCheckpoint) {
            this.title = title;
            this.files = files;
            this.timestamp = timestamp;
            this.targetBuild = targetBuild;
            this.allTitles = allTitles != null ? allTitles : java.util.List.of(title);
            this.isCheckpoint = isCheckpoint;
        }

        String title() { return title; }
        void setTitle(String title) { this.title = title; }
        String files() { return files; }
        String timestamp() { return timestamp; }
        String targetBuild() { return targetBuild; }
        java.util.List<String> allTitles() { return allTitles; }
        boolean isCheckpoint() { return isCheckpoint; }
    }

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
        if (btn == null) {
            return;
        }
        boolean allInSync = !repo.hasPendingRestores();
        btn.setText(allInSync ? "Checkpoint ✓" : "Checkpoint");
        btn.setForeground(UIManager.getColor("Button.foreground"));
    }

    private boolean isUnsynced(String path) {
        String current = repo.getClassCodeMap().get(path);
        String checkpoint = repo.getCheckpointCodeMap().get(path);
        if (current == null || checkpoint == null) {
            return false;
        }
        return !current.equals(checkpoint);
    }

    private void restoreTimestampFromSnapshot(wv.codeclip.patch.PatchUndoManager.Entry entry) {
        if (entry == null) {
            return;
        }
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
        getBuildInfoStamper().stampWithContent(content, build -> pendingTargetBuild = build);
    }

private String extractTimestampFromContent(String content) {
        if (content == null) {
            return null;
        }
        for (String line : content.split("\n")) {
            if (line.startsWith("LAST_UPDATED=")) {
                return line.substring("LAST_UPDATED=".length()).trim();
            }
        }
        return null;
    }

    private String extractBuildNoFromContent(String content) {
        if (content == null) {
            return "?";
        }
        for (String line : content.split("\n")) {
            if (line.startsWith("BUILD_NO=")) {
                return line.substring("BUILD_NO=".length()).trim();
            }
        }
        return "?";
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
                    try {
                        content = java.nio.file.Files.readString(file.toPath());
                    } catch (java.io.IOException ignored) {
                    }
                }
                if (content != null) {
                    foundTimestamp = extractTimestampFromContent(content);
                    if (foundTimestamp == null) {
                        foundTimestamp = "";
                    }
                }
            }
        }

        final boolean tsExists = hasTimestamp;
        final String tsPath = timestampPath;
        final String tsValue = foundTimestamp;

        String instructions
                = "buildinfo.properties is auto-generated by CodeClip.\n"
                + "It is placed directly in your source root.\n\n"
                + "Format:\n"
                + "  LAST_UPDATED=Wed-14:32:05\n"
                + "  BUILD_NO=2S\n\n"
                + "LAST_UPDATED format: day-of-week abbreviation, hour, minute, second.\n\n"
                + "BUILD_NO is an auto-incrementing counter in base-36 (0-9, A-Z).\n"
                + "It starts at 1 and increments every time CodeClip writes a change.\n"
                + "Parse it back to an integer with: Integer.parseInt(value, 36)\n\n"
                + "IMPORTANT: Read these values once at startup and store them.\n"
                + "Do NOT poll at runtime — they only change when CodeClip makes a change.\n\n"
                + "Suggested usage:\n"
                + "  Properties p = new Properties();\n"
                + "  p.load(new FileReader(\"buildinfo.properties\"));\n"
                + "  String buildTime = p.getProperty(\"LAST_UPDATED\");\n"
                + "  String buildNo   = p.getProperty(\"BUILD_NO\");\n\n"
                + "Or if it is on the classpath:\n"
                + "  InputStream is = MyClass.class.getResourceAsStream(\"/buildinfo.properties\");\n"
                + "  p.load(is);\n\n"
                + "CodeClip updates this file automatically whenever it applies\n"
                + "a patch, pastes a class, or performs an undo/redo.\n"
                + "Undo/redo will revert both values to what they were before that change.";

        JDialog dialog = new JDialog(this, "Version Display", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JTextArea info = new JTextArea(instructions);
        info.setEditable(false);
        info.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        info.setBackground(UIManager.getColor("Panel.background"));
        info.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        dialog.add(new JScrollPane(info), BorderLayout.CENTER);

List<JLabel> topLabels = new ArrayList<>();
if (tsExists && !tsValue.isEmpty()) {
JLabel tsLabel = new JLabel("Last recorded timestamp: " + tsValue);
tsLabel.setFont(tsLabel.getFont().deriveFont(Font.ITALIC));
topLabels.add(tsLabel);
}
if (repo.getLastChangeKind() != null) {
String kindLabel = switch (repo.getLastChangeKind()) {
case NEW -> "Created (new file)";
case WHOLE_UPDATE -> "Updated \u2014 whole file";
case PATCH_UPDATE -> "Updated \u2014 surgical patch";
};
String fileLabel = computeChangeLabel(repo.getLastChangedPath());
JLabel changeLabel = new JLabel("Last change: " + fileLabel + " \u2014 " + kindLabel);
changeLabel.setFont(changeLabel.getFont().deriveFont(Font.ITALIC));
topLabels.add(changeLabel);
}
if (!topLabels.isEmpty()) {
JPanel topPanel = new JPanel();
topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
topPanel.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
for (JLabel lbl : topLabels) {
lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
topPanel.add(lbl);
}
dialog.add(topPanel, BorderLayout.NORTH);
}

        JButton copyInstrBtn = new JButton("Copy Instructions");
        JButton copyPathBtn = new JButton("Copy File Path");
        JButton copyBothBtn = new JButton("Copy Both");
        JButton closeBtn = new JButton("Close");

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

private void refreshTitle() {
        if (titleFrozen) {
            return;
        }
        String info = wv.codeclip.config.CodeClipBuildInfo.getBuildInfo();
        if (!info.equals("unknown")) {
            setTitle(buildWindowTitle(info));
            titleFrozen = true;
        }
    }

private String buildWindowTitle(String buildInfo) {
        String project = projectNameManager.hasProjectName() ? projectNameManager.getProjectName() : null;
        if (project != null) {
            return "Code Clip — " + project + " — " + buildInfo;
        }
        return "Code Clip — " + buildInfo;
    }

private void restoreBuildInfoTitle() {
        refreshTitle();
    }

    private void restoreBuildInfoTitleFromDisk() {
// Approach 1: walk parent dirs of all loaded files
        for (java.io.File file : new ArrayList<>(repo.getClassFileMap().values())) {
            if (file == null) {
                continue;
            }
            java.io.File dir = file.getParentFile();
            for (int depth = 0; depth < 6 && dir != null; depth++) {
                java.io.File candidate = new java.io.File(dir, BUILD_INFO_FILE);
                if (candidate.exists()) {
                    if (tryRegisterAndSetTitle(candidate)) {
                        return;
                    }
                }
                dir = dir.getParentFile();
            }
        }

// Approach 2: use detectSourceRoot directly
        java.io.File sourceRoot = detectSourceRoot();
        if (sourceRoot != null) {
            java.io.File candidate = new java.io.File(sourceRoot, BUILD_INFO_FILE);
            if (candidate.exists()) {
                tryRegisterAndSetTitle(candidate);
            }
        }
    }

    private boolean tryRegisterAndSetTitle(java.io.File candidate) {
        try {
            String content = java.nio.file.Files.readString(candidate.toPath());
            String timestamp = extractTimestampFromContent(content);
            if (timestamp != null) {
                String path = candidate.getAbsolutePath();
                repo.getClassCodeMap().put(path, content);
                repo.getClassFileMap().put(path, candidate);
                repo.setCheckpoint(path, content);
                refreshTitle();
                return true;
            }
        } catch (java.io.IOException ignored) {
        }
        return false;
    }

private void stampBuildInfo() {
        if (!projectNameManager.hasProjectName()) {
            projectNameManager.promptForNameIfMissing(this);
            titleFrozen = false;
        }
        getBuildInfoStamper().stamp();
    }

private wv.codeclip.mainFrame.BuildInfoStamper getBuildInfoStamper() {
        if (buildInfoStamper == null) {
            buildInfoStamper = new wv.codeclip.mainFrame.BuildInfoStamper(
                    repo,
                    undoManager,
                    this::appendTempLog,
                    this::refreshText,
                    this::refreshTitle,
                    this::addClassPanel,
                    path -> {
                        for (java.awt.Component c : classPanel.getComponents()) {
                            if (c instanceof JPanel p
                                    && path.equals(p.getClientProperty("path"))) {
                                return true;
                            }
                        }
                        return false;
                    },
                    build -> pendingTargetBuild = build
            );
        }
        return buildInfoStamper;
    }

private java.io.File detectSourceRoot() {
        return getBuildInfoStamper().detectSourceRoot();
    }

private void openCheckpointDialog() {
        if (checkpointDialog == null || !checkpointDialog.isDisplayable()) {
            checkpointDialog = new CheckpointDialog(this, repo, () -> {
                refreshText();
                refreshPanels();
                stampBuildInfo();
                if (pendingIsCheckpoint) {
                    addCheckpointVersionEvent();
                }
            });
        } else {
            checkpointDialog.setRefreshCallback(() -> {
                refreshText();
                refreshPanels();
                stampBuildInfo();
                if (pendingIsCheckpoint) {
                    addCheckpointVersionEvent();
                }
            });
        }
        checkpointDialog.setOnCheckpointSetCallback(this::markNextVersionAsCheckpoint);
        checkpointDialog.refresh();
        checkpointDialog.setVisible(true);
    }

public void setLastPatchError(List<PatchApplier.PatchResult> results) {
        lastPatchErrors = (results != null && !results.isEmpty()) ? results : null;
        boolean hasError = lastPatchErrors != null;
        lastErrorBtn.setEnabled(hasError);
        if (lastErrorMenuItem != null) {
            lastErrorMenuItem.setEnabled(hasError);
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

private void updateDirectoryButton() {
        if (godotDirMenuItem == null) {
            return;
        }
        boolean godot = wv.codeclip.modecontext.ModeContext.isGodotMode();
        boolean html = wv.codeclip.modecontext.ModeContext.isHtmlMode();
        boolean generic = wv.codeclip.modecontext.ModeContext.isGenericMode();
        boolean isJava = !godot && !html && !generic;
        godotDirMenuItem.setVisible(godot);
        if (htmlDirMenuItem != null) {
            htmlDirMenuItem.setVisible(html);
        }
        if (fuzzySettingsMenuItem != null) {
            fuzzySettingsMenuItem.setVisible(html);
        }
        if (genericDirMenuItem != null) {
            genericDirMenuItem.setVisible(generic);
        }
        if (genericFuzzySettingsMenuItem != null) {
            genericFuzzySettingsMenuItem.setVisible(generic);
        }
        if (copyMetaItem != null) {
            copyMetaItem.setVisible(isJava);
        }
        if (autoReplaceInsertConflictItem != null) {
            autoReplaceInsertConflictItem.setVisible(isJava);
        }
        if (compileCheckItem != null) {
            compileCheckItem.setVisible(isJava);
            if (!isJava && compileCheckItem.isSelected()) {
                // Don't just hide it — a hidden-but-still-checked item leaves the
                // compile-check flag silently armed with no visible way to turn it
                // off until switching back to Java. Force it off at the source.
                compileCheckItem.setSelected(false);
                wv.codeclip.patch.PostPatchVerifierSettings.setCompileCheckEnabled(false);
            }
        }
    }

/**
     * Wires a shared InsertConflictResolver into both paste handlers. Called
     * once after settings are loaded, and whenever the resolver logic needs
     * updating. The resolver either shows the dialog or auto-replaces based on
     * the menu setting.
     */
    private void wireConflictResolver() {
        PatchApplier.InsertConflictResolver resolver = (methodName, existingCode, incomingCode) -> {
            if (autoReplaceInsertConflictItem != null && autoReplaceInsertConflictItem.isSelected()) {
                appendTempLog("Auto-replaced duplicate method: " + methodName);
                return true;
            }
            InsertMethodConflictDialog.Choice choice
                    = InsertMethodConflictDialog.show(this, methodName, existingCode, incomingCode);
            return choice == InsertMethodConflictDialog.Choice.REPLACE;
        };
        pasteHandler.setConflictResolver(resolver);

    }

    private void refreshDirectoryButtonLabel() {
    }

    private void openGodotDirectoryDialog() {
        java.io.File current = wv.codeclip.godot.GodotDirectory.get();

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel titleLabel = new JLabel("Godot Project Directory");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        panel.add(titleLabel, BorderLayout.NORTH);

        JTextArea dirDisplay = new JTextArea(current != null ? current.getAbsolutePath() : "(not set)");
        dirDisplay.setEditable(false);
        dirDisplay.setLineWrap(true);
        dirDisplay.setWrapStyleWord(false);
        dirDisplay.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        dirDisplay.setBackground(UIManager.getColor("Panel.background"));
        dirDisplay.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground"), 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        dirDisplay.setRows(3);
        dirDisplay.setColumns(40);
        panel.add(new JScrollPane(dirDisplay), BorderLayout.CENTER);

        JButton setNewBtn = new JButton("Set New Directory…");
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btnRow.add(setNewBtn);
        panel.add(btnRow, BorderLayout.SOUTH);

        JDialog dialog = new JDialog(this, "Godot Directory", true);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        dialog.add(panel, BorderLayout.CENTER);

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());
        JPanel footerRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        footerRow.add(closeBtn);
        dialog.add(footerRow, BorderLayout.SOUTH);

        setNewBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Godot Project Directory");
            if (current != null) {
                chooser.setCurrentDirectory(current);
            }
            int result = chooser.showOpenDialog(dialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                java.io.File chosen = chooser.getSelectedFile();
                wv.codeclip.godot.GodotDirectory.set(chosen);
                dirDisplay.setText(chosen.getAbsolutePath());
            }
        });

        dialog.pack();
        dialog.setMinimumSize(new Dimension(420, 200));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

private void openHtmlDirectoryDialog() {
        java.io.File current = wv.codeclip.html.HtmlDirectory.get();

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel titleLabel = new JLabel("HTML Project Directory");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        panel.add(titleLabel, BorderLayout.NORTH);

        JTextArea dirDisplay = new JTextArea(current != null ? current.getAbsolutePath() : "(not set)");
        dirDisplay.setEditable(false);
        dirDisplay.setLineWrap(true);
        dirDisplay.setWrapStyleWord(false);
        dirDisplay.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        dirDisplay.setBackground(UIManager.getColor("Panel.background"));
        dirDisplay.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground"), 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        dirDisplay.setRows(3);
        dirDisplay.setColumns(40);
        panel.add(new JScrollPane(dirDisplay), BorderLayout.CENTER);

        JButton setNewBtn = new JButton("Set New Directory…");
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btnRow.add(setNewBtn);
        panel.add(btnRow, BorderLayout.SOUTH);

        JDialog dialog = new JDialog(this, "HTML Directory", true);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        dialog.add(panel, BorderLayout.CENTER);

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());
        JPanel footerRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        footerRow.add(closeBtn);
        dialog.add(footerRow, BorderLayout.SOUTH);

        setNewBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select HTML Project Directory");
            if (current != null) {
                chooser.setCurrentDirectory(current);
            }
            int result = chooser.showOpenDialog(dialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                java.io.File chosen = chooser.getSelectedFile();
                wv.codeclip.html.HtmlDirectory.set(chosen);
                dirDisplay.setText(chosen.getAbsolutePath());
            }
        });

        dialog.pack();
        dialog.setMinimumSize(new Dimension(420, 200));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

private void openGenericDirectoryDialog() {
        java.io.File current = wv.codeclip.generic.GenericDirectory.get();

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel titleLabel = new JLabel("Generic Project Directory");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        panel.add(titleLabel, BorderLayout.NORTH);

        JTextArea dirDisplay = new JTextArea(current != null ? current.getAbsolutePath() : "(not set)");
        dirDisplay.setEditable(false);
        dirDisplay.setLineWrap(true);
        dirDisplay.setWrapStyleWord(false);
        dirDisplay.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        dirDisplay.setBackground(UIManager.getColor("Panel.background"));
        dirDisplay.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground"), 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        dirDisplay.setRows(3);
        dirDisplay.setColumns(40);
        panel.add(new JScrollPane(dirDisplay), BorderLayout.CENTER);

        JButton setNewBtn = new JButton("Set New Directory…");
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btnRow.add(setNewBtn);
        panel.add(btnRow, BorderLayout.SOUTH);

        JDialog dialog = new JDialog(this, "Generic Directory", true);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        dialog.add(panel, BorderLayout.CENTER);

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());
        JPanel footerRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        footerRow.add(closeBtn);
        dialog.add(footerRow, BorderLayout.SOUTH);

        setNewBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Generic Project Directory");
            if (current != null) {
                chooser.setCurrentDirectory(current);
            }
            int result = chooser.showOpenDialog(dialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                java.io.File chosen = chooser.getSelectedFile();
                wv.codeclip.generic.GenericDirectory.set(chosen);
                dirDisplay.setText(chosen.getAbsolutePath());
            }
        });

        dialog.pack();
        dialog.setMinimumSize(new Dimension(420, 200));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

/**
     * Sets the Godot directory from loaded files if not already set. Prefers
     * the most common parent directory among .gd files. hint: files from the
     * current DnD batch (may be null to scan repo).
     */
    private void autoSetGodotDirectoryFromRepo(List<File> hint) {
        if (wv.codeclip.godot.GodotDirectory.isSet()) {
            return;
        }

        List<File> candidates = new ArrayList<>();
        if (hint != null) {
            for (File f : hint) {
                if (f.getName().endsWith(".gd")) {
                    candidates.add(f);
                }
            }
        }
        if (candidates.isEmpty()) {
            for (File f : repo.getClassFileMap().values()) {
                if (f != null && f.getName().endsWith(".gd")) {
                    candidates.add(f);
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        java.util.LinkedHashMap<File, Long> freq = new java.util.LinkedHashMap<>();
        for (File f : candidates) {
            File parent = f.getParentFile();
            if (parent != null) {
                freq.merge(parent, 1L, Long::sum);
            }
        }

        File best = null;
        long bestCount = 0;
        for (java.util.Map.Entry<File, Long> e : freq.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }

        if (best != null) {
            wv.codeclip.godot.GodotDirectory.set(best);
            appendTempLog("Godot directory auto-set: " + best.getAbsolutePath());
        }
    }

    private void appendToPersistentLog(String message) {
        if (persistentLogPane == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            insertPersistentLogLine(message, false);
            logLineCount++;
        });
    }

    private String pendingTargetBuild = null;  // set by stampBuildInfo before version event created
    private boolean pendingIsCheckpoint = false; // set right before a checkpoint-triggered save

    /** Called by CheckpointDialog right before it triggers a refresh, so the
     *  next version event (if any is produced as a result) is marked with a
     *  star instead of the ordinary active dot. */
    private void markNextVersionAsCheckpoint() {
        pendingIsCheckpoint = true;
    }

/** Creates a dedicated version-history entry for a manual "Set New Checkpoint"
     *  action, so the star reliably lands on its own row instead of waiting to
     *  piggyback on some future unrelated edit. */
    private void addCheckpointVersionEvent() {
        String time = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String build = pendingTargetBuild != null ? pendingTargetBuild : "?";
        while (versionHistory.size() > versionCurrentIdx + 1) {
            versionHistory.remove(versionHistory.size() - 1);
        }
        String title = "Checkpoint set";
        versionHistory.add(new VersionEvent(title, "", time, build, List.of(title), true));
        versionCurrentIdx = versionHistory.size() - 1;
        pendingTargetBuild = null;
        pendingIsCheckpoint = false;
        refreshVersionPanel();
        flashNewestVersionRow();
    }

private void addVersionEventFromUndoTop() {
        wv.codeclip.patch.PatchUndoManager.Entry top = undoManager.peekUndo();
        if (top == null) {
            return;
        }
        String title = top.title() != null ? top.title() : "Change";
        java.util.List<String> allTitles = (top.allTitles() != null && !top.allTitles().isEmpty())
                ? new java.util.ArrayList<>(top.allTitles())
                : java.util.List.of(title);
        String files = top.snapshot().keySet().stream()
                .map(p -> {
                    java.io.File f = repo.getClassFileMap().get(p);
                    return f != null ? f.getName() : new java.io.File(p).getName();
                })
                .filter(n -> !n.equals(BUILD_INFO_FILE))
                .collect(java.util.stream.Collectors.joining(", "));
        while (versionHistory.size() > versionCurrentIdx + 1) {
            versionHistory.remove(versionHistory.size() - 1);
        }
        String time = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String build = pendingTargetBuild != null ? pendingTargetBuild : "?";
        boolean isCheckpoint = pendingIsCheckpoint;
        versionHistory.add(new VersionEvent(title, files, time, build, allTitles, isCheckpoint));
        versionCurrentIdx = versionHistory.size() - 1;
        pendingTargetBuild = null;
        pendingIsCheckpoint = false;
        refreshVersionPanel();
        flashNewestVersionRow();
    }

    private void versionUndo() {
        if (versionCurrentIdx >= 0) {
            versionCurrentIdx--;
            refreshVersionPanel();
        }
    }

    private void versionRedo() {
        if (versionCurrentIdx < versionHistory.size() - 1) {
            versionCurrentIdx++;
            refreshVersionPanel();
        }
    }

private void refreshVersionPanel() {
        if (versionPanel == null) {
            return;
        }
        versionPanel.removeAll();
        latestVersionRow = null;
        if (versionHistory.isEmpty()) {
            JLabel empty = new JLabel("No version events yet.");
            empty.setForeground(Color.GRAY);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            empty.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            versionPanel.add(empty);
        } else {
            for (int i = versionHistory.size() - 1; i >= 0; i--) {
                JPanel row = createVersionRow(versionHistory.get(i), i <= versionCurrentIdx);
                if (i == versionHistory.size() - 1) {
                    latestVersionRow = row;
                }
                versionPanel.add(row);
            }
        }
        versionPanel.revalidate();
        versionPanel.repaint();
        SwingUtilities.invokeLater(() -> {
            Container parent = versionPanel.getParent();
            if (parent instanceof JViewport vp) {
                vp.setViewPosition(new Point(0, 0));
            }
        });
    }

    /** Reference to the most-recently-added row, kept only long enough to
     *  drive the new-patch flash animation right after refreshVersionPanel
     *  rebuilds the list. Not used for anything else — cleared implicitly
     *  on the next refresh. */
    private JPanel latestVersionRow;

    /**
     * Briefly flashes the newest version row's background so a freshly
     * created version is visually obvious even if the person is still
     * looking at the previous entry. Implemented as a plain Swing Timer
     * ticking a color lerp back to the row's normal background — no
     * threads, nothing to leak, and it self-stops after a fixed number
     * of ticks, so there's nothing here that can get stuck running.
     */
    private void flashNewestVersionRow() {
        JPanel row = latestVersionRow;
        if (row == null) {
            return;
        }
        Color from = new Color(255, 244, 176); // soft highlight yellow
        Color to = row.getBackground();
        int totalTicks = 18;      // ~1.5s at 80ms/tick — held constant, no external tuning knobs
        int delayMs = 80;

        javax.swing.Timer timer = new javax.swing.Timer(delayMs, null);
        int[] tick = {0};
        timer.addActionListener(e -> {
            tick[0]++;
            float ratio = Math.min(1f, tick[0] / (float) totalTicks);
            row.setBackground(lerpColor(from, to, ratio));
            row.repaint();
            if (ratio >= 1f) {
                timer.stop();
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    private static Color lerpColor(Color a, Color b, float t) {
        int r = Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t);
        int g = Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = Math.round(a.getBlue() + (b.getBlue()  - a.getBlue())  * t);
        return new Color(clamp255(r), clamp255(g), clamp255(bl));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

private JPanel createVersionRow(VersionEvent ev, boolean active) {
        Color sepColor = UIManager.getColor("Separator.foreground");
        if (sepColor == null) {
            sepColor = Color.LIGHT_GRAY;
        }
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            bg = Color.WHITE;
        }
        if (!active) {
            bg = new Color(242, 242, 242);
        }
        Color fg = UIManager.getColor("Label.foreground");
        if (fg == null) {
            fg = Color.BLACK;
        }
        if (!active) {
            fg = new Color(140, 140, 140);
        }

        JPanel row = new JPanel(new BorderLayout(4, 2));
        row.setBackground(bg);
        row.setOpaque(true);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120)); // allow variable height
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, sepColor),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        String iconGlyph = ev.isCheckpoint() ? "★" : (active ? "●" : "↩");
        Color iconColor = ev.isCheckpoint()
                ? new Color(190, 150, 20)
                : (active ? new Color(40, 160, 40) : new Color(170, 170, 170));
        JLabel icon = new JLabel(iconGlyph);
        icon.setFont(icon.getFont().deriveFont(Font.BOLD, 13f));
        icon.setForeground(iconColor);
        icon.setToolTipText(ev.isCheckpoint() ? "Checkpoint set here" : (active ? "Applied" : "Undone"));
        icon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        Font titleFont = active ? baseFont.deriveFont(Font.BOLD) : baseFont.deriveFont(Font.PLAIN);
        if (!active) {
            java.util.Map attrs = new java.util.HashMap(titleFont.getAttributes());
            attrs.put(java.awt.font.TextAttribute.STRIKETHROUGH,
                    java.awt.font.TextAttribute.STRIKETHROUGH_ON);
            titleFont = titleFont.deriveFont(attrs);
        }

        JLabel titleLbl = new JLabel(ev.title());
        titleLbl.setFont(titleFont);
        titleLbl.setForeground(fg);

        JLabel timeLbl = new JLabel(ev.timestamp());
        timeLbl.setFont(timeLbl.getFont().deriveFont(Font.PLAIN, 11f));
        timeLbl.setForeground(active ? new Color(110, 110, 110) : new Color(180, 180, 180));

        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);
        header.add(titleLbl, BorderLayout.CENTER);
        header.add(timeLbl, BorderLayout.EAST);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(header);

        if (ev.allTitles() != null && ev.allTitles().size() > 1) {
            JLabel batchNote = new JLabel(ev.allTitles().size() + " changes in this batch — click for details");
            batchNote.setFont(baseFont.deriveFont(Font.ITALIC, 10.5f));
            batchNote.setForeground(active ? new Color(130, 100, 40) : new Color(180, 180, 180));
            batchNote.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(batchNote);
        }

        if (ev.files() != null && !ev.files().isBlank()) {
            // Word-wrapping file list (no truncation)
            JTextArea filesArea = new JTextArea(ev.files());
            filesArea.setFont(baseFont.deriveFont(Font.PLAIN, 11f));
            filesArea.setForeground(active ? new Color(70, 70, 200) : new Color(170, 170, 170));
            filesArea.setEditable(false);
            filesArea.setLineWrap(true);
            filesArea.setWrapStyleWord(true);
            filesArea.setOpaque(false);
            filesArea.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(filesArea);
        }

        row.add(icon, BorderLayout.WEST);
        row.add(content, BorderLayout.CENTER);

        // Click → show detail popup
        row.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                showVersionDetail(ev, active);
            }
        });

        return row;
    }

/**
     * Inserts a single styled line at the top of the persistent log pane.
     */

private void insertPersistentLogLine(String message, boolean isSeparator) {
        StyledDocument doc = persistentLogPane.getStyledDocument();
        try {
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setFontFamily(attrs, Font.MONOSPACED);
            StyleConstants.setFontSize(attrs, 12);

            if (isSeparator) {
                StyleConstants.setForeground(attrs, LOG_SEP_COLOR);
                StyleConstants.setBold(attrs, false);
                doc.insertString(0, message + "\n", attrs);
                SwingUtilities.invokeLater(() -> persistentLogPane.setCaretPosition(0));
                return;
            }

            Color fg = resolveLogLineColor(message);
            boolean bold = fg != null && !fg.equals(UIManager.getColor("TextArea.foreground"));
            StyleConstants.setForeground(attrs, fg != null ? fg
                    : (UIManager.getColor("TextArea.foreground") != null
                    ? UIManager.getColor("TextArea.foreground") : Color.BLACK));
            StyleConstants.setBold(attrs, bold);
            doc.insertString(0, message + "\n", attrs);
            SwingUtilities.invokeLater(() -> persistentLogPane.setCaretPosition(0));
        } catch (BadLocationException ignored) {
        }
    }

private Color resolveLogLineColor(String line) {
        if (line.startsWith("Copy ERROR:")) {
            return LOG_ERROR_COLOR;
        }
        if (line.startsWith("──")) {
            if (!line.contains("Smart Paste") && !line.contains("Patch [")) {
                return LOG_TITLE_COLOR;
            }
            return new Color(60, 130, 60);
        }
        if (line.startsWith("  ✓") || line.startsWith("✓")) {
            return new Color(30, 140, 30);
        }
        if (line.startsWith("  ✗") || line.startsWith("✗")) {
            return LOG_ERROR_COLOR;
        }
        if (line.startsWith("Target Build:")) {
            return new Color(100, 60, 160);
        }
        if (line.startsWith("Class Created:") || line.startsWith("Class Updated:")) {
            return LOG_CLASS_COLOR;
        }
        return UIManager.getColor("TextArea.foreground");
    }

    /**
     * Full rebuild of the persistent log pane from logLineCount + separator
     * positions.
     */
    private void rebuildPersistentLog() {
        // We store the raw lines in a list; rebuilding means re-inserting everything.
        // Since we only prepend and never edit, the simplest approach is to keep a
        // parallel list of raw lines alongside separator flags.
        // --- delegate to the line list ---
        if (persistentLogPane == null) {
            return;
        }
        StyledDocument doc = persistentLogPane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
        }
        // Re-insert from the stored list (bottom-up so index 0 ends at top)
        for (int i = logRawLines.size() - 1; i >= 0; i--) {
            RawLogLine rll = logRawLines.get(i);
            if (rll.isSeparator() && !logSeparatorsVisible) {
                continue;
            }
            insertPersistentLogLine(rll.text(), rll.isSeparator());
        }
    }

    private record RawLogLine(String text, boolean isSeparator) {

    }
    private final java.util.List<RawLogLine> logRawLines = new java.util.ArrayList<>();

    private void addRawLogLine(String text, boolean isSep) {
        logRawLines.add(0, new RawLogLine(text, isSep));
    }

private void showVersionDetail(VersionEvent ev, boolean active) {
        JDialog dlg = new JDialog(this, "Version Detail", true);
        dlg.setLayout(new BorderLayout(10, 10));
        dlg.getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        // Selectable styled text
        JTextPane detailPane = new JTextPane();
        detailPane.setEditable(false);
        detailPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailPane.setBackground(UIManager.getColor("Panel.background"));
        detailPane.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        StyledDocument doc = detailPane.getStyledDocument();
        Style base = detailPane.addStyle("base", null);
        StyleConstants.setFontFamily(base, Font.MONOSPACED);
        StyleConstants.setFontSize(base, 12);
        StyleConstants.setForeground(base, UIManager.getColor("TextArea.foreground") != null 
            ? UIManager.getColor("TextArea.foreground") : Color.BLACK);

        Style titleStyle = detailPane.addStyle("title", base);
        StyleConstants.setBold(titleStyle, true);
        StyleConstants.setFontSize(titleStyle, 13);

        Style smallStyle = detailPane.addStyle("small", base);
        StyleConstants.setFontSize(smallStyle, 11);
        StyleConstants.setForeground(smallStyle, new Color(80, 80, 80));

        Style activeStatusStyle = detailPane.addStyle("activeStatus", base);
        StyleConstants.setForeground(activeStatusStyle, new Color(30, 130, 30));
        StyleConstants.setBold(activeStatusStyle, true);

        Style undoneStatusStyle = detailPane.addStyle("undoneStatus", base);
        StyleConstants.setForeground(undoneStatusStyle, new Color(160, 40, 40));
        StyleConstants.setBold(undoneStatusStyle, true);

        Style checkpointStatusStyle = detailPane.addStyle("checkpointStatus", base);
        StyleConstants.setForeground(checkpointStatusStyle, new Color(190, 150, 20));
        StyleConstants.setBold(checkpointStatusStyle, true);

        Style wholeClassStyle = detailPane.addStyle("wholeClass", base);
        StyleConstants.setForeground(wholeClassStyle, new Color(40, 100, 200));

        Style patchStyle = detailPane.addStyle("patchStyle", base);
        StyleConstants.setForeground(patchStyle, new Color(150, 80, 0));

        Style batchItemStyle = detailPane.addStyle("batchItem", base);
        StyleConstants.setForeground(batchItemStyle, new Color(90, 90, 90));

        try {
            doc.insertString(doc.getLength(), ev.title() + "\n", titleStyle);
            doc.insertString(doc.getLength(), "Time: " + ev.timestamp() + "\n", smallStyle);
            doc.insertString(doc.getLength(), "Target Build: #" + ev.targetBuild() + "\n", smallStyle);
            if (ev.isCheckpoint()) {
                doc.insertString(doc.getLength(), "Status: Checkpoint\n", checkpointStatusStyle);
            } else if (active) {
                doc.insertString(doc.getLength(), "Status: Active\n", activeStatusStyle);
            } else {
                doc.insertString(doc.getLength(), "Status: Undone\n", undoneStatusStyle);
            }
            doc.insertString(doc.getLength(), "\n", base);

            if (ev.allTitles() != null && ev.allTitles().size() > 1) {
                doc.insertString(doc.getLength(),
                        "Changes in this batch (" + ev.allTitles().size() + "):\n", base);
                int n = 1;
                for (String t : ev.allTitles()) {
                    doc.insertString(doc.getLength(), "  " + (n++) + ". " + t + "\n", batchItemStyle);
                }
                doc.insertString(doc.getLength(), "\n", base);
            }

            if (ev.files() != null && !ev.files().isBlank()) {
                doc.insertString(doc.getLength(), "Altered files:\n", base);
                for (String file : ev.files().split(",")) {
                    String f = file.trim();
                    if (f.isEmpty()) continue;
                    boolean isWhole = isWholeClassFile(f);
                    String marker = isWhole ? "  ● whole class" : "  ⚡ patch";
                    doc.insertString(doc.getLength(), "  " + f, base);
                    doc.insertString(doc.getLength(), marker + "\n", isWhole ? wholeClassStyle : patchStyle);
                }
            } else {
                doc.insertString(doc.getLength(), "No file info recorded.\n", base);
            }
            detailPane.setCaretPosition(0);
        } catch (BadLocationException ignored) {}

        dlg.add(new JScrollPane(detailPane), BorderLayout.CENTER);

        // Button row with rename title
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

        JButton renameBtn = new JButton("Rename Title");
        renameBtn.addActionListener(e -> {
            JTextField titleField = new JTextField(ev.title());
            titleField.setFont(titleField.getFont().deriveFont(14f));
            titleField.setPreferredSize(new Dimension(420, 32));
            titleField.selectAll();

            JPanel promptPanel = new JPanel(new BorderLayout(0, 8));
            JLabel promptLabel = new JLabel("Enter new title:");
            promptPanel.add(promptLabel, BorderLayout.NORTH);
            promptPanel.add(titleField, BorderLayout.CENTER);
            promptPanel.setPreferredSize(new Dimension(440, 70));

            int result = JOptionPane.showConfirmDialog(
                    dlg, promptPanel, "Rename Title",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (result == JOptionPane.OK_OPTION) {
                String newTitle = titleField.getText();
                if (newTitle != null && !newTitle.trim().isEmpty()) {
                    ev.setTitle(newTitle.trim());
                    refreshVersionPanel();
                    dlg.dispose();
                }
            }
        });
        btnRow.add(renameBtn);

        JButton enableOnlyBtn = new JButton("Enable Only These");
        enableOnlyBtn.setToolTipText("Disables everything else, enables only files touched by this version.");
        enableOnlyBtn.addActionListener(e -> {
            java.util.List<String> targetNames = versionEventFileTargets(ev);
            if (targetNames.isEmpty()) {
                JOptionPane.showMessageDialog(dlg, "No files recorded for this version event.");
                return;
            }

            repo.getDisabledClasses().addAll(repo.getClassCodeMap().keySet());
            java.util.List<String> enabled = new java.util.ArrayList<>();
            java.util.List<String> notFound = new java.util.ArrayList<>();
            enableMatchingFiles(targetNames, enabled, notFound);

            refreshText();
            refreshPanels();
            if (!enabled.isEmpty()) {
                appendTempLog("Enabled only version files: " + String.join(", ", enabled));
            }
            if (!notFound.isEmpty()) {
                appendTempLog("Warning: not found in loaded classes: " + String.join(", ", notFound));
            }
            dlg.dispose();
        });
        btnRow.add(enableOnlyBtn);

        JButton enableAlsoBtn = new JButton("Enable Also");
        enableAlsoBtn.setToolTipText("Enables files touched by this version without disabling anything currently enabled.");
        enableAlsoBtn.addActionListener(e -> {
            java.util.List<String> targetNames = versionEventFileTargets(ev);
            if (targetNames.isEmpty()) {
                JOptionPane.showMessageDialog(dlg, "No files recorded for this version event.");
                return;
            }

            java.util.List<String> enabled = new java.util.ArrayList<>();
            java.util.List<String> notFound = new java.util.ArrayList<>();
            enableMatchingFiles(targetNames, enabled, notFound);

            refreshText();
            refreshPanels();
            if (!enabled.isEmpty()) {
                appendTempLog("Enabled version files (kept others enabled): " + String.join(", ", enabled));
            }
            if (!notFound.isEmpty()) {
                appendTempLog("Warning: not found in loaded classes: " + String.join(", ", notFound));
            }
            dlg.dispose();
        });
        btnRow.add(enableAlsoBtn);

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dlg.dispose());
        btnRow.add(closeBtn);

        dlg.add(btnRow, BorderLayout.SOUTH);

        dlg.pack();
        dlg.setMinimumSize(new Dimension(420, 320));
        int frameWidth = CodeClipFrame.this.getWidth();
        if (dlg.getWidth() > frameWidth) {
            dlg.setSize(frameWidth, dlg.getHeight());
        }
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    /** Parses ev.files() ("Foo.java, bar.js, ...") into a lowercase name list for matching. */
    private java.util.List<String> versionEventFileTargets(VersionEvent ev) {
        java.util.List<String> targetNames = new java.util.ArrayList<>();
        String filesStr = ev.files();
        if (filesStr == null || filesStr.isBlank()) return targetNames;
        for (String f : filesStr.split(",")) {
            String trimmed = f.trim();
            if (!trimmed.isEmpty()) targetNames.add(trimmed.toLowerCase());
        }
        return targetNames;
    }

    /**
     * Enables every loaded file whose bare name matches one of targetNames
     * (exact match, or with .java/.gd appended for bare-class-name style
     * @@Enable targets). Never touches disabled state of non-matching files —
     * callers decide separately whether to disable everything else first.
     */
    private void enableMatchingFiles(java.util.List<String> targetNames,
                                      java.util.List<String> enabled, java.util.List<String> notFound) {
        for (String target : targetNames) {
            boolean found = false;
            for (java.util.Map.Entry<String, java.io.File> entry : repo.getClassFileMap().entrySet()) {
                java.io.File file = entry.getValue();
                if (file == null) continue;
                String name = file.getName().toLowerCase();
                if (name.equals(target) || name.equals(target + ".java") || name.equals(target + ".gd")) {
                    repo.getDisabledClasses().remove(entry.getKey());
                    enabled.add(file.getName());
                    found = true;
                    break;
                }
            }
            if (!found) {
                notFound.add(target);
            }
        }
    }

/**
     * Heuristic: if the entry's title starts with "Class" it was a whole-class
     * paste; otherwise it was a patch. Falls back to checking the undo manager
     * top entry title.
     */

/**
     * Determines whether a file name was touched by a whole-file write
     * (new class/file creation, or a full-file overwrite) versus a surgical
     * patch, by inspecting the matching version event's title. Whole-file
     * titles come from three sources depending on mode:
     *   Java mode:            "Class Created: X" / "Class Updated: X" / "Class: X"
     *   HTML/Generic modes:   "File Created: X" / "File Updated: X"
     * Anything else (FindReplace/MethodReplace/InsertMethod-derived titles,
     * or multi-file batch titles ending in "(+N more)") is treated as a patch.
     */
    private boolean isWholeClassFile(String fileName) {
        for (VersionEvent ev : versionHistory) {
            if (ev.files() == null) continue;
            boolean matchesThisFile = java.util.Arrays.stream(ev.files().split(","))
                    .map(String::trim)
                    .anyMatch(f -> f.equalsIgnoreCase(fileName));
            if (!matchesThisFile) continue;

            // A batch's allTitles() carries the per-entry title even when the
            // combined VersionEvent.title() is a "(+N more)" summary — check
            // every title in the batch, not just the first.
            java.util.List<String> titlesToCheck = (ev.allTitles() != null && !ev.allTitles().isEmpty())
                    ? ev.allTitles()
                    : (ev.title() != null ? java.util.List.of(ev.title()) : java.util.List.of());

            for (String t : titlesToCheck) {
                if (t == null) continue;
                if (t.startsWith("Class Created") || t.startsWith("Class Updated") || t.startsWith("Class:")
                        || t.startsWith("File Created") || t.startsWith("File Updated")) {
                    return true;
                }
            }
        }
        return false;
    }

private String computeChangeLabel(String path) {
if (path == null) return "";
File f = new File(path);

File htmlRoot = wv.codeclip.html.HtmlDirectory.isSet() ? wv.codeclip.html.HtmlDirectory.get() : null;
String rel = relativizeForLabel(htmlRoot, f);
if (rel != null) return rel;

File genericRoot = wv.codeclip.generic.GenericDirectory.isSet() ? wv.codeclip.generic.GenericDirectory.get() : null;
rel = relativizeForLabel(genericRoot, f);
if (rel != null) return rel;

File godotDir = wv.codeclip.godot.GodotDirectory.get();
rel = relativizeForLabel(godotDir, f);
if (rel != null) return rel;

File sourceRoot = detectSourceRoot();
rel = relativizeForLabel(sourceRoot, f);
if (rel != null) return rel;

return f.getAbsolutePath();
}

private String relativizeForLabel(File root, File file) {
if (root == null || file == null) return null;
try {
String rootPath = root.getAbsolutePath();
String filePath = file.getAbsolutePath();
if (filePath.startsWith(rootPath + File.separator)) {
return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
}
} catch (Exception ignored) {}
return null;
}

/**
     * Checks clipboard content for @@protocol blocks before any mode-specific
     * paste routing runs. If found, routes the ENTIRE clipboard text through
     * the protocol module regardless of current AppMode, and returns true so
     * the caller skips normal paste handling entirely. Returns false if no
     * protocol content is present, so normal paste handling proceeds untouched.
     */

/**
     * Builds the text block appended to Copy All's clipboard output when
     * "Include Protocol" is checked. Reads every enabled/available protocol
     * file from this project's protocol library and renders them plainly,
     * so the person copying context to an AI also gets current protocol
     * state included. Has no relationship to pasting whatsoever.
     */
    private String buildProtocolAppendixForCopy() {
        List<String> fileNames = protocolLibrary.listFileNames();
        if (fileNames.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n===== PROTOCOLS =====\n\n");
        for (String fileName : fileNames) {
            StringBuilder err = new StringBuilder();
            wv.codeclip.protocol.model.ProtocolFile file = protocolLibrary.loadSafely(fileName, err);
            sb.append("--- ").append(fileName).append(" ---\n");
            if (err.length() > 0) {
                sb.append("[could not load: ").append(err).append("]\n");
            } else {
                sb.append(file.render());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

/**
     * Opens the Protocol Manager dialog. Modeless per protocol module design —
     * does not block this frame.
     */

private void openProtocolManagerDialog() {
        wv.codeclip.protocol.ui.ProtocolManagerDialog dialog =
                new wv.codeclip.protocol.ui.ProtocolManagerDialog(this,
                        protocolDirectoryManager,
                        protocolUndoManager,
                        this::logProtocolLine,
                        this::onProtocolLibraryChanged);
        dialog.setVisible(true);
    }

/**
     * Called when the Protocol Manager dialog switches to a different
     * protocols folder. Rebuilds this frame's own protocolLibrary and
     * protocolPasteRouter against the new folder so Copy All's protocol
     * appendix and main-frame paste routing (@@protocol blocks pasted
     * directly into the app, outside the dialog) both immediately follow
     * the switch instead of continuing to use the pre-switch folder for
     * the rest of the session.
     */
    private void onProtocolLibraryChanged(wv.codeclip.protocol.library.ProtocolLibrary newLibrary) {
        this.protocolLibrary = newLibrary;
        this.protocolPasteRouter = new wv.codeclip.protocol.engine.ProtocolPasteRouter(
                protocolLibrary, protocolUndoManager);
        appendTempLog("Protocols folder switched — Copy All and paste routing now use: "
                + protocolLibrary.getProtocolsDir());
    }

/**
     * Checks clipboard content for @@protocol blocks and, if found, routes
     * them through the review dialog. Only ever acts on the
     * @@protocol...@@protocolEnd regions — never touches or consumes any
     * other content (e.g. a @@PATCH block) sitting alongside it in the same
     * clipboard payload. Safe to call unconditionally before every paste.
     */
    private void handleProtocolPasteIfPresent() {
        String text = new ClipboardService().read();
        if (!wv.codeclip.protocol.engine.ProtocolPasteRouter.containsProtocolBlock(text)) {
            return;
        }

        List<wv.codeclip.protocol.model.Command> commands;
        wv.codeclip.protocol.engine.ProtocolEngine reviewEngine = new wv.codeclip.protocol.engine.ProtocolEngine();
        try {
            reviewEngine.recordPatch(text);
            commands = reviewEngine.getRecordedCommands();
        } catch (wv.codeclip.protocol.parser.AiOutputParser.PatchParseException e) {
            JOptionPane.showMessageDialog(this, "Could not parse protocol content:\n" + e.getMessage()
                + "\n\nAny non-protocol content (e.g. a @@PATCH block) elsewhere in the clipboard will still be applied normally.",
                "Protocol Parse Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (commands.isEmpty()) {
            return;
        }

        Map<String, List<wv.codeclip.protocol.model.Command>> byFile = new LinkedHashMap<>();
        for (wv.codeclip.protocol.model.Command c : commands) {
            byFile.computeIfAbsent(c.getTargetFile(), k -> new ArrayList<>()).add(c);
        }

        Map<String, wv.codeclip.protocol.model.ProtocolFile> originals = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        for (String fileName : byFile.keySet()) {
            StringBuilder err = new StringBuilder();
            wv.codeclip.protocol.model.ProtocolFile loaded = protocolLibrary.loadSafely(fileName, err);
            if (err.length() > 0 && protocolLibrary.exists(fileName)) {
                unreadable.add(fileName + ": " + err);
            }
            originals.put(fileName, loaded);
        }

        if (!unreadable.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "The following protocol files could not be read and were skipped:\n" + String.join("\n", unreadable),
                "Some Files Unreadable", JOptionPane.WARNING_MESSAGE);
        }

        wv.codeclip.protocol.ui.DiffAcceptDialog reviewDialog =
            new wv.codeclip.protocol.ui.DiffAcceptDialog(this, originals, byFile);
        reviewDialog.setVisible(true);

        if (!reviewDialog.wasConfirmed()) {
            appendTempLog("Protocol paste reviewed and cancelled; nothing applied.");
            return;
        }

        Map<String, Set<String>> acceptedByFile = reviewDialog.getAcceptedKeysByFile();

        wv.codeclip.protocol.engine.ProtocolEngine.AcceptanceResolver resolver =
                (fileName, original, commandsForFile) -> acceptedByFile.getOrDefault(fileName, Set.of());

        wv.codeclip.protocol.engine.ProtocolPasteRouter.RouteOutcome outcome =
                protocolPasteRouter.route(text, resolver);

        for (String line : outcome.logLines) {
            logProtocolLine(line);
        }

        if (outcome.changed) {
            addProtocolVersionEvent(outcome.result.getWrittenFiles().size());
        }
    }

/**
     * Wraps detectSourceRoot() as a no-arg supplier target for
     * ProtocolDirectoryManager, which needs to lazily re-detect the source
     * root on first use.
     */
    private java.io.File detectSourceRootForProtocols() {
        return detectSourceRoot();
    }

/**
     * Writes a protocol-related log line into BOTH the protocol module's own
     * concern (nothing extra needed there — ProtocolManagerDialog reads
     * straight from disk) and the existing code-log feeds (temp log +
     * persistent log), per the "protocols get own logs but same logs also
     * apply to normal logs" requirement — the shared feeds are the "normal
     * logs" being referred to, and protocol lines are mirrored into them
     * rather than replacing anything.
     */
    private void logProtocolLine(String message) {
        appendTempLog(message);
    }

    /** Adds a version-history entry so the Versions tab reflects protocol changes too. */
    private void addProtocolVersionEvent(int fileCount) {
        String time = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String title = "Protocol change (" + fileCount + " file" + (fileCount > 1 ? "s" : "") + ")";
        while (versionHistory.size() > versionCurrentIdx + 1) {
            versionHistory.remove(versionHistory.size() - 1);
        }
        versionHistory.add(new VersionEvent(title, "(protocol files)", time, "-", List.of(title), false));
        versionCurrentIdx = versionHistory.size() - 1;
        refreshVersionPanel();
        flashNewestVersionRow();
    }

}
