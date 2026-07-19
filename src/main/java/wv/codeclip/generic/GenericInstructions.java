// ===== GenericInstructions.java =====
package wv.codeclip.generic;

public final class GenericInstructions {

    private GenericInstructions() {}

    public static final String TEXT =
        """
        ================================================================
        GENERIC PROJECT INSTRUCTIONS
        ================================================================
        Project files live under one root directory (set via the directory
        button). Use relative paths so files land in the right folder —
        missing folders are created automatically. Any file type is accepted.

        ----------------------------------------------------------------
        NEW OR FULL FILE FORMAT
        ----------------------------------------------------------------
        #@FileStart: relative/path/to/file.ext
        <full file content>
        #@FileEnd

        Examples:
          #@FileStart: config/settings.yaml
          key: value
          #@FileEnd

          #@FileStart: scripts/build.py
          print("hello")
          #@FileEnd

        RULES
        ----------------------------------------------------------------
        - Every file you send MUST be wrapped in #@FileStart / #@FileEnd.
        - #@FileStart: must be followed immediately by the relative path
          (including subfolders, using forward slashes) on the same line.
        - #@FileEnd must appear on its own line after the last line of code.
        - Multiple files can be sent in one message — wrap each one.
        - Folders in the path that don't exist yet are created automatically.
        - Content between markers must be pure file content only — no
          commentary or markdown fences inside the block.
        - When modifying an existing file with this format, always resend
          the entire file content, not just the changed part.

        ----------------------------------------------------------------
        SURGICAL PATCH FORMAT (existing files only)
        ----------------------------------------------------------------
        For small targeted edits to a file that already exists, use the
        same @@PATCH block format as other modes:

        @@PATCH
        @@TITLE: Short summary of what this patch does

        @@FILE: relative/path/to/file.ext
        @@FIND:
        <exact lines to find — must match exactly once, byte-for-byte>
        @@REPLACE:
        <replacement lines>

        @@END

        IMPORTANT — Generic mode patches are STRICT MATCH ONLY, with
        FUZZY FALLBACK:
        - @@FIND is first tried as an exact match: same whitespace,
          same line endings, same indentation, same casing.
        - If no exact match is found, a fuzzy match is attempted. Matches
          at or above 95% similarity are applied automatically and logged.
          Matches between the configured floor (default 30%) and 95% show
          a confirmation dialog with both the requested and matched text
          side by side before applying.
        - If no match at or above the floor is found, the patch fails.
        - When in doubt, send the whole file with #@FileStart/#@FileEnd
          instead of a patch.

        NOTE: Generic mode does NOT support @@METHOD:, @@AFTER_METHOD:,
        or @@INSERT_METHOD: structural targeting (no file-type-specific
        parsing is done). Use @@FIND/@@REPLACE for edits, or resend the
        whole file to add new content.

        ================================================================
        """;
}