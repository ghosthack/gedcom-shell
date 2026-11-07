package ged.shell;

import ged.edit.FamilyForm;
import ged.edit.PersonForm;
import ged.model.Family;
import ged.model.Person;
import ged.text.Style;
import ged.vfs.Location;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

final class EditCommand implements Command {
    @Override public String name() { return "edit"; }
    @Override public boolean takesPaths() { return true; }
    @Override public String summary() { return "change a person's name, sex, life events and occupation, or a family's marriage and divorce"; }

    @Override
    public String usage() {
        return """
                edit [PATH] [FIELD=VALUE...]

                With no assignments, opens the person or family (default: where you
                are) as a short form in $VISUAL / $EDITOR (default vi). Save and quit
                to apply; quit without saving, or exit non-zero (:cq), to cancel. If a
                value is invalid the form reopens with the error at the top.

                With assignments, applies them directly, which is handy in scripts:

                  edit sex=F
                  edit mother birth.date="12 jun 1850" birthplace="Genova, Italia"
                  edit @I12@ deceased=yes
                  edit christened="20 jun 1850" buried=1920 burialplace=Staglieno
                  edit occupation=Sailor residence=Livorno
                  edit surname=Rossi given="Maria Luisa"
                  edit families/F4 married="12 jun 1870" marriage.place=Roma
                  edit @F2@ divorced=yes

                Changes are held in memory (the prompt shows *) until you run `save`.
                To change who belongs to a family, see `help link` and `help unlink`.

                Person fields: %s
                  aliases: first, last, gender, born, birthplace, died, deathplace, dead,
                  baptism, christened, baptismplace, buried, burialplace, job, residence
                  occupation and residence edit the first one recorded; others are kept
                Family fields: %s
                  aliases: married, wedding, marriageplace, divorce, divorceplace
                Dates: %s
                """.formatted(
                Arrays.stream(PersonForm.Field.values()).map(f -> f.key).collect(Collectors.joining(", ")),
                Arrays.stream(FamilyForm.Field.values()).map(f -> f.key).collect(Collectors.joining(", ")),
                ged.model.GedDate.EXAMPLES);
    }

    @Override
    public void run(List<String> args, Context ctx) {
        var path = "";
        var assignments = new LinkedHashMap<String, String>();
        for (var a : args) {
            int eq = a.indexOf('=');
            if (eq > 0) {
                var key = a.substring(0, eq);
                if (assignments.put(key, a.substring(eq + 1)) != null) throw new CommandException(key + " given twice");
            } else if (path.isEmpty()) {
                path = a;
            } else {
                throw new CommandException("unexpected '" + a + "' (quote values with spaces: birthplace=\"Genova, Italia\")");
            }
        }
        var loc = ctx.nav().resolve(path).location();
        Editable<?> target = switch (loc) {
            case Location.PersonDir(var p) -> new PersonEdit(p, ctx);
            case Location.FamilyDir(var f) -> new FamilyEdit(f, ctx);
            default -> throw new CommandException("only people and families can be edited; "
                    + ctx.fs().describe(loc) + " is neither");
        };
        if (assignments.isEmpty()) interactive(target, ctx);
        else commit(target, assignments, ctx);
    }

    /// One change, as shown after an edit.
    private record Row(String field, String before, String after) {}

    /// A person or family seen through its form. `C` is the form's change type.
    private abstract static class Editable<C> {
        abstract String key();
        abstract String render(String error);
        /// @throws IllegalArgumentException with a user-facing message
        abstract List<C> diff(Map<String, String> byKey);
        /// @throws IllegalArgumentException with a user-facing message
        abstract List<C> diffForm(String text);
        abstract List<Row> rows(List<C> changes);
        /// Applies the changes; returns the heading to print above them.
        abstract String apply(List<C> changes);

        /// Maps `FIELD=VALUE` keys to form fields.
        static <F> Map<F, String> fields(Map<String, String> byKey, Function<String, Optional<F>> parse,
                                         Function<String, String> unknown) {
            var out = new LinkedHashMap<F, String>();
            for (var e : byKey.entrySet()) {
                var f = parse.apply(e.getKey()).orElseThrow(() -> new CommandException(unknown.apply(e.getKey())));
                if (out.put(f, e.getValue()) != null) throw new CommandException(e.getKey() + " given twice");
            }
            return out;
        }
    }

    private static final class PersonEdit extends Editable<PersonForm.Change> {
        private final Person p;
        private final Context ctx;

        PersonEdit(Person p, Context ctx) { this.p = p; this.ctx = ctx; }

        @Override String key() { return p.key(); }
        @Override String render(String error) { return PersonForm.render(p, error); }

        @Override
        List<PersonForm.Change> diff(Map<String, String> byKey) {
            return PersonForm.diff(p, fields(byKey, PersonForm.Field::parse, this::unknown));
        }

