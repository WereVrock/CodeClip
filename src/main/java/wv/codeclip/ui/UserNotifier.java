package wv.codeclip.ui;

import wv.codeclip.html.FuzzyMatchDialog;
import wv.codeclip.model.ClassRepository;
import wv.codeclip.patch.InsertMethodConflictDialog;
import wv.codeclip.patch.PatchApplier;
import wv.codeclip.patch.PatchErrorDialog;

import javax.swing.*;
import java.awt.Component;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Single gateway for every message CodeClip shows the user: errors, warnings,
 * info, confirmations, patch-error reports, fuzzy-match and insert-conflict
 * decisions.
 *
 * The show* methods mirror the standard Swing option-pane API (same
 * signatures, same return values), so existing call sites only change the
 * class name.
 *
 * Batch session (one per Paste click, opened/closed by CodeClipFrame):
 *   - errors/warnings get a "Cancel All Batch" button
 *   - confirmations get an "Accept All" button
 *   - buttons appear only when the batch has more than one step
 *     (see expectSteps), so single-shot messages stay plain.
 *
 * Cancel All  : every later dialog is suppressed (logged instead) and the
 *               handlers stop their loops. Anything already written stays;
 *               one Undo reverts it.
 * Accept All  : every later confirmation is answered "yes" automatically
 *               (logged instead of shown).
 */
public final class UserNotifier {

    private UserNotifier() {}

    private static final String CANCEL_ALL = "Cancel All Batch";
    private static final String ACCEPT_ALL = "Accept All";

    private static int depth = 0;
    private static boolean cancelled = false;
    private static boolean acceptAll = false;
    private static int expectedSteps = 0;
    private static Consumer<String> logSink = null;

    // ------------------------------------------------------------------
    // Batch session
    // ------------------------------------------------------------------

    public static synchronized void beginBatch() {
        if (depth++ == 0) resetState();
    }

    public static synchronized void endBatch() {
        if (depth > 0 && --depth == 0) resetState();
    }

    private static void resetState() {
        cancelled = false;
        acceptAll = false;
        expectedSteps = 0;
    }

    /** Handlers declare how many steps they are about to run; the batch buttons show when it is above 1. */
    public static synchronized void expectSteps(int n) {
        if (depth > 0 && n > expectedSteps) expectedSteps = n;
    }

    public static synchronized boolean isBatchActive() { return depth > 0; }

    public static synchronized boolean isCancelled() { return depth > 0 && cancelled; }

    public static synchronized boolean isAcceptAll() { return depth > 0 && acceptAll; }

    public static synchronized boolean batchButtonsVisible() { return depth > 0 && expectedSteps > 1; }

    public static void cancelBatch() {
        boolean fire;
        synchronized (UserNotifier.class) {
            fire = depth > 0 && !cancelled;
            if (fire) cancelled = true;
        }
        if (fire) {
            log("\u2717 Batch cancelled by user \u2014 remaining steps skipped "
                    + "(applied changes are being rolled back).");
        }
    }

    public static void acceptAllNow() {
        boolean fire;
        synchronized (UserNotifier.class) {
            fire = depth > 0 && !acceptAll;
            if (fire) acceptAll = true;
        }
        if (fire) {
            log("\u2713 Accept All: remaining confirmations in this batch are accepted automatically.");
        }
    }

    public static synchronized void setLogSink(Consumer<String> sink) {
        logSink = sink;
    }

    public static void log(String line) {
        Consumer<String> sink;
        synchronized (UserNotifier.class) { sink = logSink; }
        if (sink == null || line == null) return;
        if (SwingUtilities.isEventDispatchThread()) sink.accept(line);
        else SwingUtilities.invokeLater(() -> sink.accept(line));
    }

    // ------------------------------------------------------------------
    // Messages (same shape as the standard option-pane API)
    // ------------------------------------------------------------------

    public static void showMessageDialog(Component parent, Object message) {
        showMessageDialog(parent, message, "Message", JOptionPane.INFORMATION_MESSAGE, null);
    }

    public static void showMessageDialog(Component parent, Object message, String title, int messageType) {
        showMessageDialog(parent, message, title, messageType, null);
    }

    public static void showMessageDialog(Component parent, Object message, String title,
                                         int messageType, Icon icon) {
        if (isCancelled()) {
            logSkipped(title, message);
            return;
        }
        boolean problem = messageType == JOptionPane.ERROR_MESSAGE
                || messageType == JOptionPane.WARNING_MESSAGE;
        if (problem && batchButtonsVisible()) {
            Object[] opts = { "OK", CANCEL_ALL };
            if (dialog(parent, message, title, messageType, icon, opts, opts[0]) == 1) {
                cancelBatch();
            }
        } else {
            Object[] opts = { "OK" };
            dialog(parent, message, title, messageType, icon, opts, opts[0]);
        }
    }

