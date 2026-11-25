package wv.codeclip.ui;

import wv.codeclip.io.ClipboardService;
import wv.codeclip.io.SettingsManager;
import wv.codeclip.model.ClassRepository;
import wv.codeclip.search.ContentSearcher;
import wv.codeclip.search.ContentSearcher.FileResult;
import wv.codeclip.search.ContentSearcher.Hit;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Non-modal "Search in Files" dialog: searches the contents of the files
 * currently loaded in CodeClip (in-memory copy), with Match case, Whole word,
 * Regex and Ignore whitespace. Searching runs on a background thread over a
 * snapshot of the repository, so it never races with paste/update workers.
 */
public final class ContentSearchDialog extends JDialog {

    private static final String PREF_QUERY = "search.query";
    private static final String PREF_CASE = "search.matchCase";
    private static final String PREF_WORD = "search.wholeWord";
    private static final String PREF_REGEX = "search.regex";
    private static final String PREF_WS = "search.ignoreWhitespace";
    private static final String PREF_SCOPE = "search.scope";
    private static final String PREF_FILES = "search.fileFilter";

    private static final int AUTO_EXPAND_HIT_LIMIT = 300;

    private record Target(String path, String name, String text) {}

    private record HitNode(FileResult file, Hit hit) {}

    private final ClassRepository repo;
    private final SettingsManager settings;
    private final Runnable onEnabledChanged;

    private final JTextField queryField = new JTextField(32);
    private final JCheckBox matchCaseBox = new JCheckBox("Match case");
    private final JCheckBox wholeWordBox = new JCheckBox("Whole word");
    private final JCheckBox regexBox = new JCheckBox("Regex");
    private final JCheckBox ignoreWsBox = new JCheckBox("Ignore whitespace");
    private final JComboBox<String> scopeBox
            = new JComboBox<>(new String[]{"Enabled files", "All loaded files"});
    private final JTextField fileFilterField = new JTextField(16);
    private final JButton searchBtn = new JButton("Search");
    private final JButton stopBtn = new JButton("Stop");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel errorLabel = new JLabel(" ");

    private final JButton enableOnlyBtn = new JButton("Enable Only Matched");
    private final JButton enableAlsoBtn = new JButton("Enable Matched");
    private final JButton copyEnableBtn = new JButton("Copy @@Enable");
    private final JButton copyResultsBtn = new JButton("Copy Results");
    private final JButton openBtn = new JButton("Open in Editor");

    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("results");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final JTree tree = new JTree(treeModel);

    private SwingWorker<Void, FileResult> worker;
    private AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private int searchGeneration = 0;

    private final List<FileResult> lastResults = new ArrayList<>();
    private final Set<String> duplicateNames = new HashSet<>();
    private String lastDescription = "";
    private int totalHits = 0;
    private int filesWithHits = 0;
    private int truncatedFiles = 0;

    public ContentSearchDialog(JFrame parent, ClassRepository repo,
                               SettingsManager settings, Runnable onEnabledChanged) {
        super(parent, "Search in Files", false);
        this.repo = repo;
        this.settings = settings;
        this.onEnabledChanged = onEnabledChanged != null ? onEnabledChanged : () -> { };

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUi();
        loadPrefs();
        wireActions();
        updateOptionStates();
        setSearching(false);
        updateActionButtons();

        setSize(860, 600);
        setMinimumSize(new Dimension(640, 420));
        setLocationRelativeTo(parent);
    }

    public void focusQuery() {
        toFront();
        queryField.requestFocusInWindow();
        queryField.selectAll();
    }

    @Override
    public void dispose() {
        cancelRunning();
        savePrefs();
        super.dispose();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        queryField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        queryField.setToolTipText("Text to find. Press Enter to search.");
        JPanel queryRow = new JPanel(new BorderLayout(6, 0));
        queryRow.add(new JLabel("Find:"), BorderLayout.WEST);
        queryRow.add(queryField, BorderLayout.CENTER);
        JPanel queryButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        queryButtons.add(searchBtn);
        queryButtons.add(stopBtn);
        queryRow.add(queryButtons, BorderLayout.EAST);

        matchCaseBox.setToolTipText("Case-sensitive matching");
        wholeWordBox.setToolTipText("Only match whole words (letters, digits and _ are word characters)");
        regexBox.setToolTipText("Treat the query as a Java regular expression");
        JPanel optionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        optionsRow.add(matchCaseBox);
        optionsRow.add(wholeWordBox);
        optionsRow.add(regexBox);
        optionsRow.add(ignoreWsBox);

        fileFilterField.setToolTipText("Limit to files, e.g.  java, .js, *.css  or part of a name. Empty = all.");
        JPanel scopeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scopeRow.add(new JLabel("Scope:"));
        scopeRow.add(scopeBox);
        scopeRow.add(new JLabel("Files:"));
        scopeRow.add(fileFilterField);

        errorLabel.setForeground(new Color(180, 30, 30));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        for (JComponent row : new JComponent[]{queryRow, optionsRow, scopeRow, errorLabel}) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(row);
            top.add(Box.createVerticalStrut(4));
        }

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ResultRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.getVerticalScrollBar().setUnitIncrement(16);

