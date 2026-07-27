package wv.codeclip.protocol.editor;

import wv.codeclip.protocol.library.ProtocolLibrary;
import wv.codeclip.protocol.ui.ProtocolClipboardHelper;
import wv.codeclip.protocol.ui.ProtocolExternalEditorLauncher;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.Timer;
import java.awt.*;
import java.util.List;
import java.util.*;
import wv.codeclip.protocol.model.ProtocolEntry;
import wv.codeclip.protocol.model.ProtocolFile;

/**
 * Embeddable structured editor for one .prtcl file. Drop this into any
 * container (the main Protocol Manager's right-hand pane, or a standalone
 * dialog) and call showFile(...) to switch which file it's editing.
 */
public final class StructuredProtocolEditorPanel extends JPanel {

    private final ProtocolLibrary library;
    private final StructuredEditorValidator validator = new StructuredEditorValidator();
    private final PlainTextExporter plainTextExporter = new PlainTextExporter();

    private final DefaultListModel<EntryDraft> entryListModel = new DefaultListModel<>();
    private final JList<EntryDraft> entryList = new JList<>(entryListModel);

    private final JTextField idField = new JTextField();
    private final JTextArea contentArea = new JTextArea();
    private final JLabel entryStatusLabel = new JLabel(" ");

    private final JTextArea previewArea = new JTextArea();
    private final JCheckBox lockCheckBox = new JCheckBox("File is locked (blocks AI edits, hand edits still allowed)");
    private final JLabel fileStatusLabel = new JLabel(" ");
    private final JLabel titleLabel = new JLabel("No file selected");

    private String fileName = null;
    private EntryDraft currentlyEditing = null;
    private boolean suppressFieldEvents = false;
    private boolean hasUnsavedChanges = false;
    private final Timer revalidateTimer;

    private Runnable onSavedCallback = () -> {};
    private Runnable onRequestNextFile = null;

    public StructuredProtocolEditorPanel(ProtocolLibrary library) {
        this.library = library;

        revalidateTimer = new Timer(150, e -> revalidateAll());
        revalidateTimer.setRepeats(false);

        setLayout(new BorderLayout());
        add(buildTopBar(), BorderLayout.NORTH);
        add(buildMainSplit(), BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);

        showEmpty();
    }

    public void setOnSavedCallback(Runnable callback) {
        this.onSavedCallback = callback;
    }

    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges;
    }

    public String getCurrentFileName() {
        return fileName;
    }

    // ---------------------------------------------------------------
    // Layout construction
    // ---------------------------------------------------------------

    private JPanel buildTopBar() {
        JPanel panel = new JPanel(new BorderLayout());

        titleLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        panel.add(titleLabel, BorderLayout.WEST);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        lockCheckBox.addActionListener(e -> { markDirty(); updatePreview(); });
        rightPanel.add(lockCheckBox);
        panel.add(rightPanel, BorderLayout.EAST);

        return panel;
    }

    private JSplitPane buildMainSplit() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setLeftComponent(buildEntryListPanel());
        split.setRightComponent(buildRightSplit());
        split.setDividerLocation(240);
        return split;
    }

private JPanel buildEntryListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Entries (in file order)"));

        entryList.setCellRenderer(new EntryListCellRenderer());
        entryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectEntry(entryList.getSelectedValue());
            }
        });
        panel.add(new JScrollPane(entryList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(1, 0, 4, 4));
        JButton addBtn = iconButton("+", "Add Entry");
        JButton removeBtn = iconButton("\u2212", "Remove Entry"); // minus sign
        JButton moveUpBtn = iconButton("\u2191", "Move Entry Up");
        JButton moveDownBtn = iconButton("\u2193", "Move Entry Down");

        addBtn.addActionListener(e -> addEntry());
        removeBtn.addActionListener(e -> removeSelectedEntry());
        moveUpBtn.addActionListener(e -> moveSelected(-1));
        moveDownBtn.addActionListener(e -> moveSelected(1));

        buttons.add(addBtn);
        buttons.add(removeBtn);
        buttons.add(moveUpBtn);
        buttons.add(moveDownBtn);
        panel.add(buttons, BorderLayout.SOUTH);

        return panel;
    }

private JSplitPane buildRightSplit() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setLeftComponent(buildEntryEditorPanel());
        split.setRightComponent(buildPreviewPanel());
        split.setResizeWeight(0.6);
        split.setDividerLocation(400);
        return split;
    }

