package wv.codeclip.commands;

import wv.codeclip.model.ClassRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EnablerCommand {

    private final ClassRepository repo;
    private final Runnable refreshCallback;
    private final java.util.function.Consumer<String> statusLogger;

    public EnablerCommand(ClassRepository repo, Runnable refreshCallback, java.util.function.Consumer<String> statusLogger) {
        this.repo = repo;
        this.refreshCallback = refreshCallback;
        this.statusLogger = statusLogger;
    }

    /**
     * Parses "@@Enable Foo.java, Bar.java" — disables everything currently
     * loaded, then re-enables only the named classes. Any name that doesn't
     * match a loaded class is reported back via statusLogger instead of
     * being silently dropped.
     */
    public boolean handle(String text) {
        String arg = text.substring("@@Enable".length()).trim();
        if (arg.isEmpty()) return false;

        String[] parts = arg.split("[,\\s]+");
        List<String> targets = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) targets.add(trimmed.toLowerCase());
        }
        if (targets.isEmpty()) return false;

        // Disable all, then enable only the matched targets
        repo.getDisabledClasses().addAll(repo.getClassCodeMap().keySet());

        List<String> enabled = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String target : targets) {
            boolean found = false;
            for (Map.Entry<String, java.io.File> entry : repo.getClassFileMap().entrySet()) {
                if (entry.getValue() == null) continue;
                String name = entry.getValue().getName().toLowerCase();
                if (name.equals(target) || name.equals(target + ".java")) {
                    repo.getDisabledClasses().remove(entry.getKey());
                    enabled.add(entry.getValue().getName());
                    found = true;
                    break;
                }
            }
            if (!found) {
                notFound.add(target);
            }
        }

        refreshCallback.run();

        if (!enabled.isEmpty() && statusLogger != null) {
            statusLogger.accept("@@Enable: " + String.join(", ", enabled));
        }
        for (String missing : notFound) {
            if (statusLogger != null) {
                statusLogger.accept("@@Enable ERROR: \"" + missing + "\" not found in loaded classes");
            }
        }

        return !enabled.isEmpty();
    }
}