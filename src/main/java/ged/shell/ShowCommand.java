package ged.shell;

import ged.gedcom.Node;
import ged.model.Event;
import ged.model.Family;
import ged.model.GedDate;
import ged.model.Genealogy;
import ged.model.Person;
import ged.model.RecordKind;
import ged.text.Style;
import ged.vfs.Location;

import java.util.List;
import java.util.stream.Collectors;

final class ShowCommand implements Command {
    private static final int LABEL = 14;

    private final Options opts = new Options()
            .flag("r", "raw", "print the GEDCOM record as stored in the file");

    @Override public String name() { return "show"; }
    @Override public Options options() { return opts; }
    @Override public boolean takesPaths() { return true; }
    @Override public String summary() { return "show the full record of a person, family, note, source or picture (at / : file summary)"; }

    @Override
    public String usage() {
        return """
                show [-r] [PATH...]

                Shows every recorded fact, the families the person belongs to, notes
                and change date. With no PATH, shows the current location; at the
                root, summarises the file (encoding, counts, date range, warnings).

                  show                   current person or family
                  show mother            the current person's mother
                  show -r @F3@           raw GEDCOM for family F3
                  show @S2@              source S2, and who cites it

                """ + opts.describe();
    }

    @Override
    public void run(List<String> args, Context ctx) {
        var o = opts.parse(args);
        var paths = o.positionals().isEmpty() ? List.of("") : o.positionals();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) ctx.out().println();
            var loc = ctx.nav().resolve(paths.get(i)).location();
            var text = switch (loc) {
                case Location.PersonDir(var p) -> o.has("raw") ? p.node().toString() : person(p, ctx);
                case Location.FamilyDir(var f) -> o.has("raw") ? f.node().toString() : family(f, ctx);
                case Location.Root _ -> o.has("raw") ? ctx.g().header().map(Node::toString).orElse("") : summary(ctx);
                case Location.RecordDir r -> o.has("raw") ? r.record().toString() : record(r, ctx);
                default -> throw new CommandException(ctx.fs().describe(loc) + " is a listing; use ls");
            };
            ctx.out().print(text);
        }
    }

    private String person(Person p, Context ctx) {
        var s = ctx.style();
        var g = ctx.g();
        var out = new StringBuilder();
        title(out, p.name().display(), p.key(), s);
        names(out, p, s);
        field(out, "Sex", switch (p.sex()) { case MALE -> "male"; case FEMALE -> "female"; default -> "unknown"; }, s);
        for (var e : p.events()) event(out, e, s, ctx);
        age(p).ifPresent(a -> field(out, "Age", a, s));
        externalIds(out, p.node(), s);

        for (var f : g.familiesAsChild(p)) {
            section(out, "Parents", f, s);
            f.husband().flatMap(g::person).ifPresent(x -> relative(out, "father", x, s));
            f.wife().flatMap(g::person).ifPresent(x -> relative(out, "mother", x, s));
            f.children().stream().filter(id -> !id.equals(p.id())).flatMap(id -> g.person(id).stream())
                    .forEach(x -> relative(out, x.sex() == Person.Sex.FEMALE ? "sister" : x.sex() == Person.Sex.MALE ? "brother" : "sibling", x, s));
        }
        var half = g.halfSiblings(p);
        if (!half.isEmpty()) {
            out.append('\n').append(s.heading("Half-siblings")).append('\n');
            for (var x : half) {
                relative(out, x.sex() == Person.Sex.FEMALE ? "half-sister" : x.sex() == Person.Sex.MALE ? "half-brother" : "half-sibling", x, s);
            }
        }
        for (var f : g.familiesAsSpouse(p)) {
            section(out, "Family", f, s);
            f.partnerOf(p.id()).flatMap(g::person).ifPresentOrElse(
                    x -> relative(out, "spouse", x, s),
                    () -> line(out, "spouse", s.dim("(unknown)"), s));
            for (var e : f.events()) event(out, e, s, ctx);
            f.children().stream().flatMap(id -> g.person(id).stream())
                    .forEach(x -> relative(out, x.sex() == Person.Sex.FEMALE ? "daughter" : x.sex() == Person.Sex.MALE ? "son" : "child", x, s));
        }
        notes(out, p.notes(), s, ctx);
        changed(out, p.node(), s);
        return out.toString();
    }

    private String family(Family f, Context ctx) {
        var s = ctx.style();
        var g = ctx.g();
        var out = new StringBuilder();
        title(out, ctx.fs().familyTitle(f), f.key(), s);
        f.husband().flatMap(g::person).ifPresent(x -> relative(out, "husband", x, s));
        f.wife().flatMap(g::person).ifPresent(x -> relative(out, "wife", x, s));
        for (var e : f.events()) event(out, e, s, ctx);
        externalIds(out, f.node(), s);
        if (!f.children().isEmpty()) {
            out.append('\n').append(s.heading("Children")).append('\n');
            f.children().stream().flatMap(id -> g.person(id).stream())
                    .forEach(x -> relative(out, x.sex() == Person.Sex.FEMALE ? "daughter" : x.sex() == Person.Sex.MALE ? "son" : "child", x, s));
        }
        notes(out, f.notes(), s, ctx);
        changed(out, f.node(), s);
        return out.toString();
    }

    private String summary(Context ctx) {
        var s = ctx.style();
        var g = ctx.g();
        var out = new StringBuilder();
        title(out, g.source().map(Object::toString).orElse("(in memory)"), "", s);
        g.header().ifPresent(h -> {
            var src = h.first("SOUR");
            src.ifPresent(n -> field(out, "Created by", (n.text("NAME").isEmpty() ? n.value() : n.text("NAME"))
                    + (n.text("VERS").isEmpty() ? "" : " " + n.text("VERS")), s));
            h.first("GEDC").ifPresent(n -> field(out, "GEDCOM", (n.text("VERS") + " " + n.text("FORM")).strip(), s));
            if (!h.text("DATE").isEmpty()) field(out, "Exported", h.text("DATE"), s);
        });
        var enc = g.encoding();
        field(out, "Encoding", enc.detected() + (enc.mismatch() ? s.yellow("  (header says " + enc.declared() + ")") : ""), s);

        var people = g.people();
        var bySex = people.stream().collect(Collectors.groupingBy(Person::sex, Collectors.counting()));
        field(out, "People", people.size() + "  (" + bySex.getOrDefault(Person.Sex.MALE, 0L) + " male, "
                + bySex.getOrDefault(Person.Sex.FEMALE, 0L) + " female, "
                + bySex.getOrDefault(Person.Sex.UNKNOWN, 0L) + " unknown)", s);
        field(out, "Families", String.valueOf(g.families().size()), s);
        for (var k : RecordKind.values()) {
            int n = g.records(k).size();
            if (n > 0) field(out, k.dirName.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + k.dirName.substring(1), String.valueOf(n), s);
        }
        var years = people.stream().map(Person::birth).filter(GedDate::hasYear).mapToInt(GedDate::year).summaryStatistics();
        if (years.getCount() > 0) field(out, "Births", years.getMin() + " – " + years.getMax(), s);
        if (!g.warnings().isEmpty()) {
            field(out, "Warnings", s.yellow(String.valueOf(g.warnings().size())), s);
            g.warnings().stream().limit(10).forEach(w -> out.append(" ".repeat(LABEL + 2)).append(s.dim(w)).append('\n'));
        }
        return out.toString();
    }

    private static final java.util.Map<String, String> LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("TITL", "Title"), java.util.Map.entry("AUTH", "Author"),
            java.util.Map.entry("PUBL", "Publication"), java.util.Map.entry("ABBR", "Short title"),
            java.util.Map.entry("TEXT", "Text"), java.util.Map.entry("REPO", "Repository"),
            java.util.Map.entry("CALN", "Call number"), java.util.Map.entry("FILE", "File"),
            java.util.Map.entry("FORM", "Format"), java.util.Map.entry("MEDI", "Medium"),
            java.util.Map.entry("TYPE", "Type"), java.util.Map.entry("DATE", "Date"),
            java.util.Map.entry("PLAC", "Place"), java.util.Map.entry("DATA", "Data"),
            java.util.Map.entry("EVEN", "Events"), java.util.Map.entry("AGNC", "Agency"),
            java.util.Map.entry("REFN", "Reference"), java.util.Map.entry("LANG", "Language"),
            java.util.Map.entry("MIME", "Format"), java.util.Map.entry("SOUR", "Source"),
            java.util.Map.entry("OBJE", "Media"), java.util.Map.entry("PAGE", "Page"),
            java.util.Map.entry("QUAY", "Quality"), java.util.Map.entry("RIN", "Record number"),
            java.util.Map.entry("_UID", "Unique id"), java.util.Map.entry("UID", "Unique id"));

    /// A note, source or picture: its text or fields, then who uses it.
    private String record(Location.RecordDir r, Context ctx) {
        var s = ctx.style();
        var g = ctx.g();
        var rec = r.record();
        var out = new StringBuilder();
        boolean note = r.kind() == RecordKind.NOTES;
        title(out, note ? "Note" : Genealogy.recordTitle(rec), r.key(), s);
        if (note) out.append(rec.value().isBlank() ? s.dim("(empty)") : rec.value()).append('\n');
        var notes = new java.util.ArrayList<String>();
        for (var c : rec.children()) {
            switch (c.tag()) {
                case "CHAN", "CONT", "CONC", "EXID" -> { }
                case "NOTE", "SNOTE" -> { if (!c.isVoid()) notes.add(c.value()); }
                case "FILE" -> field(out, "File", file(c, g, s), s);
                case "TITL" -> { if (r.kind() == RecordKind.NOTES) field(out, "Title", c.value(), s); }
                default -> fields(out, c, 0, g, s);
            }
        }
        externalIds(out, rec, s);
        notes(out, notes, s, ctx);
        var users = g.usersOf(rec);
        out.append('\n').append(s.heading("Used by")).append('\n');
        if (users.isEmpty()) out.append("  ").append(s.dim("nobody")).append('\n');
        for (var u : users) {
            switch (u.tag()) {
                case "INDI" -> g.person(u.xref()).ifPresent(p -> relative(out, "person", p, s));
                case "FAM" -> g.family(u.xref()).ifPresent(f ->
                        line(out, "family", s.dim(Style.pad(f.key(), 6)) + " " + ctx.fs().familyTitle(f), s));
                default -> RecordKind.of(u.tag()).ifPresent(k -> line(out, k.member,
                        s.dim(Style.pad(Genealogy.key(u.xref()), 6)) + " " + Genealogy.recordTitle(u), s));
            }
        }
        changed(out, rec, s);
        return out.toString();
    }

    /// A field and the lines under it, one level indented per level down.
    /// Pointers to notes, sources and pictures show what they point at.
    private static void fields(StringBuilder out, Node n, int depth, Genealogy g, Style s) {
        var label = LABELS.getOrDefault(n.tag(), n.tag());
        var value = n.value();
        if (n.isPointer()) {
            var target = g.pointed(n.value());
            value = s.dim(Genealogy.key(n.value())) + " " + target.map(Genealogy::recordTitle).orElse(s.yellow("(missing)"));
        }
        if (depth == 0) field(out, label, value, s);
        else out.append("  ".repeat(depth)).append(s.dim(Style.pad(label, LABEL - 2 * depth))).append("  ")
                .append(value.replace("\n", "\n" + " ".repeat(LABEL + 2))).append('\n');
        for (var c : n.children()) fields(out, c, depth + 1, g, s);
    }

    /// A picture's file: as written, with its format, and whether it is there.
    private static String file(Node f, Genealogy g, Style s) {
        var parts = new StringBuilder(f.value().strip());
        var form = f.first("FORM").map(Node::value).orElse("").strip();
        if (!form.isEmpty()) parts.append("  ").append(s.dim("(" + form + ")"));
        if (!f.text("TITL").isBlank()) parts.append("\n").append(f.text("TITL").strip());
        boolean web = f.value().strip().matches("(?i)[a-z][a-z0-9+.-]+://.*");
        if (!web && g.mediaPath(f.value().strip()).isEmpty()) parts.append("  ").append(s.yellow("(not found)"));
        return parts.toString();
    }

    // ---- formatting helpers ---------------------------------------------

    /// Nicknames and other names (married, birth, aka...), one per line. A
    /// name that differs from one already shown only by accents or case
    /// (`Jose` beside `José`) is a spelling kept for searching and is
    /// skipped; `find` matches both either way.
    private static void names(StringBuilder out, Person p, Style s) {
        var nicks = p.nicknames();
        if (!nicks.isEmpty()) field(out, nicks.size() == 1 ? "Nickname" : "Nicknames", String.join(", ", nicks), s);
        var seen = new java.util.HashSet<String>();
        seen.add(Genealogy.fold(p.name().full()));
        var lines = new java.util.ArrayList<String>();
        for (var o : p.otherNames()) {
            if (!seen.add(Genealogy.fold(o.name().full()))) continue;
            var type = o.type().toLowerCase(java.util.Locale.ROOT);
            // "aka" and "other" say no more than the heading does
            lines.add(o.name().full() + (type.isEmpty() || type.equals("aka") || type.equals("other") ? "" : "  " + s.dim("(" + type + ")")));
        }
        if (!lines.isEmpty()) field(out, "Also known as", String.join("\n", lines), s);
    }

    private static void title(StringBuilder out, String title, String key, Style s) {
        out.append(s.bold(title));
        if (!key.isEmpty()) out.append("  ").append(s.dim(key));
        out.append('\n').append(s.dim("─".repeat(Math.max(20, Style.width(title) + key.length() + 2)))).append('\n');
    }

    private static void field(StringBuilder out, String label, String value, Style s) {
        out.append(s.cyan(Style.pad(label, LABEL))).append("  ").append(value.replace("\n", "\n" + " ".repeat(LABEL + 2))).append('\n');
    }

    private static void line(StringBuilder out, String label, String value, Style s) {
        out.append("  ").append(s.dim(Style.pad(label, LABEL - 2))).append("  ").append(value).append('\n');
    }

    private static void relative(StringBuilder out, String label, Person p, Style s) {
        line(out, label, s.dim(Style.pad(p.key(), 6)) + " " + p.label(), s);
    }

    private static void section(StringBuilder out, String heading, Family f, Style s) {
        out.append('\n').append(s.heading(heading)).append("  ").append(s.dim(f.key())).append('\n');
    }

    private static void event(StringBuilder out, Event e, Style s, Context ctx) {
        var parts = new java.util.ArrayList<String>();
        if (!e.detail().isEmpty() && !e.detail().equals("Y")) parts.add(e.detail());
        if (!e.type().isEmpty()) parts.add(e.type());
        if (!e.date().isEmpty()) parts.add(e.date().raw());
        if (!e.place().isEmpty()) parts.add(e.place());
        var value = parts.isEmpty() ? s.dim("yes") : String.join(", ", parts);
        field(out, e.label(), value, s);
        e.node().notes().forEach(n ->
                out.append(" ".repeat(LABEL + 2)).append(s.dim(ctx.g().noteText(n.value()).replace("\n", " "))).append('\n'));
    }

    private static void notes(StringBuilder out, List<String> notes, Style s, Context ctx) {
        if (notes.isEmpty()) return;
        out.append('\n').append(s.heading("Notes")).append('\n');
        for (var n : notes) {
            out.append("  ").append(ctx.g().noteText(n).replace("\n", "\n  ")).append('\n');
        }
    }

    /// GEDCOM 7 `EXID`s, one per line: `123  (http://example.com/ids)`.
    private static void externalIds(StringBuilder out, Node n, Style s) {
        var ids = n.all("EXID")
                .map(x -> x.value() + (x.text("TYPE").isEmpty() ? "" : "  " + s.dim("(" + x.text("TYPE") + ")")))
                .toList();
        if (!ids.isEmpty()) field(out, "External ids", String.join("\n", ids), s);
    }

    private static void changed(StringBuilder out, Node n, Style s) {
        n.first("CHAN").ifPresent(c -> {
            var date = c.first("DATE");
            var text = date.map(d -> (d.value() + " " + d.text("TIME")).strip()).orElse("");
            if (!text.isEmpty()) out.append('\n').append(s.dim("changed " + text)).append('\n');
        });
    }

    /// Age at death (or "~N" when only years are known). Living people get
    /// no age: we can't tell "alive" from "death not recorded".
    static java.util.Optional<String> age(Person p) {
        var b = p.birth();
        var d = p.death();
        if (!b.hasYear() || !d.hasYear()) return java.util.Optional.empty();
        int years = d.astronomicalYear() - b.astronomicalYear();
        boolean exact = b.qualifier() == GedDate.Qualifier.EXACT && d.qualifier() == GedDate.Qualifier.EXACT
                && b.day() > 0 && d.day() > 0;
        if (exact && (d.month() < b.month() || d.month() == b.month() && d.day() < b.day())) years--;
        if (years < 0) return java.util.Optional.empty();
        return java.util.Optional.of((exact ? "" : "~") + years + " at death");
    }
}
