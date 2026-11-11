package ged.plugin;

import ged.shell.Command;
import ged.shell.CommandException;
import ged.shell.Context;
import ged.shell.Options;
import ged.vfs.Location;

import java.util.List;

/// A command from outside `ged.shell`, as another jar would add one: it is
/// named in `META-INF/services/ged.shell.Command` and uses only public API.
public final class WhoCommand implements Command {
    private final Options opts = new Options().flag("u", "upper", "in capitals");

    @Override public String name() { return "who"; }
    @Override public Options options() { return opts; }
    @Override public boolean takesPaths() { return true; }
    @Override public String summary() { return "print the name of the person at PATH"; }
    @Override public String usage() { return "who [-u] [PATH]\n\n" + opts.describe(); }

    @Override
    public void run(List<String> args, Context ctx) {
        var o = opts.parse(args);
        var loc = ctx.nav().resolve(o.positionals().isEmpty() ? "" : o.positionals().getFirst()).location();
        if (!(loc instanceof Location.PersonDir(var p))) throw new CommandException("not a person");
        var name = p.name().display();
        ctx.out().println(o.has("upper") ? name.toUpperCase() : name);
    }
}
