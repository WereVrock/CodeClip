package wv.codeclip.protocol.parser;

import java.util.*;
import java.util.regex.*;
import wv.codeclip.protocol.model.ProtocolEntry;
import wv.codeclip.protocol.model.ProtocolFile;

/**
 * Parses a .prtcl file's raw text into a ProtocolFile.
 * Format:
 *   !locked                (optional, must be the very first line if present)
 *   (optional preamble lines)
 *   !id foo
 *   ...content...
 *   (blank line = block delimiter)
 *   !id bar
 *   ...content...
 */
public final class ProtocolFileParser {

    private static final Pattern ID_LINE = Pattern.compile("^!id\\s+([a-z][a-z0-9_-]*)\\s*$");
    private static final Pattern LOCKED_LINE = Pattern.compile("^!locked\\s*$");

    public ProtocolFile parse(String fileName, String content) {
        String[] rawLines = content.split("\n", -1);
        List<String> preamble = new ArrayList<>();
        List<ProtocolEntry> entries = new ArrayList<>();

        boolean locked = false;
        int startIdx = 0;

        if (rawLines.length > 0 && LOCKED_LINE.matcher(rawLines[0].trim()).matches()) {
            locked = true;
            startIdx = 1;
        }

        String currentId = null;
        List<String> currentLines = new ArrayList<>();
        int entryIndex = 0;
        boolean seenFirstId = false;

        for (int i = startIdx; i < rawLines.length; i++) {
            String line = rawLines[i];
            Matcher m = ID_LINE.matcher(line);
            if (m.matches()) {
                if (currentId != null) {
                    trimTrailingBlank(currentLines);
                    entries.add(new ProtocolEntry(currentId, currentLines, entryIndex++));
                }
                currentId = m.group(1);
                currentLines = new ArrayList<>();
                seenFirstId = true;
            } else {
                if (!seenFirstId) {
                    preamble.add(line);
                } else {
                    currentLines.add(line);
                }
            }
        }
        if (currentId != null) {
            trimTrailingBlank(currentLines);
            entries.add(new ProtocolEntry(currentId, currentLines, entryIndex));
        }

        trimTrailingBlank(preamble);

        return new ProtocolFile(fileName, locked, preamble, entries);
    }

    private void trimTrailingBlank(List<String> lines) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
    }
}