private JPanel buildEntryEditorPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Entry Details"));

        JPanel idPanel = new JPanel(new BorderLayout());
        idPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        idPanel.add(new JLabel("ID: "), BorderLayout.WEST);
        idPanel.add(idField, BorderLayout.CENTER);

        contentArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);

        // Tab in the ID field moves focus to the next FILE in the list.
        idField.setFocusTraversalKeysEnabled(false);
        idField.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_TAB) {
                    e.consume();
                    if (onRequestNextFile != null) onRequestNextFile.run();
                }
            }
        });

        // Tab in the content area moves focus to the next ENTRY's ID field.
        contentArea.setFocusTraversalKeysEnabled(false);
        contentArea.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_TAB) {
                    e.consume();
                    advanceToNextEntryOrCreate();
                }
            }
        });

        DocumentListener liveEdit = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onFieldEdited(); checkForInlineIdSplit(); }
            public void removeUpdate(DocumentEvent e) { onFieldEdited(); }
            public void changedUpdate(DocumentEvent e) { onFieldEdited(); }
        };
        idField.getDocument().addDocumentListener(liveEdit);
        contentArea.getDocument().addDocumentListener(liveEdit);

        entryStatusLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        panel.add(idPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(contentArea), BorderLayout.CENTER);
        panel.add(entryStatusLabel, BorderLayout.SOUTH);

        setEntryFieldsEnabled(false);
        return panel;
    }

private JPanel buildPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Live Preview (exact file to be written)"));

        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        previewArea.setEditable(false);
        previewArea.setBackground(new Color(245, 245, 245));
        panel.add(new JScrollPane(previewArea), BorderLayout.CENTER);

        return panel;
    }

private JPanel buildBottomBar() {
        JPanel outer = new JPanel(new BorderLayout());

        JPanel buttonRow = new JPanel(new BorderLayout());

        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton copyPlainTextBtn = iconButton("\uD83D\uDCC4\uD83E\uDD16", "Copy as Plain Text (for another AI)");
        JButton copyRawBtn = iconButton("\uD83D\uDCCB", "Copy Raw .prtcl to Clipboard");
        copyPlainTextBtn.addActionListener(e -> copyPlainText());
        copyRawBtn.addActionListener(e -> copyRawToClipboard());
        leftButtons.add(copyPlainTextBtn);
        leftButtons.add(copyRawBtn);

        JPanel centerButtons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton saveBtn = iconButton("\uD83D\uDCBE", "Save All Changes");
        JButton discardBtn = iconButton("\u21BA", "Discard Changes");
        saveBtn.addActionListener(e -> saveAll());
        discardBtn.addActionListener(e -> discardChanges());
        centerButtons.add(saveBtn);
        centerButtons.add(discardBtn);

        JPanel cornerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton openExternalBtn = iconButton("\u2197", "Open in Notepad++");
        openExternalBtn.addActionListener(e -> openExternal());
        cornerButtons.add(openExternalBtn);

        buttonRow.add(leftButtons, BorderLayout.WEST);
        buttonRow.add(centerButtons, BorderLayout.CENTER);
        buttonRow.add(cornerButtons, BorderLayout.EAST);

        fileStatusLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

        outer.add(buttonRow, BorderLayout.NORTH);
        outer.add(fileStatusLabel, BorderLayout.SOUTH);
        return outer;
    }

