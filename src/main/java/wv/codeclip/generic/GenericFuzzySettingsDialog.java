// ===== GenericFuzzySettingsDialog.java =====
package wv.codeclip.generic;

import javax.swing.*;
import java.awt.*;

/**
 * Settings > Fuzzy Match Settings… dialog (Generic mode). Mirrors
 * wv.codeclip.html.HtmlFuzzySettingsDialog, pointed at GenericFuzzySettings.
 */
public class GenericFuzzySettingsDialog extends JDialog {

    public GenericFuzzySettingsDialog(JFrame parent) {
        super(parent, "Fuzzy Match Settings", true);
        buildUI();
    }

    private void buildUI() {
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Fuzzy @@FIND Matching (Generic mode)");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(title);
        form.add(Box.createVerticalStrut(10));

        JLabel minLabel = new JLabel("Minimum match percentage:");
        minLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(minLabel);

        SpinnerNumberModel model = new SpinnerNumberModel(
                GenericFuzzySettings.getMinMatchPercent(),
                GenericFuzzySettings.MIN_ALLOWED_PERCENT,
                GenericFuzzySettings.MAX_ALLOWED_PERCENT,
                1.0);
        JSpinner minSpinner = new JSpinner(model);
        minSpinner.setMaximumSize(new Dimension(100, minSpinner.getPreferredSize().height));
        minSpinner.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(minSpinner);

        JLabel minHint = new JLabel(
                "<html><i>Below this, @@FIND is reported as not found instead of guessing.<br>"
                + "Matches at or above 95% are always treated as high-confidence.</i></html>");
        minHint.setFont(minHint.getFont().deriveFont(11f));
        minHint.setForeground(Color.GRAY);
        minHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        minHint.setBorder(BorderFactory.createEmptyBorder(4, 0, 14, 0));
        form.add(minHint);

        JCheckBox confirmHighBox = new JCheckBox(
                "Also ask for confirmation on high-confidence matches (\u226595%)",
                GenericFuzzySettings.isConfirmHighConfidenceMatches());
        confirmHighBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(confirmHighBox);

        JLabel confirmHint = new JLabel(
                "<html><i>Off by default — 95%+ matches auto-apply and are just logged.<br>"
                + "Turn this on to review and accept/reject every fuzzy match, no matter how close.</i></html>");
        confirmHint.setFont(confirmHint.getFont().deriveFont(11f));
        confirmHint.setForeground(Color.GRAY);
        confirmHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        confirmHint.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        form.add(confirmHint);

        add(form, BorderLayout.CENTER);

        JButton saveBtn = new JButton("Save");
        JButton closeBtn = new JButton("Close");
        saveBtn.addActionListener(e -> {
            GenericFuzzySettings.setMinMatchPercent((Double) minSpinner.getValue());
            GenericFuzzySettings.setConfirmHighConfidenceMatches(confirmHighBox.isSelected());
            saveBtn.setText("Saved!");
            saveBtn.setForeground(new Color(30, 120, 30));
        });
        closeBtn.addActionListener(e -> dispose());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnPanel.add(saveBtn);
        btnPanel.add(closeBtn);
        add(btnPanel, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(440, 300));
        pack();
        setLocationRelativeTo(getOwner());
    }
}