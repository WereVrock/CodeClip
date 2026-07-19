package wv.codeclip.io;

import java.awt.*;
import java.io.*;
import java.util.Properties;

public class SettingsManager {

    private final File propFile =
            new File(System.getProperty("user.home"), "codeclip.properties");
    private final Properties properties = new Properties();

    public SettingsManager() {
        loadProperties();
    }

    public void loadProperties() {
        if (propFile.exists()) {
            try (FileReader reader = new FileReader(propFile)) {
                properties.load(reader);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void saveProperties() {
        try (FileWriter writer = new FileWriter(propFile)) {
            properties.store(writer, "CodeClip Settings");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveFrameBounds(Rectangle bounds) {
        properties.setProperty("frame.x", String.valueOf(bounds.x));
        properties.setProperty("frame.y", String.valueOf(bounds.y));
        properties.setProperty("frame.width", String.valueOf(bounds.width));
        properties.setProperty("frame.height", String.valueOf(bounds.height));
    }

    public Rectangle loadFrameBounds() {
        int x      = Integer.parseInt(properties.getProperty("frame.x", "100"));
        int y      = Integer.parseInt(properties.getProperty("frame.y", "100"));
        int width  = Integer.parseInt(properties.getProperty("frame.width", "475"));
        int height = Integer.parseInt(properties.getProperty("frame.height", "300"));
        return new Rectangle(x, y, width, height);
    }

    public void saveNotes(String notes) {
        properties.setProperty("notes", notes);
    }

    public String loadNotes() {
        return properties.getProperty("notes", "");
    }

    public void saveIncludeInstructions(boolean value) {
        properties.setProperty("include.instructions", String.valueOf(value));
    }

    public boolean loadIncludeInstructions() {
        return Boolean.parseBoolean(properties.getProperty("include.instructions", "false"));
    }

    public void saveClassPaths(String[] paths) {
        properties.setProperty("classes", String.join("|", paths));
    }

    public String[] loadClassPaths() {
        String files = properties.getProperty("classes", "");
        return files.isEmpty() ? new String[0] : files.split("\\|");
    }

    public void saveDividerPosition(int position) {
        properties.setProperty("divider.position", String.valueOf(position));
    }

    public int loadDividerPosition() {
        return Integer.parseInt(properties.getProperty("divider.position", "0"));
    }

    public void saveSmartPaste(boolean value) {
        properties.setProperty("smart.paste", String.valueOf(value));
    }

    public boolean loadSmartPaste() {
        return Boolean.parseBoolean(properties.getProperty("smart.paste", "false"));
    }

    public void saveSmartPasteAllowClasses(boolean value) {
        properties.setProperty("smart.paste.allow.classes", String.valueOf(value));
    }

    public boolean loadSmartPasteAllowClasses() {
        return Boolean.parseBoolean(properties.getProperty("smart.paste.allow.classes", "true"));
    }

    public void saveSmartPasteSkipCreateConfirm(boolean value) {
        properties.setProperty("smart.paste.skip.create", String.valueOf(value));
    }

    public boolean loadSmartPasteSkipCreateConfirm() {
        return Boolean.parseBoolean(properties.getProperty("smart.paste.skip.create", "false"));
    }

    public void saveSmartPasteSkipOverwriteConfirm(boolean value) {
        properties.setProperty("smart.paste.skip.overwrite", String.valueOf(value));
    }

    public boolean loadSmartPasteSkipOverwriteConfirm() {
        return Boolean.parseBoolean(properties.getProperty("smart.paste.skip.overwrite", "false"));
    }

    public static void main(String[] args) {
        SettingsManager settings = new SettingsManager();
        Rectangle defaultBounds = new Rectangle(100, 100, 475, 300);
        settings.saveFrameBounds(defaultBounds);
        settings.saveProperties();
        System.out.println("Frame position reset to default.");
    }

public String loadMode() {
    return properties.getProperty("mode", "JAVA");
}

public void saveMode(String mode) {
    properties.setProperty("mode", mode);
}

public String loadGodotDirectory() {
    return properties.getProperty("godot.directory", "");
}

public void saveGodotDirectory(String path) {
    properties.setProperty("godot.directory", path);
}

public String loadHtmlDirectory() {
    return properties.getProperty("html.directory", "");
}

public void saveHtmlDirectory(String path) {
    properties.setProperty("html.directory", path);
}

public String loadGenericDirectory() {
    return properties.getProperty("generic.directory", "");
}

public void saveGenericDirectory(String path) {
    properties.setProperty("generic.directory", path);
}

public boolean loadAutoReplaceOnInsertConflict() {
    return Boolean.parseBoolean(properties.getProperty("autoReplaceOnInsertConflict", "false"));
}

public void saveAutoReplaceOnInsertConflict(boolean value) {
    properties.setProperty("autoReplaceOnInsertConflict", String.valueOf(value));
}

public String loadExternalEditorPath() {
    return properties.getProperty("external.editor.path", "");
}

public void saveExternalEditorPath(String path) {
    properties.setProperty("external.editor.path", path == null ? "" : path);
}

}