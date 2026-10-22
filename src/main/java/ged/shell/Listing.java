package ged.shell;

import ged.model.Event;
import ged.model.Genealogy;
import ged.model.Person;
import ged.text.Table;
import ged.text.Table.Column;
import ged.vfs.Entry;
import ged.vfs.Location;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/// Renders numbered listings. Row numbers are the *unsorted* positions,
/// so `cd 3` means the same row no matter how the listing was sorted.
final class Listing {
    private Listing() {}

    record Row(int number, Entry entry) {}

    static final List<String> SORT_KEYS = List.of("name", "surname", "id", "birth", "death", "birthday", "rel");

    static List<Row> number(List<Entry> entries) {
        return IntStream.range(0, entries.size()).mapToObj(i -> new Row(i + 1, entries.get(i))).toList();
    }

    /// Sorts by `key`. Rows lacking the key (undated people, directories)
    /// always go last, whichever the direction; ties keep listing order.
    static List<Row> sort(List<Row> rows, String key, boolean reverse) {
        record By(Predicate<Row> missing, Comparator<Row> order) {}
        Predicate<Row> notPerson = r -> person(r) == null;
        var by = switch (key.toLowerCase(Locale.ROOT)) {
            case "", "none" -> new By(_ -> false, Comparator.comparingInt(Row::number));
            case "name" -> new By(notPerson, text(p -> p.name().full()));
            case "surname" -> new By(notPerson, text(p -> p.name().surname() + " " + p.name().given()));
            case "id" -> new By(_ -> false, Comparator.comparing((Row r) -> idKey(r.entry().target())));
            case "birth" -> new By(r -> notPerson.test(r) || !person(r).birth().hasYear(),
                    Comparator.comparing((Row r) -> person(r).birth()));
            case "death" -> new By(r -> notPerson.test(r) || !person(r).death().hasYear(),
                    Comparator.comparing((Row r) -> person(r).death()));
            case "birthday" -> new By(r -> notPerson.test(r) || person(r).birth().month() == 0,
                    Comparator.comparingInt((Row r) -> person(r).birth().month() * 100 + person(r).birth().day()));
            case "rel" -> new By(_ -> false, Comparator.comparing((Row r) -> r.entry().relation()));
            default -> throw new CommandException("unknown sort key '" + key + "' (one of " + String.join(", ", SORT_KEYS) + ")");
        };
        var present = new ArrayList<Row>();
        var missing = new ArrayList<Row>();
        for (var r : rows) (by.missing().test(r) ? missing : present).add(r);
        var order = by.order().thenComparingInt(Row::number);
        present.sort(reverse ? order.reversed() : order);
        present.addAll(missing);
        return present;
    }

    private static Comparator<Row> text(Function<Person, String> f) {
        return Comparator.comparing((Row r) -> Genealogy.fold(f.apply(person(r))));
    }

    private static String idKey(Location loc) {
        var key = switch (loc) {
            case Location.PersonDir(var p) -> p.key();
            case Location.FamilyDir(var f) -> f.key();
            default -> "";
        };
        // natural order: I2 before I10
        var digits = key.replaceAll("\\D", "");
        return key.replaceAll("\\d", "") + "%012d".formatted(digits.isEmpty() ? 0 : Long.parseLong(digits.substring(0, Math.min(12, digits.length()))));
    }

    private static Person person(Row r) {
        return r.entry().target() instanceof Location.PersonDir(var p) ? p : null;
    }

    static String render(List<Row> rows, boolean longFormat, Context ctx) {
        if (rows.isEmpty()) return ctx.style().dim("(empty)") + "\n";
        var targets = rows.stream().map(r -> r.entry().target()).toList();
        if (targets.stream().allMatch(t -> t instanceof Location.PersonDir)) return people(rows, longFormat, ctx);
        if (targets.stream().allMatch(t -> t instanceof Location.FamilyDir)) return families(rows, ctx);
        return dirs(rows, ctx);
    }

