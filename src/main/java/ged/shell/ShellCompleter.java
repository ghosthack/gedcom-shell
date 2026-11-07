package ged.shell;

import ged.shell.Completer.Candidate;
import ged.shell.Completer.Result;
import ged.vfs.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/// Tab completion for the shell: command names, then each command's
/// options and their values (`--theme ba` → `baroque`), and tree paths
/// (`cd fa` → `father/`, `cd children/` → the children's ids). A path
/// segment that matches no entry is tried as a name, the way `cd` reads
/// it: `cd olga` completes to `children/I3/` when Olga is the only match.
final class ShellCompleter implements Completer {
    private final Map<String, Command> commands;
    private final Map<String, String> aliases;
    private final Context ctx;

    ShellCompleter(Map<String, Command> commands, Map<String, String> aliases, Context ctx) {
        this.commands = commands;
        this.aliases = aliases;
        this.ctx = ctx;
    }

    @Override
    public Result complete(String line, int cursor) {
        var scan = Scan.of(line.substring(0, cursor));
        if (scan == null) return Result.none(cursor);
        return new Result(scan.start(), scan.word(), candidates(scan.words(), scan.word()));
    }

    private List<Candidate> candidates(List<String> words, String word) {
        if (words.isEmpty()) return commandNames(word, true);
        var name = words.getFirst();
        if (aliases.containsKey(name)) name = aliases.get(name).split(" ")[0];
        if (name.equals("help")) return words.size() == 1 ? commandNames(word, false) : List.of();
        var cmd = commands.get(name);
        if (cmd == null) return List.of();

        var flags = cmd.options().flags();
        int eq = word.indexOf('=');
        if (word.startsWith("--") && eq > 0) {
            var flag = find(flags, word.substring(0, eq));
            return flag == null ? List.of() : values(flag, word.substring(0, eq + 1), word.substring(eq + 1));
        }
        var pending = words.size() > 1 ? valueOf(flags, words.getLast()) : null;
        if (pending != null) return values(pending, "", word);
        if (word.startsWith("-") && !word.matches("-\\d.*")) {
            return flags.stream().filter(f -> f.name().startsWith(word))
                    .map(f -> new Candidate(f.name(), " ", f.name(), ctx.style().dim(f.help()))).toList();
        }
        var out = new ArrayList<Candidate>();
        if (words.size() == 1) {
            cmd.firstWords().stream().filter(w -> w.startsWith(word)).forEach(w -> out.add(new Candidate(w, " ")));
        }
        if (cmd.takesPaths()) out.addAll(paths(word));
        return out;
    }

    private List<Candidate> commandNames(String word, boolean withAliases) {
        var names = new TreeSet<>(commands.keySet());
        names.add("help");
        if (withAliases) {
            names.add("exit");
            names.addAll(aliases.keySet());
        }
        return names.stream().filter(n -> n.startsWith(word)).map(n -> new Candidate(n, " ")).toList();
    }

