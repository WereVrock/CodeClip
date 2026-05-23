package wv.codeclip.godot;

import java.io.File;

public class GodotSourceParser {

    /**
     * Returns the class name from a .gd file.
     * GDScript uses the filename as the implicit class name.
     */
    public String parseClassName(File file) {
        if (file == null) return null;
        String name = file.getName();
        if (name.endsWith(".gd")) {
            return name.substring(0, name.length() - 3);
        }
        return name;
    }
}