package wv.codeclip.model;

/**
 * Represents a single change instruction parsed from a @@PATCH block.
 */
public sealed interface PatchChange permits PatchChange.FindReplace, PatchChange.MethodReplace,PatchChange.InsertMethod {

    String fileName();

    record FindReplace(String fileName, String find, String replace) implements PatchChange {}

    record MethodReplace(String fileName, String methodName, String paramTypes, String replace) implements PatchChange {}

    /**
     * afterMethod is the name of the method to insert after.
     * If null, the code is inserted before the final closing brace of the class.
     */
    record InsertMethod(String fileName, String afterMethod, String code) implements PatchChange {}
}