package wv.codeclip.ui;

import wv.codeclip.model.ClassRepository;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import wv.codeclip.config.AiInstructions;
import wv.codeclip.ui.ArchitectureBuilder;

public class ClassActions {

    private static final String NOTES_END_MARK = "\n// === END NOTES ===";

    private final JFrame parent;
    private final JTextArea classTextArea;
    private final JTextComponent notesComponent;
    private final JCheckBox showMissingFileMessages;
    private final ClassRepository repo;

    // Supplier so ClassActions always reads the current value from CodeClipFrame
    private final Supplier<Boolean> includeInstructions;

    public ClassActions(
            JFrame parent,
            JTextArea classTextArea,
            JTextComponent notesComponent,
            JCheckBox showMissingFileMessages,
            ClassRepository repo,
            Supplier<Boolean> includeInstructions
    ) {
        this.parent = parent;
        this.classTextArea = classTextArea;
        this.notesComponent = notesComponent;
        this.showMissingFileMessages = showMissingFileMessages;
        this.repo = repo;
        this.includeInstructions = includeInstructions;
    }

    public void resetAll(JPanel classPanel) {
        repo.clear();
        classPanel.removeAll();
        classTextArea.setText("");
        classPanel.revalidate();
        classPanel.repaint();
    }

    /**
     * Copies code + (optionally) instructions + notes to clipboard.
     * Order: code → instructions (if ticked) → notes
     */
    public void copyAll(Runnable clearLogsCallback, String cleanNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append(classTextArea.getText());

        if (Boolean.TRUE.equals(includeInstructions.get())) {
            sb.append("\n\n").append(AiInstructions.TEXT);
        }

        sb.append("\n\n// === Notes ===\n")
          .append(cleanNotes)
          .append(NOTES_END_MARK);

        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(sb.toString()), null);

        clearLogsCallback.run();
    }

    public void copyCodeOnly() {
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(classTextArea.getText()), null);
    }

    public void copyArchitecture() {
        Object[] options = {"Copy Enabled", "Copy Added", "Copy All", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                parent,
                "Choose which classes to include in the architecture:",
                "Copy Architecture",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (choice < 0 || choice == 3) return;

        ArchitectureBuilder.Mode mode = switch (choice) {
            case 0 -> ArchitectureBuilder.Mode.ENABLED_ONLY;
            case 1 -> ArchitectureBuilder.Mode.ADDED_ONLY;
            default -> ArchitectureBuilder.Mode.ALL;
        };

        String tree = new ArchitectureBuilder(repo).build(mode, repo.getDisabledClasses());
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(tree), null);
    }

    public void updateAll(Runnable refreshCallback, Consumer<String> removePanelCallback) {
        SwingWorker<List<String>, Void> worker = new SwingWorker<>() {

            @Override
            protected List<String> doInBackground() {
                List<String> missingPaths = new ArrayList<>();

                for (Map.Entry<String, File> entry : new ArrayList<>(repo.getClassFileMap().entrySet())) {
                    String path = entry.getKey();
                    File file = entry.getValue();
                    try {
                        String updated = Files.readString(file.toPath());
                        repo.getClassCodeMap().put(path, updated);
                    } catch (IOException ex) {
                        missingPaths.add(path);
                    }
                }

                return missingPaths;
            }

            @Override
            protected void done() {
                try {
                    List<String> missingPaths = get();

                    for (String path : missingPaths) {
                        File file = repo.getClassFileMap().get(path);
                        String fileName = (file != null) ? file.getName() : path;

                        if (showMissingFileMessages.isSelected()) {
                            Object[] options = {"Remove from Program", "Keep", "Cancel"};
                            int choice = JOptionPane.showOptionDialog(
                                    parent,
                                    "File not found on disk:\n" + path + "\n\n" +
                                            "Do you want to remove " + fileName + " from the program?",
                                    "Missing File",
                                    JOptionPane.DEFAULT_OPTION,
                                    JOptionPane.WARNING_MESSAGE,
                                    null,
                                    options,
                                    options[1]
                            );

                            if (choice == 0) {
                                repo.getClassCodeMap().remove(path);
                                repo.getClassFileMap().remove(path);
                                repo.getDisabledClasses().remove(path);
                                removePanelCallback.accept(path);
                            }
                        }
                    }

                    refreshCallback.run();

                } catch (Exception ignored) {}
            }
        };
        worker.execute();
    }
}