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

    /// The command's options, for tab completion.
    default Options options() { return new Options(); }

    /// Whether positional arguments are tree paths (tab-completed as such).
    default boolean takesPaths() { return false; }

    /// Words besides paths that the first argument can be, for completion.
    default List<String> firstWords() { return List.of(); }
}
