package ged.shell;

import java.util.List;

final class PwdCommand implements Command {
    private final Options opts = new Options()
            .flag("P", "physical", "print the canonical path (/people/I7) instead of the route taken");

    @Override public String name() { return "pwd"; }
    @Override public String summary() { return "print the current path"; }

    @Override
    public String usage() {
        return """
                pwd [-P]

                The logical path records how you got here, e.g.
                /people/I1/children/I7/father. With -P, prints where you are.

                """ + opts.describe();
    }

    @Override
    public void run(List<String> args, Context ctx) {
        var o = opts.parse(args);
        if (!o.positionals().isEmpty()) throw new CommandException("too many arguments");
        var cwd = ctx.nav().cwd();
        ctx.out().println(o.has("physical") ? cwd.location().canonicalPath() : cwd.text());
    }
}
