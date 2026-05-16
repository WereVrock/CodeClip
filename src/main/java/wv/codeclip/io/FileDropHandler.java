package wv.codeclip.io;

import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class FileDropHandler extends DropTargetAdapter {

    private final java.util.function.Consumer<List<File>> batchConsumer;

    public FileDropHandler(Consumer<File> fileConsumer) {
        this.batchConsumer = files -> files.forEach(fileConsumer);
    }

    public FileDropHandler(java.util.function.Consumer<List<File>> batchConsumer, boolean batch) {
        this.batchConsumer = batchConsumer;
    }

    public void install(Component component) {
        new DropTarget(component, this);
        installChildren(component);
    }

    private void installChildren(Component component) {
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                new DropTarget(child, this);
                installChildren(child);
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
                List<File> collected = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof File file) {
                        collectFiles(file, collected);
                    }
                }
                if (!collected.isEmpty()) {
                    batchConsumer.accept(collected);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void collectFiles(File file, List<File> out) {
        if (file.isDirectory()) {
            try (Stream<java.nio.file.Path> paths = Files.walk(file.toPath())) {
                paths.filter(p -> p.toString().endsWith(".java"))
                     .map(java.nio.file.Path::toFile)
                     .forEach(out::add);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (file.getName().endsWith(".java")) {
            out.add(file);
        }
    }
}
