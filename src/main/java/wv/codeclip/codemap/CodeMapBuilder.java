package wv.codeclip.codemap;

import wv.codeclip.model.ClassRepository;
import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

public final class CodeMapBuilder {

    private final ClassRepository repo;
    private final List<CodeMapAnalyzer> analyzers;

    public CodeMapBuilder(ClassRepository repo) {
        this.repo = repo;
        this.analyzers = List.of(
                new JavaCodeMapAnalyzer(),
                new JsCodeMapAnalyzer(),
                new CssCodeMapAnalyzer(),
                new HtmlCodeMapAnalyzer(),
                new GenericCodeMapAnalyzer()
        );
    }

    public String build(Set<String> disabledPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("Codemap\n");
        sb.append("=======\n");
        sb.append("Per-file exports, imports/dependencies, and a one-line summary.\n\n");

        List<Map.Entry<String, File>> included = new ArrayList<>();
        for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
            String path = entry.getKey();
            if (disabledPaths.contains(path)) continue;
            if (entry.getValue() == null) continue;
            included.add(entry);
        }

        if (included.isEmpty()) {
            sb.append("(no files)\n");
            return sb.toString();
        }

        String commonRoot = findCommonRoot(included);
        DirNode root = new DirNode(commonRoot.isEmpty()
                ? "(root)"
                : new File(commonRoot).getName());

        for (Map.Entry<String, File> entry : included) {
            File file = entry.getValue();
            List<String> segments = relativeDirSegments(commonRoot, file.getParentFile());
            DirNode current = root;
            for (String seg : segments) {
                current = current.children.computeIfAbsent(seg, DirNode::new);
            }
            current.files.add(entry);
        }

        if (!commonRoot.isEmpty()) {
            sb.append(commonRoot).append("\n");
        }
        renderDirTree(root, sb, "");

        return sb.toString();
    }

    private static final class DirNode {
        final String name;
        final TreeMap<String, DirNode> children = new TreeMap<>();
        final List<Map.Entry<String, File>> files = new ArrayList<>();
        DirNode(String name) { this.name = name; }
    }

    private void renderDirTree(DirNode node, StringBuilder sb, String indent) {
        List<DirNode> childDirs = new ArrayList<>(node.children.values());
        node.files.sort((a, b) -> a.getValue().getName().compareToIgnoreCase(b.getValue().getName()));

        List<Object> items = new ArrayList<>();
        items.addAll(childDirs);
        items.addAll(node.files);

        for (int i = 0; i < items.size(); i++) {
            boolean last = (i == items.size() - 1);
            String connector = last ? "└── " : "├── ";
            String childIndent = indent + (last ? "    " : "│   ");
            Object item = items.get(i);

            if (item instanceof DirNode dirNode) {
                sb.append(indent).append(connector).append(dirNode.name).append("/\n");
                renderDirTree(dirNode, sb, childIndent);
            } else {
                @SuppressWarnings("unchecked")
                Map.Entry<String, File> fileEntry = (Map.Entry<String, File>) item;
                File file = fileEntry.getValue();
                String code = repo.getClassCodeMap().get(fileEntry.getKey());
                sb.append(indent).append(connector).append(file.getName()).append("\n");
                appendFileDetail(sb, childIndent, file.getName(), code);
            }
        }
    }

    private void appendFileDetail(StringBuilder sb, String indent, String fileName, String code) {
        CodeMapAnalyzer analyzer = pickAnalyzer(fileName);
        CodeMapAnalyzer.FileSummary summary = analyzer.analyze(fileName, code);

        sb.append(indent).append("Summary: ").append(summary.summary()).append("\n");
        if (!summary.exports().isEmpty()) {
            sb.append(indent).append("Exports: ").append(String.join(", ", summary.exports())).append("\n");
        }
        if (!summary.imports().isEmpty()) {
            sb.append(indent).append("Imports: ").append(String.join(", ", summary.imports())).append("\n");
        }
    }

    private String findCommonRoot(List<Map.Entry<String, File>> included) {
        if (included.isEmpty()) return "";
        List<String> candidate = new ArrayList<>();
        File dir = included.get(0).getValue().getParentFile();
        while (dir != null) {
            candidate.add(dir.getAbsolutePath());
            dir = dir.getParentFile();
        }
        outer:
        for (String root : candidate) {
            for (Map.Entry<String, File> e : included) {
                File parentDir = e.getValue().getParentFile();
                String parentAbs = parentDir != null ? parentDir.getAbsolutePath() : "";
                if (!parentAbs.equals(root) && !parentAbs.startsWith(root + File.separator)) {
                    continue outer;
                }
            }
            return root;
        }
        File firstParent = included.get(0).getValue().getParentFile();
        return firstParent != null ? firstParent.getAbsolutePath() : "";
    }

    private List<String> relativeDirSegments(String commonRoot, File dir) {
        List<String> segs = new ArrayList<>();
        if (dir == null) return segs;
        String abs = dir.getAbsolutePath();
        if (abs.equals(commonRoot)) return segs;
        String rel = abs.startsWith(commonRoot + File.separator)
                ? abs.substring(commonRoot.length() + 1)
                : abs;
        for (String seg : rel.split(java.util.regex.Pattern.quote(File.separator))) {
            if (!seg.isEmpty()) segs.add(seg);
        }
        return segs;
    }

    private CodeMapAnalyzer pickAnalyzer(String fileName) {
        for (CodeMapAnalyzer analyzer : analyzers) {
            if (analyzer.supports(fileName)) return analyzer;
        }
        return new GenericCodeMapAnalyzer();
    }
}