// CssCodeMapAnalyzer.java
package wv.codeclip.codemap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codemap analyzer for .css files. "Exports" for CSS means the top-level
 * selectors it defines (classes and ids) — the styling surface other files
 * can rely on. "Imports" covers @import statements.
 */
public final class CssCodeMapAnalyzer implements CodeMapAnalyzer {

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "@import\\s+(?:url\\()?['\"]?([^'\")\\s;]+)['\"]?\\)?");
    private static final Pattern CLASS_SELECTOR = Pattern.compile("\\.([A-Za-z_][\\w-]*)");
    private static final Pattern ID_SELECTOR = Pattern.compile("#([A-Za-z_][\\w-]*)");

    private static final int MAX_LISTED_SELECTORS = 40;

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".css");
    }

    @Override
    public FileSummary analyze(String fileName, String code) {
        String safe = code != null ? code : "";
        String noComments = safe.replaceAll("(?s)/\\*.*?\\*/", "");

        List<String> imports = new ArrayList<>();
        Matcher imM = IMPORT_PATTERN.matcher(noComments);
        while (imM.find()) imports.add(imM.group(1));

        // Only look at selector text (before each '{') to avoid matching '#'
        // or '.' that appear inside property values (e.g. url(#fragment)).
        LinkedHashSet<String> exports = new LinkedHashSet<>();
        for (String block : noComments.split("\\{")) {
            String selectorPart = block;
            int lastClose = selectorPart.lastIndexOf('}');
            if (lastClose >= 0) selectorPart = selectorPart.substring(lastClose + 1);
            Matcher cm = CLASS_SELECTOR.matcher(selectorPart);
            while (cm.find() && exports.size() < MAX_LISTED_SELECTORS) exports.add("." + cm.group(1));
            Matcher idm = ID_SELECTOR.matcher(selectorPart);
            while (idm.find() && exports.size() < MAX_LISTED_SELECTORS) exports.add("#" + idm.group(1));
        }

        String summary = "CSS stylesheet, " + exports.size() + " selector(s)"
                + (exports.size() >= MAX_LISTED_SELECTORS ? "+ (truncated)" : "")
                + (imports.isEmpty() ? "" : ", " + imports.size() + " @import(s)");

        return new FileSummary(fileName, new ArrayList<>(exports), imports, summary);
    }
}