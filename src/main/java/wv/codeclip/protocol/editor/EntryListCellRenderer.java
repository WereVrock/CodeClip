package wv.codeclip.protocol.editor;

import javax.swing.*;
import java.awt.*;

final class EntryListCellRenderer extends JLabel implements ListCellRenderer<EntryDraft> {

    @Override
    public Component getListCellRendererComponent(JList<? extends EntryDraft> list, EntryDraft value,
                                                    int index, boolean isSelected, boolean cellHasFocus) {
        setOpaque(true);

        String icon = switch (value.getValidationState().level) {
            case ERROR -> "\u26A0 ";   // warning triangle
            case WARNING -> "\u26A0 ";
            case OK -> "";
        };

        String label = value.getId() == null || value.getId().isBlank() ? "(unnamed)" : value.getId();
        setText(icon + label + (value.isNew() ? "  [new]" : ""));

        setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
        Color fg;
        if (value.getValidationState().level == EntryValidationState.Level.ERROR) {
            fg = isSelected ? Color.WHITE : new Color(180, 30, 30);
        } else if (value.isNew()) {
            fg = isSelected ? Color.WHITE : new Color(30, 120, 30);
        } else {
            fg = isSelected ? list.getSelectionForeground() : Color.BLACK;
        }
        setForeground(fg);
        setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        setToolTipText(value.getValidationState().message);
        return this;
    }
}