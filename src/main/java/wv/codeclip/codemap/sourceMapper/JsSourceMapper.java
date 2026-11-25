package wv.codeclip.codemap.sourceMapper;

import java.util.*;
import java.util.regex.*;

/**
 * JavaScript (.js/.mjs/.cjs/.jsx) için dosya haritası üretici.
 *
 * Çıktı: dosya başlığı + imports/exports + blok listesi (satır aralığı + imza + doc).
 * Amaç: LLM'e tam dosya yerine "içindekiler" vermek; LLM istediği aralığı okur.
 */
public final class JsSourceMapper implements SourceMapper {

    @Override
    public String map(String source, String fileName) {
        if (source == null) source = "";
        if (fileName == null) fileName = "unnamed.js";

        String[] lines = source.split("\n", -1);
        int totalLines   = lines.length;
        int totalChars   = source.length();
        int approxTokens = Math.max(1, totalChars / 4); // kaba tahmin

        List<String> imports = extractImports(source);
        List<String> exports = extractExports(source);
        List<Block>  blocks  = scanBlocks(lines);
        List<String> todos   = extractTodos(lines);
        String       purpose = extractPurpose(lines);

        StringBuilder sb = new StringBuilder(1024);
        sb.append("FILE: ").append(fileName)
          .append("  (").append(totalLines).append(" lines, ~")
          .append(approxTokens).append(" tok)\n");
        if (!purpose.isEmpty()) sb.append("PURPOSE: ").append(purpose).append('\n');
        if (!imports.isEmpty()) sb.append("IMPORTS: ").append(String.join(", ", imports)).append('\n');
        if (!exports.isEmpty()) sb.append("EXPORTS: ").append(String.join(", ", exports)).append('\n');

        if (!blocks.isEmpty()) {
            sb.append('\n');
            for (Block b : blocks) sb.append(b.render()).append('\n');
        }
        if (!todos.isEmpty()) {
            sb.append('\n');
            for (String t : todos) sb.append(t).append('\n');
        }
        return sb.toString();
    }

    // ---------- imports / exports ----------------------------------------

    private static final Pattern P_IMPORT_FROM = Pattern.compile(
        "(?m)^\\s*(?:import\\s+[^'\"]*?from\\s+|import\\s+)['\"]([^'\"]+)['\"]");
    private static final Pattern P_REQUIRE = Pattern.compile(
        "require\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern P_REEXPORT = Pattern.compile(
        "(?m)^\\s*export\\s+(?:\\*|\\{[^}]*\\})\\s+from\\s+['\"]([^'\"]+)['\"]");

    private static final Pattern P_EXPORT_NAMED = Pattern.compile(
        "(?m)^\\s*export\\s+(?:default\\s+)?(?:async\\s+)?(?:function\\*?|class|const|let|var)\\s+(\\w+)");
    private static final Pattern P_EXPORT_DEFAULT = Pattern.compile(
        "(?m)^\\s*export\\s+default\\s+(?!(?:async\\s+)?(?:function|class)\\b)(\\w+)");
    private static final Pattern P_MODULE_EXPORTS = Pattern.compile(
        "module\\.exports\\s*=\\s*\\{([^}]*)\\}");
    private static final Pattern P_EXPORTS_DOT = Pattern.compile(
        "(?m)^\\s*exports\\.(\\w+)\\s*=");

