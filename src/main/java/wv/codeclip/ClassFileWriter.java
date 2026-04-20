package wv.codeclip;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class ClassFileWriter {

    private final ClassRepository repo;

    public ClassFileWriter(ClassRepository repo) {
        this.repo = repo;
    }

    /**
     * Creates a new .java file under the given source root + package path.
     */
    public File createFile(String packageName, String className, String code, File sourceRoot)
            throws IOException {
        File dir = resolvePackageDir(packageName, sourceRoot);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, className + ".java");
        Files.writeString(file.toPath(), code);
        return file;
    }

    /**
     * Overwrites an existing .java file.
     */
    public void updateFile(File file, String code) throws IOException {
        Files.writeString(file.toPath(), code);
    }

    /**
     * Registers the file in the repo and removes it from disabled set.
     */
    public void registerInRepo(File file, String code) {
        String path = file.getAbsolutePath();
        repo.getClassCodeMap().put(path, code);
        repo.getClassFileMap().put(path, file);
        repo.getDisabledClasses().remove(path);
    }

    /**
     * Finds an existing .java file for the given class, or returns null.
     */
    public File findExistingFile(String packageName, String className, File sourceRoot) {
        File dir = resolvePackageDir(packageName, sourceRoot);
        File f = new File(dir, className + ".java");
        return f.exists() ? f : null;
    }

    private File resolvePackageDir(String packageName, File sourceRoot) {
        String pkgPath = (packageName != null)
                ? packageName.replace('.', File.separatorChar)
                : "";
        return new File(sourceRoot, pkgPath);
    }
}