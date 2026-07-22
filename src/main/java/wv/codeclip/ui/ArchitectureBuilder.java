package wv.codeclip.ui;

import wv.codeclip.model.ClassRepository;
import wv.codeclip.parse.JavaSourceParser;

import java.io.File;
import java.util.*;

public class ArchitectureBuilder {

    private static final String DEFAULT_PACKAGE = "(default package)";

    private final ClassRepository repo;
    private final JavaSourceParser parser = new JavaSourceParser();

    public ArchitectureBuilder(ClassRepository repo) {
        this.repo = repo;
    }

    public enum Mode { ENABLED_ONLY, ADDED_ONLY, ALL }

    public String build(Mode mode, Set<String> disabledPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("Architecture\n");
        sb.append("============\n\n");

        if (wv.codeclip.modecontext.ModeContext.getMode() == wv.codeclip.AppMode.JAVA) {
            Map<String, List<String>> packageToClasses = groupByPackage(mode, disabledPaths);
            Map<String, TreeMap<String, Object>> tree = buildPackageTree(packageToClasses);
            renderTree(tree, packageToClasses, sb, "", "", true);
        } else {
            buildDirectoryTree(mode, disabledPaths, sb);
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Non-Java modes: real on-disk directory tree (no package concept).
    // Mirrors the same longest-common-ancestor + folder-tree approach
    // ClassTreePanel already uses for its live UI tree, so "Copy
    // Architecture" matches what the tree view shows.
    // ------------------------------------------------------------------

    private void buildDirectoryTree(Mode mode, Set<String> disabledPaths, StringBuilder sb) {
        List<File> files = new ArrayList<>();
        Map<File, String> fileToPath = new java.util.LinkedHashMap<>();

        for (Map.Entry<String, String> entry : repo.getClassCodeMap().entrySet()) {
            String path = entry.getKey();
            if (mode == Mode.ENABLED_ONLY && disabledPaths.contains(path)) continue;

            File file = repo.getClassFileMap().get(path);
            boolean existsOnDisk = file != null && file.exists();
            if (mode == Mode.ADDED_ONLY && !existsOnDisk) continue;
            if (file == null) continue;

            files.add(file);
            fileToPath.put(file, path);
        }

        if (files.isEmpty()) {
            sb.append("(no files)\n");
            return;
        }

        String commonRoot = findCommonRoot(files);
        DirNode root = new DirNode(commonRoot.isEmpty()
                ? "(root)"
                : new File(commonRoot).getName());

        for (File f : files) {
            List<String> segments = relativeDirSegments(commonRoot, f.getParentFile());
            DirNode current = root;
            for (String seg : segments) {
                current = current.children.computeIfAbsent(seg, DirNode::new);
            }
            current.files.add(f.getName());
        }

        for (DirNode child : root.children.values()) {
            child.files.sort(null);
        }
        root.files.sort(null);

        if (!commonRoot.isEmpty()) {
            sb.append(root.name).append("\n");
        }
        List<DirNode> topChildren = new ArrayList<>(root.children.values());
        renderDirTree(topChildren, root.files, sb, "", commonRoot.isEmpty());
    }

    private static final class DirNode {
        final String name;
        final TreeMap<String, DirNode> children = new TreeMap<>();
        final List<String> files = new ArrayList<>();
        DirNode(String name) { this.name = name; }
    }

    private void renderDirTree(List<DirNode> children, List<String> rootFiles,
                                StringBuilder sb, String indent, boolean isTopLevel) {
        List<Object> items = new ArrayList<>();
        items.addAll(children);
        items.addAll(rootFiles);

        for (int i = 0; i < items.size(); i++) {
            boolean last = (i == items.size() - 1);
            String connector = last ? "└── " : "├── ";
            String childIndent = indent + (last ? "    " : "│   ");
            Object item = items.get(i);

            if (item instanceof DirNode node) {
                sb.append(indent).append(connector).append(node.name).append("\n");
                List<DirNode> nested = new ArrayList<>(node.children.values());
                renderDirTree(nested, node.files, sb, childIndent, false);
            } else {
                sb.append(indent).append(connector).append((String) item).append("\n");
            }
        }
    }

    private String findCommonRoot(List<File> files) {
        if (files.isEmpty()) return "";
        List<String> candidate = new ArrayList<>();
        File dir = files.get(0).getParentFile();
        while (dir != null) {
            candidate.add(dir.getAbsolutePath());
            dir = dir.getParentFile();
        }
        outer:
        for (String root : candidate) {
            for (File f : files) {
                String parentAbs = f.getParentFile() != null ? f.getParentFile().getAbsolutePath() : "";
                if (!parentAbs.equals(root) && !parentAbs.startsWith(root + File.separator)) {
                    continue outer;
                }
            }
            return root;
        }
        return files.get(0).getParentFile() != null
                ? files.get(0).getParentFile().getAbsolutePath()
                : "";
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

    private Map<String, List<String>> groupByPackage(Mode mode, Set<String> disabledPaths) {
        Map<String, List<String>> result = new TreeMap<>();

        for (Map.Entry<String, String> entry : repo.getClassCodeMap().entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();

            if (mode == Mode.ENABLED_ONLY && disabledPaths.contains(path)) continue;

            File file = repo.getClassFileMap().get(path);
            boolean existsOnDisk = file != null && file.exists();

            if (mode == Mode.ADDED_ONLY && !existsOnDisk) continue;

            String pkg = parser.parsePackage(code);
            String cls = parser.parseClassName(code);

            if (cls == null) {
                cls = (file != null) ? file.getName().replace(".java", "") : path;
            }

            String pkgKey = (pkg != null && !pkg.isEmpty()) ? pkg : DEFAULT_PACKAGE;
            result.computeIfAbsent(pkgKey, k -> new ArrayList<>()).add(cls);
        }

        for (List<String> classes : result.values()) {
            Collections.sort(classes);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, TreeMap<String, Object>> buildPackageTree(
            Map<String, List<String>> packageToClasses) {

        Map<String, TreeMap<String, Object>> root = new TreeMap<>();

        for (String pkg : packageToClasses.keySet()) {
            if (pkg.equals(DEFAULT_PACKAGE)) {
                root.computeIfAbsent(DEFAULT_PACKAGE, k -> new TreeMap<>());
                continue;
            }
            String[] parts = pkg.split("\\.");
            Map<String, TreeMap<String, Object>> current = root;
            for (String part : parts) {
                current = (Map<String, TreeMap<String, Object>>)
                        (Object) current.computeIfAbsent(part, k -> new TreeMap<>());
            }
        }

        return root;
    }

    @SuppressWarnings("unchecked")
    private void renderTree(
            Map<String, TreeMap<String, Object>> node,
            Map<String, List<String>> packageToClasses,
            StringBuilder sb,
            String currentPkg,
            String indent,
            boolean isRoot) {

        List<String> keys = new ArrayList<>(node.keySet());

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            boolean last = (i == keys.size() - 1);
            String fullPkg = (currentPkg.isEmpty() || key.equals(DEFAULT_PACKAGE))
                    ? key
                    : currentPkg + "." + key;

            String connector   = last ? "└── " : "├── ";
            String childIndent = indent + (last ? "    " : "│   ");

            if (isRoot) {
                sb.append(key).append("\n");
            } else {
                sb.append(indent).append(connector).append(key).append("\n");
            }

            Map<String, TreeMap<String, Object>> children =
                    (Map<String, TreeMap<String, Object>>) (Object) node.get(key);

            List<String> classes = packageToClasses.get(fullPkg);
            boolean hasChildren = children != null && !children.isEmpty();

            if (classes != null) {
                for (int j = 0; j < classes.size(); j++) {
                    boolean lastClass = (j == classes.size() - 1) && !hasChildren;
                    String classConnector = childIndent + (lastClass ? "└── " : "├── ");
                    sb.append(classConnector).append(classes.get(j)).append(".java\n");
                }
            }

            if (hasChildren) {
                renderTree(children, packageToClasses, sb, fullPkg, childIndent, false);
            }
        }
    }
}