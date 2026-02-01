// ===== CodeClipFrame.java =====
package wv.codeclip;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;

public class CodeClipFrame extends JFrame {

    private final JTextArea classTextArea = new JTextArea(8, 50);
    private final JTextArea notesTextArea = new JTextArea();
    private String tempLogs = ""; // temporary logs (not saved as notes)
    private final JPanel classPanel = new JPanel();

    private final JCheckBox showMissingFileMessages =
            new JCheckBox("Show missing file messages", true);
    private final JCheckBox alwaysOnTopCheck =
            new JCheckBox("Always on Top", true);

    private final JLabel enabledCountLabel = new JLabel("Enabled Classes: 0");
    private final JLabel charCountLabel = new JLabel("Code Characters: 0");

    private final ClassRepository repo = new ClassRepository();
    private final ClassActions actions;

    private final SettingsManager settings = new SettingsManager();

    private static final Color ENABLED_COLOR  = new Color(240, 240, 240);
    private static final Color DISABLED_COLOR = new Color(210, 210, 210);

    public CodeClipFrame() {
        actions = new ClassActions(
                this,
                classTextArea,
                notesTextArea,
                showMissingFileMessages,
                repo
        );

        setTitle("Code Clip");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        Rectangle bounds = settings.loadFrameBounds();
        setBounds(bounds);

        setLayout(new BorderLayout());

        buildUI();
        installDnD();

        setAlwaysOnTop(alwaysOnTopCheck.isSelected());

        for (String path : settings.loadClassPaths()) {
            File f = new File(path);
            if (f.exists()) addClass(f);
        }

        notesTextArea.setText(settings.loadNotes());

        notesTextArea.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                clearTempLogs();
            }
        });

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                clearTempLogs(); // FIX: prevent saving temp logs
                settings.saveFrameBounds(getBounds());
                settings.saveNotes(notesTextArea.getText());
                settings.saveClassPaths(
                        repo.getClassCodeMap().keySet().toArray(new String[0])
                );
                settings.saveProperties();
            }
        });

        setVisible(true);
    }

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

        notesTextArea.setLineWrap(true);
        JScrollPane notesScroll = new JScrollPane(notesTextArea);

        classPanel.setLayout(new BoxLayout(classPanel, BoxLayout.Y_AXIS));
        JScrollPane classScroll = new JScrollPane(classPanel);
        classScroll.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );

        JSplitPane splitPane =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, notesScroll, classScroll);
        splitPane.setResizeWeight(0.7);
        splitPane.setOneTouchExpandable(true);
        splitPane.setContinuousLayout(true);

        add(splitPane, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 4, 5, 5));

        JButton reset = new JButton("Reset");
        JButton update = new JButton("Update All");
        JButton copy = new JButton("Copy All");
        JButton copyCode = new JButton("Copy Code Only");
        JButton enableAll = new JButton("Enable All");
        JButton disableAll = new JButton("Disable All");
        JButton pasteClass = new JButton("Paste Class");

        reset.addActionListener(e -> actions.resetAll(classPanel));
        update.addActionListener(e -> actions.updateAll(this::refreshText));
        copy.addActionListener(e -> {
            clearTempLogs();
            actions.copyAll();
        });
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
                    this::appendTempLog
            ).handlePasteFromClipboard();
            refreshPanels();
        });

        buttons.add(reset);
        buttons.add(update);
        buttons.add(copy);
        buttons.add(copyCode);
        buttons.add(enableAll);
        buttons.add(disableAll);
        buttons.add(showMissingFileMessages);
        buttons.add(alwaysOnTopCheck);
        buttons.add(pasteClass);

        add(buttons, BorderLayout.SOUTH);
    }

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

    // ✅ RESTORED METHOD
    private void addClassPanel(String path, String name) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setOpaque(true);
        panel.setBackground(ENABLED_COLOR);

        JLabel label = new JLabel(name);

        JButton toggle = new JButton("Disable");
        JButton copy = new JButton("Copy");
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
                String text =
                        "// ===== " + name + " =====\n" + code + "\n";
                Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .setContents(
                                new java.awt.datatransfer.StringSelection(text),
                                null
                        );
            }
        });

        delete.addActionListener(e -> {
            repo.getClassCodeMap().remove(path);
            repo.getClassFileMap().remove(path);
            repo.getDisabledClasses().remove(path);

            classPanel.remove(panel);
            classPanel.revalidate();
            classPanel.repaint();

            refreshText();
        });

        panel.add(label);
        panel.add(toggle);
        panel.add(copy);
        panel.add(delete);

        classPanel.add(panel);
        classPanel.revalidate();
        classPanel.repaint();
    }

    // --- Temporary log helpers ---
    public void appendTempLog(String message) {
        tempLogs += message + "\n";
        notesTextArea.setText(tempLogs + notesTextArea.getText());
    }

    private void clearTempLogs() {
        if (!tempLogs.isEmpty()) {
            String currentText = notesTextArea.getText();
            if (currentText.startsWith(tempLogs)) {
                notesTextArea.setText(
                        currentText.substring(tempLogs.length())
                );
            }
            tempLogs = "";
        }
    }

    private void refreshText() {
        StringBuilder sb = new StringBuilder();
        repo.getClassCodeMap().forEach((path, code) -> {
            if (!repo.getDisabledClasses().contains(path)) {
                sb.append("// ===== ")
                        .append(new File(path).getName())
                        .append(" =====\n")
                        .append(code)
                        .append("\n\n");
            }
        });
        classTextArea.setText(sb.toString());
        refreshStats();
    }

    private void refreshStats() {
        long enabledCount =
                repo.getClassCodeMap().size() - repo.getDisabledClasses().size();
        int charCount = classTextArea.getText().length();
        enabledCountLabel.setText("Enabled Classes: " + enabledCount);
        charCountLabel.setText("Code Characters: " + charCount);
    }

    private void refreshPanels() {
        for (Component comp : classPanel.getComponents()) {
            if (comp instanceof JPanel panel) {
                JButton toggleButton = null;
                for (Component c : panel.getComponents()) {
                    if (c instanceof JButton b &&
                        (b.getText().equals("Enable") ||
                         b.getText().equals("Disable"))) {
                        toggleButton = b;
                        break;
                    }
                }
                if (toggleButton != null) {
                    String name = ((JLabel) panel.getComponent(0)).getText();
                    for (Map.Entry<String, File> e : repo.getClassFileMap().entrySet()) {
                        if (e.getValue().getName().equals(name)) {
                            boolean disabled =
                                    repo.getDisabledClasses().contains(e.getKey());
                            toggleButton.setText(disabled ? "Enable" : "Disable");
                            panel.setBackground(
                                    disabled ? DISABLED_COLOR : ENABLED_COLOR
                            );
                            break;
                        }
                    }
                }
            }
        }
        classPanel.revalidate();
        classPanel.repaint();
    }
}
