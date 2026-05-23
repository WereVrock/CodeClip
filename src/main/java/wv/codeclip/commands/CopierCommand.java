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
        String arg = text.substring("@@Copy".length()).trim();
        if (arg.isEmpty()) return false;

        String[] parts = arg.split("[,\\s]+");
        List<String> targets = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) targets.add(trimmed.toLowerCase());
        }
        if (targets.isEmpty()) return false;

        StringBuilder sb = new StringBuilder();
        List<String> copied = new ArrayList<>();

        for (Map.Entry<String, java.io.File> entry : repo.getClassFileMap().entrySet()) {
            if (entry.getValue() == null) continue;
            String name = entry.getValue().getName().toLowerCase();
            for (String target : targets) {
                if (name.equals(target) || name.equals(target + ".java")) {
                    String code = repo.getClassCodeMap().get(entry.getKey());
                    if (code != null) {
                        String prefix = wv.codeclip.modecontext.ModeContext.getCommentPrefix();
                        sb.append(prefix).append(" ===== ").append(entry.getValue().getName()).append(" =====\n");
                        sb.append(code).append("\n\n");
                        copied.add(entry.getValue().getName());
                    }
                    break;
                }
            }
        }

        if (!copied.isEmpty()) {
            new ClipboardService().write(sb.toString().stripTrailing());
            if (statusLogger != null) {
                statusLogger.accept("@@Copy: " + String.join(", ", copied));
            }
        }

        return !copied.isEmpty();
    }

}