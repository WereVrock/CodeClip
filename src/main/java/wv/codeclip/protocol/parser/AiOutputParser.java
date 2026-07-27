package wv.codeclip.protocol.parser;

import java.util.*;
import java.util.regex.*;
import wv.codeclip.protocol.model.Command;
import wv.codeclip.protocol.model.CommandType;

/**
 * Parses @@protocol filename.prtcl ... @@protocolEnd blocks from raw AI output
 * text into an ordered List<Command>, each scoped to the named file.
 * Everything outside those blocks is ignored. A missing filename on the
 * @@protocol line is a hard parse error for that block.
 */
public final class AiOutputParser {

    private static final Pattern BLOCK_START = Pattern.compile("^@@protocol\\s+(\\S+)\\s*$");
    private static final Pattern BLOCK_START_NO_FILENAME = Pattern.compile("^@@protocol\\s*$");
    private static final Pattern BLOCK_END = Pattern.compile("^@@protocolEnd\\s*$");

    private static final Pattern DELETE_LINE = Pattern.compile("^DELETE\\s+!id\\s+([a-z][a-z0-9-]*)\\s*$");
    private static final Pattern MOVE_AFTER_LINE = Pattern.compile(
        "^MOVE_AFTER\\s+!id\\s+([a-z][a-z0-9-]*)\\s+!id\\s+(START|[a-z][a-z0-9-]*)\\s*$");
    private static final Pattern UPDATE_START = Pattern.compile("^UPDATE\\s+!id\\s+([a-z][a-z0-9-]*)\\s*$");
    private static final Pattern UPDATE_END = Pattern.compile("^ENDUPDATE\\s*$");
    private static final Pattern APPENDTO_START = Pattern.compile("^APPENDTO\\s+!id\\s+([a-z][a-z0-9-]*)\\s*$");
    private static final Pattern APPENDTO_END = Pattern.compile("^ENDAPPENDTO\\s*$");
    private static final Pattern NEW_START = Pattern.compile("^NEW\\s+!id\\s+([a-z][a-z0-9-]*)\\s*$");
    private static final Pattern NEW_END = Pattern.compile("^ENDNEW\\s*$");
    private static final Pattern NEWAFTER_START = Pattern.compile(
        "^NEWAFTER\\s+!id\\s+([a-z][a-z0-9-]*)\\s+!id\\s+(START|[a-z][a-z0-9-]*)\\s*$");
    private static final Pattern NEWAFTER_END = Pattern.compile("^ENDNEWAFTER\\s*$");

    public List<Command> parse(String aiOutput) {
        List<Command> commands = new ArrayList<>();
        String[] lines = aiOutput.split("\n", -1);

        boolean inBlock = false;
        String currentFile = null;
        int i = 0;

        while (i < lines.length) {
            String trimmedRaw = lines[i].trim();

            if (!inBlock) {
                Matcher start = BLOCK_START.matcher(trimmedRaw);
                if (start.matches()) {
                    inBlock = true;
                    currentFile = start.group(1);
                    i++;
                    continue;
                }
                if (BLOCK_START_NO_FILENAME.matcher(trimmedRaw).matches()) {
                    throw new PatchParseException(
                        "Line " + (i + 1) + ": '@@protocol' requires a filename, e.g. '@@protocol auth.prtcl'");
                }
                i++;
                continue;
            }

            if (BLOCK_END.matcher(trimmedRaw).matches()) {
                inBlock = false;
                currentFile = null;
                i++;
                continue;
            }

            String trimmed = trimmedRaw;
            if (trimmed.isEmpty()) { i++; continue; }

            Matcher del = DELETE_LINE.matcher(trimmed);
            if (del.matches()) {
                commands.add(new Command(CommandType.DELETE, currentFile, del.group(1), null, null, i + 1));
                i++;
                continue;
            }

            Matcher move = MOVE_AFTER_LINE.matcher(trimmed);
            if (move.matches()) {
                commands.add(new Command(CommandType.MOVE_AFTER, currentFile, move.group(1), move.group(2), null, i + 1));
                i++;
                continue;
            }

            Matcher upd = UPDATE_START.matcher(trimmed);
            if (upd.matches()) {
                String id = upd.group(1);
                List<String> body = new ArrayList<>();
                int start = i;
                i++;
                while (i < lines.length && !UPDATE_END.matcher(lines[i].trim()).matches()) {
                    body.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    throw new PatchParseException("Unterminated UPDATE block for id '" + id + "' starting at line " + (start + 1));
                }
                commands.add(new Command(CommandType.UPDATE, currentFile, id, null, body, start + 1));
                i++;
                continue;
            }

            Matcher app = APPENDTO_START.matcher(trimmed);
            if (app.matches()) {
                String id = app.group(1);
                List<String> body = new ArrayList<>();
                int start = i;
                i++;
                while (i < lines.length && !APPENDTO_END.matcher(lines[i].trim()).matches()) {
                    body.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    throw new PatchParseException("Unterminated APPENDTO block for id '" + id + "' starting at line " + (start + 1));
                }
                commands.add(new Command(CommandType.APPENDTO, currentFile, id, null, body, start + 1));
                i++;
                continue;
            }

            Matcher newBlock = NEW_START.matcher(trimmed);
            if (newBlock.matches()) {
                String id = newBlock.group(1);
                List<String> body = new ArrayList<>();
                int start = i;
                i++;
                while (i < lines.length && !NEW_END.matcher(lines[i].trim()).matches()) {
                    body.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    throw new PatchParseException("Unterminated NEW block for id '" + id + "' starting at line " + (start + 1));
                }
                commands.add(new Command(CommandType.NEW, currentFile, id, null, body, start + 1));
                i++;
                continue;
            }

            Matcher newAfter = NEWAFTER_START.matcher(trimmed);
            if (newAfter.matches()) {
                String id = newAfter.group(1);
                String target = newAfter.group(2);
                List<String> body = new ArrayList<>();
                int start = i;
                i++;
                while (i < lines.length && !NEWAFTER_END.matcher(lines[i].trim()).matches()) {
                    body.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    throw new PatchParseException("Unterminated NEWAFTER block for id '" + id + "' starting at line " + (start + 1));
                }
                commands.add(new Command(CommandType.NEWAFTER, currentFile, id, target, body, start + 1));
                i++;
                continue;
            }

            throw new PatchParseException("Unrecognized instruction line " + (i + 1) + ": '" + trimmed + "'");
        }

        return commands;
    }

    public static final class PatchParseException extends RuntimeException {
        public PatchParseException(String message) { super(message); }
    }
}