        enableOnlyBtn.setToolTipText("Disable every file except the ones with matches");
        enableAlsoBtn.setToolTipText("Enable the files with matches, keep the rest as they are");
        copyEnableBtn.setToolTipText("Copies an @@Enable line listing the files with matches");
        copyResultsBtn.setToolTipText("Copies the result list (file, line, text) to the clipboard");
        openBtn.setToolTipText("Opens the selected file at the selected line (Notepad++ if installed)");

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.add(enableOnlyBtn);
        actions.add(enableAlsoBtn);
        actions.add(copyEnableBtn);
        actions.add(copyResultsBtn);
        actions.add(openBtn);
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());
        JPanel closeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        closeRow.add(closeBtn);
        JPanel buttonRow = new JPanel(new BorderLayout(6, 0));
        buttonRow.add(actions, BorderLayout.WEST);
        buttonRow.add(closeRow, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(statusLabel, BorderLayout.NORTH);
        bottom.add(buttonRow, BorderLayout.CENTER);

        content.add(top, BorderLayout.NORTH);
        content.add(treeScroll, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
    }

    private void wireActions() {
        searchBtn.addActionListener(e -> runSearch());
        queryField.addActionListener(e -> runSearch());
        fileFilterField.addActionListener(e -> runSearch());
        stopBtn.addActionListener(e -> stopSearch());

        java.awt.event.ActionListener rerun = e -> {
            updateOptionStates();
            if (!queryField.getText().isEmpty()) {
                runSearch();
            }
        };
        matchCaseBox.addActionListener(rerun);
        wholeWordBox.addActionListener(rerun);
        regexBox.addActionListener(rerun);
        ignoreWsBox.addActionListener(rerun);
        scopeBox.addActionListener(rerun);

        enableOnlyBtn.addActionListener(e -> enableMatched(true));
        enableAlsoBtn.addActionListener(e -> enableMatched(false));
        copyEnableBtn.addActionListener(e -> copyEnableLine());
        copyResultsBtn.addActionListener(e -> copyResults());
        openBtn.addActionListener(e -> openSelected());

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                if (tree.getRowForLocation(e.getX(), e.getY()) < 0) {
                    return;
                }
                if (selectedUserObject() instanceof HitNode) {
                    openSelected();
                }
            }
        });
        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    openSelected();
                }
            }
        });

        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void updateOptionStates() {
        boolean regex = regexBox.isSelected();
        ignoreWsBox.setEnabled(!regex);
        ignoreWsBox.setToolTipText(regex
                ? "Not available in regex mode — use \\s* in the pattern instead"
                : "Whitespace differences (spaces, tabs, line breaks) are ignored");
    }

    private void setSearching(boolean searching) {
        searchBtn.setEnabled(!searching);
        stopBtn.setEnabled(searching);
    }

    private void updateActionButtons() {
        boolean has = !lastResults.isEmpty();
        enableOnlyBtn.setEnabled(has);
        enableAlsoBtn.setEnabled(has);
        copyEnableBtn.setEnabled(has);
        copyResultsBtn.setEnabled(has);
        openBtn.setEnabled(has);
    }

    // ------------------------------------------------------------------
    // Searching
    // ------------------------------------------------------------------

    private void runSearch() {
        cancelRunning();
        clearResults();
        errorLabel.setText(" ");

        String query = queryField.getText();
        if (query.isEmpty()) {
            statusLabel.setText("Type something to search.");
            setSearching(false);
            return;
        }

        boolean regex = regexBox.isSelected();
        ContentSearcher.Options opts = new ContentSearcher.Options(
                query, matchCaseBox.isSelected(), wholeWordBox.isSelected(),
                regex, ignoreWsBox.isSelected() && !regex);
        Pattern pattern = compileOrReport(opts);
        if (pattern == null) {
            statusLabel.setText(" ");
            setSearching(false);
            return;
        }

        boolean enabledOnly = scopeBox.getSelectedIndex() == 0;
        String filter = fileFilterField.getText();

        Map<String, String> code = safeCopy(repo.getClassCodeMap());
        Map<String, File> files = safeCopy(repo.getClassFileMap());
        Set<String> disabled = safeCopySet(repo.getDisabledClasses());

        Map<String, Integer> nameCounts = new HashMap<>();
        List<Target> targets = new ArrayList<>();
        for (Map.Entry<String, String> e : code.entrySet()) {
            String path = e.getKey();
            String text = e.getValue();
            if (text == null) {
                continue;
            }
            File f = files.get(path);
            String name = f != null ? f.getName() : new File(path).getName();
            nameCounts.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
            if (enabledOnly && disabled.contains(path)) {
                continue;
            }
            if (!ContentSearcher.matchesNameFilter(name, filter)) {
                continue;
            }
            targets.add(new Target(path, name, text));
        }
        duplicateNames.clear();
        for (Map.Entry<String, Integer> e : nameCounts.entrySet()) {
            if (e.getValue() > 1) {
                duplicateNames.add(e.getKey());
            }
        }

        lastDescription = describe(opts, enabledOnly, filter);
        savePrefs();

        final int generation = ++searchGeneration;
        final AtomicBoolean cancel = new AtomicBoolean(false);
        cancelFlag = cancel;
        final AtomicInteger searched = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final long startNanos = System.nanoTime();
        final int total = targets.size();

        setSearching(true);
        statusLabel.setText("Searching " + total + " file(s)…");

        worker = new SwingWorker<Void, FileResult>() {
            @Override
            protected Void doInBackground() {
                for (Target t : targets) {
                    if (cancel.get()) {
                        break;
                    }
                    try {
                        FileResult r = ContentSearcher.searchFile(
                                t.path(), t.name(), t.text(), pattern, cancel::get);
                        searched.incrementAndGet();
                        if (!r.hits().isEmpty()) {
                            publish(r);
                        }
                    } catch (ContentSearcher.SearchCancelledException ex) {
                        break;
                    } catch (StackOverflowError ex) {
                        failed.incrementAndGet();
                    }
                }
                return null;
            }

            @Override
            protected void process(List<FileResult> chunks) {
                if (generation != searchGeneration) {
                    return;
                }
                for (FileResult r : chunks) {
                    addResult(r);
                }
                statusLabel.setText("Searching… " + totalHits + " match(es) in "
                        + filesWithHits + " file(s) — " + searched.get() + " / " + total);
            }

            @Override
            protected void done() {
                if (generation != searchGeneration) {
                    return;
                }
                try {
                    get();
                } catch (Exception ex) {
                    errorLabel.setText("Search failed: " + ex.getMessage());
                }
                setSearching(false);
                updateActionButtons();
                long ms = (System.nanoTime() - startNanos) / 1_000_000L;
                statusLabel.setText(finalStatus(cancel.get(), searched.get(), total, failed.get(), ms));
            }
        };
        worker.execute();
    }

    private Pattern compileOrReport(ContentSearcher.Options opts) {
        try {
            return ContentSearcher.compile(opts);
        } catch (PatternSyntaxException ex) {
            String desc = ex.getDescription();
            errorLabel.setText("Invalid regex: " + (desc != null ? desc : ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            errorLabel.setText(ex.getMessage());
        }
        return null;
    }

    private void stopSearch() {
        cancelFlag.set(true);
    }

    /** Stops any running search and makes its late callbacks harmless. */
    private void cancelRunning() {
        cancelFlag.set(true);
        searchGeneration++;
    }

    private void clearResults() {
        rootNode.removeAllChildren();
        treeModel.reload();
        lastResults.clear();
        totalHits = 0;
        filesWithHits = 0;
        truncatedFiles = 0;
        updateActionButtons();
    }

    private void addResult(FileResult r) {
        lastResults.add(r);
        totalHits += r.hits().size();
        filesWithHits++;
        if (r.truncated()) {
            truncatedFiles++;
        }
        DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(r);
        for (Hit h : r.hits()) {
            fileNode.add(new DefaultMutableTreeNode(new HitNode(r, h)));
        }
        treeModel.insertNodeInto(fileNode, rootNode, rootNode.getChildCount());
        if (rootNode.getChildCount() == 1) {
            tree.expandPath(new TreePath(rootNode));
        }
        if (totalHits <= AUTO_EXPAND_HIT_LIMIT) {
            tree.expandPath(new TreePath(fileNode.getPath()));
        }
        updateActionButtons();
    }

    private String finalStatus(boolean stopped, int searched, int total, int failed, long ms) {
        StringBuilder sb = new StringBuilder();
        if (totalHits == 0) {
            sb.append("No matches");
        } else {
            sb.append(totalHits).append(totalHits == 1 ? " match in " : " matches in ")
                    .append(filesWithHits).append(filesWithHits == 1 ? " file" : " files");
        }
        sb.append(" — searched ").append(searched).append(" of ").append(total).append(" file(s)");
        if (stopped) {
            sb.append(" (stopped)");
        }
        sb.append(" · ").append(ms).append(" ms");
        if (truncatedFiles > 0) {
            sb.append(" · capped at ").append(ContentSearcher.MAX_HITS_PER_FILE)
                    .append(" matches in ").append(truncatedFiles).append(" file(s)");
        }
        if (failed > 0) {
            sb.append(" · ").append(failed).append(" file(s) skipped (pattern too complex)");
        }
        return sb.toString();
    }

    private String describe(ContentSearcher.Options o, boolean enabledOnly, String filter) {
        List<String> flags = new ArrayList<>();
        if (o.matchCase()) flags.add("match case");
        if (o.wholeWord()) flags.add("whole word");
        if (o.regex()) flags.add("regex");
        if (o.ignoreWhitespace()) flags.add("ignore whitespace");
        flags.add(enabledOnly ? "enabled files" : "all loaded files");
        if (filter != null && !filter.isBlank()) flags.add("files: " + filter.trim());
        return "Search: " + o.query() + "  [" + String.join(", ", flags) + "]";
    }

    // ------------------------------------------------------------------
    // Result actions
    // ------------------------------------------------------------------

    private Object selectedUserObject() {
        TreePath p = tree.getSelectionPath();
        if (p == null) {
            return null;
        }
        Object last = p.getLastPathComponent();
        return last instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
    }

    private Set<String> matchedPaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (FileResult r : lastResults) {
            paths.add(r.path());
        }
        return paths;
    }

    private void enableMatched(boolean exclusive) {
        Set<String> matched = matchedPaths();
        if (matched.isEmpty()) {
            return;
        }
        if (exclusive) {
            repo.getDisabledClasses().addAll(new ArrayList<>(repo.getClassCodeMap().keySet()));
        }
        repo.getDisabledClasses().removeAll(matched);
        onEnabledChanged.run();
        statusLabel.setText((exclusive ? "Enabled only " : "Enabled ") + matched.size()
                + " matched file(s).");
    }

    private void copyEnableLine() {
        List<String> tokens = new ArrayList<>();
        for (FileResult r : lastResults) {
            String token = r.name();
            if (duplicateNames.contains(r.name().toLowerCase(Locale.ROOT))) {
                File parent = new File(r.path()).getParentFile();
                if (parent != null && !parent.getName().isEmpty()) {
                    token = parent.getName() + "/" + r.name();
                }
            }
            tokens.add(token);
        }
        new ClipboardService().write("@@Enable " + String.join(", ", tokens));
        ClipboardFlash.flash();
        statusLabel.setText("Copied @@Enable line for " + tokens.size() + " file(s).");
    }

    private void copyResults() {
        StringBuilder sb = new StringBuilder();
        sb.append(lastDescription).append("\n");
        sb.append(totalHits).append(" match(es) in ").append(filesWithHits).append(" file(s)\n\n");
        for (FileResult r : lastResults) {
            sb.append(r.path()).append("  (").append(r.hits().size()).append(")\n");
            for (Hit h : r.hits()) {
                sb.append("  L").append(h.line()).append(": ").append(h.preview()).append("\n");
            }
            sb.append("\n");
        }
        new ClipboardService().write(sb.toString());
        ClipboardFlash.flash();
        statusLabel.setText("Copied " + totalHits + " result(s) to the clipboard.");
    }

    private void openSelected() {
        Object o = selectedUserObject();
        String path;
        int line = 1;
        if (o instanceof HitNode hn) {
            path = hn.file().path();
            line = hn.hit().line();
        } else if (o instanceof FileResult fr) {
            path = fr.path();
        } else {
            return;
        }
        openInEditor(path, line);
    }

    private void openInEditor(String path, int line) {
        File f = repo.getClassFileMap().get(path);
        if (f == null) {
            f = new File(path);
        }
        if (!f.exists()) {
            JOptionPane.showMessageDialog(this, "File not found on disk:\n" + f.getAbsolutePath(),
                    "Open in Editor", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
            if (windows) {
                String npp = FileActionsDialog.findNotepadPlusPlus();
                if (npp != null) {
                    new ProcessBuilder(npp, "-n" + line, f.getAbsolutePath()).start();
                    return;
                }
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f);
                return;
            }
            JOptionPane.showMessageDialog(this, "No editor is available on this system.",
                    "Open in Editor", JOptionPane.WARNING_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not open the file:\n" + ex.getMessage(),
                    "Open in Editor", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void loadPrefs() {
        queryField.setText(settings.getRawProperty(PREF_QUERY, ""));
        matchCaseBox.setSelected(Boolean.parseBoolean(settings.getRawProperty(PREF_CASE, "false")));
        wholeWordBox.setSelected(Boolean.parseBoolean(settings.getRawProperty(PREF_WORD, "false")));
        regexBox.setSelected(Boolean.parseBoolean(settings.getRawProperty(PREF_REGEX, "false")));
        ignoreWsBox.setSelected(Boolean.parseBoolean(settings.getRawProperty(PREF_WS, "false")));
        fileFilterField.setText(settings.getRawProperty(PREF_FILES, ""));
        try {
            int idx = Integer.parseInt(settings.getRawProperty(PREF_SCOPE, "0"));
            scopeBox.setSelectedIndex(idx == 1 ? 1 : 0);
        } catch (NumberFormatException ignored) {
            scopeBox.setSelectedIndex(0);
        }
    }

    private void savePrefs() {
        settings.setRawProperty(PREF_QUERY, queryField.getText());
        settings.setRawProperty(PREF_CASE, Boolean.toString(matchCaseBox.isSelected()));
        settings.setRawProperty(PREF_WORD, Boolean.toString(wholeWordBox.isSelected()));
        settings.setRawProperty(PREF_REGEX, Boolean.toString(regexBox.isSelected()));
        settings.setRawProperty(PREF_WS, Boolean.toString(ignoreWsBox.isSelected()));
        settings.setRawProperty(PREF_FILES, fileFilterField.getText());
        settings.setRawProperty(PREF_SCOPE, Integer.toString(scopeBox.getSelectedIndex()));
        settings.saveProperties();
    }

    // ------------------------------------------------------------------
    // Snapshot helpers (repo maps can be touched by background workers)
    // ------------------------------------------------------------------

    private static <K, V> Map<K, V> safeCopy(Map<K, V> source) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return new LinkedHashMap<>(source);
            } catch (ConcurrentModificationException ignored) {
                // retry
            }
        }
        return new LinkedHashMap<>(source);
    }

    private static <T> Set<T> safeCopySet(Set<T> source) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return new HashSet<>(source);
            } catch (ConcurrentModificationException ignored) {
                // retry
            }
        }
        return new HashSet<>(source);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case ' ' -> sb.append("&nbsp;");
                case '\t' -> sb.append("&nbsp;&nbsp;&nbsp;&nbsp;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final class ResultRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                      boolean expanded, boolean leaf, int row,
                                                      boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            setIcon(null);
            String dim = sel ? "#dddddd" : "#808080";
            Object o = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;

            if (o instanceof FileResult fr) {
                File parent = new File(fr.path()).getParentFile();
                String dir = parent != null ? parent.getName() : "";
                setText("<html><b>" + esc(fr.name()) + "</b> <font color='" + dim + "'>("
                        + fr.hits().size() + (fr.truncated() ? "+" : "") + ")&nbsp;&nbsp;"
                        + esc(dir) + "</font></html>");
            } else if (o instanceof HitNode hn) {
                Hit h = hn.hit();
                String p = h.preview();
                int s = Math.max(0, Math.min(h.previewStart(), p.length()));
                int e = Math.max(s, Math.min(h.previewEnd(), p.length()));
                StringBuilder sb = new StringBuilder("<html><font face='Monospaced'>");
                sb.append("<font color='").append(dim).append("'>")
                        .append(esc(String.format("%5d", h.line()))).append("</font>&nbsp;&nbsp;");
                sb.append(esc(p.substring(0, s)));
                if (e > s) {
                    sb.append("<span style='background-color:#ffe680;color:#000000'>")
                            .append(esc(p.substring(s, e))).append("</span>");
                }
                sb.append(esc(p.substring(e)));
                sb.append("</font></html>");
                setText(sb.toString());
            }
            return this;
        }
    }
}