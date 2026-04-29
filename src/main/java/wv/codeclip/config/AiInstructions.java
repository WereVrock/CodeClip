package wv.codeclip.config;

public final class AiInstructions {

    private AiInstructions() {}

    public static final String TEXT =
        """
        ================================================================
        CODECLIP PATCH INSTRUCTIONS
        ================================================================

        When making surgical changes to existing code, produce a @@PATCH block. This should be in a code block.
        This is the preferred method for targeted changes. As an
        alternative, you can send the entire updated class as plain code. All the patching should be in a single block unless it is necessary
        to do otherwise. Use method replacement if it you are gonna change more than half of it.
        
        prefer method replacement over exact code replacement unless you are doing small changes

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

        ----------------------------------------------------------------
        WHOLE CLASS FORMAT
        ----------------------------------------------------------------

        When sending a complete class (new or updated), wrap it in a
        java code fence:

        ```java
        package com.example;
        public class MyClass {
            ...
        }
        ```

        Use this when sending full class replacements alongside patches
        in the same message. Smart Paste will extract and apply them
        in document order together with any @@PATCH blocks.

        ================================================================
        """;
}