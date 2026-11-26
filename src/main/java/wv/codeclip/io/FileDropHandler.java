package wv.codeclip.io;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

public class FileDropHandler extends DropTargetAdapter {

    private final Consumer<List<File>> batchConsumer;
    private JFrame frame;
    private JPanel glassPane;
    private DragOverlayPanel overlayPanel;
    private wv.codeclip.AppMode mode = wv.codeclip.AppMode.JAVA;
    private final List<File> rejectedThisDrop = new ArrayList<>();
    private boolean ignoreDotFiles = true;

    public FileDropHandler(Consumer<File> fileConsumer) {
        this.batchConsumer = files -> files.forEach(fileConsumer);
    }

    public FileDropHandler(Consumer<List<File>> batchConsumer, boolean batch) {
        this.batchConsumer = batchConsumer;
    }

    public void setMode(wv.codeclip.AppMode mode) {
        this.mode = mode;
    }

    // Explicit drop method to satisfy the compiler – forwards to the glass handler
    @Override
    public void drop(DropTargetDropEvent dtde) {
        handleGlassDrop(dtde);
    }

    // Override the old install – only handles the frame case
    public void install(Component component) {
        if (!(component instanceof JFrame)) {
            // fallback: install on any component as before
            new DropTarget(component, this);
            installChildren(component);
            return;
        }

        frame = (JFrame) component;
        createGlassPane();
        frame.setGlassPane(glassPane);
        glassPane.setVisible(true);
        // Attach drop target after glass pane is in the hierarchy
        SwingUtilities.invokeLater(this::attachDropTarget);
    }

    private void installChildren(Component component) {
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                new DropTarget(child, this);
                installChildren(child);
            }
        }
    }

    // ---------- glass pane & overlay ----------
    private javax.swing.Timer hideTimer = null;

    private void createGlassPane() {
        glassPane = new JPanel(new BorderLayout());
        glassPane.setOpaque(false);

        overlayPanel = new DragOverlayPanel();
        overlayPanel.setVisible(false);
        glassPane.add(overlayPanel, BorderLayout.CENTER);
    }

    private void attachDropTarget() {
        // Must be called after glassPane is part of the visible hierarchy
        new DropTarget(glassPane, DnDConstants.ACTION_COPY,
                new DropTargetListener() {
                    public void dragEnter(DropTargetDragEvent dtde) {
                        showOverlay();
                    }
                    public void dragExit(DropTargetEvent dte) {
                        scheduleHide();
                    }
                    public void dragOver(DropTargetDragEvent dtde) { }
                    public void drop(DropTargetDropEvent dtde) {
                        cancelHide();
                        handleGlassDrop(dtde);
                    }
                    public void dropActionChanged(DropTargetDragEvent dtde) { }
                }, true);
    }

    private void showOverlay() {
        cancelHide();
        if (overlayPanel != null && !overlayPanel.isVisible()) {
            overlayPanel.setVisible(true);
            glassPane.revalidate();
            glassPane.repaint();
        }
    }

    private void scheduleHide() {
        if (hideTimer != null && hideTimer.isRunning()) {
            hideTimer.restart();
        } else {
            hideTimer = new javax.swing.Timer(150, e -> {
                if (overlayPanel != null) {
                    overlayPanel.setVisible(false);
                    glassPane.revalidate();
                    glassPane.repaint();
                }
            });
            hideTimer.setRepeats(false);
            hideTimer.start();
        }
    }

    private void cancelHide() {
        if (hideTimer != null) {
            hideTimer.stop();
        }
    }

    private void hideNow() {
        cancelHide();
        if (overlayPanel != null && overlayPanel.isVisible()) {
            overlayPanel.setVisible(false);
            glassPane.revalidate();
            glassPane.repaint();
        }
    }

    // Keep existing methods that now become unused – we can remove them in a later cleanup
    // but for safety we comment them out:
    // private void showGlass() { ... }
    // private void hideGlass() { ... }

    private void handleGlassDrop(DropTargetDropEvent dtde) {
        try {
            dtde.acceptDrop(DnDConstants.ACTION_COPY);
            Object data = dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
            if (!(data instanceof List<?> list)) return;

            List<File> collected = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof File file) {
                    collectFiles(file, collected);
                }
            }
            warnAboutRejected();
            if (collected.isEmpty()) return;

            // Determine which half was hit (dropPoint is relative to glassPane)
            Point dropPoint = dtde.getLocation();
            Point panelPoint = SwingUtilities.convertPoint(glassPane, dropPoint, overlayPanel);
            boolean rightHalf = panelPoint.x >= overlayPanel.getWidth() / 2.0;

            // Always add the files
            batchConsumer.accept(collected);

            // If dropped on the right half, copy file CONTENTS to clipboard
            if (rightHalf) {
                StringBuilder sb = new StringBuilder();
                for (File f : collected) {
                    try {
                        String content = Files.readString(f.toPath());
                        sb.append(wv.codeclip.config.FileMarker.header(f.getName()));
                        sb.append(content).append("\n\n");
                    } catch (IOException e) {
                        // silently skip unreadable files
                    }
                }
                if (sb.length() > 0) {
                    Toolkit.getDefaultToolkit()
                           .getSystemClipboard()
                           .setContents(new StringSelection(sb.toString()), null);
                    wv.codeclip.ui.ClipboardFlash.flash();
                }
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            hideNow();
        }
    }

