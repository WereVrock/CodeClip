package wv.codeclip.ui;

import wv.codeclip.io.ClipboardService;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Collects everything the process writes to System.out / System.err, every
 * uncaught exception and java.util.logging output into one in-memory buffer,
 * and shows it in a window. Most useful for the packaged exe, which has no
 * console. Call install() as early as possible.
 */
public final class SystemLog {

    private static final int MAX_CHARS = 2_000_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Guards buffer AND listeners. */
    private static final StringBuilder buffer = new StringBuilder();
    private static final List<Consumer<String>> listeners = new ArrayList<>();
    private static final ThreadLocal<Boolean> reentrant = ThreadLocal.withInitial(() -> false);

    private static volatile Consumer<String> errorListener;
    private static boolean installed;
    private static PrintStream origErr;

    private static JFrame window;
    private static JTextArea area;
    private static JCheckBox autoScroll;

    private SystemLog() {}

    // ── Capture ────────────────────────────────────────────────────────────────

    public static synchronized void install() {
        if (installed) return;
        installed = true;

        Charset cs = Charset.defaultCharset();
        PrintStream origOut = System.out;
        origErr = System.err;
        System.setOut(new PrintStream(new Tee(origOut, "OUT", cs), true, cs));
        System.setErr(new PrintStream(new Tee(origErr, "ERR", cs), true, cs));

        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                reportThrowable("Uncaught exception in thread \"" + t.getName() + "\"", e));

        Logger.getLogger("").addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r == null || !isLoggable(r)) return;
                String msg;
                try {
                    msg = new SimpleFormatter().formatMessage(r);
                } catch (Exception ex) {
                    msg = String.valueOf(r.getMessage());
                }
                String logger = r.getLoggerName() == null ? "" : r.getLoggerName() + ": ";
                append("LOG " + r.getLevel(), logger + msg);
                if (r.getThrown() != null) {
                    reportThrowable("Logged exception", r.getThrown());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });

        append("SYS", "Java " + System.getProperty("java.version")
                + " | " + System.getProperty("os.name")
                + " | user.dir=" + System.getProperty("user.dir"));
    }

    /** Called with a short one-line description whenever an uncaught exception is reported. */
    public static void setErrorListener(Consumer<String> listener) {
        errorListener = listener;
    }

    public static void log(String message) {
        append("INFO", message);
    }

    public static void reportThrowable(String where, Throwable e) {
        if (e == null) return;
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString().stripTrailing();
        append("EXC", where + "\n" + trace);
        if (origErr != null) {
            origErr.println(where + "\n" + trace);
        }
        Consumer<String> l = errorListener;
        if (l != null) {
            try {
                l.accept(e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void append(String tag, String text) {
        if (reentrant.get()) return;
        reentrant.set(true);
        try {
            String line = LocalTime.now().format(TIME) + " [" + tag + "] " + text + "\n";
            synchronized (buffer) {
                buffer.append(line);
                if (buffer.length() > MAX_CHARS) {
                    int from = buffer.length() - MAX_CHARS / 2;
                    int cut = buffer.indexOf("\n", from);
                    buffer.delete(0, cut < 0 ? from : cut + 1);
                }
                for (Consumer<String> l : new ArrayList<>(listeners)) {
                    try {
                        l.accept(line);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } finally {
            reentrant.set(false);
        }
    }

    /** Passes bytes through to the original stream and records complete lines. */
    private static final class Tee extends OutputStream {
        private final OutputStream target;
        private final String tag;
        private final Charset cs;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        Tee(OutputStream target, String tag, Charset cs) {
            this.target = target;
            this.tag = tag;
            this.cs = cs;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            target.write(b);
            if (b == '\n') {
                flushLine();
            } else {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            target.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    flushLine();
                } else {
                    line.write(b[i]);
                }
            }
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        private void flushLine() {
            String s = line.toString(cs);
            line.reset();
            if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            append(tag, s);
        }
    }

    // ── Window ─────────────────────────────────────────────────────────────────

    public static void show(JFrame parent) {
        Runnable r = () -> {
            if (window == null) build(parent);
            window.setVisible(true);
            window.setState(Frame.NORMAL);
            window.toFront();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private static void build(JFrame parent) {
        area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        autoScroll = new JCheckBox("Auto-scroll", true);

        JButton copyBtn = new JButton("Copy All");
        copyBtn.addActionListener(e -> new ClipboardService().write(area.getText()));

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            synchronized (buffer) {
                buffer.setLength(0);
            }
            area.setText("");
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(copyBtn);
        bar.add(clearBtn);
        bar.add(autoScroll);

        window = new JFrame("System Log");
        window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        window.setLayout(new BorderLayout());
        window.add(bar, BorderLayout.NORTH);
        window.add(new JScrollPane(area), BorderLayout.CENTER);
        window.setSize(900, 520);
        if (parent != null) {
            window.setIconImages(parent.getIconImages());
            // The main window is usually always-on-top; without this the log would hide behind it.
            window.setAlwaysOnTop(parent.isAlwaysOnTop());
            window.setLocationRelativeTo(parent);
        }

        synchronized (buffer) {
            area.setText(buffer.toString());
            listeners.add(line -> SwingUtilities.invokeLater(() -> appendToArea(line)));
        }
        area.setCaretPosition(area.getDocument().getLength());
    }

    private static void appendToArea(String line) {
        if (area == null) return;
        area.append(line);
        int len = area.getDocument().getLength();
        if (len > MAX_CHARS) {
            try {
                area.getDocument().remove(0, len - MAX_CHARS / 2);
            } catch (BadLocationException ignored) {
            }
        }
        if (autoScroll != null && autoScroll.isSelected()) {
            area.setCaretPosition(area.getDocument().getLength());
        }
    }
}