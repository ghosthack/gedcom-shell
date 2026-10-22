package ged.vfs;

import ged.model.Family;
import ged.model.Person;

/// A place you can `cd` into. Every location is directory-like: people and
/// families "contain" their relatives.
public sealed interface Location {
    /// `/`
    record Root() implements Location {}
    /// `/people`
    record People() implements Location {}
    /// `/families`
    record Families() implements Location {}
    /// `/found` — results of the most recent `find`.
    record Found() implements Location {}
    /// `/people/I1`
    record PersonDir(Person person) implements Location {}
    /// `/families/F1`
    record FamilyDir(Family family) implements Location {}
    /// `/people/I1/children` etc.
    record Group(Person person, Relation relation) implements Location {}
    /// `/families/F1/children`
    record FamilyChildren(Family family) implements Location {}

    enum Relation {
        PARENTS("parents"), SPOUSES("spouses"), CHILDREN("children"), SIBLINGS("siblings"), FAMILIES("families");

        public final String dirName;
        Relation(String dirName) { this.dirName = dirName; }

        /// Singular label for one member of the group.
        public String member() {
            return switch (this) {
                case PARENTS -> "parent";
                case SPOUSES -> "spouse";
                case CHILDREN -> "child";
                case SIBLINGS -> "sibling";
                case FAMILIES -> "family";
            };
        }
    }

    Root ROOT = new Root();

    default String canonicalPath() {
        return switch (this) {
            case Root _ -> "/";
            case People _ -> "/people";
            case Families _ -> "/families";
            case Found _ -> "/found";
            case PersonDir(var p) -> "/people/" + p.key();
            case FamilyDir(var f) -> "/families/" + f.key();
            case Group(var p, var r) -> "/people/" + p.key() + "/" + r.dirName;
            case FamilyChildren(var f) -> "/families/" + f.key() + "/children";
        };
    }
}
