package wv.codeclip.patch;

import javax.swing.*;
import java.awt.*;
import wv.codeclip.io.ClipboardService;

/**
 * Modal dialog shown when a patch fails.
 * Displays the error message and offers a "Copy Error" button.
 */
public class PatchErrorDialog extends JDialog {

    public PatchErrorDialog(JFrame parent, String errorMessage, String classCode) {
        super(parent, "Patch Failed", true);

        setLayout(new BorderLayout(10, 10));

        JTextArea text = new JTextArea(errorMessage);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setBackground(UIManager.getColor("Panel.background"));
        text.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(500, 220));
        add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton copyBtn = new JButton("Copy Error");
        copyBtn.addActionListener(e -> {
            new ClipboardService().write(errorMessage);
            copyBtn.setText("Copied!");
        });

        if (classCode != null) {
            final String code = classCode;
            JButton copyClassBtn = new JButton("Copy Class");
            copyClassBtn.addActionListener(e -> {
                new ClipboardService().write(code);
                copyClassBtn.setText("Copied!");
            });
            buttons.add(copyClassBtn);
        }

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());

        buttons.add(copyBtn);
        buttons.add(closeBtn);
        add(buttons, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(parent);
    }

    public static void show(JFrame parent, String errorMessage, String classCode) {
        new PatchErrorDialog(parent, errorMessage, classCode).setVisible(true);
    }

    public static void show(JFrame parent, String errorMessage) {
        new PatchErrorDialog(parent, errorMessage, null).setVisible(true);
    }
}