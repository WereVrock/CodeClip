// JsCodeMapAnalyzer.java
package wv.codeclip.codemap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codemap analyzer for .js/.mjs files. Handles ES module import/export
 * syntax plus common CommonJS require()/module.exports patterns, since
 * real-world JS in an HTML-mode project may use either style.
 */
public final class JsCodeMapAnalyzer implements CodeMapAnalyzer {

    private static final Pattern ES_IMPORT = Pattern.compile(
            "(?m)^import\\s+(?:[\\w*{}\\s,]+\\s+from\\s+)?['\"]([^'\"]+)['\"]");
    private static final Pattern REQUIRE = Pattern.compile(
            "require\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    private static final Pattern EXPORT_NAMED_FUNC = Pattern.compile(
            "(?m)^export\\s+(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)?");
    private static final Pattern EXPORT_CONST = Pattern.compile(
            "(?m)^export\\s+(?:default\\s+)?(?:const|let|var|class)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern EXPORT_LIST = Pattern.compile(
            "(?m)^export\\s*\\{([^}]*)\\}");
    private static final Pattern MODULE_EXPORTS_PROP = Pattern.compile(
            "module\\.exports\\.([A-Za-z_$][\\w$]*)\\s*=");
    private static final Pattern EXPORTS_PROP = Pattern.compile(
            "(?m)^exports\\.([A-Za-z_$][\\w$]*)\\s*=");

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".js") || lower.endsWith(".mjs");
    }

    @Override
    public FileSummary analyze(String fileName, String code) {
        String safe = code != null ? code : "";

        LinkedHashSet<String> imports = new LinkedHashSet<>();
        Matcher esM = ES_IMPORT.matcher(safe);
        while (esM.find()) imports.add(esM.group(1));
        Matcher reqM = REQUIRE.matcher(safe);
        while (reqM.find()) imports.add(reqM.group(1));

        LinkedHashSet<String> exports = new LinkedHashSet<>();
        Matcher fnM = EXPORT_NAMED_FUNC.matcher(safe);
        while (fnM.find()) {
            String name = fnM.group(1);
            exports.add(name != null ? name + "()" : "(default function)");
        }
        Matcher cM = EXPORT_CONST.matcher(safe);
        while (cM.find()) exports.add(cM.group(1));
        Matcher listM = EXPORT_LIST.matcher(safe);
        while (listM.find()) {
            for (String part : listM.group(1).split(",")) {
                String name = part.trim();
                if (name.isEmpty()) continue;
                int asIdx = name.indexOf(" as ");
                exports.add(asIdx >= 0 ? name.substring(0, asIdx).trim() : name);
            }
        }
        Matcher meM = MODULE_EXPORTS_PROP.matcher(safe);
        while (meM.find()) exports.add(meM.group(1));
        Matcher epM = EXPORTS_PROP.matcher(safe);
        while (epM.find()) exports.add(epM.group(1));

        boolean isModule = safe.contains("export ") || safe.contains("export{") || safe.contains("export {");
        String summary = "JavaScript"
                + (isModule ? " (ES module)" : (safe.contains("module.exports") || safe.contains("require(") ? " (CommonJS)" : ""))
                + (imports.isEmpty() ? "" : ", " + imports.size() + " dependency import(s)")
                + (exports.isEmpty() ? ", no detected exports" : "");

        return new FileSummary(fileName, new ArrayList<>(exports), new ArrayList<>(imports), summary);
    }
}