private void collectFiles(File file, List<File> out) {
        if (file.isDirectory()) {
            final java.util.Set<String> skipDirs = java.util.Set.of(
                    ".git", ".svn", ".hg", "node_modules", ".idea", ".gradle",
                    ".vscode", "__pycache__", "target", "build", "out");
            final java.nio.file.Path rootPath = file.toPath();
            try {
                Files.walkFileTree(rootPath, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    @Override
                    public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir,
                            java.nio.file.attribute.BasicFileAttributes attrs) {
                        if (!dir.equals(rootPath) && dir.getFileName() != null
                                && (skipDirs.contains(dir.getFileName().toString().toLowerCase())
                                    || (ignoreDotFiles && dir.getFileName().toString().startsWith(".")))) {
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFile(java.nio.file.Path p,
                            java.nio.file.attribute.BasicFileAttributes attrs) {
                        File f = p.toFile();
                        if (attrs.isRegularFile() && mode.accepts(f.getName())
                                && !(ignoreDotFiles && f.getName().startsWith("."))) {
                            out.add(f);
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path p,
                            IOException exc) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (mode.accepts(file.getName())) {
            out.add(file);
        } else {
            // Explicitly dropped file that this mode doesn't accept: remember it so we can warn.
            rejectedThisDrop.add(file);
        }
    }

// ---------- inner overlay panel ----------

    private static class DragOverlayPanel extends JPanel {
        DragOverlayPanel() {
            setLayout(new GridLayout(1, 2, 10, 0));
            setOpaque(false);

            JPanel left = createHalf("Drag here to\nAdd and Enable",
                    new Color(30, 136, 229, 180), Color.WHITE);
            JPanel right = createHalf("Drag here to\nCopy, Add and Enable",
                    new Color(67, 160, 71, 180), Color.WHITE);

            add(left);
            add(right);
        }

        private JPanel createHalf(String text, Color bg, Color fg) {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBackground(bg);
            JLabel label = new JLabel(text);
            label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            label.setForeground(fg);
            label.setHorizontalAlignment(SwingConstants.CENTER);
            panel.add(label);
            return panel;
        }
    }

private void warnAboutRejected() {
        if (rejectedThisDrop.isEmpty()) return;
        List<File> rejected = new ArrayList<>(rejectedThisDrop);
        rejectedThisDrop.clear();
        StringBuilder sb = new StringBuilder();
        sb.append(rejected.size()).append(" dropped file(s) were NOT added because they don't belong to the current ")
          .append(mode).append(" mode:\n\n");
        int shown = Math.min(rejected.size(), 8);
        for (int i = 0; i < shown; i++) {
            sb.append("  • ").append(rejected.get(i).getName()).append("\n");
        }
        if (rejected.size() > shown) {
            sb.append("  … and ").append(rejected.size() - shown).append(" more\n");
        }
        sb.append("\nSwitch the language (Settings → Language…) if you want to add them.");
        String msg = sb.toString();
        SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(
                frame, msg, "Files Not Added", javax.swing.JOptionPane.WARNING_MESSAGE));
    }

public void setIgnoreDotFiles(boolean ignoreDotFiles) {
        this.ignoreDotFiles = ignoreDotFiles;
    }

}
