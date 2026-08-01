package wv.codeclip.protocol.ui;

import wv.codeclip.protocol.engine.ProtocolApplier;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.*;
import wv.codeclip.protocol.model.Command;
import wv.codeclip.protocol.model.ProtocolFile;

/**
 * Modal dialog (parented to whichever window owns it — the Protocol Manager
 * dialog, keeping the modal chain correct) that shows one panel per affected
 * file, lets the user accept/reject individual commands or accept-all, and
 * renders a live diff of the resulting content.
 */
public final class DiffAcceptDialog extends JDialog {

    private final Map<String, ProtocolFile> originals;
    private final Map<String, List<Command>> commandsByFile;
    private final ProtocolApplier applier = new ProtocolApplier();

    private final Map<String, List<PendingChange>> pendingByFile = new LinkedHashMap<>();
    private final JTabbedPane tabs = new JTabbedPane();
    private boolean confirmed = false;

    public DiffAcceptDialog(Window owner, Map<String, ProtocolFile> originals,
                             Map<String, List<Command>> commandsByFile) {
        super(owner, "Review Protocol Changes", ModalityType.APPLICATION_MODAL);
        this.originals = originals;
        this.commandsByFile = commandsByFile;

        for (Map.Entry<String, List<Command>> entry : commandsByFile.entrySet()) {
            List<PendingChange> pending = new ArrayList<>();
            for (Command c : entry.getValue()) {
                pending.add(new PendingChange(c));
            }
            pendingByFile.put(entry.getKey(), pending);
        }

        setLayout(new BorderLayout());
        buildTabs();

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton acceptAllBtn = new JButton("Accept All");
        JButton rejectAllBtn = new JButton("Reject All");
        JButton applyBtn = new JButton("Apply Accepted");
        JButton cancelBtn = new JButton("Cancel");

        acceptAllBtn.addActionListener(e -> setAllAccepted(true));
        rejectAllBtn.addActionListener(e -> setAllAccepted(false));
        applyBtn.addActionListener(e -> { confirmed = true; dispose(); });
        cancelBtn.addActionListener(e -> { confirmed = false; dispose(); });

        buttonPanel.add(acceptAllBtn);
        buttonPanel.add(rejectAllBtn);
        buttonPanel.add(applyBtn);
        buttonPanel.add(cancelBtn);

        add(tabs, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        setSize(1200, 800);
        setMinimumSize(new Dimension(700, 450));
        setLocationRelativeTo(owner);
    }

    private void buildTabs() {
        for (Map.Entry<String, List<PendingChange>> entry : pendingByFile.entrySet()) {
            String fileName = entry.getKey();
            tabs.addTab(fileName, buildFilePanel(fileName, entry.getValue()));
        }
    }

    private JPanel buildFilePanel(String fileName, List<PendingChange> pending) {
        JPanel panel = new JPanel(new BorderLayout());

        DefaultListModel<PendingChange> listModel = new DefaultListModel<>();
        for (PendingChange pc : pending) listModel.addElement(pc);

        JList<PendingChange> list = new JList<>(listModel);
        list.setCellRenderer(new PendingChangeCellRenderer());

        JPanel diffContainer = new JPanel(new BorderLayout());
        JScrollPane diffScroll = new JScrollPane();
        diffContainer.add(diffScroll, BorderLayout.CENTER);

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                PendingChange selected = list.getSelectedValue();
                if (selected != null) {
                    diffScroll.setViewportView(renderDiffFor(fileName, selected));
                }
            }
        });

        // Toggle accept/reject on double-click or space
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int idx = list.locationToIndex(e.getPoint());
                    if (idx >= 0) {
                        PendingChange pc = listModel.getElementAt(idx);
                        pc.accepted = !pc.accepted;
                        list.repaint();
                    }
                }
            }
        });

        JLabel hint = new JLabel("Double-click a change to accept/reject it. Select to preview diff.");
        hint.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(list), diffContainer);
        split.setDividerLocation(280);

        panel.add(hint, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);

        if (!listModel.isEmpty()) {
            list.setSelectedIndex(0);
        }

        return panel;
    }

    private JComponent renderDiffFor(String fileName, PendingChange pc) {
        ProtocolFile original = originals.get(fileName);

        // Apply just this one command (accepted) against the original to preview its effect.
        Set<String> singleAccepted = new HashSet<>();
        singleAccepted.add(ProtocolApplier.commandKey(pc.command));

        ProtocolApplier.ApplyOutcome outcome = applier.apply(
            original, List.of(pc.command), singleAccepted);

        List<String> oldRendered = List.of(original.render().split("\n", -1));
        List<String> newRendered = List.of(outcome.result.render().split("\n", -1));

        List<DiffLine> diffLines = SimpleDiff.diff(oldRendered, newRendered);
        return DiffRenderer.render(diffLines);
    }

    private void setAllAccepted(boolean accepted) {
        for (List<PendingChange> pending : pendingByFile.values()) {
            for (PendingChange pc : pending) pc.accepted = accepted;
        }
        tabs.repaint();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.getComponentAt(i).repaint();
        }
    }

    public boolean wasConfirmed() {
        return confirmed;
    }

    /** Returns accepted command keys grouped by file, for feeding into ProtocolEngine. */
    public Map<String, Set<String>> getAcceptedKeysByFile() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<PendingChange>> entry : pendingByFile.entrySet()) {
            Set<String> accepted = new HashSet<>();
            for (PendingChange pc : entry.getValue()) {
                if (pc.accepted) {
                    accepted.add(ProtocolApplier.commandKey(pc.command));
                }
            }
            result.put(entry.getKey(), accepted);
        }
        return result;
    }

    private static final class PendingChangeCellRenderer extends JLabel implements ListCellRenderer<PendingChange> {
        @Override
        public Component getListCellRendererComponent(JList<? extends PendingChange> list, PendingChange value,
                                                        int index, boolean isSelected, boolean cellHasFocus) {
            setText((value.accepted ? "[x] " : "[ ] ") + value.describe());
            setOpaque(true);
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(value.accepted
                ? (isSelected ? list.getSelectionForeground() : Color.BLACK)
                : Color.GRAY);
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            return this;
        }
    }
}