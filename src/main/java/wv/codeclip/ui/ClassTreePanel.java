// ===== ClassTreePanel.java =====
package wv.codeclip.ui;

import wv.codeclip.model.ClassRepository;
import wv.codeclip.modecontext.ModeColors;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tree-view panel for the class list.
 *
 * Builds a proper folder tree from the longest common ancestor of all loaded
 * file paths. Folders are collapsible (start collapsed). Each folder row has a
 * bulk-toggle button with three states: Disable / Enable / ~ All (mixed).
 */
public class ClassTreePanel extends JPanel {

    // ── Tree node ──────────────────────────────────────────────────────────────

    private static class FolderNode {
        final String name;              // just this segment, e.g. "ui"
        final String fullPath;          // absolute path of this directory
        final Map<String, FolderNode> children = new LinkedHashMap<>();
        final List<String> classPaths   = new ArrayList<>(); // repo paths whose parent == this dir

        FolderNode(String name, String fullPath) {
            this.name     = name;
            this.fullPath = fullPath;
        }
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private final ClassRepository    repo;
    private final Runnable           onToggle;
    private final Supplier<String>   filterText;

    /** Collapsed state keyed by the folder's absolute path. Default: collapsed. */
    private final Map<String, Boolean> collapsed = new HashMap<>();

    // ── Constructor ────────────────────────────────────────────────────────────

