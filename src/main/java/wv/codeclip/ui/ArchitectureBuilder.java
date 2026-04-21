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

    public String build() {
        Map<String, List<String>> packageToClasses = groupByPackage();
        Map<String, TreeMap<String, Object>> tree = buildPackageTree(packageToClasses);

        StringBuilder sb = new StringBuilder();
        sb.append("Architecture\n");
        sb.append("============\n\n");
        renderTree(tree, packageToClasses, sb, "", true);
        return sb.toString();
    }

    private Map<String, List<String>> groupByPackage() {
        Map<String, List<String>> result = new TreeMap<>();

        for (Map.Entry<String, String> entry : repo.getClassCodeMap().entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();

            String pkg = parser.parsePackage(code);
            String cls = parser.parseClassName(code);

            if (cls == null) {
                File file = repo.getClassFileMap().get(path);
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
            boolean isRoot) {

        List<String> keys = new ArrayList<>(node.keySet());

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            boolean last = (i == keys.size() - 1);
            String fullPkg = (currentPkg.isEmpty() || key.equals(DEFAULT_PACKAGE))
                    ? key
                    : currentPkg + "." + key;

            String connector   = last ? "└── " : "├── ";
            String childIndent = last ? "    " : "│   ";

            if (isRoot) {
                sb.append(key).append("\n");
            } else {
                sb.append(connector).append(key).append("\n");
            }

            Map<String, TreeMap<String, Object>> children =
                    (Map<String, TreeMap<String, Object>>) (Object) node.get(key);

            String indent = isRoot ? "" : childIndent;
            List<String> classes = packageToClasses.get(fullPkg);
            boolean hasChildren = children != null && !children.isEmpty();

            if (classes != null) {
                for (int j = 0; j < classes.size(); j++) {
                    boolean lastClass = (j == classes.size() - 1) && !hasChildren;
                    String classConnector = indent + (lastClass ? "└── " : "├── ");
                    sb.append(classConnector).append(classes.get(j)).append(".java\n");
                }
            }

            if (hasChildren) {
                renderTree(children, packageToClasses, sb, fullPkg, false);
            }
        }
    }
}