// ---------------------------------------------------------------
    // File switching
    // ---------------------------------------------------------------

    public void showEmpty() {
        this.fileName = null;
        this.hasUnsavedChanges = false;
        entryListModel.clear();
        currentlyEditing = null;
        titleLabel.setText("No file selected");
        setEntryFieldsEnabled(false);
        idField.setText("");
        contentArea.setText("");
        previewArea.setText("");
        fileStatusLabel.setText(" ");
        lockCheckBox.setSelected(false);
        lockCheckBox.setEnabled(false);
    }

    /**
     * Switches the editor to show a different file. If there are unsaved
     * changes to the current file, asks the user first.
     */
    public void showFile(String newFileName) {
        if (newFileName == null) {
            showEmpty();
            return;
        }
        if (newFileName.equals(this.fileName)) {
            return; // already showing it
        }
        if (hasUnsavedChanges) {
            int choice = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes to '" + fileName + "'. Discard them and switch files?",
                "Unsaved Changes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }
        loadFile(newFileName);
    }

    /** Reloads whatever file is currently shown from disk, discarding edits. */
    public void reload() {
        if (fileName != null) {
            loadFile(fileName);
        }
    }

    private void loadFile(String nameToLoad) {
        this.fileName = nameToLoad;
        lockCheckBox.setEnabled(true);

        StringBuilder err = new StringBuilder();
        ProtocolFile file = library.loadSafely(nameToLoad, err);

        if (err.length() > 0) {
            fileStatusLabel.setText("Could not load file: " + err + " — starting with an empty file.");
        }

        titleLabel.setText(truncateForTitle(nameToLoad));
        titleLabel.setToolTipText(nameToLoad);

        lockCheckBox.setSelected(file.isLocked());

        entryListModel.clear();
        for (ProtocolEntry entry : file.getEntries()) {
            entryListModel.addElement(new EntryDraft(entry.getId(), String.join("\n", entry.getContentLines()), false));
        }

        hasUnsavedChanges = false;
        revalidateAll();
        updatePreview();

        if (!entryListModel.isEmpty()) {
            entryList.setSelectedIndex(0);
        } else {
            setEntryFieldsEnabled(false);
        }
    }

    private String truncateForTitle(String name) {
        String label = "Editing: " + name;
        return label.length() > 45 ? label.substring(0, 42) + "..." : label;
    }

    // ---------------------------------------------------------------
    // Saving / discarding
    // ---------------------------------------------------------------

    private void saveAll() {
        if (fileName == null) return;

        List<EntryDraft> drafts = Collections.list(entryListModel.elements());
        Map<EntryDraft, EntryValidationState> results = validator.validateAll(drafts);

        if (!validator.allValid(results)) {
            StringBuilder sb = new StringBuilder("Cannot save — fix these first:\n\n");
            for (Map.Entry<EntryDraft, EntryValidationState> e : results.entrySet()) {
                if (e.getValue().level == EntryValidationState.Level.ERROR) {
                    String label = e.getKey().getId() == null || e.getKey().getId().isBlank()
                        ? "(unnamed entry)" : e.getKey().getId();
                    sb.append("• ").append(label).append(": ").append(e.getValue().message).append("\n");
                }
            }
            JOptionPane.showMessageDialog(this, sb.toString(), "Validation Errors", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<ProtocolEntry> entries = new ArrayList<>();
        int idx = 0;
        for (EntryDraft draft : drafts) {
            entries.add(new ProtocolEntry(draft.getId(), draft.contentAsLines(), idx++));
        }

        ProtocolFile toSave = new ProtocolFile(fileName, lockCheckBox.isSelected(), List.of(), entries);
        library.save(toSave);

        hasUnsavedChanges = false;
        fileStatusLabel.setText("Saved '" + fileName + "' successfully.");
        onSavedCallback.run();
        reload();
    }

    private void discardChanges() {
        if (fileName == null) return;
        int choice = JOptionPane.showConfirmDialog(this,
            "Discard all unsaved changes to '" + fileName + "'?", "Confirm Discard",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            reload();
        }
    }

    // ---------------------------------------------------------------
    // Entry list actions
    // ---------------------------------------------------------------

    private void addEntry() {
        commitCurrentEditToDraft();
        EntryDraft draft = new EntryDraft("", "", true);
        entryListModel.addElement(draft);
        entryList.setSelectedValue(draft, true);
        markDirty();
        revalidateAll();
        idField.requestFocusInWindow();
    }

    private void removeSelectedEntry() {
        EntryDraft selected = entryList.getSelectedValue();
        if (selected == null) return;
        int confirm = JOptionPane.showConfirmDialog(this,
            "Remove entry '" + (selected.getId() == null || selected.getId().isBlank() ? "(unnamed)" : selected.getId()) + "'?",
            "Confirm Remove", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        int idx = entryListModel.indexOf(selected);
        entryListModel.removeElement(selected);
        currentlyEditing = null;
        setEntryFieldsEnabled(false);
        markDirty();

        if (!entryListModel.isEmpty()) {
            int newIdx = Math.min(idx, entryListModel.size() - 1);
            entryList.setSelectedIndex(newIdx);
        }
        revalidateAll();
    }

    private void moveSelected(int direction) {
        int idx = entryList.getSelectedIndex();
        if (idx < 0) return;
        int target = idx + direction;
        if (target < 0 || target >= entryListModel.size()) return;

        EntryDraft draft = entryListModel.remove(idx);
        entryListModel.add(target, draft);
        entryList.setSelectedIndex(target);
        markDirty();
        updatePreview();
    }

    // ---------------------------------------------------------------
    // Entry editing
    // ---------------------------------------------------------------

    private void selectEntry(EntryDraft draft) {
        commitCurrentEditToDraft();
        currentlyEditing = draft;

        if (draft == null) {
            setEntryFieldsEnabled(false);
            idField.setText("");
            contentArea.setText("");
            return;
        }

        setEntryFieldsEnabled(true);
        suppressFieldEvents = true;
        idField.setText(draft.getId());
        contentArea.setText(draft.getContent());
        suppressFieldEvents = false;

        showEntryStatus(draft.getValidationState());
    }

    private void commitCurrentEditToDraft() {
        if (currentlyEditing == null) return;
        currentlyEditing.setId(idField.getText().trim());
        currentlyEditing.setContent(contentArea.getText());
    }

    private void onFieldEdited() {
        if (suppressFieldEvents || currentlyEditing == null) return;
        commitCurrentEditToDraft();
        entryList.repaint();
        markDirty();
        revalidateTimer.restart();
    }

    private void setEntryFieldsEnabled(boolean enabled) {
        idField.setEnabled(enabled);
        contentArea.setEnabled(enabled);
        if (!enabled) entryStatusLabel.setText(" ");
    }

    private void showEntryStatus(EntryValidationState state) {
        if (state.level == EntryValidationState.Level.OK) {
            entryStatusLabel.setText("Looks good.");
            entryStatusLabel.setForeground(new Color(30, 130, 30));
        } else {
            entryStatusLabel.setText(state.message);
            entryStatusLabel.setForeground(new Color(180, 30, 30));
        }
    }

    private void markDirty() {
        hasUnsavedChanges = true;
    }

    // ---------------------------------------------------------------
    // Validation / preview
    // ---------------------------------------------------------------

    private void revalidateAll() {
        List<EntryDraft> drafts = Collections.list(entryListModel.elements());
        Map<EntryDraft, EntryValidationState> results = validator.validateAll(drafts);
        for (Map.Entry<EntryDraft, EntryValidationState> e : results.entrySet()) {
            e.getKey().setValidationState(e.getValue());
        }
        entryList.repaint();

        if (currentlyEditing != null) {
            showEntryStatus(currentlyEditing.getValidationState());
        }

        long errorCount = results.values().stream().filter(s -> s.level == EntryValidationState.Level.ERROR).count();
        if (errorCount > 0) {
            fileStatusLabel.setText(errorCount + " entr" + (errorCount == 1 ? "y needs" : "ies need") + " attention before saving.");
            fileStatusLabel.setForeground(new Color(180, 30, 30));
        } else if (hasUnsavedChanges) {
            fileStatusLabel.setText("Unsaved changes.");
            fileStatusLabel.setForeground(new Color(150, 110, 0));
        } else {
            fileStatusLabel.setText("All entries valid.");
            fileStatusLabel.setForeground(new Color(30, 130, 30));
        }

        updatePreview();
    }

    private void updatePreview() {
        if (fileName == null) return;
        List<ProtocolEntry> entries = new ArrayList<>();
        int idx = 0;
        for (EntryDraft draft : Collections.list(entryListModel.elements())) {
            String id = draft.getId() == null || draft.getId().isBlank() ? "(unnamed)" : draft.getId();
            entries.add(new ProtocolEntry(id, draft.contentAsLines(), idx++));
        }
        ProtocolFile preview = new ProtocolFile(fileName, lockCheckBox.isSelected(), List.of(), entries);
        previewArea.setText(preview.render());
        previewArea.setCaretPosition(0);
    }

    // ---------------------------------------------------------------
    // Export / clipboard / external editor
    // ---------------------------------------------------------------

    private void copyPlainText() {
        if (fileName == null) return;
        List<ProtocolEntry> entries = new ArrayList<>();
        int idx = 0;
        for (EntryDraft draft : Collections.list(entryListModel.elements())) {
            String id = draft.getId() == null || draft.getId().isBlank() ? "(unnamed)" : draft.getId();
            entries.add(new ProtocolEntry(id, draft.contentAsLines(), idx++));
        }
        ProtocolFile current = new ProtocolFile(fileName, lockCheckBox.isSelected(), List.of(), entries);
        String plainText = plainTextExporter.export(current);
        ProtocolClipboardHelper.copyToClipboard(plainText);
        fileStatusLabel.setText("Copied plain-text version (no protocol markup) to clipboard.");
        fileStatusLabel.setForeground(Color.BLACK);
    }

    private void copyRawToClipboard() {
        if (fileName == null) return;
        ProtocolClipboardHelper.copyToClipboard(previewArea.getText());
        fileStatusLabel.setText("Copied raw .prtcl content to clipboard.");
        fileStatusLabel.setForeground(Color.BLACK);
    }

    private void openExternal() {
        if (fileName == null) return;
        try {
            ProtocolExternalEditorLauncher.open(library.getProtocolsDir().resolve(fileName));
            fileStatusLabel.setText("Opened saved copy in external editor. Unsaved changes here are not reflected there.");
            fileStatusLabel.setForeground(Color.BLACK);
        } catch (Exception e) {
            fileStatusLabel.setText("Could not open external editor: " + e.getMessage());
            fileStatusLabel.setForeground(new Color(180, 30, 30));
        }
    }

/**
     * Moves focus to the next entry's ID field, wrapping to a newly created
     * blank entry if we're already on the last one.
     */
    private void advanceToNextEntryOrCreate() {
        commitCurrentEditToDraft();
        int currentIdx = entryList.getSelectedIndex();

        if (currentIdx < 0) return;

        if (currentIdx + 1 < entryListModel.size()) {
            entryList.setSelectedIndex(currentIdx + 1);
        } else {
            EntryDraft draft = new EntryDraft("", "", true);
            entryListModel.addElement(draft);
            entryList.setSelectedValue(draft, true);
            markDirty();
            revalidateAll();
        }
        SwingUtilities.invokeLater(idField::requestFocusInWindow);
        SwingUtilities.invokeLater(() -> idField.selectAll());
    }

    /**
     * Watches the content box for the literal text "!id" appearing anywhere.
     * The moment it does, everything from that point onward becomes a new
     * entry: the text before "!id" stays in the current entry's content,
     * and the text after becomes the new entry's content (with its id left
     * blank for the user to fill in, unless "!id someid" was typed inline,
     * in which case that id is used directly).
     */
    private void checkForInlineIdSplit() {
        if (suppressFieldEvents || currentlyEditing == null) return;

        String text = contentArea.getText();
        int splitPoint = text.indexOf("!id");
        if (splitPoint < 0) return;

        String before = text.substring(0, splitPoint).stripTrailing();
        String after = text.substring(splitPoint + 3).stripLeading();

        // If the user typed "!id someid" inline, pull that id out for the new entry.
        String newId = "";
        String remainingContent = after;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([a-z][a-z0-9-]*)\\s*(.*)$", java.util.regex.Pattern.DOTALL).matcher(after);
        if (m.matches()) {
            newId = m.group(1);
            remainingContent = m.group(2);
        }

        int currentIdx = entryListModel.indexOf(currentlyEditing);

        suppressFieldEvents = true;
        contentArea.setText(before);
        suppressFieldEvents = false;
        currentlyEditing.setContent(before);

        EntryDraft newEntry = new EntryDraft(newId, remainingContent, true);
        entryListModel.add(currentIdx + 1, newEntry);

        markDirty();
        entryList.setSelectedValue(newEntry, true);
        SwingUtilities.invokeLater(idField::requestFocusInWindow);
    }

/** Wired by the parent dialog so Tab-in-ID-field can advance to the next file. */
    public void setOnRequestNextFile(Runnable callback) {
        this.onRequestNextFile = callback;
    }

/** Small square button showing only a symbol/icon, with the full label as a tooltip. */
    private JButton iconButton(String symbol, String tooltip) {
        JButton button = new JButton(symbol);
        button.setToolTipText(tooltip);
        button.setMargin(new Insets(2, 6, 2, 6));
        button.setFont(button.getFont().deriveFont(Font.BOLD, 14f));
        return button;
    }

}