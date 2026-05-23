package wv.codeclip.io;

import java.awt.*;
import java.io.*;
import java.util.Properties;

public class SettingsManager {

    private final File propFile =
            new File(System.getProperty("user.home"), "codeclip.properties");
    private final Properties props = new Properties();

    public SettingsManager() {
        loadProperties();
    }

    public void loadProperties() {
        if (propFile.exists()) {
            try (FileReader reader = new FileReader(propFile)) {
                props.load(reader);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void saveProperties() {
        try (FileWriter writer = new FileWriter(propFile)) {
            props.store(writer, "CodeClip Settings");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveFrameBounds(Rectangle bounds) {
        props.setProperty("frame.x", String.valueOf(bounds.x));
        props.setProperty("frame.y", String.valueOf(bounds.y));
        props.setProperty("frame.width", String.valueOf(bounds.width));
        props.setProperty("frame.height", String.valueOf(bounds.height));
    }

    public Rectangle loadFrameBounds() {
        int x      = Integer.parseInt(props.getProperty("frame.x", "100"));
        int y      = Integer.parseInt(props.getProperty("frame.y", "100"));
        int width  = Integer.parseInt(props.getProperty("frame.width", "475"));
        int height = Integer.parseInt(props.getProperty("frame.height", "300"));
        return new Rectangle(x, y, width, height);
    }

    public void saveNotes(String notes) {
        props.setProperty("notes", notes);
    }

    public String loadNotes() {
        return props.getProperty("notes", "");
    }

    public void saveIncludeInstructions(boolean value) {
        props.setProperty("include.instructions", String.valueOf(value));
    }

    public boolean loadIncludeInstructions() {
        return Boolean.parseBoolean(props.getProperty("include.instructions", "false"));
    }

    public void saveClassPaths(String[] paths) {
        props.setProperty("classes", String.join("|", paths));
    }

    public String[] loadClassPaths() {
        String files = props.getProperty("classes", "");
        return files.isEmpty() ? new String[0] : files.split("\\|");
    }

    public void saveDividerPosition(int position) {
        props.setProperty("divider.position", String.valueOf(position));
    }

    public int loadDividerPosition() {
        return Integer.parseInt(props.getProperty("divider.position", "0"));
    }

    public void saveSmartPaste(boolean value) {
        props.setProperty("smart.paste", String.valueOf(value));
    }

    public boolean loadSmartPaste() {
        return Boolean.parseBoolean(props.getProperty("smart.paste", "false"));
    }

    public void saveSmartPasteAllowClasses(boolean value) {
        props.setProperty("smart.paste.allow.classes", String.valueOf(value));
    }

    public boolean loadSmartPasteAllowClasses() {
        return Boolean.parseBoolean(props.getProperty("smart.paste.allow.classes", "true"));
    }

    public void saveSmartPasteSkipCreateConfirm(boolean value) {
        props.setProperty("smart.paste.skip.create", String.valueOf(value));
    }

    public boolean loadSmartPasteSkipCreateConfirm() {
        return Boolean.parseBoolean(props.getProperty("smart.paste.skip.create", "false"));
    }

    public void saveSmartPasteSkipOverwriteConfirm(boolean value) {
        props.setProperty("smart.paste.skip.overwrite", String.valueOf(value));
    }

    public boolean loadSmartPasteSkipOverwriteConfirm() {
        return Boolean.parseBoolean(props.getProperty("smart.paste.skip.overwrite", "false"));
    }

    public static void main(String[] args) {
        SettingsManager settings = new SettingsManager();
        Rectangle defaultBounds = new Rectangle(100, 100, 475, 300);
        settings.saveFrameBounds(defaultBounds);
        settings.saveProperties();
        System.out.println("Frame position reset to default.");
    }

public String loadMode() {
    return props.getProperty("mode", "JAVA");
}

public void saveMode(String mode) {
    props.setProperty("mode", mode);
}

public String loadGodotDirectory() {
    return props.getProperty("godot.directory", "");
}

public void saveGodotDirectory(String path) {
    props.setProperty("godot.directory", path);
}

}