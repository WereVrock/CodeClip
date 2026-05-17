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
        to do otherwise. Use method replacement if you are gonna change more than half of it.

        prefer method replacement over exact code replacement unless you are doing small changes

        ----------------------------------------------------------------
        PATCH FORMAT
        ----------------------------------------------------------------

        @@PATCH
        @@TITLE: Short summary of what this patch does
        @@DESC: Optional one-line detail or reason

        @@FILE: ExactFileName.java
        @@FIND:
        <exact lines to find in the file>
        @@REPLACE:
        <replacement lines>

        @@FILE: AnotherFile.java
        @@METHOD:
        @@REPLACE:
        <entire new method including signature and braces>

        <another method if needed>

        @@END

        ----------------------------------------------------------------
        DIRECTIVES
        ----------------------------------------------------------------

        @@TITLE: (optional)
          A short human-readable label for this patch.
          Appears as a heading in the paste log.
          Place immediately after @@PATCH, before any @@FILE:.

        @@DESC: (optional)
          A single line of extra context or reasoning.
          Place after @@TITLE: (or after @@PATCH if no title).

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
          Use to replace one or more entire methods.
          Always write @@METHOD: with no name — the method name is
          parsed automatically from each method signature in @@REPLACE.
          Multiple methods can be replaced in a single @@METHOD: block
          by placing them one after another in @@REPLACE.
          NEVER put a name after @@METHOD: unless the method is
          overloaded — in that case the explicit name selects the
          correct overload, and only that one method can be in the block.
          @@REPLACE must contain complete methods including
          signatures, opening braces, bodies, and closing braces.
          @@METHOD: can only replace existing methods, not add new ones.
          To add a new method, use @@FIND/@@REPLACE to insert it
          after an existing anchor.

        ----------------------------------------------------------------
        RULES
        ----------------------------------------------------------------

        - One @@PATCH block per response. All changes go inside it.
        - @@TITLE: and @@DESC: are optional but encouraged for clarity.
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