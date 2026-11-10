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

    public Optional<Event> event(String tag) {
        return events.stream().filter(e -> e.tag().equals(tag)).findFirst();
    }

    public GedDate birth() {
        return firstDated("BIRT", "CHR", "BAPM");
    }

    public GedDate death() {
        return firstDated("DEAT", "BURI");
    }

    /// The date of the first of these events that has one: an undated
    /// `BIRT` (place only) doesn't hide a dated christening.
    private GedDate firstDated(String... tags) {
        for (var tag : tags) {
            var d = events.stream().filter(e -> e.tag().equals(tag)).map(Event::date).filter(x -> !x.isEmpty()).findFirst();
            if (d.isPresent()) return d.get();
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
