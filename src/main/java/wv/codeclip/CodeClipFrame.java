package wv.codeclip;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;

public class CodeClipFrame extends JFrame {

    private final JTextArea classTextArea = new JTextArea(8, 50);
    private final JTextArea notesTextArea = new JTextArea();
    private final JPanel classPanel = new JPanel();

    private final JCheckBox showMissingFileMessages =
            new JCheckBox("Show missing file messages", true);
    private final JCheckBox alwaysOnTopCheck =
            new JCheckBox("Always on Top", false);

    private final ClassRepository repo = new ClassRepository();
    private final ClassActions actions;

    public CodeClipFrame() {
        setTitle("Code Clip");
        setSize(950, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        actions = new ClassActions(
                this,
                classTextArea,
                notesTextArea,
                showMissingFileMessages,
                repo
        );

        buildUI();
        installDnD();

        setVisible(true);
    }

    private void buildUI() {
        classTextArea.setEditable(false);
        classTextArea.setLineWrap(true);
        add(new JScrollPane(classTextArea), BorderLayout.NORTH);

        notesTextArea.setLineWrap(true);
        add(new JScrollPane(notesTextArea), BorderLayout.CENTER);

        classPanel.setLayout(new BoxLayout(classPanel, BoxLayout.Y_AXIS));
        JScrollPane right = new JScrollPane(classPanel);
        right.setPreferredSize(new Dimension(280, 0));
        add(right, BorderLayout.EAST);

        JPanel buttons = new JPanel();

        JButton reset = new JButton("Reset");
        JButton update = new JButton("Update All");
        JButton copy = new JButton("Copy All");
        JButton copyCode = new JButton("Copy Code Only");

        reset.addActionListener(e -> actions.resetAll(classPanel));
        update.addActionListener(e -> actions.updateAll(this::refreshText));
        copy.addActionListener(e -> actions.copyAll());
        copyCode.addActionListener(e -> actions.copyCodeOnly());
        alwaysOnTopCheck.addActionListener(e ->
                setAlwaysOnTop(alwaysOnTopCheck.isSelected()));

        buttons.add(reset);
        buttons.add(update);
        buttons.add(copy);
        buttons.add(copyCode);
        buttons.add(showMissingFileMessages);
        buttons.add(alwaysOnTopCheck);

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

   private void addClassPanel(String path, String name) {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    JButton toggle = new JButton("Disable");
    JButton delete = new JButton("Delete");

    toggle.addActionListener(e -> {
        if (repo.getDisabledClasses().remove(path)) {
            toggle.setText("Disable");
        } else {
            repo.getDisabledClasses().add(path);
            toggle.setText("Enable");
        }
        refreshText();
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

    panel.add(new JLabel(name));
    panel.add(toggle);
    panel.add(delete);

    classPanel.add(panel);
    classPanel.revalidate();   // 🔴 REQUIRED
    classPanel.repaint();      // 🔴 REQUIRED
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
    }
}
