package wv.codeclip.protocol.ui;

import wv.codeclip.protocol.library.ProtocolLibrary;
import wv.codeclip.protocol.model.ProtocolFile;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

public final class ProtocolListPanel extends JPanel {

    private final ProtocolLibrary library;
    private final SearchIndex searchIndex;
    private final SearchOptions searchOptions = new SearchOptions();

    /** File names the user has enabled for "copy" operations. Enabled by default. */
    private final Set<String> enabledFiles = new HashSet<>();

    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> fileList = new JList<>(listModel);
    private final JTextField searchField = new JTextField();
    private final JCheckBox searchFileNameBox = new JCheckBox("Filename", true);
    private final JCheckBox searchIdBox = new JCheckBox("ID", true);
    private final JCheckBox searchContentBox = new JCheckBox("Content", true);

    private Consumer<String> onFileSelected = f -> {};

    public ProtocolListPanel(ProtocolLibrary library) {
        this.library = library;
        this.searchIndex = new SearchIndex(library);
        setLayout(new BorderLayout());

        for (String f : library.listFileNames()) enabledFiles.add(f);

        fileList.setCellRenderer(new FileCellRenderer(library, enabledFiles));
        fileList.setFixedCellHeight(26);
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = fileList.getSelectedValue();
                if (selected != null) onFileSelected.accept(selected);
            }
        });

        // Click on the checkbox area (left ~24px of the row) toggles enabled state
        // without changing selection; click elsewhere on the row selects normally.
        fileList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int idx = fileList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                Rectangle cellBounds = fileList.getCellBounds(idx, idx);
                if (cellBounds == null) return;
                int relativeX = e.getX() - cellBounds.x;
                if (relativeX <= 22) {
                    String name = listModel.getElementAt(idx);
                    toggleEnabled(name);
                    e.consume();
                }
            }
        });

        JPanel searchPanel = buildSearchPanel();

        JPanel enableButtonsRow = new JPanel(new GridLayout(1, 2, 4, 0));
        JButton enableAllBtn = new JButton("Enable All");
        JButton disableAllBtn = new JButton("Disable All");
        enableAllBtn.addActionListener(e -> setAllEnabled(true));
        disableAllBtn.addActionListener(e -> setAllEnabled(false));
        enableButtonsRow.add(enableAllBtn);
        enableButtonsRow.add(disableAllBtn);

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        JButton newBtn = iconTextButton("+", "New Protocol File");
        JButton deleteBtn = iconTextButton("\u2715", "Delete");
        JButton renameBtn = iconTextButton("\u270E", "Rename");
        JButton toggleLockBtn = iconTextButton("\uD83D\uDD12", "Toggle File Lock");
        JButton refreshBtn = iconTextButton("\u21BB", "Refresh");

        newBtn.addActionListener(e -> createNewFile());
        deleteBtn.addActionListener(e -> deleteSelected());
        renameBtn.addActionListener(e -> renameSelected());
        toggleLockBtn.addActionListener(e -> toggleLockSelected());
        refreshBtn.addActionListener(e -> refresh());

        for (JButton b : List.of(newBtn, renameBtn, toggleLockBtn, deleteBtn, refreshBtn)) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
        }

        buttonPanel.add(enableButtonsRow);
        buttonPanel.add(Box.createVerticalStrut(6));
        buttonPanel.add(newBtn);
        buttonPanel.add(renameBtn);
        buttonPanel.add(toggleLockBtn);
        buttonPanel.add(deleteBtn);
        buttonPanel.add(refreshBtn);

        add(searchPanel, BorderLayout.NORTH);
        add(new JScrollPane(fileList), BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        refresh();
    }

    private JPanel buildSearchPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JLabel label = new JLabel("Search protocols:");
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        JPanel checkboxRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        checkboxRow.add(searchFileNameBox);
        checkboxRow.add(searchIdBox);
        checkboxRow.add(searchContentBox);

        DocumentListener liveSearch = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applySearch(); }
            public void removeUpdate(DocumentEvent e) { applySearch(); }
            public void changedUpdate(DocumentEvent e) { applySearch(); }
        };
        searchField.getDocument().addDocumentListener(liveSearch);
        searchFileNameBox.addActionListener(e -> applySearch());
        searchIdBox.addActionListener(e -> applySearch());
        searchContentBox.addActionListener(e -> applySearch());

        panel.add(label);
        panel.add(searchField);
        panel.add(checkboxRow);

        return panel;
    }

    private void applySearch() {
        searchOptions.matchFileName = searchFileNameBox.isSelected();
        searchOptions.matchId = searchIdBox.isSelected();
        searchOptions.matchContent = searchContentBox.isSelected();
        refresh();
    }

    public void setOnFileSelected(Consumer<String> callback) {
        this.onFileSelected = callback;
    }

    /** Returns the set of file names currently enabled for copy operations. */
    public Set<String> getEnabledFiles() {
        return new HashSet<>(enabledFiles);
    }

    private void toggleEnabled(String fileName) {
        if (enabledFiles.contains(fileName)) {
            enabledFiles.remove(fileName);
        } else {
            enabledFiles.add(fileName);
        }
        fileList.repaint();
    }

    private void setAllEnabled(boolean enabled) {
        enabledFiles.clear();
        if (enabled) {
            for (int i = 0; i < listModel.size(); i++) {
                enabledFiles.add(listModel.getElementAt(i));
            }
        }
        fileList.repaint();
    }

    public void refresh() {
        String previouslySelected = fileList.getSelectedValue();
        listModel.clear();

        List<String> results = searchIndex.search(searchField.getText(), searchOptions);
        for (String fileName : results) {
            listModel.addElement(fileName);
            // Newly discovered files (e.g. just created) default to enabled.
            if (!enabledFiles.contains(fileName) && !wasEverListed(fileName)) {
                enabledFiles.add(fileName);
            }
        }

        if (previouslySelected != null && listModel.contains(previouslySelected)) {
            fileList.setSelectedValue(previouslySelected, true);
        }
        fileList.repaint();
    }

    private final Set<String> everSeenFiles = new HashSet<>();
    private boolean wasEverListed(String fileName) {
        boolean seen = everSeenFiles.contains(fileName);
        everSeenFiles.add(fileName);
        return seen;
    }

    private void createNewFile() {
        String name = JOptionPane.showInputDialog(this, "New protocol file name (without .prtcl):");
        if (name == null || name.isBlank()) return;
        name = name.trim();
        if (!name.matches("[a-zA-Z0-9_-]+")) {
            JOptionPane.showMessageDialog(this, "File name must be alphanumeric (with - or _).",
                "Invalid name", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String fileName = name + ".prtcl";
        if (library.exists(fileName)) {
            JOptionPane.showMessageDialog(this, "A file with that name already exists.",
                "Duplicate", JOptionPane.ERROR_MESSAGE);
            return;
        }
        ProtocolFile newFile = new ProtocolFile(fileName, false, List.of(), new java.util.ArrayList<>());
        library.save(newFile);
        enabledFiles.add(fileName);
        refresh();
        fileList.setSelectedValue(fileName, true);
    }

    private void deleteSelected() {
        String selected = fileList.getSelectedValue();
        if (selected == null) return;
        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete '" + selected + "'? This cannot be undone.", "Confirm Delete",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) {
            library.delete(selected);
            enabledFiles.remove(selected);
            refresh();
            onFileSelected.accept(null);
        }
    }

    private void renameSelected() {
        String selected = fileList.getSelectedValue();
        if (selected == null) return;
        String currentBase = selected.endsWith(".prtcl") ? selected.substring(0, selected.length() - 6) : selected;
        String newName = JOptionPane.showInputDialog(this, "New name (without .prtcl):", currentBase);
        if (newName == null || newName.isBlank()) return;
        newName = newName.trim();
        if (!newName.matches("[a-zA-Z0-9_-]+")) {
            JOptionPane.showMessageDialog(this, "File name must be alphanumeric (with - or _).",
                "Invalid name", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String newFileName = newName + ".prtcl";
        if (library.exists(newFileName)) {
            JOptionPane.showMessageDialog(this, "A file with that name already exists.",
                "Duplicate", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean wasEnabled = enabledFiles.contains(selected);
        library.rename(selected, newFileName);
        enabledFiles.remove(selected);
        if (wasEnabled) enabledFiles.add(newFileName);
        refresh();
        fileList.setSelectedValue(newFileName, true);
        onFileSelected.accept(newFileName);
    }

    private void toggleLockSelected() {
        String selected = fileList.getSelectedValue();
        if (selected == null) return;
        ProtocolFile file = library.load(selected);
        file.setLocked(!file.isLocked());
        library.save(file);
        refresh();
        onFileSelected.accept(selected);
    }

    public String getSelectedFileName() {
        return fileList.getSelectedValue();
    }

    private static final class FileCellRenderer extends JPanel implements ListCellRenderer<String> {
        private final ProtocolLibrary library;
        private final Set<String> enabledFiles;
        private final JCheckBox checkBox = new JCheckBox();
        private final JLabel nameLabel = new JLabel();

        FileCellRenderer(ProtocolLibrary library, Set<String> enabledFiles) {
            this.library = library;
            this.enabledFiles = enabledFiles;
            setLayout(new BorderLayout(4, 0));
            setOpaque(true);
            checkBox.setOpaque(false);
            add(checkBox, BorderLayout.WEST);
            add(nameLabel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value,
                                                        int index, boolean isSelected, boolean cellHasFocus) {
            boolean locked = false;
            try {
                StringBuilder err = new StringBuilder();
                locked = library.loadSafely(value, err).isLocked();
            } catch (Exception ignored) {}

            checkBox.setSelected(enabledFiles.contains(value));

            String displayName = value.length() > 26 ? value.substring(0, 23) + "..." : value;
            nameLabel.setText((locked ? "\uD83D\uDD12 " : "") + displayName);
            nameLabel.setToolTipText(value); // full name on hover, regardless of truncation
            setToolTipText(value);

            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            nameLabel.setForeground(isSelected ? list.getSelectionForeground() : (locked ? new Color(150, 30, 30) : Color.BLACK));
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return this;
        }
    }

/** Selects the next file in the list, wrapping around to the first. */
    public void selectNextFile() {
        if (listModel.isEmpty()) return;
        int currentIdx = fileList.getSelectedIndex();
        int nextIdx = (currentIdx < 0 || currentIdx + 1 >= listModel.size()) ? 0 : currentIdx + 1;
        fileList.setSelectedIndex(nextIdx);
    }

/** Small icon prefix with a short label, used where a bare icon would be ambiguous in a vertical stack. */
    private JButton iconTextButton(String symbol, String label) {
        JButton button = new JButton(symbol + "  " + label);
        button.setToolTipText(label);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        return button;
    }

}