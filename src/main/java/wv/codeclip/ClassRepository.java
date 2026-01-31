// ===== ClassRepository.java =====
package wv.codeclip;

import java.io.File;
import java.util.*;

public class ClassRepository {

    private final Map<String, String> classCodeMap = new LinkedHashMap<>();
    private final Map<String, File> classFileMap = new HashMap<>();
    private final Set<String> disabledClasses = new HashSet<>();

    public Map<String, String> getClassCodeMap() {
        return classCodeMap;
    }

    public Map<String, File> getClassFileMap() {
        return classFileMap;
    }

    public Set<String> getDisabledClasses() {
        return disabledClasses;
    }

    public void clear() {
        classCodeMap.clear();
        classFileMap.clear();
        disabledClasses.clear();
    }
}

