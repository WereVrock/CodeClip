package wv.codeclip.parse;

import wv.codeclip.model.ClassRepository;
import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

public class SourceRootDetector {

    private static final String DEFAULT_SOURCE_ROOT =
            System.getProperty("user.home") + "/Documents/NetBeansProjects/CodeClip/src/main/java";

    private static final Pattern MAIN_METHOD_PATTERN = Pattern.compile(
            "public\\s+static\\s+void\\s+main\\s*\\(\\s*String\\s*\\[\\]\\s*\\w+\\s*\\)",
            Pattern.MULTILINE
    );

    private static final String PREFERRED_MAIN_CLASS_NAME = "Main";

    private final ClassRepository repo;
    private final JFrame parent;
    private final JavaSourceParser parser;

    public SourceRootDetector(ClassRepository repo, JFrame parent, JavaSourceParser parser) {
        this.repo = repo;
        this.parent = parent;
        this.parser = parser;
    }

    public File detect(String packageName) {
        File byPackage = findByPackage(packageName);
        if (byPackage != null) return byPackage;

        Map<String, File> mainClasses = collectMainClasses();

        if (mainClasses.containsKey(PREFERRED_MAIN_CLASS_NAME)) {
            File mainFile = mainClasses.get(PREFERRED_MAIN_CLASS_NAME);
            return resolveSourceRoot(mainFile.getParentFile(), getPackageOf(PREFERRED_MAIN_CLASS_NAME));
        }

        if (!mainClasses.isEmpty()) {
            File chosen = promptUserToPickMainClass(mainClasses);
            if (chosen != null) {
                try {
                    String code = Files.readString(chosen.toPath());
                    return resolveSourceRoot(chosen.getParentFile(), parser.parsePackage(code));
                } catch (IOException ignored) {}
            }
        }

        if (!repo.getClassFileMap().isEmpty()) {
            List<File> parents = collectParentDirs();
            File common = findCommonAncestor(parents);
            if (common != null) return common;
        }

        return new File(DEFAULT_SOURCE_ROOT);
    }

    private File findByPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;

        String pkgPath = packageName.replace('.', File.separatorChar);
        for (File file : repo.getClassFileMap().values()) {
            File dir = file.getParentFile();
            if (dir == null) continue;
            String abs = dir.getAbsolutePath();
            if (abs.endsWith(File.separator + pkgPath)) {
                return new File(abs.substring(0, abs.length() - pkgPath.length() - 1));
            }
        }
        return null;
    }

    private Map<String, File> collectMainClasses() {
        Map<String, File> mainClasses = new HashMap<>();
        for (File file : new ArrayList<>(repo.getClassFileMap().values())) {
            try {
                String code = Files.readString(file.toPath());
                if (MAIN_METHOD_PATTERN.matcher(code).find()) {
                    String name = parser.parseClassName(code);
                    if (name != null) mainClasses.put(name, file);
                }
            } catch (IOException ignored) {}
        }
        return mainClasses;
    }

    private File promptUserToPickMainClass(Map<String, File> mainClasses) {
        String[] options = mainClasses.keySet().toArray(new String[0]);
        String choice = (String) JOptionPane.showInputDialog(
                parent,
                "Multiple classes with main method detected. Pick folder for new class:",
                "Select Main Class Folder",
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );
        if (choice != null && mainClasses.containsKey(choice)) {
            return mainClasses.get(choice);
        }
        return null;
    }

    private File resolveSourceRoot(File packageDir, String packageName) {
        if (packageName == null || packageName.isEmpty()) return packageDir;
        int depth = packageName.split("\\.").length;
        File root = packageDir;
        for (int i = 0; i < depth; i++) {
            if (root == null) return packageDir;
            root = root.getParentFile();
        }
        return root != null ? root : packageDir;
    }

    private String getPackageOf(String className) {
        for (File file : repo.getClassFileMap().values()) {
            try {
                String code = Files.readString(file.toPath());
                if (className.equals(parser.parseClassName(code))) {
                    return parser.parsePackage(code);
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    private List<File> collectParentDirs() {
        List<File> parents = new ArrayList<>();
        for (File f : repo.getClassFileMap().values()) {
            parents.add(f.getParentFile());
        }
        return parents;
    }

    private File findCommonAncestor(List<File> paths) {
        if (paths.isEmpty()) return null;
        File common = paths.get(0);
        while (common != null) {
            boolean allMatch = true;
            for (File f : paths) {
                if (!f.getAbsolutePath().startsWith(common.getAbsolutePath())) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) break;
            common = common.getParentFile();
        }
        if (common == null) return null;

        File firstFile = repo.getClassFileMap().values().iterator().next();
        try {
            String code = Files.readString(firstFile.toPath());
            String pkg = parser.parsePackage(code);
            return resolveSourceRoot(common, pkg);
        } catch (IOException ignored) {}

        return common;
    }
}