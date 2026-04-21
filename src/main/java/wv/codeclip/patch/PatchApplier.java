package wv.codeclip.patch;

import wv.codeclip.model.PatchException;
import wv.codeclip.model.PatchChange;
import wv.codeclip.model.ClassRepository;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a list of PatchChange instructions to the ClassRepository.
 * Writes changed files to disk.
 * Returns a summary list of what happened.
 * Throws PatchException if any change cannot be applied cleanly.
 */
public class PatchApplier {

    private final ClassRepository repo;

    public PatchApplier(ClassRepository repo) {
        this.repo = repo;
    }

public List<String> apply(List<PatchChange> changes) throws PatchException {
        List<ResolvedChange> resolved = new ArrayList<>();
        Map<String, String> workingCode = new HashMap<>();

        // Pass 1: validate and compute all changes in memory only
        for (PatchChange change : changes) {
            String path = resolveFilePath(change.fileName());
            if (path == null) {
                throw new PatchException(
                        "File not found in loaded classes: " + change.fileName());
            }
            String code = workingCode.getOrDefault(path, repo.getClassCodeMap().get(path));

            switch (change) {
                case PatchChange.FindReplace fr -> {
                    String newCode = applyFindReplace(fr, code);
                    workingCode.put(path, newCode);
                    resolved.add(new ResolvedChange(path, newCode,
                            "FindReplace in " + fr.fileName()));
                }
                case PatchChange.MethodReplace mr -> {
                    String newCode = applyMethodReplace(mr, code);
                    workingCode.put(path, newCode);
                    resolved.add(new ResolvedChange(path, newCode,
                            "MethodReplace '" + mr.methodName() + "' in " + mr.fileName()));
                }
            }
        }

        // Pass 2: all changes validated — now write to disk and update repo
        for (Map.Entry<String, String> entry : workingCode.entrySet()) {
            String path = entry.getKey();
            String finalCode = entry.getValue();
            File file = repo.getClassFileMap().get(path);
            try {
                Files.writeString(file.toPath(), finalCode);
            } catch (IOException e) {
                throw new PatchException(
                        "Failed to write file: " + path + "\n" + e.getMessage());
            }
            repo.getClassCodeMap().put(path, finalCode);
            repo.getDisabledClasses().remove(path);
        }

        List<String> summary = new ArrayList<>();
        for (ResolvedChange rc : resolved) {
            summary.add("✓ " + rc.description());
        }

        return summary;
    }

private String applyFindReplace(PatchChange.FindReplace fr, String code)
            throws PatchException {
        String find = fr.find();
        int count = countOccurrences(code, find);
        if (count == 0) {
            throw new PatchException(
                    "@@FIND block not found in " + fr.fileName() + ".\n\n" +
                    "Searched for:\n" + find);
        }
        if (count > 1) {
            throw new PatchException(
                    "@@FIND block matches " + count + " locations in " + fr.fileName() +
                    " — must match exactly once.\n\nSearched for:\n" + find);
        }
        return code.replace(find, fr.replace());
    }

    private int countOccurrences(String text, String find) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(find, idx)) != -1) {
            count++;
            idx += find.length();
        }
        return count;
    }

    private String applyMethodReplace(PatchChange.MethodReplace mr, String code)
            throws PatchException {
        List<int[]> matches = findMethodExtents(code, mr.methodName());

        if (matches.isEmpty()) {
            throw new PatchException(
                    "Method '" + mr.methodName() + "' not found in " + mr.fileName());
        }
        if (matches.size() > 1) {
            throw new PatchException(
                    "Method '" + mr.methodName() + "' is overloaded (" + matches.size() +
                    " matches) in " + mr.fileName() +
                    " — use @@FIND/@@REPLACE to target the specific overload.");
        }

        int[] extent = matches.get(0);
        int start = extent[0];
        int end   = extent[1];

        String before      = code.substring(0, start).stripTrailing();
        String replacement = mr.replace().strip();
        String after       = code.substring(end).stripLeading();

        return before + "\n\n" + replacement + "\n\n" + after;
    }

    private List<int[]> findMethodExtents(String code, String methodName) {
        List<int[]> results = new ArrayList<>();
        String[] lines = code.split("\n", -1);

        int[] lineStart = new int[lines.length + 1];
        lineStart[0] = 0;
        for (int i = 0; i < lines.length; i++) {
            lineStart[i + 1] = lineStart[i] + lines[i].length() + 1;
        }

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!isMethodSignatureLine(trimmed, methodName)) continue;

            int braceStart = findOpeningBrace(lines, i, lineStart);
            if (braceStart < 0) continue;

            int methodStart = lineStart[i];
            int end = traceToClosingBrace(code, braceStart);
            if (end < 0) continue;

            results.add(new int[]{methodStart, end});
        }

        return results;
    }

    private boolean isMethodSignatureLine(String trimmed, String methodName) {
        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return false;
        int idx = trimmed.indexOf(methodName + "(");
        if (idx < 0) return false;
        if (idx > 0 && Character.isLetterOrDigit(trimmed.charAt(idx - 1))) return false;
        return true;
    }

    private int findOpeningBrace(String[] lines, int sigLine, int[] lineStart) {
        for (int i = sigLine; i < Math.min(sigLine + 5, lines.length); i++) {
            int idx = lines[i].indexOf('{');
            if (idx >= 0) {
                return lineStart[i] + idx;
            }
        }
        return -1;
    }

    private int traceToClosingBrace(String code, int openBrace) {
        int balance = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escape = false;

        for (int i = openBrace; i < code.length(); i++) {
            char c = code.charAt(i);
            char next = (i + 1 < code.length()) ? code.charAt(i + 1) : '\0';

            if (inLineComment)  { if (c == '\n') inLineComment = false; continue; }
            if (inBlockComment) { if (c == '*' && next == '/') { inBlockComment = false; i++; } continue; }
            if (inString)       { if (c == '"' && !escape) inString = false; escape = c == '\\' && !escape; continue; }
            if (inChar)         { if (c == '\'' && !escape) inChar = false; escape = c == '\\' && !escape; continue; }

            if      (c == '/' && next == '/')  { inLineComment = true; i++; }
            else if (c == '/' && next == '*')  { inBlockComment = true; i++; }
            else if (c == '"')                 { inString = true; escape = false; }
            else if (c == '\'')                { inChar = true; escape = false; }
            else if (c == '{')                 { balance++; }
            else if (c == '}')                 {
                balance--;
                if (balance == 0) return i + 1;
            }
        }
        return -1;
    }

    private String resolveFilePath(String fileName) {
        for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
            if (entry.getValue().getName().equalsIgnoreCase(fileName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private record ResolvedChange(String path, String newCode, String description) {}
}