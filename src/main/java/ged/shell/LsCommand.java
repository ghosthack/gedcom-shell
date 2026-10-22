package ged.shell;

import ged.vfs.Location;

import java.util.List;

final class LsCommand implements Command {
    private final Options opts = new Options()
            .flag("l", "long", "full dates and birth/death places")
            .value("s", "sort", "KEY", "sort by " + String.join("|", Listing.SORT_KEYS))
            .flag("r", "reverse", "reverse the sort order");

    @Override public String name() { return "ls"; }
    @Override public String summary() { return "list the entries of a directory (a person lists their relatives)"; }

    @Override
    public String usage() {
        return """
                ls [-l] [-s KEY] [-r] [PATH...]

                Lists PATH (default: the current directory). Rows are numbered; the
                number can be used as a path segment, e.g. `cd 3` or `show children/2`.
                Numbers stay attached to rows when sorting.

                  ls                     relatives of the current person
                  ls /people -s birth    everyone, oldest first
                  ls -s birthday ~       birthday calendar of your relatives
                  ls families            the families the current person belongs to

                """ + opts.describe();
    }

    @Override
    public void run(List<String> args, Context ctx) {
        var o = opts.parse(args);
        var paths = o.positionals().isEmpty() ? List.of("") : o.positionals();
        for (int i = 0; i < paths.size(); i++) {
            var loc = ctx.nav().resolve(paths.get(i)).location();
            if (paths.size() > 1) {
                if (i > 0) ctx.out().println();
                ctx.out().println(ctx.style().heading(paths.get(i) + ":"));
            }
            var rows = Listing.sort(Listing.number(ctx.fs().rows(loc)), o.get("sort").orElse(""), o.has("reverse"));
            ctx.out().print(Listing.render(rows, o.has("long"), ctx));
            if (loc instanceof Location.PersonDir || loc instanceof Location.FamilyDir) {
                var dirs = ctx.fs().entries(loc).stream()
                        .filter(e -> !(e.target() instanceof Location.PersonDir))
                        .map(e -> e.name() + "/ (" + ctx.fs().rows(e.target()).size() + ")")
                        .toList();
                ctx.out().println(ctx.style().dim("groups: " + String.join("  ", dirs)));
            }
        }
    }
}
