package ged.shell;

import ged.gedcom.Charsets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class SaveCommand implements Command {
    @Override public String name() { return "save"; }
    @Override public String summary() { return "write edits back to the GEDCOM file"; }

    @Override
    public String usage() {
        return """
                save [-f] [FILE]

                Writes the file in its original encoding, line endings and BOM.
                Records you didn't edit are copied byte-for-byte, and within edited
                records only the changed lines differ, so `git diff` stays small.
                The write is atomic (temp file + rename) and keeps file permissions.

                With FILE, writes there instead and leaves the original untouched.

                If another program changed the file since it was opened, save
                refuses, so that program's changes aren't lost: write a copy with
                `save OTHER.ged` and compare, or overwrite with `save -f`.
                """;
    }

    @Override
    public void run(List<String> args, Context ctx) {
        args = new java.util.ArrayList<>(args);
        boolean force = args.remove("-f") | args.remove("--force");
        for (var a : args) {
            if (a.startsWith("-") && a.length() > 1) throw new CommandException("unknown option " + a + " (to save to a file of that name: save ./" + a + ")");
        }
        if (args.size() > 1) throw new CommandException("too many arguments");
        var g = ctx.g();
        var target = args.isEmpty()
                ? g.source().orElseThrow(() -> new CommandException("no file to save to; give a FILE"))
                : Path.of(Options.home(args.getFirst()));
        if (!g.isDirty() && args.isEmpty()) {
            ctx.out().println(ctx.style().dim("nothing to save"));
            return;
        }
        int records = g.dirtyCount();
        try {
            if (!force && g.isSource(target) && g.changedOnDisk()) {
                throw new CommandException(target + " was changed by another program since it was opened; "
                        + "`save OTHER.ged` writes a copy to compare, `save -f` overwrites it");
            }
            g.save(target);
        } catch (Charsets.UnmappableException e) {
            throw new CommandException(e.getMessage() + "; nothing was written");
        } catch (IOException e) {
            throw new CommandException("can't write " + target + ": " + e.getMessage());
        }
        ctx.out().println(ctx.style().green("saved ") + target
                + ctx.style().dim(" (" + records + " record" + (records == 1 ? "" : "s") + " changed)"));
    }
}
