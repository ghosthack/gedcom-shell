package ged.model;

import ged.gedcom.Node;

import java.util.List;
import java.util.Optional;

/// An `INDI` record. Relationship lookups live in [Genealogy], which owns
/// the indexes; a Person only knows the family ids it points at.
public record Person(
        String id,
        Name name,
        Sex sex,
        List<Event> events,
        List<String> childOf,
        List<String> spouseIn,
        List<String> notes,
        Node node) {

    public enum Sex {
        MALE("M"), FEMALE("F"), UNKNOWN("?");
        public final String code;
        Sex(String code) { this.code = code; }

        static Sex parse(String v) {
            return switch (v.strip().toUpperCase()) {
                case "M" -> MALE;
                case "F" -> FEMALE;
                default -> UNKNOWN;
            };
        }
    }

    static Person of(Node n) {
        var events = n.children().stream()
                .filter(c -> Event.isEventTag(c.tag()))
                .map(Event::of)
                .toList();
        return new Person(
                n.xref(),
                n.first("NAME").map(Name::of).orElse(Name.EMPTY),
                Sex.parse(n.text("SEX")),
                events,
                n.links("FAMC").map(Node::value).toList(),
                n.links("FAMS").map(Node::value).toList(),
                n.notes().map(Node::value).toList(),
                n);
    }

    /// A name besides the main one, with its GEDCOM `TYPE` (`aka`, `married`,
    /// `birth`, ...; empty when not given). Where GEDCOM 7 explains the type
    /// in words (`TYPE OTHER` with `PHRASE stage name`), `type` is those words.
    public record OtherName(Name name, String type) {}

    /// Every `NAME` after the first, in file order.
    public List<OtherName> otherNames() {
        return node.all("NAME").skip(1)
                .map(n -> new OtherName(Name.of(n), n.first("TYPE")
                        .map(t -> t.text("PHRASE").isBlank() ? t.value() : t.text("PHRASE")).orElse("").strip()))
                .filter(o -> !o.name().full().isEmpty())
                .toList();
    }

    /// `NICK` values from all of the person's names, without repeats.
    public List<String> nicknames() {
        return node.all("NAME").flatMap(n -> n.all("NICK"))
                .map(n -> n.value().strip())
                .filter(v -> !v.isEmpty())
                .distinct()
                .toList();
    }

    /// Main name, other names and nicknames, for searching.
    public String allNames() {
        var parts = new java.util.ArrayList<String>();
        parts.add(name.full());
        otherNames().forEach(o -> parts.add(o.name().full()));
        parts.addAll(nicknames());
        return String.join(" ", parts);
    }

    public Optional<Event> event(String tag) {
        return events.stream().filter(e -> e.tag().equals(tag)).findFirst();
    }

    public GedDate birth() {
        return firstDated("BIRT", "CHR", "BAPM");
    }

    public GedDate death() {
        return firstDated("DEAT", "BURI", "CREM");
    }

    /// The date of the first of these events that has one: an undated
    /// `BIRT` (place only) doesn't hide a dated christening.
    private GedDate firstDated(String... tags) {
        // a date that names a year first; failing that, whatever was written (a phrase)
        for (var known : List.of(true, false)) {
            for (var tag : tags) {
                var d = events.stream().filter(e -> e.tag().equals(tag)).map(Event::date)
                        .filter(x -> known ? x.hasYear() : !x.isEmpty()).findFirst();
                if (d.isPresent()) return d.get();
            }
        }
        return GedDate.EMPTY;
    }

    public boolean isDeceased() {
        return event("DEAT").isPresent() || event("BURI").isPresent() || event("CREM").isPresent();
    }

    /// Bare id without the `@` delimiters, used as the entry name in paths.
    public String key() { return Genealogy.key(id); }

    /// `Name (1850–1920)` style label.
    public String label() {
        return name.display() + lifespan();
    }

    public String lifespan() {
        var b = birth().shortForm();
        var d = death().shortForm();
        if (b.isEmpty() && d.isEmpty()) return isDeceased() ? " (†)" : "";
        if (d.isEmpty()) return " (" + (isDeceased() ? b + "–†" : "b. " + b) + ")";
        return " (" + b + "–" + d + ")";
    }
}