    private static String people(List<Row> rows, boolean longFormat, Context ctx) {
        var s = ctx.style();
        boolean showEntry = rows.stream().anyMatch(r -> !r.entry().name().equals(person(r).key()) || r.entry().path().size() > 1);
        boolean showRel = rows.stream().anyMatch(r -> !r.entry().relation().isEmpty());
        var cols = new ArrayList<Column>();
        cols.add(Column.right("#").style(s::dim));
        if (showEntry) cols.add(Column.left("PATH").style(s::blue));
        cols.add(Column.left("ID").style(s::dim));
        if (showRel) cols.add(Column.left("REL").style(s::cyan));
        cols.add(Column.left("NAME").max(longFormat ? 0 : 40).style(s::bold));
        cols.add(Column.left("SEX"));
        cols.add(Column.right("BORN"));
        cols.add(Column.right("DIED"));
        if (longFormat) {
            cols.add(Column.left("BIRTHPLACE").max(36));
            cols.add(Column.left("DEATHPLACE").max(36));
        }
        var table = new Table(cols.toArray(Column[]::new));
        for (var r : rows) {
            var p = person(r);
            var cells = new ArrayList<String>();
            cells.add(String.valueOf(r.number()));
            if (showEntry) cells.add(String.join("/", r.entry().path()));
            cells.add(p.key());
            if (showRel) cells.add(r.entry().relation());
            cells.add(p.name().display());
            cells.add(p.sex().code);
            cells.add(longFormat ? p.birth().raw() : p.birth().shortForm());
            cells.add(longFormat ? p.death().raw() : p.isDeceased() && p.death().isEmpty() ? "†" : p.death().shortForm());
            if (longFormat) {
                cells.add(p.event("BIRT").map(Event::place).orElse(""));
                cells.add(p.event("DEAT").map(Event::place).orElse(""));
            }
            table.row(cells.toArray(String[]::new));
        }
        return table.render(s);
    }

    private static String families(List<Row> rows, Context ctx) {
        var s = ctx.style();
        var g = ctx.g();
        boolean showRel = rows.stream().anyMatch(r -> !r.entry().relation().isEmpty());
        var cols = new ArrayList<Column>();
        cols.add(Column.right("#").style(s::dim));
        cols.add(Column.left("ID").style(s::dim));
        if (showRel) cols.add(Column.left("REL").style(s::cyan));
        cols.add(Column.left("HUSBAND").max(32).style(s::bold));
        cols.add(Column.left("WIFE").max(32).style(s::bold));
        cols.add(Column.right("MARRIED"));
        cols.add(Column.right("KIDS"));
        var table = new Table(cols.toArray(Column[]::new));
        for (var r : rows) {
            var f = ((Location.FamilyDir) r.entry().target()).family();
            var cells = new ArrayList<String>();
            cells.add(String.valueOf(r.number()));
            cells.add(f.key());
            if (showRel) cells.add(r.entry().relation());
            cells.add(f.husband().flatMap(g::person).map(p -> p.name().display()).orElse("—"));
            cells.add(f.wife().flatMap(g::person).map(p -> p.name().display()).orElse("—"));
            cells.add(f.event("MARR").map(e -> e.date().shortForm()).orElse(""));
            cells.add(String.valueOf(f.children().size()));
            table.row(cells.toArray(String[]::new));
        }
        return table.render(s);
    }

    private static String dirs(List<Row> rows, Context ctx) {
        var s = ctx.style();
        var table = new Table(Column.right("#").style(s::dim), Column.left("PATH").style(s::blue),
                Column.left("REL").style(s::cyan), Column.right("ITEMS"), Column.left("NAME").style(s::bold));
        for (var r : rows) {
            var t = r.entry().target();
            var isDir = !(t instanceof Location.PersonDir || t instanceof Location.FamilyDir);
            table.row(String.valueOf(r.number()), r.entry().displayPath(), isDir ? "" : r.entry().relation(),
                    isDir ? String.valueOf(ctx.fs().rows(t).size()) : "",
                    isDir ? "" : ctx.fs().describe(t));
        }
        return table.render(s);
    }
}
