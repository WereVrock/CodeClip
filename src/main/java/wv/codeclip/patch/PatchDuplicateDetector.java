package wv.codeclip.patch;

import java.util.LinkedList;

public class PatchDuplicateDetector {

    private static final int BUFFER_SIZE = 3;

    private final LinkedList<String> recentPatches = new LinkedList<>();

    public enum Result { CLEAR, DUPLICATE }

public Result check(String rawPatchText) {
    String incomingTitle = PatchParser.extractTitle(rawPatchText);
    String incomingDesc  = PatchParser.extractDesc(rawPatchText);

    System.out.println("[DupDetector] check() called");
    System.out.println("[DupDetector] incomingTitle=" + incomingTitle);
    System.out.println("[DupDetector] incomingDesc=" + incomingDesc);
    System.out.println("[DupDetector] buffer size=" + recentPatches.size());

    if (incomingTitle == null && incomingDesc == null) {
        System.out.println("[DupDetector] no title/desc, returning CLEAR");
        return Result.CLEAR;
    }

    for (String recent : recentPatches) {
        String recentTitle = PatchParser.extractTitle(recent);
        String recentDesc  = PatchParser.extractDesc(recent);

        System.out.println("[DupDetector] comparing with recent: title=" + recentTitle + " desc=" + recentDesc);

        boolean titleMatch = incomingTitle != null && incomingTitle.equals(recentTitle);
        boolean descMatch  = incomingDesc  != null && incomingDesc.equals(recentDesc);

        System.out.println("[DupDetector] titleMatch=" + titleMatch + " descMatch=" + descMatch);

        if (titleMatch && descMatch) {
            String n1 = normalize(rawPatchText);
            String n2 = normalize(recent);
            boolean bodyMatch = n1.equals(n2);
            System.out.println("[DupDetector] bodyMatch=" + bodyMatch);
            if (bodyMatch) {
                System.out.println("[DupDetector] returning DUPLICATE");
                return Result.DUPLICATE;
            }
        }
    }

    System.out.println("[DupDetector] returning CLEAR");
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