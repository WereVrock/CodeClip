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
        - There is no fuzzy or whitespace-tolerant fallback.
          If @@FIND doesn't match exactly once, the patch fails outright.
        - When in doubt, send the whole file with #@FileStart/#@FileEnd
          instead of a patch.

        ----------------------------------------------------------------
        STRUCTURAL PATCH FORMAT (for replacing/inserting named units)
        ----------------------------------------------------------------
        This format lets you target entire structural units (HTML elements
        by id, CSS rules by selector, JavaScript functions by name) without
        having to match surrounding code exactly. Use it inside a @@PATCH
        block instead of @@FIND/@@REPLACE.

        Supported directives:
          - @@METHOD: <name>          – the target unit to replace
          - @@REPLACE:                – the new content for that unit
          - @@AFTER_METHOD: <name>    – anchor unit after which to insert
          - @@INSERT_METHOD: <name>   – optional name for the inserted unit
          - @@INSERT_METHOD:          – standalone insertion (no anchor)

        Syntax for REPLACE (overwrites an existing unit):
          @@PATCH
          @@TITLE: Replace a function/element/rule
          @@FILE: path/to/file.ext
          @@METHOD: hello
          @@REPLACE:
          function hello() {
              console.log('New implementation');
          }
          @@END

        Syntax for INSERT AFTER (inserts new unit after named anchor):
          @@PATCH
          @@TITLE: Insert after goodbye
          @@FILE: path/to/file.ext
          @@AFTER_METHOD: goodbye
          @@INSERT_METHOD:
          function afterGoodbye() {
              console.log('Inserted after goodbye');
          }
          @@END

        Syntax for STANDALONE INSERT (inserts at default location):
          @@PATCH
          @@TITLE: Insert at default location
          @@FILE: path/to/file.ext
          @@INSERT_METHOD: defaultInsert
          @@INSERT_METHOD:
          function defaultInsert() {
              console.log('At default spot');
          }
          @@END

        RULES for structural patches:
        - The name must match exactly (case‑sensitive) the id, selector, or
          function name as it appears in the source.
        - For replacement, if multiple units share the same name, the patch
          fails (must be unique within the file).
        - Insertion skips if a unit with the same name and identical content
          already exists (duplicate protection).
        - Content in @@REPLACE: and @@INSERT_METHOD: is used verbatim (no
          extra formatting).
        - Only one structural operation per @@PATCH block.
        - File types supported: .html, .htm, .css, .js, .mjs.

        ================================================================
        """;
    
}