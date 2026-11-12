package ged.vfs;

import ged.model.Genealogy;
import ged.model.Person;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Resolves paths and tracks the working directory.
///
/// Paths are *logical*, as in a shell: `..` removes the last segment you
/// walked, so `cd father; cd ..` returns to where you were rather than to
/// some arbitrary "parent" of the father. Each path segment may be:
///
/// - an entry name: `father`, `children`, `I12`
/// - a row number from `ls` of that directory: `3`
/// - a GEDCOM id from anywhere: `@I12@` (jumps to `/people/I12`)
/// - a name fragment, accent/case-insensitive: `maria`, `"john doe"`
///
/// `/` is the root, `~` the home person, and `-` (alone) the previous
/// directory.
public final class Navigator {
    private final FileSystem fs;
    private Location home;
    private Path cwd = Path.ROOT;
    private Path previous = Path.ROOT;

    /// A resolved logical path and the location it reaches.
    public record Path(List<String> segments, Location location) {
        static final Path ROOT = new Path(List.of(), Location.ROOT);

        public String text() { return "/" + String.join("/", segments); }
    }

    public static final class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }

    public Navigator(FileSystem fs, Optional<Person> home) {
        this.fs = fs;
        this.home = home.<Location>map(Location.PersonDir::new).orElse(Location.ROOT);
        this.cwd = new Path(canonicalSegments(this.home), this.home);
        this.previous = cwd;
    }

    public FileSystem fs() { return fs; }
    public Path cwd() { return cwd; }
    public Location home() { return home; }

    public void cd(Path target) {
        if (!target.equals(cwd)) previous = cwd;
        cwd = target;
    }

    public Path previous() { return previous; }

    /// Re-points every held location at current records after an edit.
    public void refresh() {
        fs.refresh();
        home = relocate(home);
        cwd = settle(cwd);
        previous = settle(previous);
    }

    /// `path` after an edit: at the current record, and by a route that
    /// still leads there. An edit can cut the route (`cd father`, then the
    /// father is unlinked), and commands that read the route to tell how two
    /// people are related must not act on what it used to mean; such a path
    /// becomes the location's own (`/people/I2`).
    private Path settle(Path path) {
        var loc = relocate(path.location());
        try {
            if (walk(path.segments()).canonicalPath().equals(loc.canonicalPath())) return new Path(path.segments(), loc);
        } catch (NotFound _) {
            // the route is gone
        }
        return new Path(canonicalSegments(loc), loc);
    }

    private Location relocate(Location loc) {
        var g = fs.genealogy();
        return switch (loc) {
            case Location.PersonDir(var p) -> new Location.PersonDir(g.person(p.id()).orElse(p));
            case Location.Group(var p, var r) -> new Location.Group(g.person(p.id()).orElse(p), r);
            // a family deleted by `unlink` is no place to stay: step out to /families
            case Location.FamilyDir(var f) -> g.family(f.id()).<Location>map(Location.FamilyDir::new).orElseGet(Location.Families::new);
            case Location.FamilyChildren(var f) -> g.family(f.id()).<Location>map(Location.FamilyChildren::new).orElseGet(Location.Families::new);
            default -> loc;
        };
    }

    public Path resolve(String path) {
        if (path.equals("-")) return previous;
        if (path.isEmpty()) return cwd;

        List<String> segs = new ArrayList<>();
        Location loc;
        String rest = path;
        if (rest.startsWith("/")) {
            loc = Location.ROOT;
        } else if (rest.equals("~") || rest.startsWith("~/")) {
            loc = home;
            segs.addAll(canonicalSegments(home));
            rest = rest.substring(1);
        } else {
            loc = cwd.location();
            segs.addAll(cwd.segments());
        }

        boolean first = true;
        for (var seg : rest.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!segs.isEmpty()) segs.removeLast();
                loc = walk(segs);
                first = false;
                continue;
            }
            var step = step(loc, seg, first);
            if (step.absolute()) segs.clear();
            segs.addAll(step.entry().path());
            loc = step.entry().target();
            first = false;
        }
        return new Path(List.copyOf(segs), loc);
    }

    private record Step(Entry entry, boolean absolute) {}

    private Step step(Location loc, String seg, boolean allowGlobal) {
        var entries = fs.entries(loc);
        for (var e : entries) {
            if (e.name().equalsIgnoreCase(seg)) return new Step(e, false);
        }
        var rows = fs.rows(loc);
        if (seg.matches("\\d+")) {
            int n = seg.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(seg);
            if (n >= 1 && n <= rows.size()) return new Step(rows.get(n - 1), false);
            throw new NotFound(seg + ": no row " + seg + " here (" + rows.size() + " rows)");
        }
        for (var e : rows) {
            if (String.join("/", e.path()).equalsIgnoreCase(seg)) return new Step(e, false);
        }

        var g = fs.genealogy();
        if (seg.startsWith("@") && seg.endsWith("@") || allowGlobal && seg.matches("(?i)[A-Z]+\\d+")) {
            var p = g.person(seg);
            if (p.isPresent()) return global("people", p.get().key(), new Location.PersonDir(p.get()));
            var f = g.family(seg);
            if (f.isPresent()) return global("families", f.get().key(), new Location.FamilyDir(f.get()));
            var r = g.record(seg);
            if (r.isPresent()) {
                var kind = ged.model.RecordKind.of(r.get().tag()).orElseThrow();
                return global(kind.dirName, Genealogy.key(r.get().xref()), new Location.RecordDir(kind, r.get()));
            }
            if (seg.startsWith("@")) throw new NotFound(seg + ": no such record");
        }

        var local = rows.stream()
                .filter(e -> e.target() instanceof Location.PersonDir(var p) && g.nameMatches(p, seg))
                .toList();
        if (local.size() == 1) return new Step(local.getFirst(), false);
        if (local.size() > 1) throw ambiguous(g, seg, local.stream().map(e -> ((Location.PersonDir) e.target()).person()).toList());

        if (allowGlobal || loc instanceof Location.People) {
            var matches = g.people().stream().filter(p -> g.nameMatches(p, seg)).toList();
            if (matches.size() == 1) {
                var p = matches.getFirst();
                return global("people", p.key(), new Location.PersonDir(p));
            }
            if (matches.size() > 1) throw ambiguous(g, seg, matches);
        }
        throw new NotFound(seg + ": not found in " + fs.describe(loc));
    }

    private static Step global(String dir, String key, Location target) {
        return new Step(new Entry(List.of(dir, key), target, ""), true);
    }

    private static NotFound ambiguous(ged.model.Genealogy g, String seg, List<Person> matches) {
        var sb = new StringBuilder(seg + ": " + matches.size() + " people match; be more specific or use an id:");
        matches.stream().limit(8).forEach(p -> sb.append("\n  ").append("%-6s".formatted(p.key())).append(p.label())
                .append(g.matchedAs(p, seg).isEmpty() ? "" : "  (matched " + g.matchedAs(p, seg) + ")"));
        if (matches.size() > 8) sb.append("\n  … (try: find ").append(seg).append(")");
        return new NotFound(sb.toString());
    }

    private Location walk(List<String> segs) {
        Location loc = Location.ROOT;
        for (var s : segs) {
            final var at = loc;
            loc = fs.entries(at).stream().filter(e -> e.name().equals(s)).findFirst()
                    .map(Entry::target)
                    .orElseThrow(() -> new NotFound(s + ": not found in " + fs.describe(at)));
        }
        return loc;
    }

    private static List<String> canonicalSegments(Location loc) {
        var c = loc.canonicalPath();
        return c.equals("/") ? List.of() : List.of(c.substring(1).split("/"));
    }
}
