package wv.codeclip.model;

/**
 * Represents a single change instruction parsed from a @@PATCH block.
 */
public sealed interface PatchChange permits PatchChange.FindReplace, PatchChange.MethodReplace,PatchChange.InsertMethod {

    String fileName();

    /**
     * A human-readable preview of the content this change would apply, used
     * in error messages so a "file not found"-style failure shows the actual
     * code/text that was being matched — not just a bare filename — making it
     * easy to tell a real filename mismatch apart from an AI response that's
     * just talking about code and happened to produce a @@PATCH-shaped block.
     */
    default String preview() {
        String raw = switch (this) {
            case FindReplace fr -> "@@FIND:\n" + fr.find();
            case MethodReplace mr -> "@@METHOD: " + mr.methodName() + "\n@@REPLACE:\n" + mr.replace();
            case InsertMethod im -> (im.afterMethod() != null ? "@@AFTER_METHOD: " + im.afterMethod() + "\n" : "")
                    + "@@INSERT_METHOD:\n" + im.code();
        };
        int maxLen = 600;
        if (raw.length() <= maxLen) return raw;
        return raw.substring(0, maxLen) + "\n... (truncated, " + (raw.length() - maxLen) + " more characters)";
    }

    record FindReplace(String fileName, String find, String replace) implements PatchChange {}

    record MethodReplace(String fileName, String methodName, String paramTypes, String replace) implements PatchChange {}

    /**
     * afterMethod is the name of the method to insert after.
     * If null, the code is inserted before the final closing brace of the class.
     */
    record InsertMethod(String fileName, String afterMethod, String code) implements PatchChange {}
}