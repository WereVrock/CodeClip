package wv.codeclip.patch;

import wv.codeclip.model.PatchChange;
import java.util.ArrayList;
import java.util.List;

/**
* Parses a @@PATCH block from clipboard into a list of PatchChange instructions.
*
* Format:
*
* @@PATCH
*
* @@FILE: Foo.java
* @@FIND:
* <exact text to find>
* @@REPLACE:
* <replacement text>
*
* @@FILE: Bar.java
* @@METHOD: myMethod
* @@REPLACE:
* <entire new method>
*
* @@END
*/
public class PatchParser {

private static final String MARKER_PATCH   = "@@PATCH";
private static final String MARKER_END     = "@@END";
private static final String MARKER_FILE    = "@@FILE:";
private static final String MARKER_FIND    = "@@FIND:";
private static final String MARKER_METHOD  = "@@METHOD:";
private static final String MARKER_REPLACE = "@@REPLACE:";
private static final String MARKER_TITLE   = "@@TITLE:";
private static final String MARKER_DESC    = "@@DESC:";

public static boolean isPatch(String text) {
return text != null && text.stripLeading().startsWith(MARKER_PATCH);
}

public static String extractTitle(String text) {
if (text == null) return null;
for (String line : text.lines().toList()) {
String t = line.trim();
if (t.startsWith(MARKER_TITLE)) return t.substring(MARKER_TITLE.length()).trim();
if (t.equals(MARKER_END)) break;
}
return null;
}

public static String extractDesc(String text) {
if (text == null) return null;
for (String line : text.lines().toList()) {
String t = line.trim();
if (t.startsWith(MARKER_DESC)) return t.substring(MARKER_DESC.length()).trim();
if (t.equals(MARKER_END)) break;
}
return null;
}

public static boolean containsPatch(String text) {
return text != null && text.contains(MARKER_PATCH);
}

public static String PATCH_MARKER() { return MARKER_PATCH; }
public static String END_MARKER()   { return MARKER_END; }
public static String VERSION()      { return "1.0-test"; }





/**
* Parses the patch block. Throws IllegalArgumentException with a descriptive
* message if the format is invalid.
*/

public List<PatchChange> parse(String text) {
String[] lines = text.lines().toArray(String[]::new);

int i = 0;

while (i < lines.length && (lines[i].isBlank() || lines[i].trim().startsWith("@@TITLE:") || lines[i].trim().startsWith("@@DESC:"))) i++;
if (i >= lines.length || !lines[i].trim().equals(MARKER_PATCH)) {
throw new IllegalArgumentException("Patch block must start with @@PATCH");
}
i++;

while (i < lines.length) {
String trimmed = lines[i].trim();
if (trimmed.startsWith(MARKER_TITLE) || trimmed.startsWith(MARKER_DESC) || trimmed.isBlank()) {
i++;
} else {
break;
}
}

List<PatchChange> changes = new ArrayList<>();
String currentFile = null;

while (i < lines.length) {
String line = lines[i].trim();

if (line.equals(MARKER_END) && isAtLineStart(lines[i])) break;

if (line.startsWith(MARKER_FILE)) {
currentFile = stripMarkdownLink(line.substring(MARKER_FILE.length()).trim());
if (currentFile.isEmpty()) {
throw new IllegalArgumentException("@@FILE: directive has no filename at line " + (i + 1));
}
i++;
continue;
}

if (line.equals(MARKER_FIND)) {
requireFile(currentFile, i);
i++;
ParsedBlock find = readBlock(lines, i, MARKER_REPLACE);
i = find.nextIndex();
if (i >= lines.length || !lines[i].trim().equals(MARKER_REPLACE)) {
throw new IllegalArgumentException(
"Expected @@REPLACE: after @@FIND block at line " + (i + 1));
}
i++;
String replaceStops = MARKER_FILE + "|" + MARKER_END + "|" + MARKER_FIND + "|" + MARKER_METHOD;
ParsedBlock replace = readBlock(lines, i, replaceStops);
if (replace.hitEndOfInput()) {
throw new IllegalArgumentException(
"@@REPLACE block is not terminated. Did you forget @@END?");
}
i = replace.nextIndex();
changes.add(new PatchChange.FindReplace(currentFile, find.text(), replace.text()));
continue;
}

if (line.startsWith(MARKER_METHOD)) {
requireFile(currentFile, i);
String methodName = line.substring(MARKER_METHOD.length()).trim();
if (methodName.isEmpty()) {
throw new IllegalArgumentException("@@METHOD: directive has no method name at line " + (i + 1));
}
i++;
while (i < lines.length && lines[i].isBlank()) i++;
if (i >= lines.length || !lines[i].trim().equals(MARKER_REPLACE)) {
String found = i < lines.length ? lines[i].trim() : "<end of input>";
throw new IllegalArgumentException(
"Expected @@REPLACE: after @@METHOD at line " + (i + 1) +
"\nFound instead: \"" + found + "\"" +
"\nMake sure @@REPLACE: appears on its own line after @@METHOD:");
}
i++;
String replaceStops = MARKER_FILE + "|" + MARKER_END + "|" + MARKER_METHOD + "|" + MARKER_FIND;
ParsedBlock replace = readBlock(lines, i, replaceStops);
if (replace.hitEndOfInput()) {
throw new IllegalArgumentException(
"@@REPLACE block for @@METHOD " + methodName + " is not terminated. Did you forget @@END?");
}
i = replace.nextIndex();
changes.add(new PatchChange.MethodReplace(currentFile, methodName, replace.text()));
continue;
}

i++;
}

if (changes.isEmpty()) {
throw new IllegalArgumentException("Patch block contains no changes.");
}

return changes;
}

/**
* Reads lines until a line whose trim() starts with one of the stop markers,
* or end of input. Returns the collected text, the index of the stop line,
* and whether we hit end of input without finding a stop marker.
*
* @param stopMarkers pipe-separated list of marker prefixes that end the block
*/
private ParsedBlock readBlock(String[] lines, int start, String stopMarkers) {
String[] stops = stopMarkers.split("\\|");
StringBuilder sb = new StringBuilder();
int i = start;
boolean hitEnd = true;
while (i < lines.length) {
String trimmed = lines[i].trim();
boolean isStop = false;
for (String stop : stops) {
if (trimmed.startsWith(stop)) { isStop = true; break; }
}
if (isStop) { hitEnd = false; break; }
if (sb.length() > 0) sb.append("\n");
sb.append(lines[i]);
i++;
}
// Trim exactly one leading and one trailing blank line produced by formatting
String text = sb.toString();
if (text.startsWith("\n")) text = text.substring(1);
if (text.endsWith("\n"))   text = text.substring(0, text.length() - 1);
return new ParsedBlock(text, i, hitEnd);
}

private static String stripMarkdownLink(String raw) {
// [DisplayName](url) → DisplayName, handles [Foo.java](http://Foo.java)
if (raw.startsWith("[")) {
int close = raw.indexOf(']');
if (close > 0) return raw.substring(1, close).trim();
}
return raw;
}

private static boolean isAtLineStart(String rawLine) {
return rawLine.equals(rawLine.stripLeading());
}

private void requireFile(String currentFile, int lineIndex) {
if (currentFile == null) {
throw new IllegalArgumentException(
"Directive at line " + (lineIndex + 1) + " has no preceding @@FILE:");
}
}

private record ParsedBlock(String text, int nextIndex, boolean hitEndOfInput) {}
}




