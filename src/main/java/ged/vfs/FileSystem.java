package ged.vfs;

import ged.model.Family;
import ged.model.Genealogy;
import ged.model.Person;
import ged.vfs.Location.FamilyChildren;
import ged.vfs.Location.FamilyDir;
import ged.vfs.Location.Group;
import ged.vfs.Location.PersonDir;
import ged.vfs.Location.Relation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/// Maps the genealogy onto a directory tree:
///
/// ```
/// /
/// ├── people/          I1, I2, ...
/// │   └── I1/          father, mother, parents/, spouses/, children/, siblings/, families/
/// ├── families/        F1, F2, ...
/// │   └── F1/          husband, wife, children/
/// └── found/           results of the last `find`
/// ```
///
/// [#entries] are the named children a path can walk through. [#rows] is
/// what `ls` shows and what row numbers index into; for a person it is the
/// flattened list of relatives.
public final class FileSystem {
    private final Genealogy g;
    private List<Person> found = List.of();
    private String foundQuery = "";

    public FileSystem(Genealogy g) { this.g = g; }

    public Genealogy genealogy() { return g; }

    public void setFound(String query, List<Person> results) {
        this.foundQuery = query;
        this.found = List.copyOf(results);
    }

    public String foundQuery() { return foundQuery; }

    /// Swaps stale [Person] objects in `/found` for current ones.
    void refresh() {
        found = found.stream().map(p -> g.person(p.id()).orElse(p)).toList();
    }

    public List<Entry> entries(Location loc) {
        return switch (loc) {
            case Location.Root _ -> List.of(
                    new Entry("people", new Location.People(), "dir"),
                    new Entry("families", new Location.Families(), "dir"),
                    new Entry("found", new Location.Found(), "dir"));
            case Location.People _ -> g.people().stream().map(p -> person(p, "")).toList();
            case Location.Families _ -> g.families().stream().map(f -> family(f, "")).toList();
            case Location.Found _ -> found.stream().map(p -> person(p, "")).toList();
            case PersonDir(var p) -> {
                var list = new ArrayList<Entry>();
                g.father(p).ifPresent(f -> list.add(new Entry("father", new PersonDir(f), "father")));
                g.mother(p).ifPresent(m -> list.add(new Entry("mother", new PersonDir(m), "mother")));
                for (var r : Relation.values()) list.add(new Entry(r.dirName, new Group(p, r), "dir"));
                yield list;
            }
            case FamilyDir(var f) -> {
                var list = new ArrayList<Entry>();
                f.husband().flatMap(g::person).ifPresent(h -> list.add(new Entry("husband", new PersonDir(h), "husband")));
                f.wife().flatMap(g::person).ifPresent(w -> list.add(new Entry("wife", new PersonDir(w), "wife")));
                list.add(new Entry("children", new FamilyChildren(f), "dir"));
                yield list;
            }
            case Group(var p, var r) -> switch (r) {
                case PARENTS -> g.parents(p).stream().map(x -> person(x, parentLabel(x))).toList();
                case SPOUSES -> g.spouses(p).stream().map(x -> person(x, spouseLabel(x))).toList();
                case CHILDREN -> g.children(p).stream().map(x -> person(x, childLabel(x))).toList();
                case SIBLINGS -> g.siblings(p).stream().map(x -> person(x, siblingLabel(x))).toList();
                case FAMILIES -> Stream.concat(
                        g.familiesAsChild(p).stream().map(f -> family(f, "as child")),
                        g.familiesAsSpouse(p).stream().map(f -> family(f, "as spouse"))).toList();
            };
            case FamilyChildren(var f) -> f.children().stream()
                    .flatMap(id -> g.person(id).stream()).map(x -> person(x, childLabel(x))).toList();
        };
    }

    public List<Entry> rows(Location loc) {
        return switch (loc) {
            case PersonDir(var p) -> {
                var rows = new ArrayList<Entry>();
                var father = g.father(p);
                var mother = g.mother(p);
                father.ifPresent(f -> rows.add(new Entry("father", new PersonDir(f), "father")));
                mother.ifPresent(m -> rows.add(new Entry("mother", new PersonDir(m), "mother")));
                for (var e : entries(new Group(p, Relation.PARENTS))) {
                    var t = ((PersonDir) e.target()).person();
                    if (father.filter(t::equals).isEmpty() && mother.filter(t::equals).isEmpty()) {
                        rows.add(nested(Relation.PARENTS, e));
                    }
                }
                for (var r : List.of(Relation.SPOUSES, Relation.CHILDREN, Relation.SIBLINGS)) {
                    entries(new Group(p, r)).forEach(e -> rows.add(nested(r, e)));
                }
                yield rows;
            }
            case FamilyDir(var f) -> {
                var rows = new ArrayList<>(entries(loc).stream().filter(e -> e.target() instanceof PersonDir).toList());
                entries(new FamilyChildren(f)).forEach(e ->
                        rows.add(new Entry(List.of("children", e.name()), e.target(), e.relation())));
                yield rows;
            }
            default -> entries(loc);
        };
    }

    private static Entry nested(Relation r, Entry e) {
        return new Entry(List.of(r.dirName, e.name()), e.target(), e.relation());
    }

    private static Entry person(Person p, String relation) {
        return new Entry(p.key(), new PersonDir(p), relation);
    }

    private static Entry family(Family f, String relation) {
        return new Entry(f.key(), new FamilyDir(f), relation);
    }

    private static String parentLabel(Person p) {
        return switch (p.sex()) { case MALE -> "father"; case FEMALE -> "mother"; default -> "parent"; };
    }

    private static String spouseLabel(Person p) {
        return switch (p.sex()) { case MALE -> "husband"; case FEMALE -> "wife"; default -> "spouse"; };
    }

    private static String childLabel(Person p) {
        return switch (p.sex()) { case MALE -> "son"; case FEMALE -> "daughter"; default -> "child"; };
    }

    private static String siblingLabel(Person p) {
        return switch (p.sex()) { case MALE -> "brother"; case FEMALE -> "sister"; default -> "sibling"; };
    }

    /// Short human description for prompts and headings.
    public String describe(Location loc) {
        return switch (loc) {
            case Location.Root _ -> "/";
            case Location.People _ -> "people";
            case Location.Families _ -> "families";
            case Location.Found _ -> "found";
            case PersonDir(var p) -> p.name().display();
            case FamilyDir(var f) -> familyTitle(f);
            case Group(var p, var r) -> p.name().display() + " › " + r.dirName;
            case FamilyChildren(var f) -> familyTitle(f) + " › children";
        };
    }

    public String familyTitle(Family f) {
        var h = f.husband().flatMap(g::person).map(p -> p.name().display());
        var w = f.wife().flatMap(g::person).map(p -> p.name().display());
        return Stream.of(h, w).flatMap(Optional::stream).reduce((a, b) -> a + " & " + b).orElse("(no partners)");
    }
}
