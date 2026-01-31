package wv.codeclip;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

public class ClassActions {

    private static final String NOTES_END_MARK = "\n// === END NOTES ===";

    private final JFrame parent;
    private final JTextArea classTextArea;
    private final JTextArea notesTextArea;
    private final JCheckBox showMissingFileMessages;
    private final ClassRepository repo;

    public ClassActions(
            JFrame parent,
            JTextArea classTextArea,
            JTextArea notesTextArea,
            JCheckBox showMissingFileMessages,
            ClassRepository repo
    ) {
        this.parent = parent;
        this.classTextArea = classTextArea;
        this.notesTextArea = notesTextArea;
        this.showMissingFileMessages = showMissingFileMessages;
        this.repo = repo;
    }

    public void resetAll(JPanel classPanel) {
        repo.clear();
        classPanel.removeAll();
        classTextArea.setText("");
        classPanel.revalidate();
        classPanel.repaint();
    }

    public void copyAll() {
        String combined = classTextArea.getText()
                + "\n\n// === Notes ===\n"
                + notesTextArea.getText()
                + NOTES_END_MARK;

        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(combined), null);
    }

    public void copyCodeOnly() {
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(classTextArea.getText()), null);
    }

    public void updateAll(Runnable refreshCallback) {
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
                    try {
                        String updated = Files.readString(entry.getValue().toPath());
                        repo.getClassCodeMap().put(entry.getKey(), updated);
                    } catch (IOException ex) {
                        if (showMissingFileMessages.isSelected()) {
                            SwingUtilities.invokeLater(() ->
                                    JOptionPane.showMessageDialog(
                                            parent,
                                            "File missing: " + entry.getValue().getAbsolutePath(),
                                            "Update Warning",
                                            JOptionPane.WARNING_MESSAGE
                                    ));
                        }
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                refreshCallback.run();
            }
        };
        worker.execute();
    }
}
