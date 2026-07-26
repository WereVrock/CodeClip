// HtmlCodeMapAnalyzer.java
package wv.codeclip.codemap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codemap analyzer for .html/.htm files. "Exports" is elements with an id
 * (the addressable surface other files' JS/CSS can target). "Imports" is
 * linked stylesheets and scripts (src=/href=), since that's what an HTML
 * file actually pulls in.
 */
public final class HtmlCodeMapAnalyzer implements CodeMapAnalyzer {

    private static final Pattern ID_ATTR = Pattern.compile("\\bid=[\"']([^\"']+)[\"']");
    private static final Pattern SCRIPT_SRC = Pattern.compile("<script[^>]*\\bsrc=[\"']([^\"']+)[\"']");
    private static final Pattern LINK_HREF = Pattern.compile(
            "<link[^>]*\\brel=[\"']stylesheet[\"'][^>]*\\bhref=[\"']([^\"']+)[\"']"
            + "|<link[^>]*\\bhref=[\"']([^\"']+)[\"'][^>]*\\brel=[\"']stylesheet[\"']");
    private static final Pattern TITLE_TAG = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);

    private static final int MAX_LISTED_IDS = 40;

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    @Override
    public FileSummary analyze(String fileName, String code) {
        String safe = code != null ? code : "";

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher idm = ID_ATTR.matcher(safe);
        while (idm.find() && ids.size() < MAX_LISTED_IDS) ids.add("#" + idm.group(1));

        LinkedHashSet<String> imports = new LinkedHashSet<>();
        Matcher sm = SCRIPT_SRC.matcher(safe);
        while (sm.find()) imports.add(sm.group(1));
        Matcher lm = LINK_HREF.matcher(safe);
        while (lm.find()) {
            String href = lm.group(1) != null ? lm.group(1) : lm.group(2);
            if (href != null) imports.add(href);
        }

        String pageTitle = null;
        Matcher tm = TITLE_TAG.matcher(safe);
        if (tm.find()) pageTitle = tm.group(1).trim();

        String summary = "HTML document"
                + (pageTitle != null && !pageTitle.isEmpty() ? " \"" + pageTitle + "\"" : "")
                + ", " + ids.size() + " id(s)"
                + (imports.isEmpty() ? "" : ", links " + imports.size() + " script/style asset(s)");

        return new FileSummary(fileName, new ArrayList<>(ids), new ArrayList<>(imports), summary);
    }
}