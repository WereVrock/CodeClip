package wv.codeclip.godot;

public final class GodotInstructions {
    private GodotInstructions() {}
    public static final String TEXT =
        """
        ================================================================
        GODOT / GDSCRIPT INSTRUCTIONS
        ================================================================
        When sending GDScript files, wrap every script in file markers:

        #@FileStart: ExactFileName.gd
        extends Node

        func _ready():
            pass
        #@FileEnd

        RULES
        ----------------------------------------------------------------
        - Every script you send MUST be wrapped in #@FileStart / #@FileEnd.
        - #@FileStart: must be followed immediately by the exact filename
          including the .gd extension on the same line.
        - #@FileEnd must appear on its own line after the last line of code.
        - Multiple scripts can be sent in one message — wrap each one.
        - Do NOT omit these markers, even for small single-file changes.
       - Content between markers must be pure GDScript only — no commentary
          or markdown inside the block.

        MULTIPLE SCRIPTS EXAMPLE
        ----------------------------------------------------------------
        #@FileStart: GameManager.gd
        extends Node
        var score = 0

        func add_points(p):
            score += p
        #@FileEnd

        #@FileStart: PowerUp.gd
        extends Area2D
        var value = 50

        func _on_body_entered(body):
            if body.has_method("add_points"):
                body.add_points(value)
            queue_free()
        #@FileEnd

        WHEN MODIFYING EXISTING SCRIPTS
        ----------------------------------------------------------------
        Always resend the entire script, not just the changed part.
        Never send partial scripts or snippets — always the full file
        content between the markers.

        ================================================================
        """;
}