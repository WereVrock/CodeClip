package wv.codeclip.protocol.ui;

import wv.codeclip.protocol.library.ProtocolLibrary;
import wv.codeclip.protocol.model.ProtocolFile;
import wv.codeclip.protocol.model.ProtocolEntry;
import java.util.*;

/**
 * Scans every .prtcl file on demand and returns which file names match a
 * search term, based on which of the three checkboxes (filename / id /
 * content) are enabled. Files that fail to load are skipped rather than
 * blowing up the search.
 */
public final class SearchIndex {

    private final ProtocolLibrary library;

    public SearchIndex(ProtocolLibrary library) {
        this.library = library;
    }

    public List<String> search(String term, SearchOptions options) {
        List<String> allFiles = library.listFileNames();

        if (term == null || term.isBlank() || !options.anySelected()) {
            return allFiles;
        }

        String needle = term.trim().toLowerCase();
        List<String> matches = new ArrayList<>();

        for (String fileName : allFiles) {
            if (options.matchFileName && fileName.toLowerCase().contains(needle)) {
                matches.add(fileName);
                continue;
            }

            StringBuilder err = new StringBuilder();
            ProtocolFile file = library.loadSafely(fileName, err);
            if (err.length() > 0) continue; // skip unreadable files silently in search

            boolean matched = false;
            for (ProtocolEntry entry : file.getEntries()) {
                if (options.matchId && entry.getId().toLowerCase().contains(needle)) {
                    matched = true;
                    break;
                }
                if (options.matchContent) {
                    for (String line : entry.getContentLines()) {
                        if (line.toLowerCase().contains(needle)) {
                            matched = true;
                            break;
                        }
                    }
                }
                if (matched) break;
            }

            if (matched) matches.add(fileName);
        }

        return matches;
    }
}