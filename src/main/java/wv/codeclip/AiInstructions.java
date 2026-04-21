package wv.codeclip;

public final class AiInstructions {

    private AiInstructions() {}

    public static final String TEXT =
        """
        ================================================================
        CODECLIP PATCH INSTRUCTIONS
        ================================================================

        When making changes to existing code, produce a @@PATCH block.
        This is the preferred method for targeted changes. As an
        alternative, you can send the entire updated class as plain code.

        ----------------------------------------------------------------
        PATCH FORMAT
        ----------------------------------------------------------------

        @@PATCH

        @@FILE: ExactFileName.java
        @@FIND:
        <exact lines to find in the file>
        @@REPLACE:
        <replacement lines>

        @@FILE: AnotherFile.java
        @@METHOD: methodName
        @@REPLACE:
        <entire new method including signature and braces>

        @@END

        ----------------------------------------------------------------
        DIRECTIVES
        ----------------------------------------------------------------

        @@FILE:
          The exact filename including .java extension.
          All changes below apply to this file until the next @@FILE:.

        @@FIND: / @@REPLACE:
          Use for small targeted changes.
          @@FIND must match exactly once in the file.
          If it could match multiple places, include more surrounding
          lines until it is unique.
          Indentation must match the source exactly.

        @@METHOD: / @@REPLACE:
          Use to replace an entire method.
          The method name must be unique in the file.
          If the method is overloaded, use @@FIND/@@REPLACE instead,
          including the full signature line in the @@FIND block.
          @@REPLACE must contain the complete new method including
          its signature, opening brace, body, and closing brace.

        ----------------------------------------------------------------
        RULES
        ----------------------------------------------------------------

        - One @@PATCH block per response. All changes go inside it.
        - Do not put explanations or prose inside the @@PATCH block.
          Put commentary before @@PATCH or after @@END.
        - Never use placeholders like // ... or // existing code.
        - Every @@PATCH block must end with @@END.

        ================================================================
        """;
}