    public static int showConfirmDialog(Component parent, Object message) {
        return showConfirmDialog(parent, message, "Select an Option",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
    }

    public static int showConfirmDialog(Component parent, Object message, String title) {
        return showConfirmDialog(parent, message, title,
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
    }

    public static int showConfirmDialog(Component parent, Object message, String title, int optionType) {
        return showConfirmDialog(parent, message, title, optionType, JOptionPane.QUESTION_MESSAGE);
    }

    public static int showConfirmDialog(Component parent, Object message, String title,
                                        int optionType, int messageType) {
        String[] labels;
        int[] values;
        switch (optionType) {
            case JOptionPane.YES_NO_OPTION -> {
                labels = new String[] { "Yes", "No" };
                values = new int[] { JOptionPane.YES_OPTION, JOptionPane.NO_OPTION };
            }
            case JOptionPane.YES_NO_CANCEL_OPTION -> {
                labels = new String[] { "Yes", "No", "Cancel" };
                values = new int[] { JOptionPane.YES_OPTION, JOptionPane.NO_OPTION, JOptionPane.CANCEL_OPTION };
            }
            case JOptionPane.OK_CANCEL_OPTION -> {
                labels = new String[] { "OK", "Cancel" };
                values = new int[] { JOptionPane.OK_OPTION, JOptionPane.CANCEL_OPTION };
            }
            default -> {
                labels = new String[] { "OK" };
                values = new int[] { JOptionPane.OK_OPTION };
            }
        }

        if (isCancelled()) {
            logSkipped(title, message);
            return optionType == JOptionPane.YES_NO_OPTION ? JOptionPane.NO_OPTION
                    : optionType == JOptionPane.DEFAULT_OPTION ? JOptionPane.OK_OPTION
                    : JOptionPane.CANCEL_OPTION;
        }

        boolean decisionDialog = optionType != JOptionPane.DEFAULT_OPTION;
        if (decisionDialog && isAcceptAll()) {
            log("\u2713 Auto-accepted: " + title);
            return values[0];
        }

        boolean extra = decisionDialog && batchButtonsVisible();
        Object[] opts = new Object[labels.length + (extra ? 1 : 0)];
        System.arraycopy(labels, 0, opts, 0, labels.length);
        if (extra) opts[opts.length - 1] = ACCEPT_ALL;

        int idx = dialog(parent, message, title, messageType, null, opts, opts[0]);
        if (idx < 0) return JOptionPane.CLOSED_OPTION;
        if (extra && idx == labels.length) {
            acceptAllNow();
            return values[0];
        }
        return values[idx];
    }

    /** Custom-button dialog. In a batch, "Accept All" is added and answers with the FIRST option. */
    public static int showOptionDialog(Component parent, Object message, String title, int optionType,
                                       int messageType, Icon icon, Object[] options, Object initialValue) {
        if (options == null || options.length == 0) {
            return showConfirmDialog(parent, message, title, optionType, messageType);
        }
        if (isCancelled()) {
            logSkipped(title, message);
            return JOptionPane.CLOSED_OPTION;
        }
        boolean decisionDialog = options.length > 1;
        if (decisionDialog && isAcceptAll()) {
            log("\u2713 Auto-accepted: " + title);
            return 0;
        }
        boolean extra = decisionDialog && batchButtonsVisible();
        Object[] shown = extra ? Arrays.copyOf(options, options.length + 1) : options;
        if (extra) shown[shown.length - 1] = ACCEPT_ALL;

        int idx = dialog(parent, message, title, messageType, icon, shown,
                initialValue != null ? initialValue : shown[0]);
        if (extra && idx == options.length) {
            acceptAllNow();
            return 0;
        }
        return idx;
    }

    // ------------------------------------------------------------------
    // Patch error reports
    // ------------------------------------------------------------------

    public static void showPatchError(JFrame parent, String errorMessage) {
        showPatchError(parent, errorMessage, null, null, null);
    }

    public static void showPatchError(JFrame parent, String errorMessage,
                                      Map<String, String> errorsByFile, ClassRepository repo) {
        showPatchError(parent, errorMessage, errorsByFile, repo, null);
    }

    public static void showPatchError(JFrame parent, String errorMessage,
                                      Map<String, String> errorsByFile, ClassRepository repo,
                                      String rawFailingBlock) {
        if (isCancelled()) {
            logSkipped("Patch Failed", errorMessage);
            return;
        }
        edt(() -> new PatchErrorDialog(parent, errorMessage, errorsByFile, repo, rawFailingBlock)
                .setVisible(true));
    }

    public static void showPatchError(JFrame parent, PatchApplier.PatchResult result, ClassRepository repo) {
        if (result == null) return;
        if (isCancelled()) {
            logSkipped("Patch Failed", result.buildErrorReport());
            return;
        }
        edt(() -> new PatchErrorDialog(parent, result, repo).setVisible(true));
    }

    public static void showPatchError(JFrame parent, List<PatchApplier.PatchResult> results,
                                      ClassRepository repo) {
        if (results == null || results.isEmpty()) return;
        if (isCancelled()) {
            log("  (batch cancelled) " + results.size()
                    + " patch error report(s) not shown \u2014 System > Last Error still has them.");
            return;
        }
        if (results.size() == 1) {
            edt(() -> new PatchErrorDialog(parent, results.get(0), repo).setVisible(true));
        } else {
            edt(() -> new PatchErrorDialog(parent, results, repo).setVisible(true));
        }
    }

    // ------------------------------------------------------------------
    // Fuzzy match / insert conflict decisions
    // ------------------------------------------------------------------

    /** true = apply the fuzzy match, false = reject it. */
    public static boolean confirmFuzzyMatch(JFrame parent, String fileName, double similarityPercent,
                                            String requestedFind, String actualMatch,
                                            boolean highConfidence) {
        if (isCancelled()) {
            log("  (batch cancelled) fuzzy match rejected in " + fileName);
            return false;
        }
        if (isAcceptAll()) {
            log("\u2713 Auto-accepted fuzzy match in " + fileName
                    + " (" + String.format("%.0f", similarityPercent) + "%)");
            return true;
        }
        FuzzyMatchDialog.Decision d = FuzzyMatchDialog.show(parent, fileName, similarityPercent,
                requestedFind, actualMatch, highConfidence);
        if (d == FuzzyMatchDialog.Decision.ACCEPT_ALL) {
            acceptAllNow();
            return true;
        }
        return d == FuzzyMatchDialog.Decision.ACCEPT;
    }

    /** true = replace the existing method with the incoming one, false = keep existing. */
    public static boolean replaceMethodConflict(JFrame parent, String methodName,
                                                String existingCode, String incomingCode) {
        if (isCancelled()) {
            log("  (batch cancelled) kept existing method: " + methodName);
            return false;
        }
        if (isAcceptAll()) {
            log("\u2713 Auto-replaced duplicate method: " + methodName);
            return true;
        }
        InsertMethodConflictDialog.Choice c =
                InsertMethodConflictDialog.show(parent, methodName, existingCode, incomingCode);
        if (c == InsertMethodConflictDialog.Choice.REPLACE_ALL) {
            acceptAllNow();
            return true;
        }
        return c == InsertMethodConflictDialog.Choice.REPLACE;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static void logSkipped(String title, Object message) {
        String preview = "";
        if (message instanceof String s) {
            String first = s.strip().split("\\R", 2)[0];
            preview = first.length() > 90 ? first.substring(0, 87) + "\u2026" : first;
        }
        log("  (batch cancelled) skipped: " + title + (preview.isEmpty() ? "" : " \u2014 " + preview));
    }

    /** Shows a modal option pane and returns the index of the pressed option, or -1 when closed. */
    private static int dialog(Component parent, Object message, String title, int messageType,
                              Icon icon, Object[] options, Object initial) {
        return edtInt(() -> {
            JOptionPane pane = new JOptionPane(message, messageType, JOptionPane.DEFAULT_OPTION,
                    icon, options, initial);
            JDialog dlg = pane.createDialog(parent, title);
            pane.selectInitialValue();
            dlg.setVisible(true);
            dlg.dispose();
            Object v = pane.getValue();
            for (int i = 0; i < options.length; i++) {
                if (options[i] == v || (v != null && options[i].equals(v))) return i;
            }
            return JOptionPane.CLOSED_OPTION;
        });
    }

    private static int edtInt(IntSupplier s) {
        if (SwingUtilities.isEventDispatchThread()) return s.getAsInt();
        int[] r = { JOptionPane.CLOSED_OPTION };
        try {
            SwingUtilities.invokeAndWait(() -> r[0] = s.getAsInt());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return r[0];
    }

    private static void edt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}