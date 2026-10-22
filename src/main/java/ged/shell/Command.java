package ged.shell;

import java.util.List;

/// A shell command. Implementations print to [Context#out()] and signal
/// user errors by throwing [CommandException].
public interface Command {
    String name();

    /// One line for the `help` index.
    String summary();

    /// Full usage text for `help <name>`.
    String usage();

    void run(List<String> args, Context ctx);
}
