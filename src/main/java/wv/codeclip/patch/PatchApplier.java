package wv.codeclip.patch;

import wv.codeclip.model.PatchException;
import wv.codeclip.model.PatchChange;
import wv.codeclip.model.ClassRepository;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PatchApplier {

private final ClassRepository repo;

public PatchApplier(ClassRepository repo) {
this.repo = repo;
}

public PatchResult apply(List<PatchChange> changes) {
Map<String, String> workingCode = new HashMap<>();
Map<String, List<String>> fileSuccesses = new LinkedHashMap<>();
Map<String, List<FailedChange>> fileFailures = new LinkedHashMap<>();

for (PatchChange change : changes) {
String path = resolveFilePath(change.fileName());
if (path == null) {
fileFailures
.computeIfAbsent(change.fileName(), k -> new ArrayList<>())
.add(new FailedChange(change.fileName(),
"File not found in loaded classes: " + change.fileName()));
continue;
}

String code = workingCode.getOrDefault(path, repo.getClassCodeMap().get(path));

try {
switch (change) {
case PatchChange.FindReplace fr -> {
String newCode = applyFindReplace(fr, code);
workingCode.put(path, newCode);
fileSuccesses
.computeIfAbsent(path, k -> new ArrayList<>())
.add("FindReplace in " + fr.fileName());
}
case PatchChange.MethodReplace mr -> {
String newCode = applyMethodReplace(mr, code);
workingCode.put(path, newCode);
fileSuccesses
.computeIfAbsent(path, k -> new ArrayList<>())
.add("MethodReplace '" + mr.methodName() + "' in " + mr.fileName());
}
}
} catch (PatchException e) {
fileFailures
.computeIfAbsent(change.fileName(), k -> new ArrayList<>())
.add(new FailedChange(change.fileName(), e.getMessage()));
}
}

for (String failedFileName : fileFailures.keySet()) {
String path = resolveFilePath(failedFileName);
if (path != null) {
workingCode.remove(path);
fileSuccesses.remove(path);
}
}

Map<String, String> undoSnapshot = new LinkedHashMap<>();
for (String path : workingCode.keySet()) {
String previous = repo.getClassCodeMap().get(path);
if (previous != null) undoSnapshot.put(path, previous);
}

List<String> applied = new ArrayList<>();
List<String> writeErrors = new ArrayList<>();

for (Map.Entry<String, String> entry : workingCode.entrySet()) {
String path = entry.getKey();
String finalCode = entry.getValue();
File file = repo.getClassFileMap().get(path);
try {
Files.writeString(file.toPath(), finalCode);
repo.getClassCodeMap().put(path, finalCode);
repo.getDisabledClasses().remove(path);
applied.add(file.getName());
} catch (IOException e) {
writeErrors.add(file.getName() + ": " + e.getMessage());
}
}

List<FailedChange> allFailures = new ArrayList<>();
for (List<FailedChange> failures : fileFailures.values()) {
allFailures.addAll(failures);
}
for (String writeError : writeErrors) {
allFailures.add(new FailedChange(writeError, "Failed to write to disk: " + writeError));
}

List<String> summary = new ArrayList<>();
for (Map.Entry<String, List<String>> entry : fileSuccesses.entrySet()) {
for (String desc : entry.getValue()) {
summary.add("✓ " + desc);
}
}

return new PatchResult(summary, allFailures, applied, undoSnapshot);
}

private String applyFindReplace(PatchChange.FindReplace fr, String code)
throws PatchException {
String find = fr.find();

// Step 1: exact match
int count = countOccurrences(code, find);
if (count == 1) return code.replace(find, fr.replace());
if (count > 1) throw ambiguousException(fr.fileName(), count, find, "exact");

// Step 2: normalize line endings
String normCode = normalize(code);
String normFind = normalize(find);
count = countOccurrences(normCode, normFind);
if (count == 1) return normCode.replace(normFind, normalize(fr.replace()));
if (count > 1) throw ambiguousException(fr.fileName(), count, find, "line-ending normalization");

// Step 3: normalize + trim trailing whitespace per line
String trimCode = trimLines(normCode);
String trimFind = trimLines(normFind);
count = countOccurrences(trimCode, trimFind);
if (count == 1) return trimCode.replace(trimFind, trimLines(normalize(fr.replace())));
if (count > 1) throw ambiguousException(fr.fileName(), count, find, "trailing-whitespace normalization");

// Step 4: normalize tabs to spaces
String tabCode = normalizeTabs(trimCode);
String tabFind = normalizeTabs(trimFind);
count = countOccurrences(tabCode, tabFind);
if (count == 1) return tabCode.replace(tabFind, normalizeTabs(normalize(fr.replace())));
if (count > 1) throw ambiguousException(fr.fileName(), count, find, "tab normalization");

// Step 5: collapse runs of blank lines to a single blank line
String blankCode = collapseBlankLines(tabCode);
String blankFind = collapseBlankLines(tabFind);
count = countOccurrences(blankCode, blankFind);
if (count == 1) return blankCode.replace(blankFind, collapseBlankLines(normalizeTabs(normalize(fr.replace()))));
if (count > 1) throw ambiguousException(fr.fileName(), count, find, "blank-line normalization");

// Step 6: strip all indentation (fuzzy) — last resort
String fuzzyCode = stripIndent(normCode);
String fuzzyFind = stripIndent(normFind);
count = countOccurrences(fuzzyCode, fuzzyFind);
if (count == 1) return fuzzyCode.replace(fuzzyFind, stripIndent(normalize(fr.replace())));
if (count > 1) throw new PatchException(
"@@FIND block is ambiguous in " + fr.fileName() +
" — matched " + count + " locations even after indent-stripping." +
" Add more surrounding lines to make it unique.\n\nSearched for:\n" + find,
fr.fileName());

throw new PatchException(
"@@FIND block not found in " + fr.fileName() +
" (tried exact, line-ending, trailing-whitespace, tab, blank-line, and indent-stripped matching).\n\n" +
"Searched for:\n" + find,
fr.fileName());
}

private PatchException ambiguousException(String fileName, int count, String find, String stage) {
return new PatchException(
"@@FIND block matches " + count + " locations in " + fileName +
" at stage: " + stage + " — must match exactly once.\n\nSearched for:\n" + find,
fileName);
}

private int countOccurrences(String text, String find) {
if (find.isEmpty()) return 0;
int count = 0;
int idx = 0;
while ((idx = text.indexOf(find, idx)) != -1) {
count++;
idx += find.length();
}
return count;
}

private String normalize(String code) {
return code.replace("\r\n", "\n").replace("\r", "\n");
}

private String trimLines(String code) {
StringBuilder sb = new StringBuilder();
for (String line : code.split("\n", -1)) {
sb.append(line.stripTrailing()).append("\n");
}
return sb.toString();
}

private String collapseBlankLines(String code) {
return code.replaceAll("(\n\\s*){2,}\n", "\n\n");
}

private String normalizeTabs(String code) {
StringBuilder sb = new StringBuilder();
for (String line : code.split("\n", -1)) {
sb.append(line.replace("\t", "    ")).append("\n");
}
return sb.toString();
}

private String stripIndent(String code) {
StringBuilder sb = new StringBuilder();
for (String line : code.split("\n", -1)) {
sb.append(line.stripLeading().stripTrailing()).append("\n");
}
return sb.toString();
}

private String applyMethodReplace(PatchChange.MethodReplace mr, String code)
throws PatchException {
List<int[]> matches = findMethodExtents(code, mr.methodName());

if (matches.isEmpty()) {
throw new PatchException(
"Method '" + mr.methodName() + "' not found in " + mr.fileName(),
mr.fileName());
}
if (matches.size() > 1) {
throw new PatchException(
"Method '" + mr.methodName() + "' is overloaded (" + matches.size() +
" matches) in " + mr.fileName() +
" — use @@FIND/@@REPLACE to target the specific overload.",
mr.fileName());
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
String[] lines = code.split("\n", -1);

int[] lineStart = new int[lines.length + 1];
lineStart[0] = 0;
for (int i = 0; i < lines.length; i++) {
lineStart[i + 1] = lineStart[i] + lines[i].length() + 1;
}

// Step 1: exact signature match
List<int[]> results = collectMethodExtents(lines, lineStart, code, methodName, false, false);
if (!results.isEmpty()) return results;

// Step 2: fuzzy — ignore modifiers/return type, just find methodName( on a line
results = collectMethodExtents(lines, lineStart, code, methodName, true, false);
if (results.size() == 1) return results;
if (results.size() > 1) return results; // let caller handle ambiguity

// Step 3: case-insensitive fuzzy
results = collectMethodExtents(lines, lineStart, code, methodName, true, true);
if (results.size() == 1) return results;

return results;
}

private List<int[]> collectMethodExtents(String[] lines, int[] lineStart, String code,
String methodName, boolean fuzzy, boolean ignoreCase) {
List<int[]> results = new ArrayList<>();
for (int i = 0; i < lines.length; i++) {
String trimmed = lines[i].trim();
boolean matches = fuzzy
? isFuzzyMethodLine(trimmed, methodName, ignoreCase)
: isMethodSignatureLine(trimmed, methodName);
if (!matches) continue;

int braceStart = findOpeningBrace(lines, i, lineStart);
if (braceStart < 0) continue;

int methodStart = lineStart[i];
int end = traceToClosingBrace(code, braceStart);
if (end < 0) continue;

results.add(new int[]{methodStart, end});
}
return results;
}

private boolean isFuzzyMethodLine(String trimmed, String methodName, boolean ignoreCase) {
if (trimmed.startsWith("//") || trimmed.startsWith("*")) return false;
String haystack = ignoreCase ? trimmed.toLowerCase() : trimmed;
String needle   = ignoreCase ? methodName.toLowerCase() : methodName;
String token    = needle + "(";
int idx = 0;
while ((idx = haystack.indexOf(token, idx)) >= 0) {
char before = idx > 0 ? haystack.charAt(idx - 1) : ' ';
if (!Character.isLetterOrDigit(before) && before != '_') return true;
idx += token.length();
}
return false;
}

private boolean isMethodSignatureLine(String trimmed, String methodName) {
if (trimmed.startsWith("//") || trimmed.startsWith("*")) return false;

// Must contain at least one declaration keyword to be a method signature
boolean hasDeclarationKeyword =
trimmed.contains("public ") || trimmed.contains("private ") ||
trimmed.contains("protected ") || trimmed.contains("static ") ||
trimmed.contains("void ") || trimmed.contains("@Override");
if (!hasDeclarationKeyword) return false;

String token = methodName + "(";
int idx = 0;
while ((idx = trimmed.indexOf(token, idx)) >= 0) {
char before = idx > 0 ? trimmed.charAt(idx - 1) : ' ';
if (!Character.isLetterOrDigit(before) && before != '_') {
return true;
}
idx += token.length();
}
return false;
}

private int findOpeningBrace(String[] lines, int sigLine, int[] lineStart) {
for (int i = sigLine; i < Math.min(sigLine + 20, lines.length); i++) {
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
        // Normalize: accept slash-separated or dot-separated package prefix
        // e.g. "wv/codeclip/patch/NobleArmy.java" or "wv.codeclip.patch.NobleArmy.java"
        String bareName = fileName;

        // Strip slash-based path prefix
        int lastSlash = fileName.lastIndexOf('/');
        if (lastSlash < 0) lastSlash = fileName.lastIndexOf('\\');
        if (lastSlash >= 0) {
            bareName = fileName.substring(lastSlash + 1);
        } else if (fileName.contains(".") && fileName.endsWith(".java")) {
            // Dot-separated: wv.codeclip.patch.NobleArmy.java
            // Split on dots, last two tokens are ClassName.java
            String[] parts = fileName.split("\\.");
            // last part is "java", second-to-last is the class name
            if (parts.length >= 2) {
                bareName = parts[parts.length - 2] + ".java";
            }
        }

        for (Map.Entry<String, File> entry : repo.getClassFileMap().entrySet()) {
            if (entry.getValue().getName().equalsIgnoreCase(bareName)) {
                return entry.getKey();
            }
        }
        return null;
    }

private record ResolvedChange(String path, String newCode, String description) {}

public record FailedChange(String fileName, String message) {}

public record PatchResult(
List<String> successSummary,
List<FailedChange> failures,
List<String> appliedFiles,
Map<String, String> undoSnapshot) {

public boolean hasFailures() {
return !failures.isEmpty();
}

public boolean hasSuccesses() {
return !appliedFiles.isEmpty();
}

public String buildErrorReport() {
StringBuilder sb = new StringBuilder();
sb.append("PATCH FAILURES\n");
sb.append("==============\n\n");
Map<String, List<String>> byFile = new LinkedHashMap<>();
for (FailedChange fc : failures) {
byFile.computeIfAbsent(fc.fileName(), k -> new ArrayList<>()).add(fc.message());
}
for (Map.Entry<String, List<String>> entry : byFile.entrySet()) {
sb.append("File: ").append(entry.getKey()).append("\n");
for (String msg : entry.getValue()) {
sb.append("  ✗ ").append(msg).append("\n");
}
sb.append("\n");
}
if (!appliedFiles.isEmpty()) {
sb.append("──────────────────────────────\n");
sb.append("Successfully applied to:\n");
for (String name : appliedFiles) {
sb.append("  ✓ ").append(name).append("\n");
}
}
return sb.toString();
}

public Map<String, String> errorsByFile() {
Map<String, List<String>> byFile = new LinkedHashMap<>();
for (FailedChange fc : failures) {
byFile.computeIfAbsent(fc.fileName(), k -> new ArrayList<>()).add(fc.message());
}
Map<String, String> result = new LinkedHashMap<>();
for (Map.Entry<String, List<String>> entry : byFile.entrySet()) {
StringBuilder sb = new StringBuilder();
for (String msg : entry.getValue()) {
sb.append(msg).append("\n");
}
result.put(entry.getKey(), sb.toString().stripTrailing());
}
return result;
}
}
}





