package ged.shell;

import ged.edit.PersonForm;
import ged.edit.PersonForm.Change;
import ged.edit.PersonForm.Field;
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
import java.util.stream.Collectors;

final class EditCommand implements Command {
    @Override public String name() { return "edit"; }
    @Override public String summary() { return "change name, sex, birth and death details (in $EDITOR or FIELD=VALUE)"; }

    @Override
    public String usage() {
        return """
                edit [PATH] [FIELD=VALUE...]

                With no assignments, opens the person (default: current) as a short form
                in $VISUAL / $EDITOR (default vi). Save and quit to apply; quit without
                saving, or exit non-zero (:cq), to cancel. If a value is invalid the
                form reopens with the error at the top.

                With assignments, applies them directly, which is handy in scripts:

                  edit sex=F
                  edit mother birth.date="12 jun 1850" birthplace="Genova, Italia"
                  edit @I12@ deceased=yes
                  edit surname=Rossi given="Maria Luisa"

                Changes are held in memory (the prompt shows *) until you run `save`.

                Fields: %s
                Aliases: first, last, gender, born, birthplace, died, deathplace, dead
                Dates:   %s
                """.formatted(Arrays.stream(Field.values()).map(f -> f.key).collect(Collectors.joining(", ")),
                ged.model.GedDate.EXAMPLES);
    }

    @Override
    public void run(List<String> args, Context ctx) {
        var path = "";
        var assignments = new LinkedHashMap<Field, String>();
        for (var a : args) {
            int eq = a.indexOf('=');
            if (eq > 0) {
                var key = a.substring(0, eq);
                var field = Field.parse(key).orElseThrow(() -> new CommandException("unknown field '" + key + "' (see: help edit)"));
                if (assignments.put(field, a.substring(eq + 1)) != null) throw new CommandException(field.key + " given twice");
            } else if (path.isEmpty()) {
                path = a;
            } else {
                throw new CommandException("unexpected '" + a + "' (quote values with spaces: birthplace=\"Genova, Italia\")");
            }
        }
        var loc = ctx.nav().resolve(path).location();
        if (!(loc instanceof Location.PersonDir(var person))) {
            throw new CommandException("only people can be edited; " + ctx.fs().describe(loc) + " is not a person");
        }
        if (assignments.isEmpty()) interactive(person, ctx);
        else apply(person, assignments, ctx);
    }

    private void apply(Person p, Map<Field, String> values, Context ctx) {
        List<Change> changes;
        try {
            changes = PersonForm.diff(p, values);
        } catch (IllegalArgumentException e) {
            throw new CommandException(e.getMessage());
        }
        commit(p, changes, ctx);
    }

    private void interactive(Person p, Context ctx) {
        String error = null;
        try {
            var file = Files.createTempFile("ged-" + p.key() + "-", ".txt");
            try {
                while (true) {
                    var form = PersonForm.render(p, error);
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
                        commit(p, PersonForm.diff(p, PersonForm.parse(edited)), ctx);
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

    private void commit(Person p, List<Change> changes, Context ctx) {
        var s = ctx.style();
        if (changes.isEmpty()) {
            ctx.out().println(s.dim("no changes"));
            return;
        }
        var fresh = PersonForm.apply(ctx.g(), p, changes);
        ctx.nav().refresh();
        ctx.out().println(s.bold(fresh.name().display()) + "  " + s.dim(fresh.key()) + "  "
                + changes.size() + (changes.size() == 1 ? " change" : " changes"));
        for (var c : changes) {
            ctx.out().println("  " + s.cyan(Style.pad(c.field().key, 12)) + " "
                    + (c.before().isEmpty() ? s.dim("(empty)") : s.red(c.before())) + s.dim(" → ")
                    + (c.after().isEmpty() ? s.dim("(removed)") : s.green(c.after())));
        }
        ctx.out().println(s.yellow("unsaved") + s.dim(": " + ctx.g().dirtyCount() + " record(s) modified; run `save` to write the file"));
    }
}
