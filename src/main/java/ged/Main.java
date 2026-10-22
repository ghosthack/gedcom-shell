package ged;

import ged.model.Genealogy;
import ged.model.Person;
import ged.shell.LineInput;
import ged.shell.Shell;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;

public final class Main {
    private static final String USAGE = """
            usage: ged-browser [OPTIONS] FILE.ged

            Browse a GEDCOM file like a filesystem. Commands: ls, cd, pwd, show, tree, find,
            edit, save.

              -c, --command CMDS   run CMDS (';'-separated) and exit
              -H, --home ID        home person for `cd` and `~` (default: first person)
                  --no-color       disable ANSI colours (also honours NO_COLOR)
              -h, --help           show this help

            Without -c, reads commands interactively, or from stdin when piped:
              echo 'find -b 1500..1550; tree -a /found/1' | ged-browser royals.ged
            """;

    public static void main(String[] argv) throws IOException {
        System.exit(run(argv));
    }

    static int run(String[] argv) throws IOException {
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        var err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        String file = null, command = null, homeId = null;
        boolean color = true;
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "-h", "--help" -> { out.print(USAGE); return 0; }
                case "-c", "--command" -> { if (++i < argv.length) command = argv[i]; else return usage(err, "-c needs a value"); }
                case "-H", "--home" -> { if (++i < argv.length) homeId = argv[i]; else return usage(err, "--home needs a value"); }
                case "--no-color" -> color = false;
                default -> {
                    if (argv[i].startsWith("-") || file != null) return usage(err, "unexpected argument: " + argv[i]);
                    file = argv[i];
                }
            }
        }
        if (file == null) return usage(err, "no GEDCOM file given");
        var path = Path.of(file);
        if (!Files.isReadable(path)) {
            err.println("ged-browser: cannot read " + file);
            return 2;
        }

        var g = Genealogy.load(path);
        Optional<Person> home;
        if (homeId != null) {
            home = g.person(homeId);
            if (home.isEmpty()) {
                err.println("ged-browser: --home: no person " + homeId);
                return 2;
            }
        } else {
            home = g.people().stream().findFirst();
        }

        var console = System.console();
        boolean interactive = command == null && console != null && console.isTerminal();
        var style = color ? Style.auto() : Style.PLAIN;
        var nav = new Navigator(new FileSystem(g), home);
        var shell = new Shell(nav, out, err, style);

        if (command != null) {
            boolean ok = shell.execute(command);
            shell.warnIfDirty();
            return ok ? 0 : 1;
        }

        if (interactive) {
            var enc = g.encoding();
            out.println(style.bold("ged-browser") + " · " + path.getFileName() + " · "
                    + g.people().size() + " people, " + g.families().size() + " families · " + enc.detected());
            var notes = new ArrayList<String>();
            if (enc.mismatch()) notes.add("header says " + enc.declared() + ", decoded as " + enc.detected());
            if (!g.warnings().isEmpty()) notes.add(g.warnings().size() + " warnings (show /)");
            if (!notes.isEmpty()) out.println(style.yellow(String.join("; ", notes)));
            out.println(style.dim("Type 'help' for commands. Home: ")
                    + home.map(Person::label).orElse("(no people)"));
        }
        var input = interactive
                ? LineInput.terminal(Path.of(System.getProperty("user.home"), ".ged_browser_history"), out)
                : LineInput.of(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        return shell.repl(input, interactive);
    }

    private static int usage(PrintStream err, String problem) {
        err.println("ged-browser: " + problem);
        err.print(USAGE);
        return 2;
    }
}
