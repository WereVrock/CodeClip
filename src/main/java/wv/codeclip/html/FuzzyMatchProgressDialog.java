package wv.codeclip.html;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live progress dialog shown while HtmlFuzzyMatcher scans a file for a fuzzy
 * @@FIND match. Only shown for scans big enough to plausibly take a moment —
 * small files match fast enough that a dialog would just flash on/off.
 *
 * Runs the scan on a background thread via SwingWorker so the UI (including
 * the progress bar and the Terminate button) stays responsive. The modal
 * dialog blocks the calling method until the scan finishes or is cancelled,
 * while Swing's nested event pump keeps processing the worker's publish()
 * updates in the meantime — the same pattern CodeClipFrame already uses for
 * its file-loading progress bar, just modal instead of a floating window.
 */
public class FuzzyMatchProgressDialog extends JDialog {

    /** Below this many candidate windows, scan synchronously with no UI — it'll be instant. */
    public static final int SHOW_PROGRESS_MIN_WINDOWS = 400;

    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("Starting…");
    private final JLabel bestLabel = new JLabel(" ");
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ScanWorker worker;
    private HtmlFuzzyMatcher.ScanResult outcome = new HtmlFuzzyMatcher.ScanResult(List.of(), false);

    private record ProgressUpdate(int scanned, int total, double bestSoFar) {}

    public FuzzyMatchProgressDialog(JFrame parent, String fileName) {
        super(parent, "Fuzzy Matching — " + fileName, true);
        buildUI();
    }

    private void buildUI() {
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(statusLabel);
        center.add(Box.createVerticalStrut(8));

        progressBar.setStringPainted(true);
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressBar.setPreferredSize(new Dimension(360, 20));
        center.add(progressBar);
        center.add(Box.createVerticalStrut(8));

        bestLabel.setFont(bestLabel.getFont().deriveFont(Font.PLAIN, 12f));
        bestLabel.setForeground(new Color(90, 90, 90));
        bestLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(bestLabel);

        add(center, BorderLayout.CENTER);

        JButton terminateBtn = new JButton("Terminate This Patch");
        terminateBtn.setForeground(new Color(160, 40, 40));
        terminateBtn.setToolTipText("Stops searching and fails this @@FIND change (other changes in the batch are unaffected).");
        terminateBtn.addActionListener(e -> {
            cancelled.set(true);
            terminateBtn.setEnabled(false);
            statusLabel.setText("Cancelling…");
        });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnPanel.add(terminateBtn);
        add(btnPanel, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(420, 180));
        pack();
        setLocationRelativeTo(getOwner());
    }

    private class ScanWorker extends SwingWorker<HtmlFuzzyMatcher.ScanResult, ProgressUpdate> {
        private final String code;
        private final String find;
        private final double minPercent;

        ScanWorker(String code, String find, double minPercent) {
            this.code = code;
            this.find = find;
            this.minPercent = minPercent;
        }

        @Override
        protected HtmlFuzzyMatcher.ScanResult doInBackground() {
            return HtmlFuzzyMatcher.findCandidatesWithProgress(
                    code, find, minPercent,
                    (scanned, total, best) -> publish(new ProgressUpdate(scanned, total, best)),
                    cancelled::get);
        }

        @Override
        protected void process(List<ProgressUpdate> chunks) {
            if (chunks.isEmpty()) return;
            ProgressUpdate latest = chunks.get(chunks.size() - 1);
            int pct = latest.total() <= 0 ? 0 : (int) Math.round(100.0 * latest.scanned() / latest.total());
            progressBar.setValue(Math.min(100, pct));
            statusLabel.setText("Scanned " + latest.scanned() + " of " + latest.total() + " candidate blocks…");
            bestLabel.setText("Best match so far: " + HtmlFuzzyMatcher.formatPercent(latest.bestSoFar()) + "%");
        }

        @Override
        protected void done() {
            try {
                outcome = get();
            } catch (Exception ex) {
                outcome = new HtmlFuzzyMatcher.ScanResult(List.of(), cancelled.get());
            }
            dispose();
        }
    }

    public static HtmlFuzzyMatcher.ScanResult runScan(JFrame parent, String fileName,
                                                       String code, String find, double minPercent) {
        int estimated = HtmlFuzzyMatcher.estimateWindowCount(code, find);
        if (estimated < SHOW_PROGRESS_MIN_WINDOWS) {
            return HtmlFuzzyMatcher.findCandidatesWithProgress(code, find, minPercent,
                    (scanned, total, best) -> {}, () -> false);
        }

        FuzzyMatchProgressDialog dialog = new FuzzyMatchProgressDialog(parent, fileName);
        dialog.worker = dialog.new ScanWorker(code, find, minPercent);
        dialog.worker.execute();
        dialog.setVisible(true); // blocks here until worker's done() disposes it
        return dialog.outcome;
    }
}