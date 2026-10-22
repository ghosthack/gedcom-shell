package ged.model;

import ged.gedcom.Node;

import java.util.List;
import java.util.Optional;

/// A `FAM` record: up to two partners plus children, in file order.
public record Family(
        String id,
        Optional<String> husband,
        Optional<String> wife,
        List<String> children,
        List<Event> events,
        List<String> notes,
        Node node) {

    static Family of(Node n) {
        return new Family(
                n.xref(),
                n.first("HUSB").map(Node::value),
                n.first("WIFE").map(Node::value),
                n.all("CHIL").map(Node::value).toList(),
                n.children().stream().filter(c -> Event.isEventTag(c.tag())).map(Event::of).toList(),
                n.all("NOTE").map(Node::value).toList(),
                n);
    }

    public String key() { return Genealogy.key(id); }

    public Optional<Event> event(String tag) {
        return events.stream().filter(e -> e.tag().equals(tag)).findFirst();
    }

    /// The partner of `personId` in this family, if recorded.
    public Optional<String> partnerOf(String personId) {
        if (husband.filter(personId::equals).isPresent()) return wife;
        if (wife.filter(personId::equals).isPresent()) return husband;
        return Optional.empty();
    }
}
