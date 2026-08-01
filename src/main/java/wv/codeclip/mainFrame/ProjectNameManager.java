// ===== ProjectNameManager.java =====
package wv.codeclip.mainFrame;

import wv.codeclip.io.SettingsManager;

import javax.swing.*;
import java.awt.*;

/**
 * Owns the user-facing "project name" — a short label the person assigns to
 * distinguish this CodeClip instance/workspace from others. Persisted via
 * SettingsManager so it survives restarts. Also owns the icon tint seed,
 * since re-rolling the tint is exposed alongside renaming in the same menu.
 */
public final class ProjectNameManager {

    private static final String DEFAULT_NAME = "";

    private final SettingsManager settings;
    private String projectName;
    private long iconTintSeed;

    public ProjectNameManager(SettingsManager settings) {
        this.settings = settings;
        this.projectName = settings.loadProjectName();
        this.iconTintSeed = settings.loadIconTintSeed();
        if (this.iconTintSeed == 0L) {
            // First run: seed deterministically from whatever name we have
            // (possibly empty), so the tint is stable until re-rolled.
            this.iconTintSeed = seedFromString(this.projectName);
            settings.saveIconTintSeed(this.iconTintSeed);
        }
    }

    public boolean hasProjectName() {
        return projectName != null && !projectName.isBlank();
    }

    public String getProjectName() {
        return projectName;
    }

    public long getIconTintSeed() {
        return iconTintSeed;
    }

    /**
     * Prompts the user for a project name (used on first run, or whenever a
     * build event happens and no name has been set yet). Returns true if a
     * name was set as a result of this call.
     */
    public boolean promptForNameIfMissing(Component parent) {
        if (hasProjectName()) {
            return false;
        }
        return promptForName(parent, "Name This Project",
                "Give this CodeClip workspace a short name.\n"
                        + "It will appear in the window title so you can tell\n"
                        + "multiple open instances apart.");
    }

    /** Opens a rename dialog regardless of whether a name is already set. */
    public boolean promptForRename(Component parent) {
        return promptForName(parent, "Rename Project",
                "Enter a new project name:");
    }

    private boolean promptForName(Component parent, String title, String message) {
        JTextField field = new JTextField(projectName != null ? projectName : "");
        field.setFont(field.getFont().deriveFont(14f));
        field.setPreferredSize(new Dimension(320, 28));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JLabel label = new JLabel("<html>" + message.replace("\n", "<br>") + "</html>");
        panel.add(label, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(360, 100));

        int result = JOptionPane.showConfirmDialog(
                parent, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) {
            return false;
        }
        String newName = field.getText() == null ? "" : field.getText().trim();
        if (newName.isEmpty()) {
            return false;
        }
        boolean nameChanged = !newName.equals(projectName);
        this.projectName = newName;
        settings.saveProjectName(projectName);

        // If this is effectively the first time a real name is set, and the
        // tint seed was only ever derived from an empty string, refresh the
        // seed from the new name so the color reflects the project.
        if (nameChanged) {
            this.iconTintSeed = seedFromString(projectName);
            settings.saveIconTintSeed(iconTintSeed);
        }
        settings.saveProperties();
        return true;
    }

    /** Assigns a brand-new random tint, independent of the project name. */
    public void rerollIconTint() {
        this.iconTintSeed = System.nanoTime() ^ (long) (Math.random() * Long.MAX_VALUE);
        settings.saveIconTintSeed(iconTintSeed);
        settings.saveProperties();
    }

    private static long seedFromString(String s) {
        if (s == null || s.isEmpty()) {
            // Still produce a stable-for-this-run seed rather than 0, so an
            // unnamed project doesn't collapse to "no tint" every time.
            return System.nanoTime();
        }
        long h = 1125899906842597L; // arbitrary large prime seed
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }
}