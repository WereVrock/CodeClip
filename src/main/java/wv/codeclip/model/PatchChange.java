package wv.codeclip.model;

/**
 * Represents a single change instruction parsed from a @@PATCH block.
 */
public sealed interface PatchChange permits PatchChange.FindReplace, PatchChange.MethodReplace {

    String fileName();

    record FindReplace(String fileName, String find, String replace) implements PatchChange {}

    record MethodReplace(String fileName, String methodName, String replace) implements PatchChange {}
}