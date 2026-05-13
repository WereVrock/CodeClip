package wv.codeclip.config;

public final class MetaInstructions {
    private MetaInstructions() {}
    public static final String TEXT =
        """
        ================================================================
        CODECLIP META INSTRUCTIONS — Escape & Ignore Directives
        ================================================================

        CodeClip's Smart Paste scans your entire message for:
          - @@PATCH ... @@END blocks
          - ```java ... ``` fenced class blocks

        To prevent Smart Paste from processing certain blocks, use the
        following escape mechanisms:

        ----------------------------------------------------------------
        ESCAPE SINGLE LINES:  !
        ----------------------------------------------------------------
        Prefix any line with ! to escape it. Smart Paste will treat it
        as a comment and ignore it.

        Example:
          !@@PATCH
          !@@FILE: Foo.java
          !... this entire block will be ignored ...

        This is useful when you are *explaining* a patch format without
        actually wanting it to be applied.

        ----------------------------------------------------------------
        IGNORE ENTIRE BLOCKS:  @@IGNORE ... @@IGNOREEND
        ----------------------------------------------------------------
        Wrap any section of your message with @@IGNORE and @@IGNOREEND
        to have Smart Paste skip it entirely. Everything between these
        markers is stripped before processing.

        Example:
          @@IGNORE
          Here is a draft patch that I don't want applied yet:
          @@PATCH
          @@FILE: Test.java
          @@FIND: old
          @@REPLACE: new
          @@END
          @@IGNOREEND

          The real patch follows below:
          @@PATCH
          ...

        ----------------------------------------------------------------
        RULES SUMMARY
        ----------------------------------------------------------------

        - ! escapes a single line (must be the first character)
        - @@IGNORE ... @@IGNOREEND escapes a multi-line block
        - Both work for @@PATCH blocks AND ```java fenced classes
        - Use these when discussing patches or showing examples that
          should NOT be applied by Smart Paste

        ================================================================
        """;
}