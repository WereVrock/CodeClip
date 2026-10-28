package wv.codeclip.mainFrame;

import wv.codeclip.io.ProjectSettings;

import javax.swing.*;
import java.awt.*;

/**
 * Owns the project name and icon tint seed.
 *
 * Model:
 *   - Before any project is loaded: random tint for this session, nothing
 *     written anywhere.
 *   - Once a project is attached: reads from / writes to that project's
 *     buildinfo.properties only. No global ~/codeclip.properties involvement.
 *   - Renaming before any project loads is stashed (pending) and flushed to
 *     the first project attached.
 */
public final class ProjectNameManager {

    private ProjectSettings projectSettings;

    private String projectName = "";
    private long iconTintSeed = System.nanoTime();

    private String pendingProjectName = null;
    private Long pendingIconTint = null;

    public ProjectNameManager() {}

    /**
     * Rebinds to a specific project's settings file. Called by CodeClipFrame
     * once the project's source root is known.
     */
    public void attachProjectSettings(ProjectSettings ps) {
        if (ps == null) {
            return;
        }
        this.projectSettings = ps;

        if (pendingProjectName != null) {
            ps.saveProjectName(pendingProjectName);
            pendingProjectName = null;
        }
        if (pendingIconTint != null) {
            ps.saveIconTintSeed(pendingIconTint);
            pendingIconTint = null;
        }

        String storedName = ps.loadProjectName();
        this.projectName = (storedName == null) ? "" : storedName;

        long storedSeed = ps.loadIconTintSeed();
        if (storedSeed != 0L) {
            this.iconTintSeed = storedSeed;
        } else if (!projectName.isBlank()) {
            this.iconTintSeed = seedFromString(projectName);
            ps.saveIconTintSeed(iconTintSeed);
        } else {
            // Unnamed with no saved seed: fresh random for this session,
            // not persisted. An explicit reroll or rename will save a seed.
            this.iconTintSeed = System.nanoTime() ^ (long) (Math.random() * Long.MAX_VALUE);
        }
    }

/**
     * Clears in-memory project state and detaches from any project file.
     * Used by Reset, which is in-memory-only (files, versions, undo all
     * cleared; the project's buildinfo.properties on disk is left untouched
     * — re-loading that project later re-reads it as usual). Name becomes
     * empty so the title stops showing it; tint gets a fresh random so the
     * icon visibly changes.
     */
    public void resetToUnattached() {
        this.projectSettings = null;
        this.pendingProjectName = null;
        this.pendingIconTint = null;
        this.projectName = "";
        this.iconTintSeed = System.nanoTime() ^ (long) (Math.random() * Long.MAX_VALUE);
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

public boolean promptForNameIfMissing(Component parent) {
    System.out.println("[PNM] promptForNameIfMissing hasProjectName="
            + hasProjectName() + " projectSettings=" + (projectSettings != null)
            + " pendingName='" + pendingProjectName + "'");
    if (hasProjectName()) {
        return false;
    }
    return promptForName(parent, "Name This Project",
            "Give this CodeClip workspace a short name.\n"
                    + "It will appear in the window title so you can tell\n"
                    + "multiple open instances apart.");
}

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
        persistProjectName();

        if (nameChanged) {
            this.iconTintSeed = seedFromString(projectName);
            persistIconTint();
        }
        return true;
    }

    public void rerollIconTint() {
        this.iconTintSeed = System.nanoTime() ^ (long) (Math.random() * Long.MAX_VALUE);
        persistIconTint();
    }

private void persistProjectName() {
    System.out.println("[PNM] persistProjectName name='" + projectName
            + "' projectSettings=" + (projectSettings != null));
    if (projectSettings != null) {
        projectSettings.saveProjectName(projectName);
        System.out.println("[PNM] saveProjectName readback='"
                + projectSettings.loadProjectName() + "'");
    } else {
        pendingProjectName = projectName;
        System.out.println("[PNM] STASHED pendingProjectName='" + pendingProjectName + "'");
    }
}

public boolean hasPendingName() {
    return pendingProjectName != null;
}

/**
 * Applies any name that was stashed before projectSettings was attached.
 * Safe to call repeatedly — no-op when nothing is pending.
 */
public void flushPendingName() {
    if (pendingProjectName != null && projectSettings != null) {
        System.out.println("[PNM] flushPendingName applying '" + pendingProjectName + "'");
        projectSettings.saveProjectName(pendingProjectName);
        System.out.println("[PNM] flushPendingName readback='"
                + projectSettings.loadProjectName() + "'");
        pendingProjectName = null;
    } else {
        System.out.println("[PNM] flushPendingName no-op pending="
                + (pendingProjectName != null)
                + " projectSettings=" + (projectSettings != null));
    }
}

private void persistIconTint() {
        if (projectSettings != null) {
            projectSettings.saveIconTintSeed(iconTintSeed);
        } else {
            pendingIconTint = iconTintSeed;
        }
    }

    private static long seedFromString(String s) {
        if (s == null || s.isEmpty()) {
            return System.nanoTime();
        }
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }
}