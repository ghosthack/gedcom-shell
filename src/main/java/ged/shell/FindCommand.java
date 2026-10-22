package ged.shell;

import ged.model.Event;
import ged.model.GedDate;
import ged.model.Genealogy;
import ged.model.Person;
import ged.vfs.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

final class FindCommand implements Command {
    private final Options opts = new Options()
            .value("b", "born", "RANGE", "birth year in RANGE")
            .value("d", "died", "RANGE", "death year in RANGE")
            .value("p", "place", "TEXT", "any event place contains TEXT")
            .value("x", "sex", "M|F|U", "sex")
            .value(null, "ancestors-of", "PATH", "only ancestors of PATH")
            .value(null, "descendants-of", "PATH", "only descendants of PATH")
            .flag("l", "long", "full dates and places in the result table")
            .value("s", "sort", "KEY", "sort by " + String.join("|", Listing.SORT_KEYS) + " (default name)")
            .flag("r", "reverse", "reverse the sort order");

    @Override public String name() { return "find"; }
    @Override public String summary() { return "search people by name, dates, place, sex or lineage"; }

    @Override
    public String usage() {
        return """
                find [OPTIONS] [NAME...]

                Every NAME word must appear in the person's name (case- and
                accent-insensitive: `jose` finds José). Options narrow further; at
                least one NAME or option is required. Results are kept in /found, so
                `cd /found/2` or `show /found/2` reaches the second hit.

                RANGE is YEAR, FROM..TO, ..TO or FROM.. (inclusive), e.g. 1800..1850.

                  find maria lopez
                  find -b 1700..1750 -p paris
                  find --ancestors-of ~ -p italy -s birth
                  find -x F --descendants-of @I12@

                """ + opts.describe();
    }

    @Override
    public void run(List<String> args, Context ctx) {
        var o = opts.parse(args);
        if (o.positionals().isEmpty() && o.values().keySet().stream().allMatch(k -> List.of("long", "sort", "reverse").contains(k))) {
            throw new CommandException("give a NAME or a filter (see: help find)");
        }
        var g = ctx.g();
        var filters = new ArrayList<Predicate<Person>>();
        var name = String.join(" ", o.positionals());
        if (!name.isBlank()) filters.add(p -> g.nameMatches(p, name));
        o.get("born").map(FindCommand::range).ifPresent(r -> filters.add(p -> r.test(p.birth())));
        o.get("died").map(FindCommand::range).ifPresent(r -> filters.add(p -> r.test(p.death())));
        o.get("place").map(Genealogy::fold).ifPresent(t -> filters.add(p ->
                p.events().stream().map(Event::place).anyMatch(pl -> Genealogy.fold(pl).contains(t))));
        o.get("sex").map(FindCommand::sex).ifPresent(sx -> filters.add(p -> p.sex() == sx));

        List<Person> pool = g.people();
        if (o.has("ancestors-of")) pool = g.ancestors(personAt(o.get("ancestors-of").get(), ctx));
        if (o.has("descendants-of")) {
            var desc = g.descendants(personAt(o.get("descendants-of").get(), ctx));
            pool = pool.stream().filter(desc::contains).toList();
        }
        var results = pool.stream().filter(filters.stream().reduce(_ -> true, Predicate::and)).toList();

        var rows = Listing.number(results.stream().map(p -> new ged.vfs.Entry(p.key(), new Location.PersonDir(p), "")).toList());
        var sorted = Listing.sort(rows, o.get("sort").orElse("name"), o.has("reverse"));
        // Store results in display order so /found/N matches the # column.
        var ordered = sorted.stream().map(r -> ((Location.PersonDir) r.entry().target()).person()).toList();
        ctx.fs().setFound(String.join(" ", args), ordered);

        var s = ctx.style();
        if (ordered.isEmpty()) {
            ctx.out().println(s.yellow("no matches"));
            return;
        }
        ctx.out().print(Listing.render(Listing.number(ctx.fs().rows(new Location.Found())), o.has("long"), ctx));
        ctx.out().println(s.dim(ordered.size() + (ordered.size() == 1 ? " match" : " matches") + " → /found  (cd /found/N)"));
    }

    private static Person personAt(String path, Context ctx) {
        if (ctx.nav().resolve(path).location() instanceof Location.PersonDir(var p)) return p;
        throw new CommandException(path + " is not a person");
    }

    private static final Pattern RANGE = Pattern.compile("(\\d{1,4})?\\s*(\\.\\.|-)?\\s*(\\d{1,4})?");

    static Predicate<GedDate> range(String spec) {
        var m = RANGE.matcher(spec.strip());
        if (!m.matches() || m.group(1) == null && m.group(3) == null || m.group(2) == null && m.group(3) != null && m.group(1) != null) {
            throw new CommandException("bad year range '" + spec + "' (try 1850, 1800..1850, ..1700, 1900..)");
        }
        int from, to;
        if (m.group(2) == null) {
            from = to = Integer.parseInt(m.group(1));
        } else {
            from = m.group(1) == null ? Integer.MIN_VALUE : Integer.parseInt(m.group(1));
            to = m.group(3) == null ? Integer.MAX_VALUE : Integer.parseInt(m.group(3));
        }
        if (from > to) throw new CommandException("empty year range '" + spec + "'");
        return d -> d.hasYear() && d.year() >= from && d.year() <= to;
    }

    private static Person.Sex sex(String v) {
        return switch (v.toUpperCase(Locale.ROOT)) {
            case "M", "MALE" -> Person.Sex.MALE;
            case "F", "FEMALE" -> Person.Sex.FEMALE;
            case "U", "?", "UNKNOWN" -> Person.Sex.UNKNOWN;
            default -> throw new CommandException("sex must be M, F or U");
        };
    }
}
