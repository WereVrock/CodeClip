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
import wv.codeclip.io.ProjectSettings;
import wv.codeclip.modecontext.ModeColors;
import wv.codeclip.patch.InsertMethodConflictDialog;
import wv.codeclip.ui.ClassActions;
import wv.codeclip.ui.SimpleDocumentListener;
import wv.codeclip.ui.SmartPasteSettings;
import wv.codeclip.ui.SmartPasteSettingsDialog;
import wv.codeclip.patch.PatchApplier;
import wv.codeclip.patch.PatchErrorDialog;
import wv.codeclip.ui.ClassTreePanel;
import wv.codeclip.ui.TreeViewIcons;

public class CodeClipFrame extends JFrame implements java.awt.event.FocusListener {

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
    private JButton treeCollapseBtn;
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
    /** Single files larger than this are not loaded. Prevents one minified
     *  bundle or generated dump from dominating the heap and stalling every
     *  refresh. 2 MB comfortably covers real source files. */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /** When the total enabled content exceeds this many characters, the
     *  display area shows a summary instead of the full text. Copy operations
     *  read from the repository, not the display, so nothing is lost — only
     *  scrolling the whole corpus in a Swing JTextArea is what gets skipped. */
    private static final long MAX_DISPLAY_CHARS = 500_000L;
    private boolean titleFrozen = false;
    private wv.codeclip.mainFrame.BuildInfoStamper buildInfoStamper;

    /** Source root that project-scoped settings (name, tint, protocols folder)
     *  are currently bound to. Null until the first successful detection. */
    private java.io.File projectSourceRoot = null;
    private wv.codeclip.io.ProjectSettings projectSettings = null;
    /**
     * Absolute path of the project root for which backup checkpoints have
     * already been restored this session. Set once per project attach so
     * additional batches, mode changes, and pre-stamp reloads do not
     * re-apply the backup. Cleared by Reset so the next project can restore
     * from its own backup independently.
     */
    private String backupCheckpointsAppliedForRoot = null;
    /**
     * True immediately after Reset. The first batch that lands while this is
     * true forces auto-set of the current mode's directory — even if one is
     * already set — because Reset means "I want fresh directories for the
     * next project". Cleared on any successful auto-set or manual pick.
     *
     * Needed because *Directory.load() ignores empty saved values, so clearing
     * the settings string alone does not clear the in-memory directory.
     */
    private boolean pendingDirectoryAutoSet = false;

    /** Only one Protocol Manager dialog is ever open at a time; kept here so
     *  a project switch can rebind it to the new protocols folder. */
    private wv.codeclip.protocol.ui.ProtocolManagerDialog protocolManagerDialog = null;

    private final wv.codeclip.io.RecentProjects recentProjects = new wv.codeclip.io.RecentProjects();
    private JMenu recentProjectsMenu;

    private JWindow loadBarWindow;
    private JProgressBar loadProgressBar;
    private JLabel loadProgressLabel;

    public CodeClipFrame() {

        wv.codeclip.config.CodeClipBuildInfo.getBuildInfo();
        recentProjects.load(settings);
        wv.codeclip.godot.GodotDirectory.load(settings);
        wv.codeclip.html.HtmlDirectory.load(settings);
        wv.codeclip.generic.GenericDirectory.load(settings);
        undoManager = new wv.codeclip.patch.PatchUndoManager();
        projectNameManager = new wv.codeclip.mainFrame.ProjectNameManager();
        protocolDirectoryManager = new wv.codeclip.protocol.library.ProtocolDirectoryManager(
                this::detectSourceRootForProtocols);
        protocolLibrary = new wv.codeclip.protocol.library.ProtocolLibrary(
                protocolDirectoryManager.getProtocolsDir());
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
                registerCurrentProjectInRecent();
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

// --- Menu bar ---
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem quickSetupItem = new JMenuItem("Quick Setup…");
        quickSetupItem.setToolTipText(
                "Pick a parent folder. Creates <name>/{files,protocol,backup}, "
                + "resets current state, and rebinds everything to the new project.");
        quickSetupItem.addActionListener(e -> quickSetup());
        fileMenu.add(quickSetupItem);
        menuBar.add(fileMenu);

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
                    "Write the command @@Enable to allow the user to enable the classes you want by copy pasting the command\n\n"
                    + "@@Enable ClassName1, ClassName2, ClassName3" +"\n if you are working with javascripts, add .js to file end."
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
            wv.codeclip.codemap.CodeMapBuilder.Scope scope = promptCodemapScope();
            if (scope == null) {
                return; // user cancelled
            }
            String map = new wv.codeclip.codemap.CodeMapBuilder(repo)
                    .build(repo.getDisabledClasses(), scope);
            new ClipboardService().write(map);
            settings.saveCodemapScope(scope.name());
            settings.saveProperties();
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
                resetProjectState();
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

        recentProjectsMenu = new JMenu("Recent Projects");
        recentProjectsMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) { rebuildRecentProjectsMenu(); }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        systemMenu.add(recentProjectsMenu);

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

        java.util.function.Consumer<Boolean> postPaste = (changed) -> {
            syncUndoRedo.run();
            if (changed) {
                stampBuildInfo();
                addVersionEventFromUndoTop();
                // Re-read every loaded file from disk so external edits made
                // between pastes are pulled in too. Silent variant: no
                // missing-file dialogs, missing files stay in the repo and
                // get refreshed once they reappear. Missing names are
                // mirrored into the temp log for visibility without modal
                // interruption.
                actions.updateAllSilent(
                        this::refreshText,
                        missing -> {
                            StringBuilder sb = new StringBuilder(
                                    "Auto-update: skipped " + missing.size()
                                            + " file(s) not found on disk:");
                            for (String p : missing) {
                                File f = repo.getClassFileMap().get(p);
                                sb.append("\n  • ")
                                  .append(f != null ? f.getName() : p);
                            }
                            appendTempLog(sb.toString());
                        });
            }
        };
        pasteHandler.setPostPasteCallback(postPaste);
        godotPasteHandler.setPostPasteCallback(postPaste);
        htmlPasteHandler.setPostPasteCallback(postPaste);
        genericPasteHandler.setPostPasteCallback(postPaste);

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
            // Tree view keeps its own filtered layout — rebuild it on every
            // keystroke so results appear live instead of only after a
            // mode switch or an expand/collapse cycle.
            if (treeViewActive && classTreePanel != null) {
                classTreePanel.refresh();
            }
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

