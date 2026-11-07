package ged.shell;

import ged.edit.PersonForm;
import ged.model.Person;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AddCommand implements Command {
    /// Friendly words for where the new person goes, and the `link` target each means.
    private static final Map<String, String> RELATIONS = Map.of(
            "child", "children", "spouse", "spouses", "partner", "spouses",
            "sibling", "siblings", "father", "father", "mother", "mother");

    @Override public String name() { return "add"; }
    @Override public boolean takesPaths() { return true; }
    @Override public List<String> firstWords() { return List.of("child", "spouse", "partner", "father", "mother", "sibling", "person"); }
    @Override public String summary() { return "add a new person as a child, partner, parent or sibling"; }

    @Override
    public String usage() {
        return """
                add RELATION NAME [FIELD=VALUE...]

                Creates a person and links them to where you are. RELATION is child,
                spouse (or partner), father, mother, sibling, or person for someone not
                linked to anyone yet. It can also be any WHERE that `link` accepts,
                such as families/F3/children.

                  add child "Maria Rossi" sex=F born="12 jun 1850"
                  add father "Giovanni /Rossi/" birthplace=Genova
                  add spouse "Juan /Pérez García/" sex=M
                  add person "Ana Ruiz"

                In NAME, the surname goes between slashes, as in GEDCOM. Without
                slashes the last word is taken as the surname, so write
                "Juan /Pérez García/" for a compound one. Fathers are recorded as
                male and mothers as female unless sex= says otherwise. The fields
                are those of `edit` (see: help edit).

                The person is numbered after the file's own ids. If anything is
                refused, nothing is added. Changes are held until `save`.
                """;
    }

    @Override
    public void run(List<String> args, Context ctx) {
        if (args.size() < 2) throw new CommandException("usage: add RELATION NAME [FIELD=VALUE...] (see: help add)");
        var relation = args.get(0);
        var name = args.get(1);
        var fields = new LinkedHashMap<PersonForm.Field, String>();
        for (var a : args.subList(2, args.size())) {
            int eq = a.indexOf('=');
            if (eq <= 0) throw new CommandException("unexpected '" + a + "': after the name come FIELD=VALUE pairs (quote names with spaces)");
            var key = a.substring(0, eq);
            var f = PersonForm.Field.parse(key).orElseThrow(() -> new CommandException("unknown field '" + key + "' (see: help edit)"));
            if (fields.put(f, a.substring(eq + 1)) != null) throw new CommandException(key + " given twice");
        }
        var rel = relation.toLowerCase(Locale.ROOT);
        var where = rel.equals("person") ? null : RELATIONS.getOrDefault(rel, relation);
        var sex = switch (rel) {
            case "father" -> Person.Sex.MALE;
            case "mother" -> Person.Sex.FEMALE;
            default -> Person.Sex.UNKNOWN;
        };
        if (name.isBlank()) throw new CommandException("the name is empty");
        if (name.chars().filter(c -> c == '/').count() % 2 != 0) {
            throw new CommandException("unbalanced '/' in the name; write the surname between two: \"Maria /Rossi/\"");
        }

        var g = ctx.g();
        var s = ctx.style();
        // where must resolve before anything is created
        if (where != null) ctx.nav().resolve(where.replaceAll("/?(father|mother|husband|wife)/*$", ""));
        var p = g.newPerson(gedcomName(name), sex);
        String linked;
        List<PersonForm.Change> changes;
        try {
            changes = PersonForm.diff(p, fields);
            p = PersonForm.apply(g, p, changes);
            linked = where == null ? null : LinkCommand.link(ctx, g, p, where);
        } catch (IllegalArgumentException | CommandException | ged.vfs.Navigator.NotFound e) {
            g.removePerson(g.person(p.id()).orElse(p));
            throw e instanceof IllegalArgumentException ? new CommandException(e.getMessage()) : (RuntimeException) e;
        }
        p = g.person(p.id()).orElse(p);
        ctx.nav().refresh();
        var facts = new StringBuilder("given: " + p.name().given() + " · surname: " + p.name().surname());
        facts.append(" · sex: ").append(p.sex() == Person.Sex.UNKNOWN ? "U" : p.sex().code);
        for (var c : changes) {
            if (!List.of(PersonForm.Field.GIVEN, PersonForm.Field.SURNAME, PersonForm.Field.SEX).contains(c.field()) && !c.after().isEmpty()) facts.append(" · ").append(c.field().key).append(": ").append(c.after());
        }
        ctx.out().println(s.green("added ") + s.bold(p.name().display()) + " " + s.dim(p.key()) + "  " + s.dim(facts.toString()));
        if (linked != null) ctx.out().println(linked);
        EditCommand.unsaved(ctx);
    }

    /// `Maria /Rossi/` as given; `Maria Rossi` → `Maria /Rossi/`; `Maria` → `Maria`.
    static String gedcomName(String typed) {
        var t = typed.strip().replaceAll("\\s+", " ");
        if (t.contains("/")) return t;
        int sp = t.lastIndexOf(' ');
        return sp < 0 ? t : t.substring(0, sp) + " /" + t.substring(sp + 1) + "/";
    }
}
