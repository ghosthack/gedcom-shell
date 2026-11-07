package ged.edit;

import ged.gedcom.Node;
import ged.model.Family;
import ged.model.Genealogy;
import ged.model.Person;

import java.util.List;

/// The pointer pairs that make relationships: a family's `HUSB`/`WIFE`/
/// `CHIL` lines and the matching `FAMS`/`FAMC` lines on each person. Every
/// operation changes both sides together, so the file never says one thing
/// in the family and another in the person.
///
/// Operations throw [IllegalArgumentException] with a user-facing message
/// when the link already exists or the slot is taken.
public final class Links {
    private Links() {}

    public enum Slot {
        HUSB("husband"), WIFE("wife");

        public final String word;

        Slot(String word) { this.word = word; }

        /// The slot that fits `p`'s sex, or null if their sex is unknown.
        public static Slot of(Person p) {
            return switch (p.sex()) {
                case MALE -> HUSB;
                case FEMALE -> WIFE;
                default -> null;
            };
        }

        public Slot other() { return this == HUSB ? WIFE : HUSB; }
    }

    /// Makes `child` a child of `f`.
    public static void addChild(Genealogy g, Family f, Person child) {
        f = fresh(g, f);
        child = fresh(g, child);
        if (f.children().contains(child.id())) {
            throw new IllegalArgumentException(child.name().display() + " is already a child in " + f.key());
        }
        if (f.husband().filter(child.id()::equals).isPresent() || f.wife().filter(child.id()::equals).isPresent()) {
            throw new IllegalArgumentException(child.name().display() + " is a partner in " + f.key() + ", not a child");
        }
        f.node().insertAfter(List.of("HUSB", "WIFE", "CHIL"), "CHIL", child.id());
        var rec = child.node();
        if (rec.first("FAMC").isPresent()) rec.insertAfter(List.of("FAMC"), "FAMC", f.id());
        else rec.insertBefore(PersonForm.TRAILING_TAGS, "FAMC", f.id());
        done(g, f, child);
    }

    /// Puts `p` in the empty `slot` of `f`.
    public static void setPartner(Genealogy g, Family f, Slot slot, Person p) {
        f = fresh(g, f);
        p = fresh(g, p);
        var current = slot == Slot.HUSB ? f.husband() : f.wife();
        if (current.isPresent()) {
            var who = current.flatMap(g::person).map(Person::label).orElse(current.get());
            throw new IllegalArgumentException(f.key() + " already has a " + slot.word + ": " + who);
        }
        if (f.husband().filter(p.id()::equals).isPresent() || f.wife().filter(p.id()::equals).isPresent()) {
            throw new IllegalArgumentException(p.name().display() + " is already a partner in " + f.key());
        }
        if (f.children().contains(p.id())) {
            throw new IllegalArgumentException(p.name().display() + " is a child in " + f.key() + ", not a partner");
        }
        var empty = f.node().all(slot.name()).filter(Node::isVoid).findFirst();
        if (empty.isPresent()) empty.get().setValue(p.id()); // GEDCOM 7: fill `HUSB @VOID@`
        else if (slot == Slot.HUSB) f.node().insert(0, "HUSB", p.id());
        else f.node().insertAfter(List.of("HUSB"), "WIFE", p.id());
        var rec = p.node();
        if (rec.first("FAMS").isPresent()) rec.insertAfter(List.of("FAMS"), "FAMS", f.id());
        else if (rec.first("FAMC").isPresent()) rec.insertAfter(List.of("FAMC"), "FAMS", f.id());
        else rec.insertBefore(PersonForm.TRAILING_TAGS, "FAMS", f.id());
        done(g, f, p);
    }

    /// Takes `child` out of `f`'s children.
    public static void removeChild(Genealogy g, Family f, Person child) {
        f = fresh(g, f);
        child = fresh(g, child);
        remove(f.node(), "CHIL", child.id());
        remove(child.node(), "FAMC", f.id());
        done(g, f, child);
    }

    /// Takes `p` out of `f`'s partners; the family and its children stay.
    public static void removePartner(Genealogy g, Family f, Person p) {
        f = fresh(g, f);
        p = fresh(g, p);
        remove(f.node(), "HUSB", p.id());
        remove(f.node(), "WIFE", p.id());
        remove(p.node(), "FAMS", f.id());
        done(g, f, p);
    }

    /// True once a family connects nobody (one member or none) and records
    /// nothing else worth keeping (events, notes, sources).
    public static boolean isDisposable(Family f) {
        int members = f.husband().stream().mapToInt(_ -> 1).sum() + f.wife().stream().mapToInt(_ -> 1).sum() + f.children().size();
        return members <= 1
                && f.node().children().stream().allMatch(c -> List.of("HUSB", "WIFE", "CHIL", "CHAN", "RIN", "_UID", "REFN").contains(c.tag()));
    }

    /// Deletes a family, first taking out whoever is left in it.
    public static void dissolve(Genealogy g, Family f) {
        f = fresh(g, f);
        for (var id : f.children()) {
            var p = g.person(id);
            if (p.isPresent()) removeChild(g, f, p.get());
        }
        for (var id : java.util.stream.Stream.of(f.husband(), f.wife()).flatMap(java.util.Optional::stream).toList()) {
            var p = g.person(id);
            if (p.isPresent()) removePartner(g, f, p.get());
        }
        g.removeFamily(fresh(g, f));
    }

    private static Family fresh(Genealogy g, Family f) { return g.family(f.id()).orElse(f); }

    private static Person fresh(Genealogy g, Person p) { return g.person(p.id()).orElse(p); }

    private static void remove(Node rec, String tag, String pointer) {
        rec.all(tag).filter(n -> n.value().equals(pointer)).toList().forEach(rec::remove);
    }

    private static void done(Genealogy g, Family f, Person p) {
        Records.touch(f.node());
        Records.touch(p.node());
        g.updated(f);
        g.updated(p);
    }
}
