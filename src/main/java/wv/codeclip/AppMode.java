package wv.codeclip;

public enum AppMode {

    JAVA(
        new String[]{".java"},
        wv.codeclip.config.AiInstructions.TEXT
    ),
    GODOT(
        new String[]{".gd"},
        wv.codeclip.godot.GodotInstructions.TEXT
    ),
    HTML(
        wv.codeclip.html.HtmlExtensions.EXTENSIONS,
        wv.codeclip.html.HtmlInstructions.TEXT
    );

    private final String[] extensions;
    private final String instructions;

    AppMode(String[] extensions, String instructions) {
        this.extensions = extensions;
        this.instructions = instructions;
    }

    public String[] getExtensions() { return extensions; }
    public String getInstructions() { return instructions; }

    public boolean accepts(String filename) {
        String lower = filename.toLowerCase();
        for (String ext : extensions) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }
}