    public ClassTreePanel(ClassRepository repo, Runnable onToggle, Supplier<String> filterText) {
        this.repo       = repo;
        this.onToggle   = onToggle;
        this.filterText = filterText;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void refresh() {
        removeAll();

        String q = filterText.get().trim().toLowerCase();

        // Build tree from loaded files
        FolderNode root = buildTree();

        // Render children of root (root itself is not shown as a row)
        for (FolderNode child : root.children.values()) {
            renderFolder(child, 0, q);
        }
        // Files directly under root (edge case: no subdirectories)
        for (String path : root.classPaths) {
            if (matchesFilter(path, q)) {
                add(buildClassRow(path, 0));
            }
        }

        revalidate();
        repaint();
    }

    // ── Tree construction ──────────────────────────────────────────────────────

    private FolderNode buildTree() {
        List<File> files = new ArrayList<>();
        for (String path : repo.getClassCodeMap().keySet()) {
            File f = repo.getClassFileMap().get(path);
            if (f != null) files.add(f);
        }

        String commonRoot = findCommonRoot(files);
        FolderNode root = new FolderNode("", commonRoot);

        for (String path : repo.getClassCodeMap().keySet()) {
            File f = repo.getClassFileMap().get(path);
            String absPath = (f != null) ? f.getAbsolutePath() : path;
            File   dir     = (f != null) ? f.getParentFile()   : new File(path).getParentFile();

            // Build relative path segments from commonRoot to this file's parent
            List<String> segments = relativeDirSegments(commonRoot, dir);

            // Walk/create nodes
            FolderNode current = root;
            StringBuilder cumPath = new StringBuilder(commonRoot);
            for (String seg : segments) {
                cumPath.append(File.separator).append(seg);
                String cp = cumPath.toString();
                current = current.children.computeIfAbsent(
                        seg, k -> new FolderNode(k, cp));
            }
            current.classPaths.add(path);
        }

        return root;
    }

    /** Returns the longest common ancestor directory path of all files. */
    private String findCommonRoot(List<File> files) {
        if (files.isEmpty()) return "";

        // Collect all ancestor paths of the first file
        List<String> candidate = new ArrayList<>();
        File dir = files.get(0).getParentFile();
        while (dir != null) {
            candidate.add(dir.getAbsolutePath());
            dir = dir.getParentFile();
        }

        // Trim candidate until all files are under it
        outer:
        for (int i = 0; i < candidate.size(); i++) {
            String root = candidate.get(i);
            for (File f : files) {
                if (!f.getAbsolutePath().startsWith(root + File.separator)
                        && !f.getParentFile().getAbsolutePath().equals(root)) {
                    continue outer;
                }
            }
            return root;
        }
        return files.get(0).getParentFile() != null
                ? files.get(0).getParentFile().getAbsolutePath()
                : "";
    }

    /**
     * Returns the path segments between commonRoot and dir.
     * e.g. commonRoot="/src", dir="/src/wv/ui" → ["wv", "ui"]
     */
    private List<String> relativeDirSegments(String commonRoot, File dir) {
        List<String> segs = new ArrayList<>();
        if (dir == null) return segs;

        String abs = dir.getAbsolutePath();
        if (abs.equals(commonRoot)) return segs; // file is directly under root

        String rel = abs.startsWith(commonRoot + File.separator)
                ? abs.substring(commonRoot.length() + 1)
                : abs;

        for (String seg : rel.split(java.util.regex.Pattern.quote(File.separator))) {
            if (!seg.isEmpty()) segs.add(seg);
        }
        return segs;
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    /**
     * Recursively renders a folder and its contents.
     * @param depth 0 = top-level folder under root
     */
    private void renderFolder(FolderNode node, int depth, String q) {
        // Gather all repo paths that live anywhere under this node
        List<String> allPaths = collectAllPaths(node);

        // If filter is active and nothing matches, skip entire subtree
        if (!q.isEmpty() && allPaths.stream().noneMatch(p -> matchesFilter(p, q))
                && !node.name.toLowerCase().contains(q)) {
            return;
        }

        boolean isCollapsed = collapsed.getOrDefault(node.fullPath, true);

        JPanel folderRow = buildFolderRow(node, allPaths, depth, isCollapsed);
        add(folderRow);

        if (!isCollapsed) {
            // Child folders first
            for (FolderNode child : node.children.values()) {
                renderFolder(child, depth + 1, q);
            }
            // Then files directly in this folder
            for (String path : node.classPaths) {
                if (q.isEmpty() || matchesFilter(path, q)) {
                    add(buildClassRow(path, depth + 1));
                }
            }
        }
    }

    /** Collects all repo paths under this node and its descendants. */
    private List<String> collectAllPaths(FolderNode node) {
        List<String> result = new ArrayList<>(node.classPaths);
        for (FolderNode child : node.children.values()) {
            result.addAll(collectAllPaths(child));
        }
        return result;
    }

    // ── Row builders ───────────────────────────────────────────────────────────

    private static final int INDENT_PX = 14;

    private JPanel buildFolderRow(FolderNode node, List<String> allPaths,
                                   int depth, boolean isCollapsed) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(true);
        row.setBackground(folderRowBg());
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor()),
                BorderFactory.createEmptyBorder(3, 4 + depth * INDENT_PX, 3, 4)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        // Arrow + name
        String arrow = isCollapsed ? "▶" : "▼";
        long enabledCount = allPaths.stream()
                .filter(p -> !repo.getDisabledClasses().contains(p))
                .count();

        JLabel nameLabel = new JLabel(arrow + "  " + node.name
                + "  (" + enabledCount + "/" + allPaths.size() + ")");
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Clicking the label toggles collapse
        nameLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                collapsed.put(node.fullPath, !collapsed.getOrDefault(node.fullPath, true));
                refresh();
            }
        });

        // Bulk toggle button
        JButton toggleBtn = new JButton(folderBtnLabel(allPaths));
        toggleBtn.setFont(toggleBtn.getFont().deriveFont(Font.PLAIN, 11f));
        toggleBtn.setMargin(new Insets(1, 6, 1, 6));
        toggleBtn.setFocusable(false);
        toggleBtn.addActionListener(e -> {
            onFolderToggle(allPaths);
            onToggle.run();
        });

        row.add(nameLabel,  BorderLayout.CENTER);
        row.add(toggleBtn,  BorderLayout.EAST);

        // Clicking the row background also toggles collapse
        row.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                // Only if the click wasn't on the button
                if (!(e.getSource() instanceof JButton)) {
                    collapsed.put(node.fullPath, !collapsed.getOrDefault(node.fullPath, true));
                    refresh();
                }
            }
        });
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        return row;
    }

