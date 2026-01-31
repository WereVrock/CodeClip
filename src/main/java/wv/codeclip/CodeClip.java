package wv.codeclip;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

public class CodeClip extends JFrame {

    private JTextArea classTextArea;
    private JTextArea notesTextArea;
    private JPanel classPanel;
    private JButton resetButton, copyButton, updateButton, copyCodeOnlyButton;
    private JCheckBox showMissingFileMessages, alwaysOnTopCheck;

    private Map<String, String> classCodeMap = new LinkedHashMap<>();
    private Map<String, File> classFileMap = new HashMap<>();
    private Set<String> disabledClasses = new HashSet<>();

    private static final String NOTES_END_MARK = "\n// === END NOTES ===";

    public CodeClip() {
        setTitle("Code Clip");
        setSize(950, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Top panel
        classTextArea = new JTextArea(8, 50);
        classTextArea.setEditable(false);
        classTextArea.setLineWrap(true);
        classTextArea.setWrapStyleWord(true);
        JScrollPane classScroll = new JScrollPane(classTextArea);
        add(classScroll, BorderLayout.NORTH);

        // Notes panel
        notesTextArea = new JTextArea();
        notesTextArea.setLineWrap(true);
        notesTextArea.setWrapStyleWord(true);
        JScrollPane notesScroll = new JScrollPane(notesTextArea);
        add(notesScroll, BorderLayout.CENTER);

        // Class button panel
        classPanel = new JPanel();
        classPanel.setLayout(new BoxLayout(classPanel, BoxLayout.Y_AXIS));
        JScrollPane classButtonScroll = new JScrollPane(classPanel);
        classButtonScroll.setPreferredSize(new Dimension(280, 0));
        add(classButtonScroll, BorderLayout.EAST);

        // Bottom buttons
        JPanel buttonPanel = new JPanel();
        resetButton = new JButton("Reset");
        copyButton = new JButton("Copy All");
        copyCodeOnlyButton = new JButton("Copy Code Only");
        updateButton = new JButton("Update All");
        showMissingFileMessages = new JCheckBox("Show missing file messages", true);
        alwaysOnTopCheck = new JCheckBox("Always on Top", false);

        buttonPanel.add(resetButton);
        buttonPanel.add(updateButton);
        buttonPanel.add(copyButton);
        buttonPanel.add(copyCodeOnlyButton);
        buttonPanel.add(showMissingFileMessages);
        buttonPanel.add(alwaysOnTopCheck);
        add(buttonPanel, BorderLayout.SOUTH);

        // Actions
        resetButton.addActionListener(e -> resetAll());
        copyButton.addActionListener(e -> copyAllToClipboard());
        copyCodeOnlyButton.addActionListener(e -> copyCodeOnlyToClipboard());
        updateButton.addActionListener(e -> updateAllClasses());
        alwaysOnTopCheck.addActionListener(e -> setAlwaysOnTop(alwaysOnTopCheck.isSelected()));

        // 🔥 Drag & Drop everywhere
        installGlobalFileDrop(this);

        setVisible(true);
    }

    // ================= Reset =================
    // Does NOT clear notes anymore
    private void resetAll() {
        classCodeMap.clear();
        classFileMap.clear();
        disabledClasses.clear();
        classPanel.removeAll();
        classTextArea.setText("");

        classPanel.revalidate();
        classPanel.repaint();
    }

    // ================= Copy =================
    private void copyAllToClipboard() {
        String combined = classTextArea.getText()
                + "\n\n// === Notes ===\n"
                + notesTextArea.getText()
                + NOTES_END_MARK;

        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(combined), null);
    }

    private void copyCodeOnlyToClipboard() {
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(classTextArea.getText()), null);
    }

    // ================= Update =================
    private void updateAllClasses() {
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (Map.Entry<String, File> entry : classFileMap.entrySet()) {
                    try {
                        String updatedContent = Files.readString(entry.getValue().toPath());
                        classCodeMap.put(entry.getKey(), updatedContent);
                    } catch (IOException ex) {
                        if (showMissingFileMessages.isSelected()) {
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                    CodeClip.this,
                                    "File missing: " + entry.getValue().getAbsolutePath(),
                                    "Update All Warning",
                                    JOptionPane.WARNING_MESSAGE
                            ));
                        }
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                refreshClassTextArea();
            }
        };
        worker.execute();
    }

    // ================= Add Class =================
    private void addClass(File file) {
        String fullPath = file.getAbsolutePath();
        if (classCodeMap.containsKey(fullPath)) return;

        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return Files.readString(file.toPath());
            }

            @Override
            protected void done() {
                try {
                    String content = get();
                    classCodeMap.put(fullPath, content);
                    classFileMap.put(fullPath, file);
                    addClassButtonPanel(fullPath, file.getName());
                    refreshClassTextArea();
                } catch (Exception e) {
                    if (showMissingFileMessages.isSelected()) {
                        JOptionPane.showMessageDialog(
                                CodeClip.this,
                                "Failed to read file: " + file.getAbsolutePath(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                        );
                    }
                }
            }
        };
        worker.execute();
    }

    // ================= Class Button UI =================
    private void addClassButtonPanel(String fullPath, String displayName) {

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setMaximumSize(new Dimension(260, 60));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);

        JLabel label = new JLabel(displayName);
        JButton toggleButton = new JButton("Disable");
        JButton deleteButton = new JButton("Delete");

        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        panel.add(label, c);

        c.gridwidth = 1;
        c.gridx = 0; c.gridy = 1;
        panel.add(toggleButton, c);

        c.gridx = 1;
        panel.add(deleteButton, c);

        classPanel.add(panel);
        classPanel.revalidate();
        classPanel.repaint();

        toggleButton.addActionListener(e -> {
            if (disabledClasses.contains(fullPath)) {
                disabledClasses.remove(fullPath);
                toggleButton.setText("Disable");
            } else {
                disabledClasses.add(fullPath);
                toggleButton.setText("Enable");
            }
            refreshClassTextArea();
        });

        deleteButton.addActionListener(e -> {
            classCodeMap.remove(fullPath);
            classFileMap.remove(fullPath);
            disabledClasses.remove(fullPath);

            classPanel.remove(panel);
            classPanel.revalidate();
            classPanel.repaint();
            refreshClassTextArea();
        });
    }

    // ================= Refresh Text =================
    private void refreshClassTextArea() {
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, String> entry : classCodeMap.entrySet()) {
            if (!disabledClasses.contains(entry.getKey())) {
                sb.append("// ===== ")
                        .append(new File(entry.getKey()).getName())
                        .append(" =====\n");

                sb.append(entry.getValue()).append("\n\n");
            }
        }

        classTextArea.setText(sb.toString());
    }

    // ================= GLOBAL DRAG & DROP =================
    private void installGlobalFileDrop(Component component) {

        new DropTarget(component, new FileDropListener());

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                installGlobalFileDrop(child);
            }
        }
    }

    private class FileDropListener extends DropTargetAdapter {

        @Override
        public void drop(DropTargetDropEvent dtde) {
            try {
                dtde.acceptDrop(DnDConstants.ACTION_COPY);

                Transferable t = dtde.getTransferable();
                Object data = t.getTransferData(DataFlavor.javaFileListFlavor);

                if (data instanceof List<?> list) {
                    for (Object obj : list) {
                        if (obj instanceof File file && file.getName().endsWith(".java")) {
                            addClass(file);
                        }
                    }
                }

            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(CodeClip::new);
    }
}
