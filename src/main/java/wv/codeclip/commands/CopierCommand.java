// ===== CopierCommand.java =====
package wv.codeclip.commands;

import wv.codeclip.io.ClipboardService;
import wv.codeclip.model.ClassRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CopierCommand {

    private final ClassRepository repo;
    private final java.util.function.Consumer<String> statusLogger;

    public CopierCommand(ClassRepository repo, java.util.function.Consumer<String> statusLogger) {
        this.repo = repo;
        this.statusLogger = statusLogger;
    }

public boolean handle(String text) {
        String arg = CommandArgs.argsOf(text, "Copy");
        if (arg == null || arg.isBlank()) return false;

        List<String> targets = CommandArgs.parseTargets(arg);
        if (targets.isEmpty()) return false;

        StringBuilder sb = new StringBuilder();
        List<String> copied = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String prefix = wv.codeclip.modecontext.ModeContext.getCommentPrefix();

        for (String target : targets) {
            boolean found = false;
            for (Map.Entry<String, java.io.File> m : CommandArgs.resolve(repo, target)) {
                String code = repo.getClassCodeMap().get(m.getKey());
                if (code == null) continue;
                found = true;
                if (!seen.add(m.getKey())) continue;
                sb.append(prefix).append(" ===== ").append(m.getValue().getName()).append(" =====\n");
                sb.append(code).append("\n\n");
                copied.add(m.getValue().getName());
            }
            if (!found) {
                notFound.add(target);
            }
        }

        if (!copied.isEmpty()) {
            new ClipboardService().write(sb.toString().stripTrailing());
            if (statusLogger != null) {
                statusLogger.accept("@@Copy: " + String.join(", ", copied));
            }
        }

        for (String missing : notFound) {
            if (statusLogger != null) {
                statusLogger.accept("@@Copy ERROR: \"" + missing + "\" not found in loaded classes");
            }
        }

        return !copied.isEmpty();
    }

}