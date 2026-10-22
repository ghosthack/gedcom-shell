package ged.shell;

import ged.model.Genealogy;
import ged.model.Person;
import ged.text.Style;
import ged.vfs.Location;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TreeCommand implements Command {
    private final Options opts = new Options()
            .flag("a", "ancestors", "show the pedigree (parents, grandparents, ...)")
            .flag("d", "descendants", "show descendants (default)")
            .value("n", "depth", "N", "generations to show (default 3)")
            .flag(null, "all", "no depth limit")
            .flag("S", "no-spouses", "descendants: don't show partners; list children directly");

    @Override public String name() { return "tree"; }
    @Override public String summary() { return "draw descendants (default) or ancestors (-a)"; }

    @Override
    public String usage() {
        return """
                tree [-a|-d] [-n N|--all] [-S] [PATH]

                Draws the family tree rooted at PATH (default: current person; for a
                family, its partners). People appearing twice — common in royal
                lines — are drawn once and then marked ↻.

                  tree                   3 generations of descendants
                  tree -a -n 5           5 generations of ancestors
                  tree --all -S ~        every descendant of the home person, no partners

                """ + opts.describe();
    }

    @Override
    public void run(List<String> args, Context ctx) {
        var o = opts.parse(args);
        if (o.positionals().size() > 1) throw new CommandException("too many arguments");
        if (o.has("ancestors") && o.has("descendants")) throw new CommandException("choose one of -a and -d");
        int depth = o.has("all") ? Integer.MAX_VALUE : o.get("depth").map(TreeCommand::depth).orElse(3);

        var loc = ctx.nav().resolve(o.positionals().isEmpty() ? "" : o.positionals().getFirst()).location();
        List<Person> roots = switch (loc) {
            case Location.PersonDir(var p) -> List.of(p);
            case Location.FamilyDir(var f) -> {
                var partners = new ArrayList<Person>();
                f.husband().flatMap(ctx.g()::person).ifPresent(partners::add);
                f.wife().flatMap(ctx.g()::person).ifPresent(partners::add);
                if (partners.isEmpty()) throw new CommandException("family " + f.key() + " has no partners");
                yield o.has("ancestors") ? partners : partners.subList(0, 1);
            }
            default -> throw new CommandException(ctx.fs().describe(loc) + " is not a person or family");
        };
        var r = new Renderer(ctx.g(), ctx.style(), depth, !o.has("no-spouses"));
        for (var p : roots) {
            if (o.has("ancestors")) r.ancestors(p);
            else r.descendants(p);
        }
        ctx.out().print(r.out);
    }

    private static int depth(String v) {
        try {
            int n = Integer.parseInt(v);
            if (n < 1) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new CommandException("depth must be a positive number, got '" + v + "'");
        }
    }

    private static final class Renderer {
        final Genealogy g;
        final Style s;
        final int maxDepth;
        final boolean spouses;
        final StringBuilder out = new StringBuilder();
        final Set<Person> drawn = new HashSet<>();

        Renderer(Genealogy g, Style s, int maxDepth, boolean spouses) {
            this.g = g;
            this.s = s;
            this.maxDepth = maxDepth;
            this.spouses = spouses;
        }

        String label(Person p) {
            return s.bold(p.name().display()) + s.dim(p.lifespan()) + "  " + s.dim(p.key());
        }

        /// Prints a person; returns false (and marks ↻) if already drawn.
        boolean node(String prefix, String branch, String relation, Person p) {
            out.append(s.dim(prefix + branch));
            if (!relation.isEmpty()) out.append(s.cyan(relation)).append(' ');
            out.append(label(p));
            boolean first = drawn.add(p);
            if (!first) out.append(' ').append(s.yellow("↻"));
            out.append('\n');
            return first;
        }

        void descendants(Person root) {
            if (!node("", "", "", root)) return;
            descend(root, "", 1);
        }

        private void descend(Person p, String prefix, int gen) {
            if (gen > maxDepth) return;
            var families = g.familiesAsSpouse(p);
            if (!spouses) {
                var kids = g.children(p);
                for (int i = 0; i < kids.size(); i++) {
                    boolean last = i == kids.size() - 1;
                    if (node(prefix, last ? "└── " : "├── ", "", kids.get(i))) {
                        descend(kids.get(i), prefix + (last ? "    " : "│   "), gen + 1);
                    }
                }
                return;
            }
            for (int i = 0; i < families.size(); i++) {
                var f = families.get(i);
                boolean last = i == families.size() - 1;
                var partner = f.partnerOf(p.id()).flatMap(g::person);
                var marr = f.event("MARR").map(e -> e.date().shortForm()).filter(d -> !d.isEmpty())
                        .map(d -> " m. " + d).orElse("");
                out.append(s.dim(prefix + (last ? "└── " : "├── "))).append(s.cyan("∞ "))
                        .append(partner.map(this::label).orElse(s.dim("(unknown partner)")))
                        .append(s.dim("  [" + f.key() + marr + "]")).append('\n');
                var inner = prefix + (last ? "    " : "│   ");
                var kids = f.children().stream().flatMap(id -> g.person(id).stream()).toList();
                for (int k = 0; k < kids.size(); k++) {
                    boolean lastKid = k == kids.size() - 1;
                    if (node(inner, lastKid ? "└── " : "├── ", "", kids.get(k))) {
                        descend(kids.get(k), inner + (lastKid ? "    " : "│   "), gen + 1);
                    }
                }
            }
        }

        void ancestors(Person root) {
            if (!node("", "", "", root)) return;
            ascend(root, "", 1);
        }

        private void ascend(Person p, String prefix, int gen) {
            if (gen > maxDepth) return;
            var parents = new ArrayList<Person>();
            g.father(p).ifPresent(parents::add);
            g.mother(p).ifPresent(parents::add);
            for (int i = 0; i < parents.size(); i++) {
                var parent = parents.get(i);
                boolean last = i == parents.size() - 1;
                if (node(prefix, last ? "└── " : "├── ", relation(parent, gen) + ":", parent)) {
                    ascend(parent, prefix + (last ? "    " : "│   "), gen + 1);
                }
            }
        }

        /// father, grandfather, great-grandfather, 2nd great-grandfather...
        static String relation(Person p, int gen) {
            var base = switch (p.sex()) { case MALE -> "father"; case FEMALE -> "mother"; default -> "parent"; };
            return switch (gen) {
                case 1 -> base;
                case 2 -> "grand" + base;
                case 3 -> "great-grand" + base;
                default -> ordinal(gen - 2) + " great-grand" + base;
            };
        }

        static String ordinal(int n) {
            var suffix = n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) {
                case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th";
            };
            return n + suffix;
        }
    }
}
