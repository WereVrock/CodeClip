package wv.codeclip;

import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

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
                    if (o instanceof File file && file.getName().endsWith(".java")) {
                        fileConsumer.accept(file);
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
