package ged.edit;

import ged.gedcom.Node;
import ged.model.GedDate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/// Helpers shared by the person and family forms: reading the `field:
/// value` text, normalising values, and writing events into records.
final class Records {
    private Records() {}

    /// Parses an edited form. Fields left out of the text are unchanged.
    ///
    /// @throws IllegalArgumentException on unknown or repeated fields
    static <F> Map<F, String> parseForm(String text, Function<String, Optional<F>> field, Function<F, String> key) {
        var out = new LinkedHashMap<F, String>();
        int lineNo = 0;
        for (var line : text.split("\r?\n")) {
            lineNo++;
            var t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int colon = t.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("line " + lineNo + ": expected 'field: value', got '" + t + "'");
            var name = t.substring(0, colon).strip();
            int at = lineNo;
            var f = field.apply(name).orElseThrow(() ->
                    new IllegalArgumentException("line " + at + ": unknown field '" + name + "'"));
            if (out.put(f, t.substring(colon + 1).strip()) != null) {
                throw new IllegalArgumentException("'" + key.apply(f) + "' appears twice");
            }
        }
        return out;
    }

    /// The `# ERROR:` banner that tops a reopened form.
    static String errorBanner(String error) {
        return error == null ? "" : "# ERROR: " + error.replace("\n", "\n#        ") + "\n#\n";
    }

    static String clean(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    /// A date, normalised; text equal to `current` is kept as it is, even
    /// if the file's form is unusual.
    static String date(String key, String v, String current) {
        if (v.equals(current)) return current;
        try {
            return GedDate.normalize(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + ": " + e.getMessage());
        }
    }

    static String yesNo(String key, String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1" -> "yes";
            case "no", "n", "false", "0", "" -> "no";
            default -> throw new IllegalArgumentException(key + " must be yes or no, not '" + v + "'");
        };
    }

    /// True if the event carries more than a date and place (sources,
    /// notes, …), so removing it would lose information.
    static boolean hasDetails(Node event) {
        return event.children().stream().anyMatch(c -> !List.of("DATE", "PLAC").contains(c.tag()));
    }

    private static final DateTimeFormatter CHAN_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT);
    private static final DateTimeFormatter CHAN_TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /// Updates the record's existing `CHAN` date/time, as genealogy
    /// programs do. A record without one is left without one.
    static void touch(Node rec) {
        rec.first("CHAN").ifPresent(chan -> {
            var now = LocalDateTime.now();
            var date = chan.first("DATE").orElseGet(() -> chan.insert(0, "DATE", ""));
            date.setValue(CHAN_DATE.format(now).toUpperCase(Locale.ROOT));
            var time = date.first("TIME");
            if (time.isPresent()) time.get().setValue(CHAN_TIME.format(now));
        });
    }

    /// Sets DATE/PLAC under an event (null = leave as is), creating the
    /// event if needed and removing it once it carries nothing at all.
    /// With `keepAsFlag`, an event with no date or place is kept as `TAG Y`
    /// (GEDCOM's "this happened, details unknown").
    static void event(Node rec, String tag, Function<Node, Node> create, String date, String place, boolean keepAsFlag) {
        var ev = rec.first(tag).orElseGet(() -> create.apply(rec));
        if (date != null) setOrRemove(ev, "DATE", date, 0);
        if (place != null) setOrRemove(ev, "PLAC", place, ev.first("DATE").isPresent() ? 1 : 0);
        boolean hasFacts = ev.first("DATE").isPresent() || ev.first("PLAC").isPresent();
        if (keepAsFlag) {
            ev.setValue(hasFacts ? "" : "Y");
        } else if (ev.children().isEmpty() && ev.value().isEmpty()) {
            rec.remove(ev);
        }
    }

    private static void setOrRemove(Node ev, String tag, String value, int index) {
        var existing = ev.first(tag);
        if (value.isEmpty()) existing.ifPresent(ev::remove);
        else if (existing.isPresent()) existing.get().setValue(value);
        else ev.insert(index, tag, value);
    }
}
