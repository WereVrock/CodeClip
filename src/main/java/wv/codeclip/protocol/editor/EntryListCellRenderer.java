package wv.codeclip.protocol.editor;

import javax.swing.*;
import java.awt.*;

final class EntryListCellRenderer extends JLabel implements ListCellRenderer<EntryDraft> {

    private static final Color ERROR_BG = new Color(255, 225, 225);
    private static final Color ERROR_BG_SELECTED = new Color(190, 60, 60);
    private static final Color NEW_BG = new Color(224, 246, 224);
    private static final Color NEW_BG_SELECTED = new Color(60, 150, 70);
    private static final Color OK_FG = new Color(20, 110, 20);

    @Override

public Component getListCellRendererComponent(JList<? extends EntryDraft> list, EntryDraft value,
                                                    int index, boolean isSelected, boolean cellHasFocus) {
        setOpaque(true);

        String icon = switch (value.getValidationState().level) {
            case ERROR -> "\u26A0 ";   // warning triangle
            case WARNING -> "\u26A0 ";
            case OK -> "\u2713 ";      // checkmark, makes "all good" scannable too
        };

        String label = value.getId() == null || value.getId().isBlank() ? "(unnamed)" : value.getId();
        setText(icon + label + (value.isNew() ? "  [new]" : ""));
        setFont(getFont().deriveFont(13f));
        setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));

        boolean isError = value.getValidationState().level == EntryValidationState.Level.ERROR;
        boolean isNew = value.isNew();

        Color bg;
        Color fg;
        if (isError) {
            bg = isSelected ? ERROR_BG_SELECTED : ERROR_BG;
            fg = isSelected ? Color.WHITE : new Color(160, 20, 20);
        } else if (isNew) {
            bg = isSelected ? NEW_BG_SELECTED : NEW_BG;
            fg = isSelected ? Color.WHITE : new Color(20, 110, 20);
        } else {
            bg = isSelected ? list.getSelectionBackground() : list.getBackground();
            fg = isSelected ? list.getSelectionForeground() : (list.getForeground() != null ? list.getForeground() : Color.BLACK);
        }

        setBackground(bg);
        setForeground(fg);
        setToolTipText(value.getValidationState().message);
        return this;
    }

}