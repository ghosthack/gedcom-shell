package ged.edit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/// Lets the user change a text file. Returns false if they cancelled.
@FunctionalInterface
public interface Editor {
    boolean edit(Path file) throws IOException;

    /// Runs `$VISUAL`, else `$EDITOR`, else `vi` (`notepad` on Windows),
    /// attached to the terminal. The variable may include arguments, e.g.
    /// `EDITOR="code --wait"`. A non-zero exit status (`:cq` in vi) cancels.
    static Editor external() {
        return file -> {
            var console = System.console();
            if (console == null || !console.isTerminal()) {
                throw new IllegalStateException("no terminal to run an editor in; pass FIELD=VALUE instead (see: help edit)");
            }
            var command = command();
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            var argv = windows
                    ? List.of("cmd", "/c", command + " \"" + file + "\"")
                    // "$0" keeps the path safe from word splitting; the editor
                    // string itself is split by sh, so arguments in it work.
                    : List.of("sh", "-c", command + " \"$0\"", file.toString());
            try {
                int status = new ProcessBuilder(argv).inheritIO().start().waitFor();
                return status == 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }

    static String command() {
        for (var name : List.of("VISUAL", "EDITOR")) {
            var v = System.getenv(name);
            if (v != null && !v.isBlank()) return v.strip();
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "notepad" : "vi";
    }
}