        JToggleButton treeToggleBtn = new JToggleButton(TreeViewIcons.tree());
        treeToggleBtn.setFocusable(false);
        treeToggleBtn.setMargin(new Insets(2, 6, 2, 6));
        treeToggleBtn.setToolTipText("Switch to tree view");
        treeToggleBtn.addActionListener(e -> {
            treeViewActive = treeToggleBtn.isSelected();
            CardLayout cl = (CardLayout) classViewStack.getLayout();
            if (treeViewActive) {
                classTreePanel.refresh();
                cl.show(classViewStack, "tree");
                treeToggleBtn.setIcon(TreeViewIcons.list());
                treeToggleBtn.setToolTipText("Switch to list view");
            } else {
                cl.show(classViewStack, "list");
                treeToggleBtn.setIcon(TreeViewIcons.tree());
                treeToggleBtn.setToolTipText("Switch to tree view");
            }
            updateTreeCollapseButton();
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

        // Expand All / Collapse All — visible only in tree mode. The label
        // reflects the ACTION (what clicking will do), not the current state:
        // "Expand All" when the tree is fully collapsed, "Collapse All"
        // otherwise (partial or fully expanded).
        treeCollapseBtn = new JButton("⊞");
        treeCollapseBtn.setFont(treeCollapseBtn.getFont().deriveFont(Font.PLAIN, 14f));
        treeCollapseBtn.setFocusable(false);
        treeCollapseBtn.setVisible(false);
        treeCollapseBtn.setMargin(new Insets(2, 8, 2, 8));
        treeCollapseBtn.setToolTipText("Expand every folder in the tree");
        treeCollapseBtn.addActionListener(e -> {
            if (classTreePanel.isAllCollapsed()) {
                classTreePanel.expandAll();
            } else {
                classTreePanel.collapseAll();
            }
            updateTreeCollapseButton();
        });

        JPanel treeControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        treeControls.add(treeToggleBtn);
        treeControls.add(treeCollapseBtn);

        JPanel searchAndToggleRow = new JPanel(new BorderLayout(4, 0));
        searchAndToggleRow.add(treeControls, BorderLayout.WEST);
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
            // Mode change can change what detectSourceRoot() returns
            // (HtmlDirectory / GenericDirectory are consulted first in those
            // modes). reloadProjectSettings re-detects and only re-attaches
            // when the effective root actually differs.
            reloadProjectSettings();
        }
    }

private void quickSetup() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle("Quick Setup — Pick parent folder for the new project");
    if (projectSourceRoot != null
            && projectSourceRoot.getParentFile() != null
            && projectSourceRoot.getParentFile().exists()) {
        chooser.setCurrentDirectory(projectSourceRoot.getParentFile());
    }
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    File chosenRoot = chooser.getSelectedFile();
    if (chosenRoot == null || !chosenRoot.isDirectory()) return;

    resetProjectState();

    if (!projectNameManager.promptForRename(this)) return;
    String name = projectNameManager.getProjectName();
    if (name == null || name.isBlank()) return;

    String safeName = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    File anaKlasor = new File(chosenRoot, safeName);
    if (!anaKlasor.exists() && !anaKlasor.mkdirs()) {
        JOptionPane.showMessageDialog(this,
                "Could not create project folder:\n" + anaKlasor,
                "Quick Setup Failed", JOptionPane.ERROR_MESSAGE);
        return;
    }
    File filesDir    = new File(anaKlasor, "files");    filesDir.mkdirs();
    File protocolDir = new File(anaKlasor, "protocol"); protocolDir.mkdirs();
    File backupDir   = new File(anaKlasor, "backup");   backupDir.mkdirs();

    projectSourceRoot = filesDir;
    projectSettings = new ProjectSettings(filesDir,
            () -> syncBuildInfoFromDisk(filesDir));
    projectNameManager.attachProjectSettings(projectSettings);
    projectSettings.saveProjectName(name);
    projectSettings.saveBackupDirectory(backupDir.getAbsolutePath());
    projectSettings.saveProtocolsDirectory(protocolDir.getAbsolutePath());
    projectSettings.savePermanentCheckpointEnabled(true);

    projectNameManager.rerollIconTint();
    setCurrentModeDirectory(filesDir);
    reloadProjectSettings();

    protocolDirectoryManager.attachProjectSettings(projectSettings);
    protocolLibrary = protocolDirectoryManager.buildLibrary();
    protocolPasteRouter = new wv.codeclip.protocol.engine.ProtocolPasteRouter(
            protocolLibrary, protocolUndoManager);

    titleFrozen = false;
    refreshTitle();
    setIcon();
    appendTempLog("Quick Setup: project=" + name
            + " | files=" + filesDir.getAbsolutePath()
            + " | protocol=" + protocolDir.getAbsolutePath()
            + " | backup=" + backupDir.getAbsolutePath());
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
    if (files.isEmpty()) return;
    files = filterBackupFolderFiles(files);
    if (files.isEmpty()) return;

    List<File> toLoad = new ArrayList<>();
    List<File> tooLarge = new ArrayList<>();
    for (File file : files) {
        String path = file.getAbsolutePath();
        if (repo.getClassCodeMap().containsKey(path)) {
            repo.getDisabledClasses().remove(path);
            continue;
        }
        if (file.length() > MAX_FILE_BYTES) {
            tooLarge.add(file);
            continue;
        }
        toLoad.add(file);
    }

    if (toLoad.isEmpty()) {
        reportSkippedLargeFiles(tooLarge);
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
                            autoSetModeDirectoryFromRepo(toLoad);
                            refreshText();
                            refreshPanels();
                            reloadProjectSettings();
                            registerCurrentProjectInRecent();
                            hideLoadBar();
                            reportSkippedLargeFiles(tooLarge);
                        });
                    }
                }
            }
        };
        worker.execute();
    }
}

/**
 * Prompts the user when the incoming batch contains files that live inside
 * the project's backup folder. Adding those makes backup copies editable
 * through CodeClip, which usually defeats the point of having them.
 * Declining removes only the backup-resident files; everything else in the
 * same drop still loads.
 */