    private List<String> extractImports(String source) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Matcher m = P_IMPORT_FROM.matcher(source); while (m.find()) set.add(m.group(1));
        m = P_REQUIRE.matcher(source);            while (m.find()) set.add(m.group(1));
        m = P_REEXPORT.matcher(source);           while (m.find()) set.add(m.group(1));
        return new ArrayList<>(set);
    }

    private List<String> extractExports(String source) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Matcher m = P_EXPORT_NAMED.matcher(source);
        while (m.find()) set.add(m.group(1));
        m = P_EXPORT_DEFAULT.matcher(source);
        while (m.find()) set.add("default(" + m.group(1) + ")");
        m = P_EXPORTS_DOT.matcher(source);
        while (m.find()) set.add(m.group(1));
        m = P_MODULE_EXPORTS.matcher(source);
        while (m.find()) {
            for (String piece : m.group(1).split(",")) {
                piece = piece.trim();
                if (piece.isEmpty()) continue;
                int eq = piece.indexOf(':');
                set.add(eq >= 0 ? piece.substring(0, eq).trim() : piece.split("\\s+")[0].trim());
            }
        }
        return new ArrayList<>(set);
    }

    // ---------- blok tarama ----------------------------------------------

    private static final Set<String> RESERVED = Set.of(
        "if","for","while","switch","catch","do","else","try","finally","return",
        "new","typeof","instanceof","delete","void","yield","await","throw","super","with");

    private static final Pattern P_FUNC = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\*?\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern P_CLASS = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:default\\s+)?class\\s+(\\w+)");
    private static final Pattern P_CONST_FN = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:default\\s+)?(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?function\\s*\\w*\\s*\\(([^)]*)\\)");
    private static final Pattern P_CONST_ARROW = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:default\\s+)?(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?(?:\\(([^)]*)\\)|(\\w+))\\s*=>");

    private List<Block> scanBlocks(String[] lines) {
        List<Block> blocks = new ArrayList<>();
        BraceCounter counter = new BraceCounter();
        int depth = 0;
        Block current = null;

        for (int i = 0; i < lines.length; i++) {
            String clean = counter.clean(lines[i]);

            if (current == null && depth == 0) {
                Block b = detectTopLevel(clean, i);
                if (b != null) {
                    if (!clean.contains("{")) {
                        b.endLine = i;
                        blocks.add(b);
                    } else {
                        current = b;
                    }
                }
            }

            depth += count(clean, '{') - count(clean, '}');

            if (current != null && depth == 0) {
                current.endLine = i;
                if ("class".equals(current.kind))
                    current.methods = extractMethodNames(lines, current.startLine, current.endLine);
                blocks.add(current);
                current = null;
            }
        }
        if (current != null) {
            current.endLine = lines.length - 1;
            if ("class".equals(current.kind))
                current.methods = extractMethodNames(lines, current.startLine, current.endLine);
            blocks.add(current);
        }
        return blocks;
    }

    private Block detectTopLevel(String clean, int lineIdx) {
        Matcher m;
        if ((m = P_FUNC.matcher(clean)).find())
            return Block.of("function", m.group(1), "(" + truncate(m.group(2), 60) + ")", lineIdx);
        if ((m = P_CLASS.matcher(clean)).find())
            return Block.of("class", m.group(1), "", lineIdx);
        if ((m = P_CONST_FN.matcher(clean)).find())
            return Block.of("fn", m.group(1), "(" + truncate(m.group(2), 60) + ")", lineIdx);
        if ((m = P_CONST_ARROW.matcher(clean)).find()) {
            String params = m.group(2) != null ? m.group(2) : m.group(3);
            return Block.of("arrow", m.group(1), "(" + truncate(params, 60) + ")", lineIdx);
        }
        return null;
    }

    private List<String> extractMethodNames(String[] lines, int start, int end) {
        List<String> names = new ArrayList<>();
        BraceCounter counter = new BraceCounter();
        Pattern P_METHOD = Pattern.compile(
            "^\\s{1,8}(?:async\\s+)?(?:static\\s+)?(?:get\\s+|set\\s+)?(\\w+)\\s*\\([^)]*\\)\\s*\\{");
        int depth = 0;
        for (int i = start; i <= end && i < lines.length; i++) {
            String clean = counter.clean(lines[i]);
            if (depth == 1) {
                Matcher m = P_METHOD.matcher(clean);
                if (m.find() && !RESERVED.contains(m.group(1))) names.add(m.group(1));
            }
            depth += count(clean, '{') - count(clean, '}');
        }
        return names;
    }

    // ---------- yardımcılar ----------------------------------------------

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.trim().replaceAll("\\s+", " ");
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static final Pattern P_TODO = Pattern.compile(
        "(?://|/\\*+|\\*)\\s*(TODO[^\\n]*)");

    private List<String> extractTodos(String[] lines) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = P_TODO.matcher(lines[i]);
            if (m.find())
                out.add("L" + (i + 1) + ": " + m.group(1).replace("*/", "").trim());
        }
        return out;
    }

    private String extractPurpose(String[] lines) {
        for (int i = 0; i < Math.min(lines.length, 5); i++) {
            String t = lines[i].trim();
            if (t.startsWith("//")) return t.substring(2).trim();
            if (t.startsWith("/*")) {
                String s = t.replaceFirst("^/\\*+", "").replaceFirst("\\*/.*$", "").trim();
                if (!s.isEmpty() && !s.startsWith("*")) return s;
            }
        }
        return "";
    }

    // ---------- string / yorum temizleyici --------------------------------

    private static final class BraceCounter {
        private boolean inBlock = false;
        private char    inStr   = 0;

        String clean(String line) {
            StringBuilder sb = new StringBuilder(line.length());
            int i = 0;
            while (i < line.length()) {
                if (inBlock) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) return sb.toString();
                    i = end + 2; inBlock = false; continue;
                }
                if (inStr != 0) {
                    while (i < line.length()) {
                        char sc = line.charAt(i);
                        if (sc == '\\') { i += 2; continue; }
                        if (sc == inStr) { i++; inStr = 0; break; }
                        i++;
                    }
                    continue;
                }
                char c = line.charAt(i);
                if (c == '/' && i + 1 < line.length()) {
                    char n = line.charAt(i + 1);
                    if (n == '/') break;
                    if (n == '*') { inBlock = true; i += 2; continue; }
                }
                if (c == '"' || c == '\'' || c == '`') { inStr = c; i++; continue; }
                sb.append(c); i++;
            }
            return sb.toString();
        }
    }

    // ---------- Block modeli ---------------------------------------------

    private static final class Block {
        String kind, name, signature;
        int startLine, endLine;
        List<String> methods = new ArrayList<>();

        static Block of(String kind, String name, String sig, int startLine) {
            Block b = new Block();
            b.kind = kind; b.name = name;
            b.signature = sig == null ? "" : sig;
            b.startLine = startLine;
            return b;
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append('L').append(startLine + 1).append('-').append(endLine + 1).append('\t');
            sb.append(pad(kind, 9)).append(' ');
            sb.append(pad(name, 22));
            if (!signature.isEmpty()) sb.append(' ').append(signature);
            if ("class".equals(kind) && !methods.isEmpty())
                sb.append("  methods: ").append(String.join(", ", methods));
            return sb.toString().replaceAll("\\s+$", "");
        }

        private static String pad(String s, int n) {
            if (s.length() >= n) return s;
            StringBuilder b = new StringBuilder(s);
            while (b.length() < n) b.append(' ');
            return b.toString();
        }
    }
}