    private static Options.Flag find(List<Options.Flag> flags, String name) {
        return flags.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    /// The option whose value `word` leaves pending: `--theme`, `-o`, or
    /// the last letter of a bundle like `-an`.
    private static Options.Flag valueOf(List<Options.Flag> flags, String word) {
        if (word.startsWith("--")) {
            var f = find(flags, word);
            return f != null && f.takesValue() ? f : null;
        }
        if (!word.matches("-[A-Za-z]+")) return null;
        for (int i = 1; i < word.length(); i++) {
            var f = find(flags, "-" + word.charAt(i));
            if (f != null && f.takesValue()) return i == word.length() - 1 ? f : null;
        }
        return null;
    }

    private List<Candidate> values(Options.Flag flag, String prefix, String word) {
        if (!flag.choices().isEmpty()) {
            return flag.choices().stream().filter(c -> startsWithIgnoreCase(c, word))
                    .map(c -> new Candidate(prefix + c, " ", c, "")).toList();
        }
        return flag.arg().equals("PATH") && prefix.isEmpty() ? paths(word) : List.of();
    }

    // ---- paths ------------------------------------------------------------

    private List<Candidate> paths(String word) {
        int slash = word.lastIndexOf('/');
        var dir = word.substring(0, slash + 1);
        var part = word.substring(slash + 1);
        if (dir.isEmpty() && part.equals("~")) return List.of(new Candidate("~", "/"));
        if (part.equals("..") || part.equals(".")) return List.of(new Candidate(dir + "..", "/"));

        Location loc;
        try {
            loc = dir.isEmpty() ? ctx.nav().cwd().location() : ctx.nav().resolve(dir).location();
        } catch (RuntimeException e) {
            return List.of(); // the directory part doesn't resolve (or is ambiguous)
        }
        var fs = ctx.fs();
        var out = new ArrayList<Candidate>();
        for (var e : fs.entries(loc)) {
            if (startsWithIgnoreCase(e.name(), part)) out.add(entry(dir + e.name(), e.name(), e.target()));
        }
        if (!out.isEmpty() || part.isBlank()) return out;

        // Like `cd`, a first segment may be any record id, or a name.
        boolean global = dir.isEmpty() || loc instanceof Location.People;
        var g = ctx.g();
        if (dir.isEmpty() && part.matches("(?i)@?[A-Z]+\\d+@?")) {
            var key = part.replace("@", "");
            for (var p : g.people()) {
                if (startsWithIgnoreCase(p.key(), key)) out.add(entry(p.key(), p.key(), new Location.PersonDir(p)));
            }
            for (var f : g.families()) {
                if (startsWithIgnoreCase(f.key(), key)) out.add(entry(f.key(), f.key(), new Location.FamilyDir(f)));
            }
            if (!out.isEmpty()) return out;
        }
        for (var e : fs.rows(loc)) {
            if (e.target() instanceof Location.PersonDir(var p) && g.nameMatches(p, part)) {
                var rel = String.join("/", e.path());
                out.add(entry(dir + rel, rel, e.target()));
            }
        }
        if (out.isEmpty() && global) {
            for (var p : g.people()) {
                if (g.nameMatches(p, part)) out.add(entry(dir + p.key(), p.key(), new Location.PersonDir(p)));
            }
        }
        return out;
    }

    /// A directory candidate, listed with who or what it leads to.
    private Candidate entry(String text, String name, Location target) {
        var note = switch (target) {
            case Location.PersonDir(var p) -> (name.equals(p.key()) || name.endsWith("/" + p.key()) ? "" : p.key() + "  ") + p.label();
            case Location.FamilyDir(var f) -> ctx.fs().familyTitle(f);
            default -> "";
        };
        return new Candidate(text, "/", name + "/", ctx.style().dim(note));
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    // ---- reading the line -------------------------------------------------

    /// The words of the command the cursor is in, and the word being typed.
    /// Reads quotes, escapes and `;` the way [Tokenizer] does.
    record Scan(List<String> words, int start, String word) {
        /// Returns null when the cursor is inside a `#` comment.
        static Scan of(String line) {
            var words = new ArrayList<String>();
            var word = new StringBuilder();
            int start = -1;
            char quote = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                    else if (c == '\\' && quote == '"' && i + 1 < line.length()) word.append(line.charAt(++i));
                    else word.append(c);
                } else if (Character.isWhitespace(c) || c == ';') {
                    if (start >= 0) words.add(word.toString());
                    word.setLength(0);
                    start = -1;
                    if (c == ';') words.clear();
                } else if (c == '#' && start < 0) {
                    return null;
                } else {
                    if (start < 0) start = i;
                    if (c == '\'' || c == '"') quote = c;
                    else if (c == '\\') { if (i + 1 < line.length()) word.append(line.charAt(++i)); }
                    else word.append(c);
                }
            }
            return new Scan(List.copyOf(words), start < 0 ? line.length() : start, word.toString());
        }
    }
}
