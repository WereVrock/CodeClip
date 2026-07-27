package wv.codeclip.protocol.ui;

import wv.codeclip.protocol.library.ProtocolLibrary;
import wv.codeclip.protocol.engine.ProtocolEngine;
import wv.codeclip.protocol.editor.StructuredProtocolEditorPanel;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import static javax.swing.text.SimpleAttributeSet.EMPTY;
import wv.codeclip.patch.PatchApplier.PatchResult;
import wv.codeclip.protocol.model.Command;
import static wv.codeclip.protocol.model.ProtocolPatchResult.Status.APPLIED;
import static wv.codeclip.protocol.model.ProtocolPatchResult.Status.CANCELLED;
import static wv.codeclip.protocol.model.ProtocolPatchResult.Status.FILE_VALIDATION_FAILED;
import static wv.codeclip.protocol.model.ProtocolPatchResult.Status.VALIDATION_FAILED;
import wv.codeclip.protocol.model.ProtocolFile;
import wv.codeclip.protocol.model.ValidationError;

public final class ProtocolManagerDialog extends JDialog {

    private final ProtocolLibrary library;
    private final ProtocolEngine engine = new ProtocolEngine();

    private final ProtocolListPanel listPanel;
    private final StructuredProtocolEditorPanel editorPanel;
    private final LockIndicator masterLockIndicator = new LockIndicator();
    private final JLabel statusBar = new JLabel(" ");

    private final wv.codeclip.protocol.engine.ProtocolUndoManager protocolUndoManager;
    private final java.util.function.Consumer<String> protocolLogCallback;
    private JButton protocolUndoBtn;
    private JButton protocolRedoBtn;

    public ProtocolManagerDialog(Frame owner, Path baseDir,
            wv.codeclip.protocol.engine.ProtocolUndoManager protocolUndoManager,
            java.util.function.Consumer<String> protocolLogCallback) {
        // Modeless: does not block the owner window, and does not prevent
        // minimizing the app while this dialog is open.
        super(owner, "Protocol Manager", false);
        this.library = new ProtocolLibrary(baseDir);
        this.protocolUndoManager = protocolUndoManager;
        this.protocolLogCallback = protocolLogCallback != null ? protocolLogCallback : (msg -> {});

        this.listPanel = new ProtocolListPanel(library);
        this.editorPanel = new StructuredProtocolEditorPanel(library);

        listPanel.setOnFileSelected(editorPanel::showFile);
        editorPanel.setOnSavedCallback(listPanel::refresh);
        editorPanel.setOnRequestNextFile(listPanel::selectNextFile);

        setLayout(new BorderLayout());
        add(buildTopBar(), BorderLayout.NORTH);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, editorPanel);
        mainSplit.setDividerLocation(260);
        add(mainSplit, BorderLayout.CENTER);

        add(buildBottomBar(), BorderLayout.SOUTH);

