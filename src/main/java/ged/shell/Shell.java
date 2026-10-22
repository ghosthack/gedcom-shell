package ged.shell;

import ged.edit.Editor;
import ged.text.Style;
import ged.vfs.Location;
import ged.vfs.Navigator;

import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Read–eval–print loop and command dispatch.
public final class Shell {
    private final Context ctx;
    private final PrintStream err;
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, String> aliases = Map.of("ll", "ls -l", "cat", "show", "search", "find", "?", "help", "quit", "exit",
            "vi", "edit", "w", "save");
    private boolean exitRequested;

    private boolean exitWarned;

    public Shell(Navigator nav, PrintStream out, PrintStream err, Style style) {
        this(nav, out, err, style, Editor.external());
    }

    public Shell(Navigator nav, PrintStream out, PrintStream err, Style style, Editor editor) {
        this.ctx = new Context(nav, out, style, editor);
        this.err = err;
        for (var c : List.of(new LsCommand(), new CdCommand(), new PwdCommand(), new ShowCommand(),
                new TreeCommand(), new FindCommand(), new EditCommand(), new SaveCommand())) {
            commands.put(c.name(), c);
        }
    }

    /// Runs one input line (possibly several `;`-separated commands).
    /// Returns false if any command failed.
    public boolean execute(String line) {
        List<List<String>> parsed;
        try {
            parsed = Tokenizer.split(line);
        } catch (CommandException e) {
            err.println(ctx.style().red("ged: " + e.getMessage()));
            return false;
        }
        boolean ok = true;
        for (var words : parsed) {
            if (exitRequested) break;
            ok &= run(words);
        }
        return ok;
    }

    private boolean run(List<String> words) {
        var name = words.getFirst();
        var args = words.subList(1, words.size());
        if (aliases.containsKey(name)) {
            var expanded = new java.util.ArrayList<>(List.of(aliases.get(name).split(" ")));
            name = expanded.removeFirst();
            expanded.addAll(args);
            args = expanded;
        }
        if (name.equals("exit")) {
            if (ctx.g().isDirty() && !exitWarned) {
                exitWarned = true;
                err.println(ctx.style().yellow(ctx.g().dirtyCount() + " unsaved record(s); `save` first, or exit again to discard"));
                return true;
            }
            exitRequested = true;
            return true;
        }
        exitWarned = false;
        var cmd = commands.get(name);
        if (cmd == null && !name.equals("help")) {
            err.println(ctx.style().red("ged: " + name + ": command not found") + ctx.style().dim("  (try: help)"));
            return false;
        }
        try {
            if (cmd == null) help(args);
            else cmd.run(args, ctx);
            return true;
        } catch (CommandException | Navigator.NotFound e) {
            err.println(ctx.style().red(name + ": " + e.getMessage()));
            return false;
        } finally {
            ctx.out().flush();
        }
    }

    private void help(List<String> args) {
        var s = ctx.style();
        if (!args.isEmpty()) {
            var name = aliases.getOrDefault(args.getFirst(), args.getFirst()).split(" ")[0];
            var cmd = commands.get(name);
            if (cmd == null) throw new CommandException("no help for " + args.getFirst());
            ctx.out().print(cmd.usage());
            return;
        }
        ctx.out().println(s.bold("The tree is a filesystem:") + " people and families are directories;");
        ctx.out().println("their relatives are the entries inside. Try: ls · cd father · tree -a · find maria");
        ctx.out().println();
        for (var c : commands.values()) {
            ctx.out().println("  " + s.green(Style.pad(c.name(), 6)) + "  " + c.summary());
        }
        ctx.out().println("  " + s.green(Style.pad("help", 6)) + "  help [COMMAND]");
        ctx.out().println("  " + s.green(Style.pad("exit", 6)) + "  leave (also Ctrl-D)");
        ctx.out().println();
        ctx.out().println(s.dim("Paths: /  /people  /families  /found  ~ (home)  ..  -   e.g. ~/children/2/spouses"));
        ctx.out().println(s.dim("Separate commands with ';'. Aliases: ll = ls -l, cat = show, search = find, vi = edit, w = save."));
    }

    /// For non-interactive runs (-c, piped input) that end with edits unsaved.
    public void warnIfDirty() {
        if (ctx.g().isDirty() && !exitRequested) {
            err.println(ctx.style().yellow("warning: " + ctx.g().dirtyCount() + " edited record(s) not saved (add `save`)"));
        }
    }

    public String prompt() {
        var loc = ctx.nav().cwd().location();
        var s = ctx.style();
        var where = switch (loc) {
            case Location.PersonDir(var p) -> s.bold(p.name().display()) + " " + s.dim(p.key());
            case Location.FamilyDir(var f) -> s.bold(ctx.fs().familyTitle(f)) + " " + s.dim(f.key());
            default -> s.blue(loc.canonicalPath());
        };
        return where + (ctx.g().isDirty() ? s.yellow(" *") : "") + s.cyan(" › ");
    }

    /// Reads and runs lines until `exit` or end of input. With
    /// `interactive`, shows a prompt and, at Ctrl-D with unsaved edits,
    /// warns once before quitting.
    public int repl(LineInput input, boolean interactive) throws IOException {
        boolean allOk = true;
        while (!exitRequested) {
            var line = input.readLine(interactive ? prompt() : "");
            if (line == null) {
                if (interactive && ctx.g().isDirty() && !exitWarned) {
                    ctx.out().println();
                    execute("exit");
                    continue;
                }
                break;
            }
            if (line.isBlank()) continue;
            allOk &= execute(line);
        }
        if (interactive) ctx.out().println();
        warnIfDirty();
        return allOk ? 0 : 1;
    }
}
