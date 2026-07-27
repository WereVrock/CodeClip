package wv.codeclip.protocol.model;

import java.util.*;

/**
 * Full parsed representation of one .prtcl file: an optional file-level lock
 * flag, any preamble text before the first !id block, and an ordered list of entries.
 */
public final class ProtocolFile {
    private final String fileName; // e.g. "auth.prtcl", not a full path
    private boolean locked;
    private final List<String> preambleLines;
    private final List<ProtocolEntry> entries;

    public ProtocolFile(String fileName, boolean locked, List<String> preambleLines, List<ProtocolEntry> entries) {
        this.fileName = fileName;
        this.locked = locked;
        this.preambleLines = new ArrayList<>(preambleLines);
        this.entries = new ArrayList<>(entries);
    }

    public String getFileName() { return fileName; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
    public List<String> getPreambleLines() { return preambleLines; }
    public List<ProtocolEntry> getEntries() { return entries; }

    public Optional<ProtocolEntry> findById(String id) {
        return entries.stream().filter(e -> e.getId().equals(id)).findFirst();
    }

    public boolean containsId(String id) {
        return findById(id).isPresent();
    }

    public int indexOf(String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getId().equals(id)) return i;
        }
        return -1;
    }

    public ProtocolFile deepCopy() {
        List<ProtocolEntry> copied = new ArrayList<>();
        for (ProtocolEntry e : entries) copied.add(e.copy());
        return new ProtocolFile(fileName, locked, preambleLines, copied);
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        if (locked) sb.append("!locked\n");
        for (String line : preambleLines) sb.append(line).append('\n');
        for (ProtocolEntry e : entries) {
            sb.append("!id ").append(e.getId()).append('\n');
            for (String line : e.getContentLines()) sb.append(line).append('\n');
            sb.append('\n'); // blank line delimiter between blocks
        }
        return sb.toString();
    }
}