        /// Points family fields at the family they belong to.
        private String unknown(String key) {
            if (FamilyForm.Field.parse(key).isEmpty()) return "unknown field '" + key + "' (see: help edit)";
            var families = ctx.g().familiesAsSpouse(p);
            var where = families.size() == 1 ? "edit families/" + families.getFirst().key() + " " + key + "=…"
                    : families.isEmpty() ? "and " + p.name().display() + " has no family as a partner"
                    : "use: edit families/F… " + key + "=… (one of: "
                      + families.stream().map(Family::key).collect(Collectors.joining(", ")) + ")";
            return key + " is a family field: " + where;
        }

        @Override List<PersonForm.Change> diffForm(String text) { return PersonForm.diff(p, PersonForm.parse(text)); }

        @Override
        List<Row> rows(List<PersonForm.Change> changes) {
            return changes.stream().map(c -> new Row(c.field().key, c.before(), c.after())).toList();
        }

        @Override
        String apply(List<PersonForm.Change> changes) {
            var fresh = PersonForm.apply(ctx.g(), p, changes);
            var s = ctx.style();
            return s.bold(fresh.name().display()) + "  " + s.dim(fresh.key());
        }
    }

    private static final class FamilyEdit extends Editable<FamilyForm.Change> {
        private final Family f;
        private final Context ctx;

        FamilyEdit(Family f, Context ctx) { this.f = f; this.ctx = ctx; }

        @Override String key() { return f.key(); }
        @Override String render(String error) { return FamilyForm.render(ctx.g(), f, error); }

        @Override
        List<FamilyForm.Change> diff(Map<String, String> byKey) {
            return FamilyForm.diff(f, fields(byKey, FamilyForm.Field::parse, key ->
                    PersonForm.Field.parse(key).isPresent()
                            ? key + " is a person field: edit husband, wife or a child (e.g. edit husband " + key + "=…)"
                            : "unknown field '" + key + "' (see: help edit)"));
        }

        @Override List<FamilyForm.Change> diffForm(String text) { return FamilyForm.diff(f, FamilyForm.parse(text)); }

        @Override
        List<Row> rows(List<FamilyForm.Change> changes) {
            return changes.stream().map(c -> new Row(c.field().key, c.before(), c.after())).toList();
        }

        @Override
        String apply(List<FamilyForm.Change> changes) {
            var fresh = FamilyForm.apply(ctx.g(), f, changes);
            var s = ctx.style();
            return s.bold(ctx.fs().familyTitle(fresh)) + "  " + s.dim(fresh.key());
        }
    }

    private <C> void commit(Editable<C> target, Map<String, String> byKey, Context ctx) {
        List<C> changes;
        try {
            changes = target.diff(byKey);
        } catch (IllegalArgumentException e) {
            throw new CommandException(e.getMessage());
        }
        show(target, changes, ctx);
    }

    private <C> void interactive(Editable<C> target, Context ctx) {
        String error = null;
        try {
            var file = Files.createTempFile("ged-" + target.key() + "-", ".txt");
            try {
                while (true) {
                    var form = target.render(error);
                    Files.writeString(file, form, StandardCharsets.UTF_8);
                    boolean saved;
                    try {
                        saved = ctx.editor().edit(file);
                    } catch (IllegalStateException e) {
                        throw new CommandException(e.getMessage());
                    }
                    var edited = Files.readString(file, StandardCharsets.UTF_8);
                    if (!saved || edited.equals(form)) {
                        ctx.out().println(ctx.style().dim("edit cancelled; nothing changed"));
                        return;
                    }
                    try {
                        show(target, target.diffForm(edited), ctx);
                        return;
                    } catch (IllegalArgumentException e) {
                        error = e.getMessage();
                        ctx.out().println(ctx.style().red("edit: " + error) + ctx.style().dim("  (reopening the form)"));
                    }
                }
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <C> void show(Editable<C> target, List<C> changes, Context ctx) {
        var s = ctx.style();
        if (changes.isEmpty()) {
            ctx.out().println(s.dim("no changes"));
            return;
        }
        var heading = target.apply(changes);
        ctx.nav().refresh();
        ctx.out().println(heading + "  " + changes.size() + (changes.size() == 1 ? " change" : " changes"));
        var rows = target.rows(changes);
        int width = Math.max(12, rows.stream().mapToInt(r -> r.field().length() + 1).max().orElse(0));
        for (var c : rows) {
            ctx.out().println("  " + s.cyan(Style.pad(c.field(), width)) + " "
                    + (c.before().isEmpty() ? s.dim("(empty)") : s.red(c.before())) + s.dim(" → ")
                    + (c.after().isEmpty() ? s.dim("(removed)") : s.green(c.after())));
        }
        unsaved(ctx);
    }

    static void unsaved(Context ctx) {
        var s = ctx.style();
        ctx.out().println(s.yellow("unsaved") + s.dim(": " + ctx.g().dirtyCount() + " record(s) modified; run `save` to write the file"));
    }
}
