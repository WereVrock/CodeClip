package wv.codeclip;

import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class FileDropHandler extends DropTargetAdapter {

    private final Consumer<File> fileConsumer;

    public FileDropHandler(Consumer<File> fileConsumer) {
        this.fileConsumer = fileConsumer;
    }

    public void install(Component component) {
        new DropTarget(component, this);

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                install(child);
            }
        }
    }

    @Override
    public void drop(DropTargetDropEvent dtde) {
        try {
            dtde.acceptDrop(DnDConstants.ACTION_COPY);

            Object data = dtde.getTransferable()
                    .getTransferData(DataFlavor.javaFileListFlavor);

            if (data instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof File file) {
                        handleFileOrDirectory(file);
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void handleFileOrDirectory(File file) {
        if (file.isDirectory()) {
            scanDirectory(file);
        } else if (file.getName().endsWith(".java")) {
            fileConsumer.accept(file);
        }
    }

    private void scanDirectory(File dir) {
        try (Stream<java.nio.file.Path> paths = Files.walk(dir.toPath())) {
            paths
                .filter(p -> p.toString().endsWith(".java"))
                .map(java.nio.file.Path::toFile)
                .forEach(fileConsumer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
