// GenericCodeMapAnalyzer.java
package wv.codeclip.codemap;

import java.util.List;

/**
 * Fallback analyzer used when no more specific analyzer supports a file's
 * extension (e.g. .json, .yaml, .py, .md, or any file type in Generic mode
 * CodeClip doesn't have a dedicated analyzer for yet). Reports basic
 * line/character stats only — never claims exports or imports it can't
 * actually detect. This is what keeps CodeMapBuilder mode-agnostic: any
 * future AppMode's unrecognized file types degrade to this instead of
 * requiring a CodeMapBuilder change.
 */
public final class GenericCodeMapAnalyzer implements CodeMapAnalyzer {

    @Override
    public boolean supports(String fileName) {
        return true; // catch-all — always last in CodeMapBuilder's analyzer list
    }

    @Override
    public FileSummary analyze(String fileName, String code) {
        String safe = code != null ? code : "";
        int lines = safe.isEmpty() ? 0 : (int) safe.lines().count();
        String summary = "Unrecognized file type — " + lines + " line(s), "
                + safe.length() + " character(s)";
        return new FileSummary(fileName, List.of(), List.of(), summary);
    }
}