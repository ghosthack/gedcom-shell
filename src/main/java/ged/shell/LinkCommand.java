package ged.shell;

import ged.edit.Links;
import ged.edit.Links.Slot;
import ged.model.Family;
import ged.model.Genealogy;
import ged.model.Person;
import ged.vfs.Location;

import java.util.List;
import java.util.stream.Collectors;

final class LinkCommand implements Command {
    @Override public String name() { return "link"; }
    @Override public boolean takesPaths() { return true; }
    @Override public String summary() { return "make an existing person a child, partner, parent or sibling"; }

    @Override
    public String usage() {
        return """
                link PERSON WHERE

                Adds PERSON (any path or id) to a family, as WHERE says, relative to
                where you are:

                  link I12 children          a child of the current person
                  link I12 spouses           a new partner: starts a family for the two
                  link I12 father            the father (likewise mother, parents)
                  link I12 siblings          a child of the current person's parents
                  link I12 /families/F3/children     a child of family F3
                  link I12 /families/F3/wife         the wife in F3 (likewise husband)

                A family is created when one is needed (a first child, a new partner,
                a parent for someone with none). When the current person has several
                families, say which: link I12 families/F3/children.

                Both sides are written (the family's CHIL/HUSB/WIFE line and the
                person's FAMC/FAMS line). Changes are held until `save`; undo one
                with `unlink`.
                """;
    }

    @Override
    public void run(List<String> args, Context ctx) {
        if (args.size() != 2) throw new CommandException("usage: link PERSON WHERE (see: help link)");
        var who = person(ctx, args.get(0));
        var where = args.get(1);
        var g = ctx.g();
        try {
            var said = link(ctx, g, who, where);
            ctx.nav().refresh();
            ctx.out().println(said);
            EditCommand.unsaved(ctx);
        } catch (IllegalArgumentException e) {
            throw new CommandException(e.getMessage());
        }
    }

    static Person person(Context ctx, String path) {
        var loc = ctx.nav().resolve(path).location();
        if (loc instanceof Location.PersonDir(var p)) return p;
        throw new CommandException(path + " is not a person (" + ctx.fs().describe(loc) + ")");
    }

    private String link(Context ctx, Genealogy g, Person x, String where) {
        var s = ctx.style();
        var name = s.bold(x.name().display()) + " " + s.dim(x.key());

        // father / mother / husband / wife name a slot that may be empty, so
        // they can't be resolved as paths: resolve what comes before them
        int slash = where.replaceAll("/+$", "").lastIndexOf('/');
        var last = where.replaceAll("/+$", "").substring(slash + 1).toLowerCase(java.util.Locale.ROOT);
        var base = slash < 0 ? "" : where.substring(0, slash + 1);
        if (List.of("father", "mother", "husband", "wife").contains(last)) {
            var loc = ctx.nav().resolve(base).location();
            return switch (loc) {
                case Location.PersonDir(var p) -> asParent(g, x, p, last.equals("father") ? Slot.HUSB : Slot.WIFE, name);
                case Location.FamilyDir(var f) -> {
                    Links.setPartner(g, f, last.equals("husband") ? Slot.HUSB : Slot.WIFE, x);
                    yield name + " is now the " + last + " in " + family(ctx, f);
                }
                default -> throw new CommandException(last + " only makes sense for a person or a family");
            };
        }

        var loc = ctx.nav().resolve(where).location();
        return switch (loc) {
            case Location.FamilyChildren(var f) -> {
                Links.addChild(g, f, x);
                yield name + " is now a child in " + family(ctx, f);
            }
            case Location.FamilyDir(var f) -> {
                var slot = Slot.of(x);
                if (slot == null) throw new CommandException(x.name().display() + "'s sex is unknown; say which: "
                        + "link " + x.key() + " " + where.replaceAll("/+$", "") + "/husband (or /wife)");
                Links.setPartner(g, f, slot, x);
                yield name + " is now the " + slot.word + " in " + family(ctx, f);
            }
            case Location.Group(var p, var r) -> switch (r) {
                case CHILDREN -> {
                    var f = onlyFamily(ctx, p, g.familiesAsSpouse(p), "children");
                    var created = f == null;
                    if (created) {
                        f = g.newFamily();
                        Links.setPartner(g, f, slotOrHusband(p), p);
                    }
                    Links.addChild(g, f, x);
                    yield name + " is now a child of " + s.bold(p.name().display()) + " in " + family(ctx, f)
                            + (created ? s.dim(" (new family)") : "");
                }
                case SPOUSES -> asPartner(ctx, g, x, p, name);
                case PARENTS -> {
                    var slot = Slot.of(x);
                    if (slot == null) {
                        var open = g.familiesAsChild(p).stream().findFirst()
                                .filter(f -> f.husband().isEmpty() != f.wife().isEmpty());
                        if (open.isEmpty()) throw new CommandException(x.name().display()
                                + "'s sex is unknown; say which: link " + x.key() + " father (or mother)");
                        slot = open.get().husband().isEmpty() ? Slot.HUSB : Slot.WIFE;
                    }
                    yield asParent(g, x, p, slot, name);
                }
                case SIBLINGS -> {
                    var f = g.familiesAsChild(p).stream().findFirst().orElseThrow(() -> new CommandException(
                            p.name().display() + " has no parents' family yet; link a parent first"));
                    Links.addChild(g, f, x);
                    yield name + " is now a sibling of " + s.bold(p.name().display()) + " in " + family(ctx, f);
                }
                case FAMILIES -> throw new CommandException("say how: children, spouses, parents, siblings, "
                        + "father or mother (or families/F…/children)");
            };
            default -> throw new CommandException("can't link to " + ctx.fs().describe(loc)
                    + "; try children, spouses, parents, siblings, father, mother or a family");
        };
    }

