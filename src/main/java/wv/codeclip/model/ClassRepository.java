package wv.codeclip.model;
import java.io.File;
import java.util.*;
public class ClassRepository {
    private final Map<String, String> classCodeMap = new LinkedHashMap<>();
    private final Map<String, File> classFileMap = new HashMap<>();
    private final Set<String> disabledClasses = new HashSet<>();
    private final Map<String, String> checkpointCodeMap = new HashMap<>();
    public Map<String, String> getClassCodeMap() {
        return classCodeMap;
    }
    public Map<String, File> getClassFileMap() {
        return classFileMap;
    }
    public Set<String> getDisabledClasses() {
        return disabledClasses;
    }
    public Map<String, String> getCheckpointCodeMap() {
        return checkpointCodeMap;
    }
    public void setCheckpoint(String path, String code) {
        checkpointCodeMap.put(path, code);
    }
    public void setAllCheckpoints() {
        checkpointCodeMap.clear();
        checkpointCodeMap.putAll(classCodeMap);
    }
    public boolean hasCheckpoint(String path) {
        return checkpointCodeMap.containsKey(path);
    }
    public boolean hasPendingRestores() {
        for (Map.Entry<String, String> entry : classCodeMap.entrySet()) {
            String checkpoint = checkpointCodeMap.get(entry.getKey());
            if (checkpoint != null && !checkpoint.equals(entry.getValue())) return true;
        }
        return false;
    }

public void clear() {
        classCodeMap.clear();
        classFileMap.clear();
        disabledClasses.clear();
        checkpointCodeMap.clear();
        lastChangedPath = null;
        lastChangeKind = null;
    }

public enum ChangeKind { NEW, WHOLE_UPDATE, PATCH_UPDATE }

    private String lastChangedPath;
    private ChangeKind lastChangeKind;

    public void recordChange(String path, ChangeKind kind) {
        this.lastChangedPath = path;
        this.lastChangeKind = kind;
    }

    public String getLastChangedPath() {
        return lastChangedPath;
    }

    public ChangeKind getLastChangeKind() {
        return lastChangeKind;
    }

}