package wv.codeclip.html;

public final class HtmlInstructions {

    private HtmlInstructions() {}
    public static final String TEXT =
        """
        ================================================================
        HTML / WEB PROJECT INSTRUCTIONS
        ================================================================
        Project files live under one root directory (set via the directory
        button). Use relative paths so files land in the right folder —
        missing folders are created automatically.

        ----------------------------------------------------------------
        NEW OR FULL FILE FORMAT
        ----------------------------------------------------------------
        #@FileStart: relative/path/to/file.ext
        <full file content>
        #@FileEnd

        Examples:
          #@FileStart: index.html
          <!DOCTYPE html>
          ...
          #@FileEnd

          #@FileStart: css/style.css
          body { margin: 0; }
          #@FileEnd

          #@FileStart: js/app.js
          console.log("hi");
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
        same @@PATCH block format as Java mode:

        @@PATCH
        @@TITLE: Short summary of what this patch does

        @@FILE: relative/path/to/file.ext
        @@FIND:
        <exact lines to find — must match exactly once, byte-for-byte>
        @@REPLACE:
        <replacement lines>

        @@END

        IMPORTANT — HTML mode patches are STRICT MATCH ONLY:
        - @@FIND must match the file's content exactly: same whitespace,
          same line endings, same indentation, same casing.
        - There is no fuzzy or whitespace-tolerant fallback like Java mode.
          If @@FIND doesn't match exactly once, the patch fails outright.
        - When in doubt, send the whole file with #@FileStart/#@FileEnd
          instead of a patch.
        
        ================================================================
        """;

    
}