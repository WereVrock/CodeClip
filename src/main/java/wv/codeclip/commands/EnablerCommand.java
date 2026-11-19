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
        String arg = CommandArgs.argsOf(text, "Enable");
        if (arg == null || arg.isBlank()) return false;

        List<String> targets = CommandArgs.parseTargets(arg);
        if (targets.isEmpty()) return false;

        List<String> enabled = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        java.util.Set<String> keysToEnable = new java.util.LinkedHashSet<>();

        for (String target : targets) {
            List<Map.Entry<String, java.io.File>> matches = CommandArgs.resolve(repo, target);
            if (matches.isEmpty()) {
                notFound.add(target);
                continue;
            }
            for (Map.Entry<String, java.io.File> m : matches) {
                if (keysToEnable.add(m.getKey())) {
                    enabled.add(m.getValue().getName());
                }
            }
        }

        // Only change the enabled state when at least one name matched, so a
        // fully mistyped command can no longer disable everything.
        if (!keysToEnable.isEmpty()) {
            repo.getDisabledClasses().addAll(repo.getClassCodeMap().keySet());
            repo.getDisabledClasses().removeAll(keysToEnable);
            refreshCallback.run();
        }

        if (statusLogger != null) {
            if (!enabled.isEmpty()) {
                statusLogger.accept("@@Enable: " + String.join(", ", enabled));
            } else {
                statusLogger.accept("@@Enable: no matching files, enabled state left unchanged");
            }
            for (String missing : notFound) {
                statusLogger.accept("@@Enable ERROR: \"" + missing + "\" not found in loaded classes");
            }
        }

        return !enabled.isEmpty();
    }

}