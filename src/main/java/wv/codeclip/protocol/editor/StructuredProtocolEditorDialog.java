package wv.codeclip.protocol.editor;

import wv.codeclip.protocol.library.ProtocolLibrary;
import wv.codeclip.protocol.ui.ClipboardHelper;
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
 * Full structured editor for a single .prtcl file. Modal to whichever window
 * opens it. Shows entries as a manageable list (add/remove/reorder), edits
 * one entry's content at a time in a larger text area, validates live as you
 * type, shows a live preview of the exact file that will be written, and
 * offers export to plain markup-free text for use with any other AI.
 */
public final class StructuredProtocolEditorDialog extends JDialog {

    private final ProtocolLibrary library;
    private final String fileName;
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

    private EntryDraft currentlyEditing = null;
    private boolean suppressFieldEvents = false;
    private final Timer revalidateTimer;

    public StructuredProtocolEditorDialog(Window owner, ProtocolLibrary library, String fileName) {
        super(owner, "Structured Editor — " + fileName, ModalityType.APPLICATION_MODAL);
        this.library = library;
        this.fileName = fileName;

        revalidateTimer = new Timer(150, e -> revalidateAll());
        revalidateTimer.setRepeats(false);

        setLayout(new BorderLayout());
        add(buildTopBar(), BorderLayout.NORTH);
        add(buildMainSplit(), BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);

        loadFromDisk();

        setSize(1100, 700);
        setLocationRelativeTo(owner);
    }

    // ---------------------------------------------------------------
    // Layout construction
    // ---------------------------------------------------------------

    private JPanel buildTopBar() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Editing: " + fileName);
        title.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        panel.add(title, BorderLayout.WEST);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        lockCheckBox.addActionListener(e -> updatePreview());
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

        JPanel buttons = new JPanel(new GridLayout(0, 1, 4, 4));
        JButton addBtn = new JButton("+ Add Entry");
        JButton removeBtn = new JButton("Remove Entry");
        JButton moveUpBtn = new JButton("Move Up");
        JButton moveDownBtn = new JButton("Move Down");

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
        split.setDividerLocation(430);
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

        DocumentListener liveEdit = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onFieldEdited(); }
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
        JPanel panel = new JPanel(new BorderLayout());

        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton copyPlainTextBtn = new JButton("Copy as Plain Text (for another AI)");
        JButton copyRawBtn = new JButton("Copy Raw .prtcl to Clipboard");
        JButton openExternalBtn = new JButton("Open Raw File in Notepad++");

        copyPlainTextBtn.addActionListener(e -> copyPlainText());
        copyRawBtn.addActionListener(e -> copyRawToClipboard());
        openExternalBtn.addActionListener(e -> openExternal());

        leftButtons.add(copyPlainTextBtn);
        leftButtons.add(copyRawBtn);
        leftButtons.add(openExternalBtn);

        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveBtn = new JButton("Save All Changes");
        JButton cancelBtn = new JButton("Cancel");

        saveBtn.addActionListener(e -> saveAll());
        cancelBtn.addActionListener(e -> confirmCancel());

        rightButtons.add(saveBtn);
        rightButtons.add(cancelBtn);

        panel.add(leftButtons, BorderLayout.WEST);
        panel.add(rightButtons, BorderLayout.EAST);

        JPanel statusWrap = new JPanel(new BorderLayout());
        fileStatusLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        statusWrap.add(panel, BorderLayout.NORTH);
        statusWrap.add(fileStatusLabel, BorderLayout.SOUTH);

        return statusWrap;
    }

    // ---------------------------------------------------------------
    // Data loading / saving
    // ---------------------------------------------------------------

    private void loadFromDisk() {
        StringBuilder err = new StringBuilder();
        ProtocolFile file = library.loadSafely(fileName, err);

        if (err.length() > 0) {
            fileStatusLabel.setText("Could not load file: " + err + " — starting with an empty file.");
        }

        lockCheckBox.setSelected(file.isLocked());

        entryListModel.clear();
        for (ProtocolEntry entry : file.getEntries()) {
            entryListModel.addElement(new EntryDraft(entry.getId(), String.join("\n", entry.getContentLines()), false));
        }

        revalidateAll();
        updatePreview();

        if (!entryListModel.isEmpty()) {
            entryList.setSelectedIndex(0);
        }
    }

    private void saveAll() {
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

        fileStatusLabel.setText("Saved '" + fileName + "' successfully.");
        JOptionPane.showMessageDialog(this, "Saved successfully.", "Saved", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    private void confirmCancel() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Discard all changes made in this editor?", "Confirm Cancel",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            dispose();
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
        revalidateAll();
        updatePreview();
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

        if (!entryListModel.isEmpty()) {
            int newIdx = Math.min(idx, entryListModel.size() - 1);
            entryList.setSelectedIndex(newIdx);
        }
        revalidateAll();
        updatePreview();
    }

    private void moveSelected(int direction) {
        int idx = entryList.getSelectedIndex();
        if (idx < 0) return;
        int target = idx + direction;
        if (target < 0 || target >= entryListModel.size()) return;

        EntryDraft draft = entryListModel.remove(idx);
        entryListModel.add(target, draft);
        entryList.setSelectedIndex(target);
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
        } else {
            fileStatusLabel.setText("All entries valid.");
            fileStatusLabel.setForeground(new Color(30, 130, 30));
        }

        updatePreview();
    }

    private void updatePreview() {
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
        List<ProtocolEntry> entries = new ArrayList<>();
        int idx = 0;
        for (EntryDraft draft : Collections.list(entryListModel.elements())) {
            String id = draft.getId() == null || draft.getId().isBlank() ? "(unnamed)" : draft.getId();
            entries.add(new ProtocolEntry(id, draft.contentAsLines(), idx++));
        }
        ProtocolFile current = new ProtocolFile(fileName, lockCheckBox.isSelected(), List.of(), entries);
        String plainText = plainTextExporter.export(current);
        ClipboardHelper.copyToClipboard(plainText);
        fileStatusLabel.setText("Copied plain-text version (no protocol markup) to clipboard — safe to paste to any AI.");
        fileStatusLabel.setForeground(Color.BLACK);
    }

    private void copyRawToClipboard() {
        ClipboardHelper.copyToClipboard(previewArea.getText());
        fileStatusLabel.setText("Copied raw .prtcl content to clipboard.");
        fileStatusLabel.setForeground(Color.BLACK);
    }

    private void openExternal() {
        try {
            ProtocolExternalEditorLauncher.open(library.getProtocolsDir().resolve(fileName));
            fileStatusLabel.setText("Opened saved copy in external editor. Unsaved changes here are not reflected there.");
            fileStatusLabel.setForeground(Color.BLACK);
        } catch (Exception e) {
            fileStatusLabel.setText("Could not open external editor: " + e.getMessage());
            fileStatusLabel.setForeground(new Color(180, 30, 30));
        }
    }
}