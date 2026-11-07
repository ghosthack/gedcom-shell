package ged.shell;

import ged.edit.Links;
import ged.model.Family;
import ged.model.Genealogy;
import ged.model.Person;
import ged.vfs.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class UnlinkCommand implements Command {
    @Override public String name() { return "unlink"; }
    @Override public boolean takesPaths() { return true; }
    @Override public String summary() { return "take a person out of a family (the person stays in the file)"; }

    @Override
    public String usage() {
        return """
                unlink PATH

                PATH ends with the relative to cut loose, and that person leaves the
                family that connects the two. Nobody is deleted:

                  unlink children/I12        I12 is no longer a child in this family
                  unlink father              the father leaves the parents' family
                                             (so he is no longer the father of the
                                             siblings either)
                  unlink spouses/I8          I8 leaves the couple's family; the family
                                             and its children stay with you
                  unlink siblings/I3         I3 leaves the shared parents' family
                  unlink families/F3         you leave family F3
                  unlink /families/F3/wife   the wife leaves F3

                To say "this is not my father" without touching his other children,
                unlink yourself from his side instead: cd father; unlink children/…

                A family left connecting nobody (one member or none) and recording
                nothing else, such as a marriage date or a note, is deleted. Changes are held until `save`; undo with `link`.
                """;
    }

    @Override
    public void run(List<String> args, Context ctx) {
        if (args.size() != 1) throw new CommandException("usage: unlink PATH (see: help unlink)");
        var path = ctx.nav().resolve(args.getFirst());
        var segs = path.segments();
        if (segs.size() < 2) throw new CommandException(args.getFirst() + " is not a relationship; try children/I3, spouses/I8, father");
        var g = ctx.g();
        var s = ctx.style();
        var last = segs.getLast().toLowerCase(Locale.ROOT);
        var rel = segs.get(segs.size() - 2).toLowerCase(Locale.ROOT);
        var lines = new ArrayList<String>();
        var touched = new ArrayList<Family>();

        // the relative's own location, and the location it was reached from
        if (List.of("father", "mother", "husband", "wife").contains(last)) {
            var x = person(path.location(), args.getFirst());
            var from = parent(ctx, segs, 1);
            switch (from) {
                case Location.PersonDir(var p) -> leaveAsParent(g, x, p, touched);
                case Location.FamilyDir(var f) -> touched.add(partnerLeaves(g, f, x));
                default -> throw new CommandException("can't tell which family " + args.getFirst() + " means");
            }
            lines.add(said(ctx, x, touched, "partner"));
        } else {
            var from = parent(ctx, segs, 2);
            var target = path.location();
            switch (rel) {
                case "families" -> {
                    if (!(target instanceof Location.FamilyDir(var f)) || !(from instanceof Location.PersonDir(var p))) {
                        throw new CommandException(args.getFirst() + " is not a relationship; from a person, unlink families/F… leaves that family");
                    }
                    boolean asChild = f.children().contains(p.id());
                    if (asChild) Links.removeChild(g, f, p);
                    else Links.removePartner(g, f, p);
                    touched.add(f);
                    lines.add(said(ctx, p, touched, asChild ? "child" : "partner"));
                }
                case "children", "spouses", "parents", "siblings" -> {
                    var x = person(target, args.getFirst());
                    var families = switch (from) {
                        case Location.PersonDir(var p) -> connecting(g, rel, p, x);
                        case Location.FamilyDir(var f) when rel.equals("children") -> List.of(f);
                        default -> throw new CommandException("can't tell which family " + args.getFirst() + " means");
                    };
                    if (families.isEmpty()) throw new CommandException("no family connects these two");
                    boolean asChild = rel.equals("children") || rel.equals("siblings");
                    for (var f : families) {
                        if (asChild) Links.removeChild(g, f, x);
                        else Links.removePartner(g, f, x);
                        touched.add(f);
                    }
                    lines.add(said(ctx, x, touched, asChild ? "child" : "partner"));
                }
                default -> throw new CommandException(args.getFirst() + " is not a relationship; try children/I3, spouses/I8, father");
            }
        }

        for (var f : touched) {
            var fresh = g.family(f.id()).orElse(f);
            if (Links.isDisposable(fresh)) {
                Links.dissolve(g, fresh);
                lines.add(s.dim("  " + fresh.key() + " no longer connected anyone and was deleted"));
            }
        }
        ctx.nav().refresh();
        lines.forEach(ctx.out()::println);
        EditCommand.unsaved(ctx);
    }

    private static Person person(Location loc, String path) {
        if (loc instanceof Location.PersonDir(var p)) return p;
        throw new CommandException(path + " is not a person");
    }

    /// The location `drop` segments up the logical path.
    private static Location parent(Context ctx, List<String> segs, int drop) {
        return ctx.nav().resolve("/" + String.join("/", segs.subList(0, segs.size() - drop))).location();
    }

    /// Families that make `x` the `rel` of `p`, which `x` should leave.
    private static List<Family> connecting(Genealogy g, String rel, Person p, Person x) {
        return switch (rel) {
            case "children" -> g.familiesAsSpouse(p).stream().filter(f -> f.children().contains(x.id())).toList();
            case "spouses" -> g.familiesAsSpouse(p).stream().filter(f -> f.partnerOf(p.id()).filter(x.id()::equals).isPresent()).toList();
            case "parents" -> g.familiesAsChild(p).stream().filter(f -> isPartner(f, x)).toList();
            case "siblings" -> {
                var shared = g.familiesAsChild(p).stream().filter(f -> f.children().contains(x.id())).toList();
                if (!shared.isEmpty()) yield shared;
                // a half-sibling leaves the family that gives them the shared parent
                var parents = g.parents(p);
                yield g.familiesAsChild(x).stream()
                        .filter(f -> parents.stream().anyMatch(par -> isPartner(f, par)))
                        .toList();
            }
            default -> List.of();
        };
    }

    private static void leaveAsParent(Genealogy g, Person x, Person p, List<Family> touched) {
        for (var f : g.familiesAsChild(p)) {
            if (isPartner(f, x)) {
                Links.removePartner(g, f, x);
                touched.add(f);
            }
        }
        if (touched.isEmpty()) throw new CommandException("no family connects these two");
    }

    private static Family partnerLeaves(Genealogy g, Family f, Person x) {
        Links.removePartner(g, f, x);
        return f;
    }

    private static boolean isPartner(Family f, Person x) {
        return f.husband().filter(x.id()::equals).isPresent() || f.wife().filter(x.id()::equals).isPresent();
    }

    /// "Luca Rossi I7 left F4 (was a partner; Sofia Verdi and 1 child remain)".
    private static String said(Context ctx, Person x, List<Family> families, String role) {
        var s = ctx.style();
        var g = ctx.g();
        var sb = new StringBuilder(s.bold(x.name().display()) + " " + s.dim(x.key()) + " left ");
        for (int i = 0; i < families.size(); i++) {
            var f = g.family(families.get(i).id()).orElse(families.get(i));
            if (i > 0) sb.append(", ");
            sb.append(f.key());
            var remain = new ArrayList<String>();
            f.husband().flatMap(g::person).ifPresent(q -> remain.add(q.name().display()));
            f.wife().flatMap(g::person).ifPresent(q -> remain.add(q.name().display()));
            int kids = f.children().size();
            if (kids > 0) remain.add(kids + (kids == 1 ? " child" : " children"));
            var verb = remain.size() == 1 && !remain.getFirst().endsWith(" children") ? " remains" : " remain";
            sb.append(s.dim(" (was a " + role + (remain.isEmpty() ? "" : "; " + String.join(" and ", remain) + verb) + ")"));
        }
        return sb.toString();
    }
}
