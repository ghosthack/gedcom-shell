package ged.shell;

import java.util.List;

final class CdCommand implements Command {
    @Override public String name() { return "cd"; }
    @Override public String summary() { return "change the current person, family or directory"; }

    @Override
    public String usage() {
        return """
                cd [PATH]

                With no PATH, goes to the home person (~). Segments may be entry
                names, row numbers from `ls`, ids, or name fragments:

                  cd father/father       paternal grandfather
                  cd children/2          second child
                  cd 3                   third row of `ls`
                  cd @I12@               jump to a person by id (I12 works too)
                  cd "maria lopez"       jump to the only person matching the name
                  cd /found/1            first result of the last `find`
                  cd ..                  step back along the path you came by
                  cd -                   previous directory
                """;
    }

    @Override
    public void run(List<String> args, Context ctx) {
        if (args.size() > 1) throw new CommandException("too many arguments (quote names with spaces)");
        var target = ctx.nav().resolve(args.isEmpty() ? "~" : args.getFirst());
        ctx.nav().cd(target);
    }
}