private JPanel buildClassRow(String path, int depth) {
        boolean disabled = repo.getDisabledClasses().contains(path);
        File    file     = repo.getClassFileMap().get(path);
        String  name     = (file != null) ? file.getName() : new File(path).getName();

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(true);
        row.setBackground(disabled
                ? ModeColors.getDisabledBackground()
                : ModeColors.getEnabledBackground());
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor()),
                BorderFactory.createEmptyBorder(2, 6 + depth * INDENT_PX, 2, 4)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, 11f));
        if (disabled) {
            nameLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        }

        JButton copyBtn = new JButton("Copy");
        copyBtn.setFont(copyBtn.getFont().deriveFont(Font.PLAIN, 10f));
        copyBtn.setMargin(new Insets(0, 4, 0, 4));
        copyBtn.setFocusable(false);
        copyBtn.setToolTipText("Copy this file's source to the clipboard");
        copyBtn.addActionListener(e -> {
            String code = repo.getClassCodeMap().get(path);
            if (code != null) {
                String prefix = wv.codeclip.modecontext.ModeContext.getCommentPrefix();
                String text = prefix + " ===== " + name + " =====\n" + code + "\n";
                java.awt.Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(text), null);
                copyBtn.setText("Copied!");
                javax.swing.Timer t = new javax.swing.Timer(1200, ev -> copyBtn.setText("Copy"));
                t.setRepeats(false);
                t.start();
            }
        });

        JButton moreBtn = new JButton("...");
        moreBtn.setFont(moreBtn.getFont().deriveFont(Font.PLAIN, 10f));
        moreBtn.setMargin(new Insets(0, 4, 0, 4));
        moreBtn.setFocusable(false);
        moreBtn.setToolTipText("Directory, edit, play, open file location, delete");
        moreBtn.addActionListener(e -> {
            java.awt.Window w = SwingUtilities.getWindowAncestor(row);
            JFrame frame = (w instanceof JFrame) ? (JFrame) w : null;
            wv.codeclip.ui.FileActionsDialog.show(frame, file,
                    deletedPath -> {
                        repo.getClassCodeMap().remove(deletedPath);
                        repo.getClassFileMap().remove(deletedPath);
                        repo.getDisabledClasses().remove(deletedPath);
                        File onDisk = new File(deletedPath);
                        if (onDisk.exists()) {
                            onDisk.delete();
                        }
                        onToggle.run();
                        refresh();
                    },
                    removedPath -> {
                        repo.getClassCodeMap().remove(removedPath);
                        repo.getClassFileMap().remove(removedPath);
                        repo.getDisabledClasses().remove(removedPath);
                        onToggle.run();
                        refresh();
                    });
        });

        JButton toggleBtn = new JButton(disabled ? "Enable" : "Disable");
        toggleBtn.setFont(toggleBtn.getFont().deriveFont(Font.PLAIN, 10f));
        toggleBtn.setMargin(new Insets(0, 4, 0, 4));
        toggleBtn.setFocusable(false);
        toggleBtn.addActionListener(e -> {
            if (!repo.getDisabledClasses().remove(path)) {
                repo.getDisabledClasses().add(path);
            }
            onToggle.run();
        });

        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnGroup.setOpaque(false);
        btnGroup.add(copyBtn);
        btnGroup.add(moreBtn);
        btnGroup.add(toggleBtn);

        row.add(nameLabel,  BorderLayout.CENTER);
        row.add(btnGroup,  BorderLayout.EAST);
        return row;
    }

// ── Folder toggle logic ────────────────────────────────────────────────────

    private void onFolderToggle(List<String> paths) {
        long disabledCount = paths.stream()
                .filter(p -> repo.getDisabledClasses().contains(p))
                .count();
        if (disabledCount == paths.size()) {
            // All disabled → enable all
            paths.forEach(repo.getDisabledClasses()::remove);
        } else {
            // All enabled or mixed → disable all
            repo.getDisabledClasses().addAll(paths);
        }
    }

    private String folderBtnLabel(List<String> paths) {
        long disabled = paths.stream()
                .filter(p -> repo.getDisabledClasses().contains(p))
                .count();
        if (disabled == 0)            return "Disable";
        if (disabled == paths.size()) return "Enable";
        return "~ All";
    }

    // ── Filter ─────────────────────────────────────────────────────────────────

    private boolean matchesFilter(String path, String q) {
        if (q.isEmpty()) return true;
        File f    = repo.getClassFileMap().get(path);
        String name = (f != null) ? f.getName() : new File(path).getName();
        return name.toLowerCase().contains(q);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Color folderRowBg() {
        Color base = UIManager.getColor("Panel.background");
        if (base == null) return new Color(230, 230, 230);
        int r = Math.max(0, base.getRed()   - 12);
        int g = Math.max(0, base.getGreen() - 12);
        int b = Math.max(0, base.getBlue()  - 12);
        return new Color(r, g, b);
    }

    private static Color separatorColor() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : new Color(180, 180, 180, 80);
    }
}