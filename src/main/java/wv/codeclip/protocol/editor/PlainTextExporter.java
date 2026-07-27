package wv.codeclip.protocol.editor;

import wv.codeclip.protocol.model.ProtocolFile;
import wv.codeclip.protocol.model.ProtocolEntry;

/**
 * Converts a protocol file into plain, markup-free text suitable for handing
 * to a different AI or person who has no knowledge of the .prtcl format.
 * No !id, !locked, or block syntax — just readable labeled sections.
 */
public final class PlainTextExporter {

    public String export(ProtocolFile file) {
        StringBuilder sb = new StringBuilder();
        sb.append("Protocol file: ").append(file.getFileName()).append("\n");
        sb.append("=".repeat(40)).append("\n\n");

        int number = 1;
        for (ProtocolEntry entry : file.getEntries()) {
            sb.append(number).append(". ").append(entry.getId()).append("\n");
            sb.append("-".repeat(entry.getId().length() + 4)).append("\n");
            for (String line : entry.getContentLines()) {
                sb.append(line).append("\n");
            }
            sb.append("\n");
            number++;
        }

        return sb.toString();
    }

    /** Exports every file in the library as one combined plain-text document. */
    public String exportAll(Iterable<ProtocolFile> files) {
        StringBuilder sb = new StringBuilder();
        for (ProtocolFile file : files) {
            sb.append(export(file));
            sb.append("\n");
        }
        return sb.toString();
    }
}