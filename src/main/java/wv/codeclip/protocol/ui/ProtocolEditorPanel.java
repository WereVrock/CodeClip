package wv.codeclip.protocol.ui;

import wv.codeclip.protocol.library.ProtocolLibrary;
import wv.codeclip.protocol.engine.ProtocolEngine;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import wv.codeclip.protocol.model.ProtocolFile;
import wv.codeclip.protocol.model.ValidationError;
import wv.codeclip.protocol.model.ValidationResult;

public final class ProtocolEditorPanel extends JPanel {

    private final ProtocolLibrary library;
    private final ProtocolEngine engine = new ProtocolEngine();

    private final JTextArea textArea = new JTextArea();
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel titleLabel = new JLabel("No file selected");
    private String currentFileName = null;
    private boolean currentFileWasLocked = false;

    public ProtocolEditorPanel(ProtocolLibrary library) {
        this.library = library;
        setLayout(new BorderLayout());

        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        textArea.setEditable(false);

        JPanel topPanel = new JPanel(new BorderLayout());
        titleLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        topPanel.add(titleLabel, BorderLayout.WEST);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton copyFileBtn = new JButton("Copy File to Clipboard");
        JButton openExternalBtn = new JButton("Open in Notepad++");
        JButton saveBtn = new JButton("Save Hand Edit");
        JButton discardBtn = new JButton("Discard Changes");

        copyFileBtn.addActionListener(e -> copyCurrentFile());
        openExternalBtn.addActionListener(e -> openInExternalEditor());
        saveBtn.addActionListener(e -> saveHandEdit());
        discardBtn.addActionListener(e -> reloadCurrentFile());

        buttonPanel.add(copyFileBtn);
        buttonPanel.add(openExternalBtn);
        buttonPanel.add(saveBtn);
        buttonPanel.add(discardBtn);
        topPanel.add(buttonPanel, BorderLayout.EAST);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(textArea), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        showEmpty();
    }

    public void showFile(String fileName) {
        if (fileName == null) { showEmpty(); return; }
        this.currentFileName = fileName;
        reloadCurrentFile();
    }

    public void showEmpty() {
        this.currentFileName = null;
        textArea.setText("");
        textArea.setEditable(false);
        titleLabel.setText("No file selected");
        statusLabel.setText(" ");
    }

    private void reloadCurrentFile() {
        if (currentFileName == null) { showEmpty(); return; }

        StringBuilder err = new StringBuilder();
        ProtocolFile file = library.loadSafely(currentFileName, err);

        if (err.length() > 0) {
            textArea.setText("");
            textArea.setEditable(false);
            titleLabel.setText(currentFileName + "  [COULD NOT LOAD]");
            statusLabel.setText("Error loading file: " + err);
            return;
        }

        currentFileWasLocked = file.isLocked();
        textArea.setText(file.render());
        textArea.setEditable(true);
        titleLabel.setText(currentFileName + (currentFileWasLocked ? "  [LOCKED — AI edits blocked, hand edits allowed]" : ""));
        statusLabel.setText(" ");
    }

    private void copyCurrentFile() {
        if (currentFileName == null) return;
        ClipboardHelper.copyToClipboard(textArea.getText());
        statusLabel.setText("Copied '" + currentFileName + "' to clipboard.");
    }

    private void openInExternalEditor() {
        if (currentFileName == null) return;
        Path path = library.getProtocolsDir().resolve(currentFileName);
        try {
            ProtocolExternalEditorLauncher.open(path);
            statusLabel.setText("Opened in external editor. Use 'Discard Changes' to reload after editing externally.");
        } catch (Exception e) {
            statusLabel.setText("Could not open external editor: " + e.getMessage());
        }
    }

    private void saveHandEdit() {
        if (currentFileName == null) return;
        String text = textArea.getText();

        ValidationResult result = engine.validateFileContent(currentFileName, text);
        if (!result.isValid()) {
            StringBuilder sb = new StringBuilder("Validation failed:\n");
            for (ValidationError err : result.getErrors()) {
                sb.append("• ").append(err.getMessage()).append("\n");
            }
            JOptionPane.showMessageDialog(this, sb.toString(), "Cannot Save", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // The lock state is only ever changed through the dedicated Toggle Lock
        // button, never by editing text. If the file was locked when opened,
        // the saved copy stays locked even if the "!locked" line was edited
        // or removed by hand — this prevents accidentally unlocking a file
        // just by editing its text.
        boolean textClaimsLocked = text.stripLeading().startsWith("!locked");
        if (currentFileWasLocked && !textClaimsLocked) {
            int choice = JOptionPane.showConfirmDialog(this,
                "This file was locked, but the '!locked' marker is missing from your edit.\n" +
                "Saving will keep it locked regardless. Continue?",
                "Lock Preserved", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
        }

        try {
            String finalText = currentFileWasLocked && !textClaimsLocked
                ? "!locked\n" + text
                : text;
            Files.writeString(library.getProtocolsDir().resolve(currentFileName), finalText);
            statusLabel.setText("Saved '" + currentFileName + "'.");
            reloadCurrentFile();
        } catch (Exception e) {
            statusLabel.setText("Save failed: " + e.getMessage());
        }
    }

    public String getCurrentFileName() {
        return currentFileName;
    }
}