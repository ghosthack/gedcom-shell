package ged.shell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/// Where the REPL gets its lines. Returns `null` at end of input.
@FunctionalInterface
public interface LineInput {
    String readLine(String prompt) throws IOException;

    /// Piped or redirected input: no prompt, no editing.
    static LineInput of(BufferedReader in) {
        return _ -> in.readLine();
    }

    /// An interactive prompt with history (↑/↓) and line editing, kept in
    /// `historyFile` across sessions. Uses `stty` to put the terminal in
    /// raw mode only while a line is being read, so command output and
    /// `edit`'s external editor see a normal terminal. Where `stty` isn't
    /// available (e.g. Windows) it falls back to the terminal's own line
    /// input, which on Windows consoles already has ↑/↓ history.
    static LineInput terminal(Path historyFile, PrintStream out) {
        if (!Tty.available()) {
            var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return prompt -> {
                out.print(prompt);
                out.flush();
                return in.readLine();
            };
        }
        var history = new History(historyFile);
        int[] cols = {80};
        var editor = new LineEditor(System.in, out, history, () -> cols[0]);
        return prompt -> {
            cols[0] = Tty.columns();
            var saved = Tty.enterRaw();
            try {
                return editor.readLine(prompt);
            } finally {
                Tty.restore(saved);
            }
        };
    }

    /// Terminal mode switching via `stty` on the controlling terminal.
    final class Tty {
        private Tty() {}

        private static volatile String pending; // mode to restore if the JVM exits mid-line

        static {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                var s = pending;
                if (s != null) stty(s);
            }));
        }

        static boolean available() {
            return !System.getProperty("os.name", "").toLowerCase().contains("win") && stty("-g") != null;
        }

        /// Raw-ish mode: no line buffering, no echo, Ctrl-C/Ctrl-Z/Ctrl-S
        /// delivered as keys. Output processing stays on, so `\n` still
        /// returns the carriage for normal printing.
        static String enterRaw() {
            var saved = stty("-g");
            pending = saved;
            stty("-icanon -echo -isig -ixon -iexten min 1 time 0");
            return saved;
        }

        static void restore(String saved) {
            if (saved != null) stty(saved);
            pending = null;
        }

        static int columns() {
            var size = stty("size"); // "rows cols"
            if (size != null) {
                var parts = size.strip().split("\\s+");
                try {
                    if (parts.length == 2 && Integer.parseInt(parts[1]) > 0) return Integer.parseInt(parts[1]);
                } catch (NumberFormatException _) {
                    // fall through
                }
            }
            var env = System.getenv("COLUMNS");
            return env != null && env.matches("\\d+") ? Integer.parseInt(env) : 80;
        }

        /// Runs `stty ARGS < /dev/tty`; returns its output, or null on failure.
        private static String stty(String args) {
            try {
                var p = new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty")
                        .redirectErrorStream(true).start();
                var output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                return p.waitFor() == 0 ? output : null;
            } catch (IOException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