    /// `x` becomes `p`'s father or mother: in `p`'s family as a child, or a
    /// new one if `p` has none.
    private String asParent(Genealogy g, Person x, Person p, Slot slot, String name) {
        var word = slot == Slot.HUSB ? "father" : "mother";
        if (Slot.of(x) == slot.other()) {
            throw new IllegalArgumentException(x.name().display() + " is recorded as " + (slot == Slot.HUSB ? "female" : "male")
                    + "; link as " + (slot == Slot.HUSB ? "mother" : "father") + ", or fix with: edit " + x.key() + " sex=…");
        }
        var families = g.familiesAsChild(p);
        Family f;
        boolean created = families.isEmpty();
        if (created) {
            f = g.newFamily();
            Links.setPartner(g, f, slot, x);
            Links.addChild(g, f, p);
        } else {
            // the first family is the one `father` and `mother` read
            f = families.getFirst();
            var current = slot == Slot.HUSB ? f.husband() : f.wife();
            if (current.isPresent()) {
                var who = current.flatMap(g::person).map(q -> q.key() + "  " + q.label()).orElse(current.get());
                throw new IllegalArgumentException(p.name().display() + " already has a " + word + ": " + who
                        + " (unlink " + word + " first)");
            }
            Links.setPartner(g, f, slot, x);
        }
        var others = g.family(f.id()).orElseThrow().children().stream().filter(id -> !id.equals(p.id())).count();
        return name + " is now the " + word + " of " + p.name().display()
                + (others > 0 ? " and " + others + " sibling" + (others == 1 ? "" : "s") : "")
                + " (" + f.key() + (created ? ", new family" : "") + ")";
    }

    /// `x` and `p` become partners: filling the empty slot of `p`'s only
    /// half-empty family, or in a new family.
    private String asPartner(Context ctx, Genealogy g, Person x, Person p, String name) {
        if (g.spouses(p).contains(x)) throw new IllegalArgumentException(x.name().display() + " is already a partner of " + p.name().display());
        var open = g.familiesAsSpouse(p).stream().filter(f -> f.husband().isEmpty() || f.wife().isEmpty()).toList();
        Family f;
        boolean created = open.size() != 1;
        if (created) {
            f = g.newFamily();
            // by sex where known; same sex or both unknown: p first, as GEDCOM 5.5 has only these two slots
            Slot sp = Slot.of(p), sx = Slot.of(x);
            var pSlot = sp != null && sx != sp ? sp : sp == null && sx != null ? sx.other() : Slot.HUSB;
            Links.setPartner(g, f, pSlot, p);
            Links.setPartner(g, f, pSlot.other(), x);
        } else {
            f = open.getFirst();
            Links.setPartner(g, f, f.husband().isEmpty() ? Slot.HUSB : Slot.WIFE, x);
        }
        return name + " and " + ctx.style().bold(p.name().display()) + " are now partners in "
                + family(ctx, g.family(f.id()).orElseThrow()) + (created ? ctx.style().dim(" (new family)") : "");
    }

    private static Slot slotOrHusband(Person p) {
        var slot = Slot.of(p);
        return slot == null ? Slot.HUSB : slot;
    }

    /// `p`'s one family as a partner, null if none; asks which if several.
    private static Family onlyFamily(Context ctx, Person p, List<Family> families, String rest) {
        if (families.size() <= 1) return families.isEmpty() ? null : families.getFirst();
        throw new CommandException(p.name().display() + " has " + families.size() + " families; say which, e.g. families/"
                + families.getFirst().key() + "/" + rest + "\n" + families.stream()
                .map(f -> "  " + f.key() + "  " + ctx.fs().familyTitle(f)).collect(Collectors.joining("\n")));
    }

    static String family(Context ctx, Family f) {
        return ctx.style().dim(f.key()) + " " + ctx.fs().familyTitle(ctx.g().family(f.id()).orElse(f));
    }
}
