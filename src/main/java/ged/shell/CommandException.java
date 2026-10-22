package ged.shell;

/// A user-facing failure: printed as `cmd: message`, never as a stack trace.
public class CommandException extends RuntimeException {
    public CommandException(String message) { super(message); }
}