private List<File> filterBackupFolderFiles(List<File> files) {
    if (projectSettings == null || files.isEmpty()) return files;
    String backupDir = projectSettings.loadBackupDirectory();
    if (backupDir == null || backupDir.isBlank()) return files;

    File backupRoot = new File(backupDir);
    String prefix;
    try {
        prefix = backupRoot.getCanonicalPath() + File.separator;
    } catch (java.io.IOException e) {
        prefix = backupRoot.getAbsolutePath() + File.separator;
    }

    List<File> inBackup = new ArrayList<>();
    for (File f : files) {
        String abs;
        try { abs = f.getCanonicalPath(); }
        catch (java.io.IOException e) { abs = f.getAbsolutePath(); }
        if (abs.startsWith(prefix)) inBackup.add(f);
    }
    if (inBackup.isEmpty()) return files;

    StringBuilder msg = new StringBuilder();
    msg.append(inBackup.size()).append(" file(s) are inside your backup folder:\n\n");
    for (File f : inBackup) msg.append("  • ").append(f.getName()).append("\n");
    msg.append("\nAdding them makes backup copies editable through CodeClip. ")
       .append("Add them anyway?");

    int choice = JOptionPane.showConfirmDialog(this, msg.toString(),
            "Backup Folder Files Detected",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
    if (choice == JOptionPane.YES_OPTION) return files;

    List<File> filtered = new ArrayList<>();
    for (File f : files) {
        if (!inBackup.contains(f)) filtered.add(f);
    }
    return filtered;
}

/**
     * Reports files that were not loaded because they exceed MAX_FILE_BYTES.
     * Called once per batch, after the batch's remaining files finish loading,
     * so the user gets a single aggregated dialog rather than one per file.
     */
    private void reportSkippedLargeFiles(List<File> skipped) {
        if (skipped.isEmpty()) return;
        StringBuilder msg = new StringBuilder(
                "Skipped " + skipped.size() + " file"
                + (skipped.size() == 1 ? "" : "s")
                + " larger than " + (MAX_FILE_BYTES / 1024 / 1024) + " MB:\n\n");
        for (File f : skipped) {
            msg.append("  • ").append(f.getName())
               .append(" (").append(f.length() / 1024 / 1024).append(" MB)\n");
            appendTempLog("Skipped large file (" + (f.length() / 1024 / 1024) + " MB): " + f.getName());
        }
        msg.append("\nLikely generated or minified — not loaded. ")
           .append("Open them directly if needed.");
        JOptionPane.showMessageDialog(this, msg.toString(),
                "Large Files Skipped", JOptionPane.WARNING_MESSAGE);
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

        List<String> accepted = new ArrayList<>();
        List<File> tooLarge = new ArrayList<>();
        for (String path : paths) {
            File f = new File(path);
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                tooLarge.add(f);
                continue;
            }
            accepted.add(path);
        }

        if (accepted.isEmpty()) {
            reportSkippedLargeFiles(tooLarge);
            return;
        }

        java.util.concurrent.atomic.AtomicInteger remaining
                = new java.util.concurrent.atomic.AtomicInteger(accepted.size());
        java.util.concurrent.atomic.AtomicInteger loaded
                = new java.util.concurrent.atomic.AtomicInteger(0);
        int total = accepted.size();

        for (String path : accepted) {
            File f = new File(path);
            if (!f.exists()) {
                int done = loaded.incrementAndGet();
                SwingUtilities.invokeLater(() -> updateLoadBar(done, total));
                if (remaining.decrementAndGet() == 0) {
                    SwingUtilities.invokeLater(() -> {
                        autoSetModeDirectoryFromRepo(null);
                        refreshText();
                        refreshPanels();
                        reloadProjectSettings();
                        registerCurrentProjectInRecent();
                        hideLoadBar();
                        reportSkippedLargeFiles(tooLarge);
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
                        autoSetModeDirectoryFromRepo(null);
                        refreshText();
                        refreshPanels();
                        reloadProjectSettings();
                        registerCurrentProjectInRecent();
                        hideLoadBar();
                        reportSkippedLargeFiles(tooLarge);
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
                                autoSetModeDirectoryFromRepo(null);
                                refreshText();
                                refreshPanels();
                                reloadProjectSettings();
                                registerCurrentProjectInRecent();
                                hideLoadBar();
                                reportSkippedLargeFiles(tooLarge);
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
        // In-app code display was removed. All copy operations read from the
        // repository directly. Kept as a named method so existing callers
        // (undo/redo, paste callbacks, reloadProjectSettings, batch loads)
        // continue to work unchanged.
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
    // Drop panels whose path is no longer in the repo. Needed when
    // "Restore to Checkpoint" removes files created after the checkpoint —
    // without this the panel sticks around even though classCodeMap no
    // longer knows about the path, so the UI lies.
    for (Component c : classPanel.getComponents()) {
        if (c instanceof JPanel panel) {
            Object storedPath = panel.getClientProperty("path");
            if (storedPath instanceof String path && !repo.getClassCodeMap().containsKey(path)) {
                classPanel.remove(panel);
            }
        }
    }

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
    if (current == null) return false;
    // File exists in memory but not in the checkpoint map = created after
    // the last checkpoint. Returning false here made new-file rows render
    // as "in sync" in the panel, which is what made the bug invisible.
    if (checkpoint == null) return true;
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
    System.out.println("[Frame] stampBuildInfo start projectSourceRoot="
            + projectSourceRoot + " projectSettings=" + (projectSettings != null));
    reloadProjectSettings();
    System.out.println("[Frame] after reloadProjectSettings root=" + projectSourceRoot
            + " settings=" + (projectSettings != null)
            + " hasName=" + projectNameManager.hasProjectName());
    if (!projectNameManager.hasProjectName()) {
        projectNameManager.promptForNameIfMissing(this);
        titleFrozen = false;
        System.out.println("[Frame] after prompt hasName="
                + projectNameManager.hasProjectName()
                + " hasPending=" + projectNameManager.hasPendingName());
        // The paste just created a file — source root is usually detectable
        // now even if the first reload ran before the file existed. Without
        // this retry, pendingProjectName stays stashed forever.
        reloadProjectSettings();
        projectNameManager.flushPendingName();
        System.out.println("[Frame] after retry settings=" + (projectSettings != null)
                + " hasPending=" + projectNameManager.hasPendingName());
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
        java.io.File detected = getBuildInfoStamper().detectSourceRoot();
        if (detected == null) {
            return null;
        }
        // In Html/Generic modes, BuildInfoStamper.detectSourceRoot returns
        // HtmlDirectory/GenericDirectory directly. If the currently loaded
        // files aren't inside that directory (user reset and opened files
        // from a different project without changing modes), returning it
        // would make reloadProjectSettings attach to a folder that isn't
        // this project's, leaking the previous project's name, tint, and
        // protocols folder across the reset. Verify at least one loaded
        // file lives under the detected folder; if not, return null so
        // callers treat the source root as unknown (empty name, empty
        // protocols folder) until a proper project is loaded.
        if (!repo.getClassFileMap().isEmpty()) {
            String prefix = detected.getAbsolutePath() + File.separator;
            String detectedAbs = detected.getAbsolutePath();
            boolean containsLoaded = false;
            for (java.io.File f : repo.getClassFileMap().values()) {
                if (f == null) continue;
                String fp = f.getAbsolutePath();
                if (fp.equals(detectedAbs) || fp.startsWith(prefix)) {
                    containsLoaded = true;
                    break;
                }
            }
            if (!containsLoaded) {
                return null;
            }
        }
        return detected;
    }

/**
     * Rebinds project-scoped state (name, icon tint, protocols folder) to the
     * folder that is currently the source root. Called after class files
     * finish loading, after a DnD batch finishes, before a build stamp (so a
     * rename prompt writes to the right project file), and whenever the
     * language mode changes — the only paths where the source root can change
     * at runtime.
     *
     * Idempotent: no-op if the source root is unchanged, so ordinary
     * refreshes don't thrash name/tint/protocol lookups or spam the log.
     */

private void reloadProjectSettings() {
        java.io.File sourceRoot = detectSourceRoot();
        if (sourceRoot == null) {
            return;
        }
        // File.equals is case-insensitive on Windows, which matters because
        // different dialogs (directory picker, source-root detection) can
        // return the same physical folder with different letter-casing.
        if (projectSourceRoot != null && sourceRoot.equals(projectSourceRoot)) {
            return;
        }
        projectSourceRoot = sourceRoot;
        projectSettings = new wv.codeclip.io.ProjectSettings(
                sourceRoot,
                () -> syncBuildInfoFromDisk(sourceRoot));

        String oldName = projectNameManager.getProjectName();
        long oldSeed = projectNameManager.getIconTintSeed();
        java.io.File oldProtocolsDir = protocolDirectoryManager.getProtocolsDir().toFile();

        projectNameManager.attachProjectSettings(projectSettings);
        protocolDirectoryManager.attachProjectSettings(projectSettings);
        applyBackupCheckpointsIfNeeded();

        boolean nameOrTintChanged = !java.util.Objects.equals(
                oldName, projectNameManager.getProjectName())
                || oldSeed != projectNameManager.getIconTintSeed();
        if (nameOrTintChanged) {
            titleFrozen = false;
            refreshTitle();
            setIcon();
        }

        java.io.File newProtocolsDir = protocolDirectoryManager.getProtocolsDir().toFile();
        if (!newProtocolsDir.equals(oldProtocolsDir)) {
            this.protocolLibrary = protocolDirectoryManager.buildLibrary();
            this.protocolPasteRouter = new wv.codeclip.protocol.engine.ProtocolPasteRouter(
                    protocolLibrary, protocolUndoManager);
            if (protocolManagerDialog != null && protocolManagerDialog.isDisplayable()) {
                protocolManagerDialog.rebind(this.protocolLibrary);
            }
            appendTempLog("Project protocols folder: " + this.protocolLibrary.getProtocolsDir());
        }

        // Project-scoped backup settings may differ — resync the checkpoint dialog
        if (checkpointDialog != null && checkpointDialog.isDisplayable()) {
            checkpointDialog.refresh();
        }
    }

/**
     * Re-reads buildinfo.properties from disk and refreshes CodeClip's
     * in-memory copy of it. Called after ProjectSettings writes, so the repo
     * map (used by undo, checkpoint state, Versions tab) doesn't lie about
     * what's on disk. Checkpoint is advanced to the new content — a rename or
     * protocols-folder pick is an intentional save, not an unsynced drift.
     */
    private void syncBuildInfoFromDisk(java.io.File sourceRoot) {
        if (sourceRoot == null) {
            return;
        }
        java.io.File file = new java.io.File(sourceRoot, BUILD_INFO_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            String content = Files.readString(file.toPath());
            String path = file.getAbsolutePath();
            repo.getClassCodeMap().put(path, content);
            repo.getClassFileMap().put(path, file);
            repo.setCheckpoint(path, content);
            refreshText();
        } catch (java.io.IOException ignored) {
        }
    }

/**
     * Restores checkpoint entries from the project's backup directory.
     *
     * Called from reloadProjectSettings on project attach. Restores only
     * when:
     *   - permanent checkpoint is enabled in this project's buildinfo.properties
     *   - backup.directory is set and the folder exists with files
     *   - at least one loaded file lives under the current project root
     *
     * Applies only to files that are loaded AND have an unambiguous backup
     * match (exactly one loaded file with that name). Files in backup but
     * not loaded are skipped — never resurrect deleted files from stale
     * backup. Files loaded but not in backup keep their disk-content
     * checkpoint (they were added after the last backup).
     *
     * The effect: after this runs, checkpointCodeMap holds the backup
     * contents, classCodeMap holds disk contents. Any file where they
     * differ triggers hasPendingRestores() → CheckpointDialog shows
     * "you have pending restores" and Restore to Checkpoint pulls the
     * backup state back in-memory. Persists across app restarts because
     * the backup lives on disk.
     */

private void applyBackupCheckpointsIfNeeded() {
    System.out.println("[Frame] applyBackup start settings="
            + (projectSettings != null) + " root=" + projectSourceRoot
            + " repoSize=" + repo.getClassCodeMap().size()
            + " alreadyApplied=" + backupCheckpointsAppliedForRoot);
    if (projectSettings == null || projectSourceRoot == null) {
        System.out.println("[Frame] applyBackup: no project");
        return;
    }
    if (repo.getClassCodeMap().isEmpty()) {
        System.out.println("[Frame] applyBackup: repo empty");
        return;
    }

    String rootKey = projectSourceRoot.getAbsolutePath();
    if (rootKey.equals(backupCheckpointsAppliedForRoot)) {
        System.out.println("[Frame] applyBackup: already applied for " + rootKey);
        return;
    }

    String rootPrefix = rootKey + java.io.File.separator;
    boolean anyFileUnderRoot = false;
    for (java.io.File f : repo.getClassFileMap().values()) {
        if (f == null) continue;
        String fp = f.getAbsolutePath();
        if (fp.equals(rootKey) || fp.startsWith(rootPrefix)) {
            anyFileUnderRoot = true;
            break;
        }
    }
    if (!anyFileUnderRoot) {
        System.out.println("[Frame] applyBackup: no loaded file under " + rootKey);
        return;
    }

    try {
        boolean enabled = projectSettings.loadPermanentCheckpointEnabled();
        System.out.println("[Frame] applyBackup: permanentEnabled=" + enabled);
        if (!enabled) return;

        String base = projectSettings.loadBackupDirectory();
        System.out.println("[Frame] applyBackup: baseDir='" + base + "'");
        if (base == null || base.isBlank()) return;

        java.io.File backupFolder = projectSettings.resolveBackupFolder();
        System.out.println("[Frame] applyBackup: resolvedFolder=" + backupFolder);
        if (backupFolder == null) return;

        migrateStrayBackupsToSubfolder(projectSettings, backupFolder);

        if (!backupFolder.exists() || !backupFolder.isDirectory()) {
            appendTempLog("Backup restore: no backup folder at "
                    + backupFolder.getAbsolutePath());
            System.out.println("[Frame] applyBackup: folder missing");
            return;
        }

        java.io.File[] backupFiles = backupFolder.listFiles(java.io.File::isFile);
        if (backupFiles == null || backupFiles.length == 0) {
            appendTempLog("Backup restore: folder is empty: "
                    + backupFolder.getAbsolutePath());
            System.out.println("[Frame] applyBackup: folder empty");
            return;
        }

        int applied = 0;
        for (java.io.File backupFile : backupFiles) {
            String name = backupFile.getName();
            List<String> matches = new ArrayList<>();
            for (Map.Entry<String, java.io.File> e : repo.getClassFileMap().entrySet()) {
                java.io.File loaded = e.getValue();
                if (loaded != null && loaded.getName().equalsIgnoreCase(name)) {
                    matches.add(e.getKey());
                }
            }
            System.out.println("[Frame] applyBackup: file=" + name
                    + " matches=" + matches.size());
            if (matches.size() != 1) continue;
            try {
                String content = Files.readString(backupFile.toPath());
                repo.setCheckpoint(matches.get(0), content);
                applied++;
            } catch (java.io.IOException ex) {
                appendTempLog("Backup restore: read failed for " + name + ": " + ex.getMessage());
            }
        }

        // Mark applied and register the Versions entry as soon as a backup
        // folder with files was found — even when every file already matches
        // its backup (applied == 0). Otherwise an unchanged checkpoint is
        // invisible on the next launch, which is exactly the confusion this
        // is fixing.
        backupCheckpointsAppliedForRoot = rootKey;
        addRestoredCheckpointVersionEntry(backupFolder, backupFiles);

        if (applied > 0) {
            appendTempLog("Restored " + applied + " checkpoint(s) from backup: "
                    + backupFolder.getAbsolutePath());
            refreshPanels();
            if (checkpointDialog != null && checkpointDialog.isDisplayable()) {
                checkpointDialog.refresh();
            }
        } else {
            appendTempLog("Backup restore: " + backupFiles.length
                    + " backup file(s) already in sync. Folder: "
                    + backupFolder.getAbsolutePath());
        }
    } catch (Exception ex) {
        appendTempLog("Backup restore failed: " + ex);
        ex.printStackTrace();
    }
}

/**
     * Moves files from the old flat-in-base-folder layout into the project
     * subfolder. Idempotent — safe to call on every open. Skips files that
     * already exist in the subfolder so live backups are never overwritten.
     */
    private void migrateStrayBackupsToSubfolder(wv.codeclip.io.ProjectSettings ps,
                                                 java.io.File subfolder) {
        String basePath = ps.loadBackupDirectory();
        if (basePath == null || basePath.isBlank()) return;
        java.io.File base = new java.io.File(basePath);
        if (!base.exists() || !base.isDirectory()) return;
        if (base.getAbsolutePath().equals(subfolder.getAbsolutePath())) return;

        java.io.File[] strays = base.listFiles(java.io.File::isFile);
        if (strays == null || strays.length == 0) return;
        if (!subfolder.exists() && !subfolder.mkdirs()) return;

        int migrated = 0;
        for (java.io.File f : strays) {
            java.io.File dest = new java.io.File(subfolder, f.getName());
            if (dest.exists()) continue;
            try {
                Files.move(f.toPath(), dest.toPath());
                migrated++;
            } catch (java.io.IOException ignored) {
            }
        }
        if (migrated > 0) {
            appendTempLog("Migrated " + migrated
                    + " backup file(s) from base folder to: "
                    + subfolder.getAbsolutePath());
        }
    }

/**
     * Adds a single Versions-tab entry for the checkpoint restored from backup.
     * Only runs on a fresh session (empty versionHistory) so later patches
     * naturally stack above it — leaving the checkpoint entry at the bottom,
     * marking "state on disk was at this point when you last saved".
     *
     * No-op when no name is persisted (user never named a checkpoint) or when
     * versionHistory already has entries (user did something before this ran).
     */

    private boolean checkpointVersionEntryAdded = false;

    private void addRestoredCheckpointVersionEntry(java.io.File backupFolder,
                                                       java.io.File[] backupFiles) {
            if (projectSettings == null) return;
            if (checkpointVersionEntryAdded) return;
            // A checkpoint entry may already exist if the user just pressed
            // "Convert to Permanent" in this session (that path registers
            // one immediately). Adding a second on the next dialog open
            // would append a duplicate row that appears chronologically
            // newer than patches that actually followed it.
            boolean hasCheckpointEntry = versionHistory.stream()
                    .anyMatch(VersionEvent::isCheckpoint);
            if (hasCheckpointEntry) {
                checkpointVersionEntryAdded = true;
                return;
            }
            if (backupFiles == null || backupFiles.length == 0) return;
            checkpointVersionEntryAdded = true;

    String name = projectSettings.loadCheckpointName();
    if (name == null || name.isBlank() || "Checkpoint set".equals(name)) {
        name = "Checkpoint";
    }

    String time = "--:--:--";
    List<String> fileNames = new ArrayList<>();
    long newestMtime = 0;
    for (java.io.File f : backupFiles) {
        fileNames.add(f.getName());
        newestMtime = Math.max(newestMtime, f.lastModified());
    }
    if (newestMtime > 0) {
        time = new java.text.SimpleDateFormat("HH:mm:ss")
                .format(new java.util.Date(newestMtime));
    }

    String filesStr = String.join(", ", fileNames);
    versionHistory.add(new VersionEvent(name, filesStr, time, "?", List.of(name), true));
    versionCurrentIdx = versionHistory.size() - 1;
    refreshVersionPanel();
    flashNewestVersionRow();
}

/**
     * Clears everything project-scoped in memory without touching disk.
     * Used by Reset and by Recent Projects restore. Recent Projects itself
     * is NOT cleared — that list is app-level history, not project state.
     */
    private void resetProjectState() {
        actions.resetAll(classPanel);
        undoManager.clear();
        syncUndoRedo.run();
        versionHistory.clear();
        versionCurrentIdx = -1;
        checkpointVersionEntryAdded = false;
        refreshVersionPanel();

        projectSourceRoot = null;
        projectSettings = null;
        backupCheckpointsAppliedForRoot = null;
        projectNameManager.resetToUnattached();
        protocolDirectoryManager.resetToUnattached();

        // Reset means "fresh directories for the next project". The pending
        // flag is the reliable mechanism (works regardless of how
        // *Directory.load handles empty strings); the save+load below is only
        // best-effort cleanup of the intermediate state so dialogs opened
        // between Reset and the first drop don't show the stale value.
        pendingDirectoryAutoSet = true;
        settings.saveGodotDirectory("");
        settings.saveHtmlDirectory("");
        settings.saveGenericDirectory("");
        wv.codeclip.godot.GodotDirectory.load(settings);
        wv.codeclip.html.HtmlDirectory.load(settings);
        wv.codeclip.generic.GenericDirectory.load(settings);

        protocolLibrary = protocolDirectoryManager.buildLibrary();
        protocolPasteRouter = new wv.codeclip.protocol.engine.ProtocolPasteRouter(
                protocolLibrary, protocolUndoManager);
        if (protocolManagerDialog != null && protocolManagerDialog.isDisplayable()) {
            protocolManagerDialog.dispose();
        }
        protocolManagerDialog = null;
        titleFrozen = false;
        refreshTitle();
        setIcon();

        // Reset also wipes the persistent log. Without this, a session that
        // cycles reset->load many times piles up styled log lines in the
        // JTextPane and slowly fragments the heap. Recent Projects history
        // is app-level state and deliberately survives (it lives in
        // ~/codeclip.properties, not in the reset surface).
        logRawLines.clear();
        if (persistentLogPane != null) {
            try {
                persistentLogPane.getStyledDocument().remove(
                        0, persistentLogPane.getStyledDocument().getLength());
            } catch (javax.swing.text.BadLocationException ignored) {
            }
        }
    }

    /** Rebuilds the Recent Projects submenu on demand — before the user
     *  opens the menu, so it reflects the latest set of entries. */
    private void rebuildRecentProjectsMenu() {
        if (recentProjectsMenu == null) return;
        recentProjectsMenu.removeAll();
        if (recentProjects.isEmpty()) {
            JMenuItem empty = new JMenuItem("(no recent projects)");
            empty.setEnabled(false);
            recentProjectsMenu.add(empty);
            return;
        }
        for (wv.codeclip.io.RecentProjects.Entry entry : recentProjects.getEntries()) {
            JMenuItem item = new JMenuItem(entry.menuLabel());
            item.setToolTipText("<html>" + entry.sourceRoot()
                    + "<br>" + entry.filePaths().size() + " file(s)</html>");
            item.addActionListener(e -> restoreRecentProject(entry));
            recentProjectsMenu.add(item);
        }
    }

    /**
     * Reloads a project from Recent Projects: reset in-memory state, then
     * load the recorded file set. reloadProjectSettings re-attaches as part
     * of loading, and registerCurrentProjectInRecent bumps this entry to the
     * top so it stays first next time.
     */
    private void restoreRecentProject(wv.codeclip.io.RecentProjects.Entry entry) {
        if (entry.sourceRoot() == null || entry.sourceRoot().isBlank()) return;

        java.io.File rootDir = new java.io.File(entry.sourceRoot());
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "The project folder no longer exists:\n" + entry.sourceRoot()
                    + "\n\nRemove it from Recent Projects?",
                    "Project Missing", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                recentProjects.remove(entry.sourceRoot());
                settings.saveProperties();
            }
            return;
        }

        List<File> existing = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String path : entry.filePaths()) {
            File f = new File(path);
            if (f.exists()) existing.add(f);
            else missing.add(path);
        }
        if (existing.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "None of the files recorded for this project could be found on disk.\n"
                    + "Source root: " + entry.sourceRoot(),
                    "Files Missing", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!missing.isEmpty()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    missing.size() + " file(s) for this project are missing from disk.\n\n"
                    + "Continue loading the remaining " + existing.size() + " file(s)?",
                    "Some Files Missing", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        resetProjectState();
        addFilesBatched(existing);
    }

    /**
     * Registers the currently attached project in Recent Projects. Only files
     * under the source root are recorded, so a session that briefly mixed
     * files from two folders doesn't pollute one project's entry with the
     * other's files.
     */

/**
     * Registers the currently loaded files in Recent Projects. Records only
     * files under the attached source root when available, so a mixed
     * session doesn't pollute one project's entry with another's files.
     * Falls back to the loaded files' common parent dir when no source root
     * was attached — otherwise Recent Projects would silently stay empty
     * for projects that detectSourceRoot can't resolve (default-package
     * files, or files dropped while in a mismatched mode).
     */
    private void registerCurrentProjectInRecent() {
        if (repo.getClassCodeMap().isEmpty()) return;

        String rootPath = projectSourceRoot != null
                ? projectSourceRoot.getAbsolutePath()
                : null;

        List<String> paths = new ArrayList<>();
        if (rootPath != null) {
            String prefix = rootPath + File.separator;
            for (String path : repo.getClassCodeMap().keySet()) {
                if (path.equals(rootPath) || path.startsWith(prefix)) {
                    paths.add(path);
                }
            }
        }
        if (paths.isEmpty()) {
            for (String path : repo.getClassCodeMap().keySet()) {
                paths.add(path);
            }
            rootPath = commonParentDirOf(paths);
            if (rootPath == null) return;
        }
        recentProjects.add(rootPath, projectNameManager.getProjectName(), paths);
        recentProjects.save(settings);
        settings.saveProperties();
    }

    private String commonParentDirOf(List<String> filePaths) {
        if (filePaths.isEmpty()) return null;
        java.io.File common = new java.io.File(filePaths.get(0)).getParentFile();
        if (common == null) return null;
        for (String p : filePaths) {
            java.io.File parent = new java.io.File(p).getParentFile();
            if (parent == null) return null;
            while (common != null
                    && !parent.getAbsolutePath().equals(common.getAbsolutePath())
                    && !parent.getAbsolutePath().startsWith(common.getAbsolutePath() + File.separator)) {
                common = common.getParentFile();
            }
            if (common == null) return null;
        }
        return common.getAbsolutePath();
    }

private void openCheckpointDialog() {
    // Force a retry here — this covers the case where the initial restore
    // at file-load time bailed out before the user's backup settings or
    // project name were fully in place.
    applyBackupCheckpointsIfNeeded();

    if (checkpointDialog == null || !checkpointDialog.isDisplayable()) {
        checkpointDialog = new CheckpointDialog(this, repo, () -> {
            refreshText();
            refreshPanels();
            stampBuildInfo();
            if (pendingIsCheckpoint) {
                addCheckpointVersionEvent();
            }
        }, () -> projectSettings);
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
    checkpointDialog.setOnRestoreCallback(this::onCheckpointRestored);
    checkpointDialog.refresh();
    checkpointDialog.setVisible(true);
    }

    /**
     * Called by CheckpointDialog after Restore-to-Checkpoint succeeds.
     *
     * Clearing the undo stack is required: restore moved in-memory state
     * back to the checkpoint, so every undo entry created AFTER the
     * checkpoint now describes a state that no longer follows from the
     * current one. If left in the stack, pressing Undo would jump the
     * user to some intermediate pre-restore state (e.g. state B when the
     * checkpoint was A and pre-restore was C). Undoing PAST the checkpoint
     * is also undesirable after a deliberate restore — "I want to be at
     * the checkpoint" means starting fresh from there.
     *
     * Version history is truncated to the checkpoint event for the same
     * reason: entries after it describe patches whose effects no longer
     * exist. Leaving them would show zombie "undone" rows the user can
     * never reach.
     */
    private void onCheckpointRestored() {
        System.out.println("[Frame] onCheckpointRestored: clearing undo, truncating versions");
        undoManager.clear();
        syncUndoRedo.run();

        int checkpointIdx = -1;
        int scanFrom = Math.min(versionCurrentIdx, versionHistory.size() - 1);
        for (int i = scanFrom; i >= 0; i--) {
            if (versionHistory.get(i).isCheckpoint()) {
                checkpointIdx = i;
                break;
            }
        }
        if (checkpointIdx >= 0) {
            while (versionHistory.size() > checkpointIdx + 1) {
                versionHistory.remove(versionHistory.size() - 1);
            }
            versionCurrentIdx = checkpointIdx;
            System.out.println("[Frame] version history truncated to index " + checkpointIdx);
            refreshVersionPanel();
        } else {
            // Edge case: no checkpoint event in history (e.g. user set
            // checkpoint before this session's version tracking started,
            // or history was lost). Don't touch it — undoing is disabled
            // anyway, and the current version pointer being slightly
            // stale is less confusing than wiping everything.
            System.out.println("[Frame] no checkpoint entry in version history; left as-is");
        }
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
    // Intentionally does NOT touch repo.setCheckpoint. This method fires
    // for patches (existing files) and new pastes. Auto-promoting any path
    // that wasn't already in the checkpoint map silently absorbed the very
    // first edit after project load, which is exactly why "Set New
    // Checkpoint" stayed disabled until a second edit.
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
                setCurrentModeDirectory(chosen); // routes through the flag-clearing setter
                dirDisplay.setText(chosen.getAbsolutePath());
                projectSourceRoot = null;
                reloadProjectSettings();
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
                setCurrentModeDirectory(chosen);
                dirDisplay.setText(chosen.getAbsolutePath());
                projectSourceRoot = null;
                reloadProjectSettings();
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
                setCurrentModeDirectory(chosen);
                dirDisplay.setText(chosen.getAbsolutePath());
                projectSourceRoot = null;
                reloadProjectSettings();
            }
        });

        dialog.pack();
        dialog.setMinimumSize(new Dimension(420, 200));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * Auto-sets the mode-specific main directory (Godot / HTML / Generic)
     * from the first loaded batch, if not already set. Java mode is excluded
     * — it uses source-root / main-class detection instead. Once set (by this
     * method or by the user), the directory is never overridden automatically.
     *
     * hint: files from the current DnD batch (may be null to scan the repo).
     */

private void autoSetModeDirectoryFromRepo(List<File> hint) {
        AppMode mode = wv.codeclip.modecontext.ModeContext.getMode();
        if (mode == AppMode.JAVA) {
            return; // Java uses main-class / source-root detection, not batch-based auto-set
        }

        boolean unset = getCurrentModeDirectory() == null;
        // Respect an existing directory UNLESS Reset was pressed and the flag
        // is still waiting to be satisfied by the next batch.
        if (!unset && !pendingDirectoryAutoSet) {
            return;
        }

        List<File> source = new ArrayList<>();
        if (hint != null) {
            source.addAll(hint);
        } else {
            source.addAll(repo.getClassFileMap().values());
        }

        List<File> candidates = new ArrayList<>();
        for (File f : source) {
            if (f != null && mode.accepts(f.getName())) {
                candidates.add(f);
            }
        }
        if (candidates.isEmpty()) {
            return; // leave flag set — next batch with real files retries
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
            setCurrentModeDirectory(best); // also clears pendingDirectoryAutoSet
            appendTempLog(modeDirectoryLabel(mode) + " directory auto-set: "
                    + best.getAbsolutePath());
        }
    }

private File getCurrentModeDirectory() {
        return switch (wv.codeclip.modecontext.ModeContext.getMode()) {
            case GODOT -> wv.codeclip.godot.GodotDirectory.isSet()
                    ? wv.codeclip.godot.GodotDirectory.get() : null;
            case HTML -> wv.codeclip.html.HtmlDirectory.isSet()
                    ? wv.codeclip.html.HtmlDirectory.get() : null;
            case GENERIC -> wv.codeclip.generic.GenericDirectory.isSet()
                    ? wv.codeclip.generic.GenericDirectory.get() : null;
            default -> null;
        };
    }

private void setCurrentModeDirectory(File dir) {
        switch (wv.codeclip.modecontext.ModeContext.getMode()) {
            case GODOT -> wv.codeclip.godot.GodotDirectory.set(dir);
            case HTML -> wv.codeclip.html.HtmlDirectory.set(dir);
            case GENERIC -> wv.codeclip.generic.GenericDirectory.set(dir);
            default -> { }
        }
        // Any set — auto or manual — satisfies the post-Reset "fresh dirs"
        // intent, so subsequent batches must not override this choice.
        pendingDirectoryAutoSet = false;
    }

private static String modeDirectoryLabel(AppMode mode) {
        return switch (mode) {
            case GODOT -> "Godot";
            case HTML -> "HTML";
            case GENERIC -> "Generic";
            default -> mode.name();
        };
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

        // Inherit name: prefer the version immediately below this new one
        // (the last non-undone version), else fall back to the persisted name
        // from buildinfo (survives app restarts where versionHistory starts empty).
        String previousTitle = (versionCurrentIdx >= 0 && versionCurrentIdx < versionHistory.size())
                ? versionHistory.get(versionCurrentIdx).title()
                : null;
        String title = (previousTitle != null && !previousTitle.isBlank())
                ? previousTitle
                : (projectSettings != null ? projectSettings.loadCheckpointName() : null);
        if (title == null || title.isBlank()) {
            title = "Checkpoint set";
        }

        if (projectSettings != null) {
            projectSettings.saveCheckpointName(title);
        }

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

        // Single click → detail dialog (delayed to detect double-click).
        // Double click → inline rename (persists name for checkpoints).
        row.addMouseListener(new java.awt.event.MouseAdapter() {
            private javax.swing.Timer singleClickTimer;
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    if (singleClickTimer != null) singleClickTimer.stop();
                    renameVersionEvent(ev);
                } else if (e.getClickCount() == 1) {
                    singleClickTimer = new javax.swing.Timer(250,
                            evt -> showVersionDetail(ev, active));
                    singleClickTimer.setRepeats(false);
                    singleClickTimer.start();
                }
            }
        });

        return row;
    }

/**
     * Inserts a single styled line at the top of the persistent log pane.
     */

private static final java.util.regex.Pattern FILE_PATH_IN_LOG_PATTERN =
        java.util.regex.Pattern.compile(
            "[\\w\\-\\\\./\\\\:]*\\.(?:java|py|yaml|yml|html|htm|js|ts|"
            + "txt|properties|xml|json|md|css|gradle|bat|sh|cs|gd|gdscript|"
            + "sql|ini|cfg|conf|toml)\\b"
        );

private void insertPersistentLogLine(String message, boolean isSeparator) {
    StyledDocument doc = persistentLogPane.getStyledDocument();
    try {
        SimpleAttributeSet baseStyle = new SimpleAttributeSet();
        StyleConstants.setFontFamily(baseStyle, Font.MONOSPACED);
        StyleConstants.setFontSize(baseStyle, 12);

        if (isSeparator) {
            StyleConstants.setForeground(baseStyle, LOG_SEP_COLOR);
            doc.insertString(0, message + "\n", baseStyle);
            SwingUtilities.invokeLater(() -> persistentLogPane.setCaretPosition(0));
            return;
        }

        Color lineColor = resolveLogLineColor(message);
        Color defaultFg = UIManager.getColor("TextArea.foreground");
        if (defaultFg == null) defaultFg = Color.BLACK;

        boolean lineHasOwnColor = lineColor != null && !lineColor.equals(defaultFg);
        if (lineHasOwnColor) {
            // "Class Created:", "✓", "✗", "Target Build:", section headers —
            // respect the existing whole-line coloring.
            StyleConstants.setForeground(baseStyle, lineColor);
            StyleConstants.setBold(baseStyle, true);
            doc.insertString(0, message + "\n", baseStyle);
            SwingUtilities.invokeLater(() -> persistentLogPane.setCaretPosition(0));
            return;
        }

        // Base-colored line — split it so file names come out in blue,
        // matching the class-name highlight in the Notes view.
        StyleConstants.setForeground(baseStyle, defaultFg);
        SimpleAttributeSet pathStyle = new SimpleAttributeSet(baseStyle);
        StyleConstants.setForeground(pathStyle, LOG_CLASS_COLOR);
        StyleConstants.setBold(pathStyle, true);

        String full = message + "\n";
        java.util.regex.Matcher m = FILE_PATH_IN_LOG_PATTERN.matcher(full);
        int last = 0;
        int pos = 0;
        while (m.find()) {
            if (m.start() > last) {
                String seg = full.substring(last, m.start());
                doc.insertString(pos, seg, baseStyle);
                pos += seg.length();
            }
            String pathSeg = m.group();
            doc.insertString(pos, pathSeg, pathStyle);
            pos += pathSeg.length();
            last = m.end();
        }
        if (last < full.length()) {
            doc.insertString(pos, full.substring(last), baseStyle);
        }
        SwingUtilities.invokeLater(() -> persistentLogPane.setCaretPosition(0));
    } catch (BadLocationException ignored) {}
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
    private static final int MAX_RAW_LOG_LINES = 10_000;
    private final java.util.List<RawLogLine> logRawLines = new java.util.ArrayList<>();

    private void addRawLogLine(String text, boolean isSep) {
        logRawLines.add(0, new RawLogLine(text, isSep));
        while (logRawLines.size() > MAX_RAW_LOG_LINES) {
            logRawLines.remove(logRawLines.size() - 1);
        }
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
                    String trimmed = newTitle.trim();
                    ev.setTitle(trimmed);
                    if (ev.isCheckpoint() && projectSettings != null) {
                        projectSettings.saveCheckpointName(trimmed);
                    }
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

/** Prompts for a new title and applies it. For checkpoints, persists to project buildinfo. */
    private void renameVersionEvent(VersionEvent ev) {
        if (ev == null) return;
        JTextField titleField = new JTextField(ev.title());
        titleField.setFont(titleField.getFont().deriveFont(14f));
        titleField.setPreferredSize(new Dimension(420, 32));
        titleField.selectAll();

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("Enter new title:"), BorderLayout.NORTH);
        panel.add(titleField, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(440, 70));

        int result = JOptionPane.showConfirmDialog(
                this, panel, ev.isCheckpoint() ? "Rename Checkpoint" : "Rename Version",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String newTitle = titleField.getText();
        if (newTitle == null || newTitle.trim().isEmpty()) return;
        newTitle = newTitle.trim();

        ev.setTitle(newTitle);
        if (ev.isCheckpoint() && projectSettings != null) {
            projectSettings.saveCheckpointName(newTitle);
        }
        refreshVersionPanel();
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
        protocolManagerDialog = new wv.codeclip.protocol.ui.ProtocolManagerDialog(this,
                protocolDirectoryManager,
                protocolUndoManager,
                this::logProtocolLine,
                this::onProtocolLibraryChanged);
        protocolManagerDialog.setVisible(true);
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
        // No project attached — protocolsBaseDir is a placeholder (user.dir)
        // and must not be written into. Refuse rather than silently route to
        // the wrong place. The rest of the clipboard still flows through
        // normal paste handling untouched.
        if (!protocolDirectoryManager.isAttached()) {
            JOptionPane.showMessageDialog(this,
                "Protocol commands found in clipboard, but no project is loaded yet.\n"
                + "Load a project first — protocols are per-project.\n\n"
                + "Any non-protocol content in the clipboard will still be applied normally.",
                "No Project Loaded", JOptionPane.WARNING_MESSAGE);
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

private wv.codeclip.codemap.CodeMapBuilder.Scope promptCodemapScope() {
    // Default selection reflects the last-used scope, so repeat use of the
    // same choice is a single Enter keypress instead of a re-decision.
    wv.codeclip.codemap.CodeMapBuilder.Scope last =
            wv.codeclip.codemap.CodeMapBuilder.Scope.ENABLED;
    try {
        last = wv.codeclip.codemap.CodeMapBuilder.Scope.valueOf(settings.loadCodemapScope());
    } catch (IllegalArgumentException ignored) {
        // Stored value missing or corrupted — fall back to ENABLED.
    }
    int defaultIdx = (last == wv.codeclip.codemap.CodeMapBuilder.Scope.ALL) ? 1 : 0;
    Object[] options = {"Enabled Only", "All Files", "Cancel"};
    int choice = JOptionPane.showOptionDialog(
            this,
            "Which files should the codemap include?\n\n"
            + "  • Enabled Only\n"
            + "  • All Files",
            "Copy Codemap",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[defaultIdx]);
    if (choice == 0) {
        return wv.codeclip.codemap.CodeMapBuilder.Scope.ENABLED;
    }
    if (choice == 1) {
        return wv.codeclip.codemap.CodeMapBuilder.Scope.ALL;
    }
    return null; // Cancel / dialog dismissed
}

/** Keeps the Expand All / Collapse All button in sync with tree state. */
    private void updateTreeCollapseButton() {
        if (treeCollapseBtn == null || classTreePanel == null) return;
        treeCollapseBtn.setVisible(treeViewActive);
        if (!treeViewActive) return;
        boolean allCollapsed = classTreePanel.isAllCollapsed();
        treeCollapseBtn.setText(allCollapsed ? "⊞" : "⊟");
        treeCollapseBtn.setToolTipText(allCollapsed
                ? "Expand every folder in the tree"
                : "Collapse every folder in the tree");
    }

}
