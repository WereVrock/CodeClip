package wv.codeclip;

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
        int x = Integer.parseInt(props.getProperty("frame.x", "100"));
        int y = Integer.parseInt(props.getProperty("frame.y", "100"));
        int width = Integer.parseInt(props.getProperty("frame.width", "475"));
        int height = Integer.parseInt(props.getProperty("frame.height", "300"));
        return new Rectangle(x, y, width, height);
    }

    public void saveNotes(String notes) {
        props.setProperty("notes", notes);
    }

    public String loadNotes() {
        return props.getProperty("notes", "");
    }

    public void saveClassPaths(String[] paths) {
        props.setProperty("classes", String.join("|", paths));
    }

    public String[] loadClassPaths() {
        String files = props.getProperty("classes", "");
        return files.isEmpty() ? new String[0] : files.split("\\|");
    }
}
