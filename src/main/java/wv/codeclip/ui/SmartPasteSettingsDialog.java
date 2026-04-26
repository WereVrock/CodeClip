package wv.codeclip.ui;

import wv.codeclip.io.SettingsManager;
import javax.swing.*;
import java.awt.*;

public class SmartPasteSettingsDialog extends JDialog {

    public SmartPasteSettingsDialog(JFrame parent, SettingsManager settings) {
        super(parent, "Smart Paste Settings", true);
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JCheckBox allowClassesCheck = new JCheckBox(
                "Extract and paste whole classes (//@CLASS blocks)",
                SmartPasteSettings.isAllowClasses());
        JCheckBox skipCreateCheck = new JCheckBox(
                "Skip confirmation when creating new files",
                SmartPasteSettings.isSkipCreateConfirm());
        JCheckBox skipOverwriteCheck = new JCheckBox(
                "Skip confirmation when overwriting existing files",
                SmartPasteSettings.isSkipOverwriteConfirm());

        JTextArea note = new JTextArea(
                "Note: Skipping overwrite confirmation will still run the missing\n" +
                "method check but proceed automatically without prompting."
        );
        note.setEditable(false);
        note.setFont(UIManager.getFont("Label.font").deriveFont(Font.ITALIC, 11f));
        note.setBackground(UIManager.getColor("Panel.background"));
        note.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel checks = new JPanel();
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        checks.add(allowClassesCheck);
        checks.add(Box.createVerticalStrut(8));
        checks.add(skipCreateCheck);
        checks.add(Box.createVerticalStrut(4));
        checks.add(skipOverwriteCheck);
        checks.add(Box.createVerticalStrut(10));
        checks.add(note);
        add(checks, BorderLayout.CENTER);

        JButton save  = new JButton("Save");
        JButton close = new JButton("Cancel");

        save.addActionListener(e -> {
            SmartPasteSettings.setAllowClasses(allowClassesCheck.isSelected());
            SmartPasteSettings.setSkipCreateConfirm(skipCreateCheck.isSelected());
            SmartPasteSettings.setSkipOverwriteConfirm(skipOverwriteCheck.isSelected());
            SmartPasteSettings.save(settings);
            dispose();
        });
        close.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(save);
        buttons.add(close);
        add(buttons, BorderLayout.SOUTH);

        pack();
        setResizable(false);
        setLocationRelativeTo(parent);
    }
}