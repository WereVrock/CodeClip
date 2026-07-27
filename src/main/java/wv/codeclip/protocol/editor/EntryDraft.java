package wv.codeclip.protocol.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * A mutable, in-progress version of one !id block being edited in the
 * structured editor. Not written to disk until the user saves.
 */
public final class EntryDraft {
    private String id;
    private String content; // multi-line content as one editable string
    private final boolean isNew;
    private EntryValidationState validationState = EntryValidationState.ok();

    public EntryDraft(String id, String content, boolean isNew) {
        this.id = id;
        this.content = content;
        this.isNew = isNew;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public boolean isNew() { return isNew; }
    public EntryValidationState getValidationState() { return validationState; }
    public void setValidationState(EntryValidationState state) { this.validationState = state; }

    public List<String> contentAsLines() {
        if (content == null || content.isEmpty()) return new ArrayList<>();
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}