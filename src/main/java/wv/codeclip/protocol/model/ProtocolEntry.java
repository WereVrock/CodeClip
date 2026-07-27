package wv.codeclip.protocol.model;

import java.util.ArrayList;
import java.util.List;

public final class ProtocolEntry {
    private String id;
    private List<String> contentLines;
    private final int originalIndex;

    public ProtocolEntry(String id, List<String> contentLines, int originalIndex) {
        this.id = id;
        this.contentLines = new ArrayList<>(contentLines);
        this.originalIndex = originalIndex;
    }

    public ProtocolEntry copy() {
        return new ProtocolEntry(id, new ArrayList<>(contentLines), originalIndex);
    }

    public String getId() { return id; }
    public List<String> getContentLines() { return contentLines; }
    public void setContentLines(List<String> lines) { this.contentLines = new ArrayList<>(lines); }
    public int getOriginalIndex() { return originalIndex; }
    public boolean isNewlyCreated() { return originalIndex < 0; }

    @Override
    public String toString() {
        return "Entry[" + id + ", lines=" + contentLines.size() + "]";
    }
}