        setSize(1150, 700);
        setLocationRelativeTo(owner);
    }

    private JPanel buildTopBar() {
        JPanel topBar = new JPanel(new BorderLayout());

        JPanel leftTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel folderLabel = new JLabel("Protocols folder: " + shortenPath(library.getProtocolsDir().toAbsolutePath().toString()));
        folderLabel.setToolTipText(library.getProtocolsDir().toAbsolutePath().toString());
        leftTop.add(folderLabel);
        topBar.add(leftTop, BorderLayout.WEST);

        JPanel rightTop = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        protocolUndoBtn = new JButton("↩ Undo");
        protocolRedoBtn = new JButton("↪ Redo");
        protocolUndoBtn.setToolTipText("Undo the last protocol file change");
        protocolRedoBtn.setToolTipText("Redo the last undone protocol file change");
        protocolUndoBtn.addActionListener(e -> {
            wv.codeclip.protocol.engine.ProtocolUndoManager.Entry entry = protocolUndoManager.undo(library);
            if (entry != null) {
                protocolLogCallback.accept("Protocol Undo: " + entry.title());
                statusBar.setText("Protocol undo: " + entry.title());
                listPanel.refresh();
                editorPanel.reload();
            }
            syncProtocolUndoRedoButtons();
        });
        protocolRedoBtn.addActionListener(e -> {
            wv.codeclip.protocol.engine.ProtocolUndoManager.Entry entry = protocolUndoManager.redo(library);
            if (entry != null) {
                protocolLogCallback.accept("Protocol Redo: " + entry.title());
                statusBar.setText("Protocol redo: " + entry.title());
                listPanel.refresh();
                editorPanel.reload();
            }
            syncProtocolUndoRedoButtons();
        });
        rightTop.add(protocolUndoBtn);
        rightTop.add(protocolRedoBtn);

        masterLockIndicator.setLocked(library.isMasterLocked());
        masterLockIndicator.setOnToggle(locked -> {
            library.setMasterLocked(locked);
            listPanel.refresh();
        });
        rightTop.add(new JLabel("Master Lock:"));
        rightTop.add(masterLockIndicator);
        topBar.add(rightTop, BorderLayout.EAST);

        syncProtocolUndoRedoButtons();

        return topBar;
    }

    private void syncProtocolUndoRedoButtons() {
        if (protocolUndoBtn == null || protocolRedoBtn == null || protocolUndoManager == null) return;
        protocolUndoBtn.setEnabled(protocolUndoManager.canUndo());
        protocolRedoBtn.setEnabled(protocolUndoManager.canRedo());
    }

    private String shortenPath(String path) {
        return path.length() > 50 ? "..." + path.substring(path.length() - 47) : path;
    }

    private JPanel buildBottomBar() {
        JPanel bottomBar = new JPanel(new BorderLayout());

        JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton pasteAiBtn = iconTextButton("\uD83D\uDCE5", "Paste AI Output & Apply");
        JButton copyEnabledBtn = iconTextButton("\uD83D\uDCCB", "Copy Enabled to Clipboard");
        JButton copyInstructionsBtn = iconTextButton("\u2139", "Copy AI Format Instructions");

        pasteAiBtn.addActionListener(e -> handlePasteAiOutput());
        copyEnabledBtn.addActionListener(e -> handleCopyEnabled());
        copyInstructionsBtn.addActionListener(e -> handleCopyInstructions());

        actionButtons.add(pasteAiBtn);
        actionButtons.add(copyEnabledBtn);
        actionButtons.add(copyInstructionsBtn);

        JPanel closePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = iconTextButton("\u2715", "Close");
        closeBtn.addActionListener(e -> attemptClose());
        closePanel.add(closeBtn);

        bottomBar.add(actionButtons, BorderLayout.WEST);
        bottomBar.add(closePanel, BorderLayout.EAST);

        JPanel wrap = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        wrap.add(bottomBar, BorderLayout.NORTH);
        wrap.add(statusBar, BorderLayout.SOUTH);
        return wrap;
    }

    private void attemptClose() {
        if (editorPanel.hasUnsavedChanges()) {
            int choice = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes in the editor. Close anyway?",
                "Unsaved Changes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }
        dispose();
    }

    private void handleCopyEnabled() {
        Set<String> enabled = listPanel.getEnabledFiles();
        if (enabled.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No protocols are enabled. Enable at least one to copy.",
                "Nothing to Copy", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (String fileName : library.listFileNames()) {
            if (!enabled.contains(fileName)) continue;
            StringBuilder err = new StringBuilder();
            ProtocolFile file = library.loadSafely(fileName, err);
            sb.append("=== ").append(fileName).append(" ===\n");
            if (err.length() > 0) {
                sb.append("[could not load: ").append(err).append("]\n");
            } else {
                sb.append(file.render());
            }
            sb.append("\n");
        }
        ProtocolClipboardHelper.copyToClipboard(sb.toString());
        statusBar.setText("Copied " + enabled.size() + " enabled protocol file(s) to clipboard.");
    }

    private void handleCopyInstructions() {
        String instructions = buildAiFormatInstructions();
        ProtocolClipboardHelper.copyToClipboard(instructions);
        statusBar.setText("Copied AI format instructions to clipboard.");
    }

    private void handlePasteAiOutput() {
        String clipboardText = ProtocolClipboardHelper.readFromClipboard();
        if (clipboardText == null || clipboardText.isBlank()) {
            JOptionPane.showMessageDialog(this, "Clipboard is empty or unreadable.",
                "Nothing to paste", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<Command> commands;
        try {
            engine.recordPatch(clipboardText);
            commands = engine.getRecordedCommands();
        } catch (wv.codeclip.protocol.parser.AiOutputParser.PatchParseException e) {
            JOptionPane.showMessageDialog(this, "Could not parse AI output:\n" + e.getMessage(),
                "Parse Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (commands.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No @@protocol blocks found in clipboard content.",
                "Nothing to apply", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Map<String, List<Command>> byFile = new LinkedHashMap<>();
        for (Command c : commands) {
            byFile.computeIfAbsent(c.getTargetFile(), k -> new java.util.ArrayList<>()).add(c);
        }

        Map<String, ProtocolFile> originals = new LinkedHashMap<>();
        List<String> unreadable = new java.util.ArrayList<>();
        for (String fileName : byFile.keySet()) {
            StringBuilder err = new StringBuilder();
            ProtocolFile loaded = library.loadSafely(fileName, err);
            if (err.length() > 0 && library.exists(fileName)) {
                unreadable.add(fileName + ": " + err);
            }
            originals.put(fileName, loaded);
        }

        if (!unreadable.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "The following files could not be read and were skipped:\n" + String.join("\n", unreadable),
                "Some Files Unreadable", JOptionPane.WARNING_MESSAGE);
        }

        DiffAcceptDialog reviewDialog = new DiffAcceptDialog(this, originals, byFile);
        reviewDialog.setVisible(true);

        if (!reviewDialog.wasConfirmed()) {
            statusBar.setText("Patch review cancelled; nothing applied.");
            return;
        }

        Map<String, Set<String>> acceptedByFile = reviewDialog.getAcceptedKeysByFile();

        wv.codeclip.protocol.model.ProtocolPatchResult result = engine.processRecorded(library, (fileName, original, commandsForFile) ->
            acceptedByFile.getOrDefault(fileName, Set.of()));

        showPatchResult(result);
        listPanel.refresh();
        editorPanel.reload();
    }

    private void showPatchResult(wv.codeclip.protocol.model.ProtocolPatchResult result) {
        switch (result.getStatus()) {
            case APPLIED -> {
                StringBuilder sb = new StringBuilder("Applied successfully.\n\n");
                for (String line : result.getLog()) sb.append(line).append("\n");
                if (!result.getValidation().getWarnings().isEmpty()) {
                    sb.append("\nWarnings:\n");
                    for (ValidationError w : result.getValidation().getWarnings()) {
                        sb.append("• ").append(w.getMessage()).append("\n");
                    }
                }
                statusBar.setText("Patch applied. " + result.getWrittenFiles().size() + " file(s) updated.");
                JOptionPane.showMessageDialog(this, sb.toString(), "Patch Applied", JOptionPane.INFORMATION_MESSAGE);
            }
            case CANCELLED -> statusBar.setText("Patch cancelled; no changes accepted.");
            case VALIDATION_FAILED -> {
                StringBuilder sb = new StringBuilder("Validation failed — nothing was applied to any file:\n\n");
                for (ValidationError err : result.getValidation().getErrors()) {
                    sb.append("• ").append(err.getMessage()).append("\n");
                }
                JOptionPane.showMessageDialog(this, sb.toString(), "Validation Failed", JOptionPane.ERROR_MESSAGE);
                statusBar.setText("Validation failed; nothing applied.");
            }
            case FILE_VALIDATION_FAILED -> {
                StringBuilder sb = new StringBuilder("Applied changes failed a final check and were rolled back:\n\n");
                for (ValidationError err : result.getValidation().getErrors()) {
                    sb.append("• ").append(err.getMessage()).append("\n");
                }
                JOptionPane.showMessageDialog(this, sb.toString(), "Rolled Back", JOptionPane.ERROR_MESSAGE);
                statusBar.setText("File validation failed; changes rolled back.");
            }
            case EMPTY -> statusBar.setText("No protocol commands found.");
        }
    }

    private String buildAiFormatInstructions() {
        return """
            PROTOCOL EDITING FORMAT
            ========================
            To propose changes to protocol files, wrap commands in a block like this,
            always specifying the target filename:

            @@protocol <filename.prtcl>
            <commands>
            @@protocolEnd

            You may include multiple @@protocol ... @@protocolEnd blocks targeting
            different files in one response.

            Commands (each command's !id must match [a-z][a-z0-9-]*):

            DELETE !id <id>
                Removes the protocol entry with that id.

            UPDATE !id <id>
            <new content, one or more lines>
            ENDUPDATE
                Replaces the content of an existing entry.

            APPENDTO !id <id>
            <lines to append>
            ENDAPPENDTO
                Appends lines to the end of an existing entry's content.

            NEW !id <id>
            <content>
            ENDNEW
                Creates a brand-new entry at the end of the file. The id must not
                already exist in that file.

            NEWAFTER !id <id> !id <target>
            <content>
            ENDNEWAFTER
                Creates a brand-new entry positioned after <target>. <target> may be
                START to insert at the beginning. If <target> doesn't exist, the
                entry is appended at the end instead, with a warning.

            MOVE_AFTER !id <id> !id <target>
                Moves an existing entry to just after <target>. <target> may be START.

            Rules:
            - Every @@protocol block MUST specify a filename. A block with no
              filename is rejected entirely.
            - Ids only need to be unique within a single file, not across files.
            - If a file is locked, none of your commands will apply to it — the
              user must unlock it first.
            - All changes are reviewed and can be accepted or rejected individually
              by the user before being written to disk.
            """;
    }

/** Small icon prefix with a short label, matching the style used elsewhere in the module. */
    private JButton iconTextButton(String symbol, String label) {
        JButton button = new JButton(symbol + "  " + label);
        button.setToolTipText(label);
        return button;
    }

}