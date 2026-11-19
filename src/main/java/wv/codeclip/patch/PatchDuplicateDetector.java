package wv.codeclip.patch;

import java.util.LinkedList;

public class PatchDuplicateDetector {

    private static final int BUFFER_SIZE = 3;

    private final LinkedList<String> recentPatches = new LinkedList<>();

    public enum Result { CLEAR, DUPLICATE }

public Result check(String rawPatchText) {
    // Compare the full normalized body. The old check required BOTH @@TITLE: and
    // @@DESC: to be present and equal, so patches with only a title (the normal
    // HTML-mode shape) were never detected as duplicates.
    String incomingBody = normalize(rawPatchText);
    for (String recent : recentPatches) {
        if (incomingBody.equals(normalize(recent))) {
            return Result.DUPLICATE;
        }
    }
    return Result.CLEAR;
}

public void record(String rawPatchText) {
    System.out.println("[DupDetector] record() called, buffer before=" + recentPatches.size());
    recentPatches.addFirst(rawPatchText);
    if (recentPatches.size() > BUFFER_SIZE) {
        recentPatches.removeLast();
    }
    System.out.println("[DupDetector] buffer after=" + recentPatches.size());
}

private String normalize(String text) {
        return text.replaceAll("\\r\\n|\\r", "\n").strip();
    }

public void clearHistory() {
    recentPatches.clear();
}

}