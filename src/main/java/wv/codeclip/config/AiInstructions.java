package wv.codeclip.config;

public final class AiInstructions {
    private AiInstructions() {}
    public static final String TEXT =
        """
        ================================================================
        CODECLIP PATCH INSTRUCTIONS
        ================================================================
        When making surgical changes to existing code, produce a @@PATCH block inside a code block.
        This is the preferred method for targeted changes. As an alternative, send the entire
        updated class as plain code. All patching should be in a single block unless strictly necessary.
        Use method replacement if changing more than half a method.

        Prefer method replacement over exact code replacement unless making small changes.

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

        @@FILE: AnotherFile.java
        @@METHOD: overloadedMethod(String, int)
        @@REPLACE:
        <entire new method including signature and braces>

        @@FILE: AnotherFile.java
        @@AFTER_METHOD: existingMethodName
        @@INSERT_METHOD:
        <entire new method including signature and braces>

        @@FILE: AnotherFile.java
        @@INSERT_METHOD:
        <entire new method including signature and braces>

        @@END

        ----------------------------------------------------------------
        DIRECTIVES
        ----------------------------------------------------------------

        @@TITLE: (optional)
          A short human-readable label for this patch.
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
          Write @@METHOD: with no name when the method name is unique in the file —
          the name is parsed automatically from the signature in @@REPLACE.
          Multiple methods can be replaced in a single @@METHOD: block by placing
          them one after another in @@REPLACE.

          OVERLOADED METHODS: If a method name appears more than once in the file
          (different parameter lists), you MUST specify the parameter types:
            @@METHOD: methodName(Type1, Type2)
          Only simple type names are needed — no generics, no variable names, no packages.
          Examples:
            @@METHOD: process(String, int)
            @@METHOD: handle(List, boolean)
          Only one method per overloaded @@METHOD: block.
          If you receive an ambiguity error, it will list the exact signatures to use.

          @@METHOD: can only replace existing methods. To add a new method use @@INSERT_METHOD:.

        @@AFTER_METHOD: / @@INSERT_METHOD:
          Use to add a new method after an existing one.
          @@AFTER_METHOD: takes the name of the existing anchor method.
          @@INSERT_METHOD: must follow immediately after @@AFTER_METHOD:.
          The block must contain a complete method including signature,
          opening brace, body, and closing brace.

        @@INSERT_METHOD: (standalone)
          Inserts a new method just before the final closing brace of the class.
          No @@AFTER_METHOD: needed.

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

        When sending a complete class (new or updated), wrap it in a java code fence:

```java
        package com.example;
        public class MyClass {
            ...
        }
```

        Use this when sending full class replacements alongside patches in the same message.
        Smart Paste extracts and applies them in document order together with any @@PATCH blocks.
        ================================================================
        """;
}