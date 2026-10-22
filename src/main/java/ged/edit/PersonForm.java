package ged.edit;

import ged.gedcom.Node;
import ged.model.GedDate;
import ged.model.Genealogy;
import ged.model.Person;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// The editable view of a person: a flat `field: value` form that can be
/// rendered for a text editor, parsed back, diffed, and applied to the
/// person's GEDCOM nodes. Only fields whose value actually changed are
/// written, so untouched lines keep their original formatting.
public final class PersonForm {
    private PersonForm() {}

    public enum Field {
        GIVEN("given", "Given names"),
        SURNAME("surname", "Surname"),
        SUFFIX("suffix", "Suffix (Jr., III)"),
        SEX("sex", "M, F or U"),
        BIRTH_DATE("birth.date", "Date"),
        BIRTH_PLACE("birth.place", "Place"),
        DEATH_DATE("death.date", "Date"),
        DEATH_PLACE("death.place", "Place"),
        DECEASED("deceased", "yes or no");

        public final String key;
        final String hint;

        Field(String key, String hint) {
            this.key = key;
            this.hint = hint;
        }

        /// Accepts the form key plus a few natural aliases for `FIELD=VALUE`.
        public static Optional<Field> parse(String name) {
            var n = name.strip().toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
            var alias = switch (n) {
                case "given.name", "first", "firstname", "first.name", "forename" -> "given";
                case "last", "lastname", "last.name", "family.name" -> "surname";
                case "gender" -> "sex";
                case "born", "birth", "birthdate", "dob" -> "birth.date";
                case "birthplace", "pob" -> "birth.place";
                case "died", "death", "deathdate", "dod" -> "death.date";
                case "deathplace", "pod" -> "death.place";
                case "dead" -> "deceased";
                default -> n;
            };
            return Arrays.stream(values()).filter(f -> f.key.equals(alias)).findFirst();
        }
    }

    public record Change(Field field, String before, String after) {}

    /// Current values, in form order.
    public static Map<Field, String> values(Person p) {
        var n = p.node();
        var v = new LinkedHashMap<Field, String>();
        v.put(Field.GIVEN, p.name().given());
        v.put(Field.SURNAME, p.name().surname());
        v.put(Field.SUFFIX, p.name().suffix());
        v.put(Field.SEX, p.sex() == Person.Sex.UNKNOWN ? "U" : p.sex().code);
        v.put(Field.BIRTH_DATE, n.first("BIRT").map(b -> b.text("DATE")).orElse(""));
        v.put(Field.BIRTH_PLACE, n.first("BIRT").map(b -> b.text("PLAC")).orElse(""));
        v.put(Field.DEATH_DATE, n.first("DEAT").map(d -> d.text("DATE")).orElse(""));
        v.put(Field.DEATH_PLACE, n.first("DEAT").map(d -> d.text("PLAC")).orElse(""));
        v.put(Field.DECEASED, p.isDeceased() ? "yes" : "no");
        return v;
    }

    // ---- the text form ----------------------------------------------------

    public static String render(Person p, String error) {
        var sb = new StringBuilder();
        if (error != null) {
            sb.append("# ERROR: ").append(error.replace("\n", "\n#        ")).append("\n#\n");
        }
        sb.append("# Editing ").append(p.key()).append("  ").append(p.name().display()).append('\n');
        sb.append("""
                #
                # Change values after the colon; leave a value empty to remove it.
                # Save and quit to apply. Quit without saving (or :cq in vi) to cancel.
                #
                #   sex       M, F or U
                #   dates     12 JUN 1850 · JUN 1850 · 1850 · ABT 1850 · BEF 1900 · AFT 1900
                #             BET 1800 AND 1810 · FROM 1914 TO 1918 · (free text)
                #             1850-06-12 and "12 june 1850" are converted for you
                #   deceased  yes or no; a death date or place implies yes

                """);
        var values = values(p);
        Field previous = null;
        for (var e : values.entrySet()) {
            var f = e.getKey();
            if (previous != null && group(previous) != group(f)) sb.append('\n');
            sb.append(String.format("%-13s %s", f.key + ":", e.getValue()).stripTrailing()).append('\n');
            previous = f;
        }
        return sb.toString();
    }

    private static int group(Field f) {
        return switch (f) {
            case GIVEN, SURNAME, SUFFIX, SEX -> 0;
            case BIRTH_DATE, BIRTH_PLACE -> 1;
            default -> 2;
        };
    }

    /// Parses the edited form. Fields left out of the text are unchanged.
    ///
    /// @throws IllegalArgumentException on unknown or repeated fields
    public static Map<Field, String> parse(String text) {
        var out = new LinkedHashMap<Field, String>();
        int lineNo = 0;
        for (var line : text.split("\r?\n")) {
            lineNo++;
            var t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int colon = t.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("line " + lineNo + ": expected 'field: value', got '" + t + "'");
            var key = t.substring(0, colon).strip();
            int at = lineNo;
            var field = Field.parse(key).orElseThrow(() ->
                    new IllegalArgumentException("line " + at + ": unknown field '" + key + "'"));
            if (out.put(field, t.substring(colon + 1).strip()) != null) {
                throw new IllegalArgumentException("'" + field.key + "' appears twice");
            }
        }
        return out;
    }

    // ---- validation, diff, apply ------------------------------------------

    /// Validates and normalises `requested` against the person's current
    /// values; returns only real changes, in form order.
    ///
    /// @throws IllegalArgumentException with a user-facing message
    public static List<Change> diff(Person p, Map<Field, String> requested) {
        var current = values(p);
        var wanted = new LinkedHashMap<>(current);
        for (var e : requested.entrySet()) wanted.put(e.getKey(), normalize(e.getKey(), e.getValue(), current.get(e.getKey())));

        boolean hasDeathFacts = !wanted.get(Field.DEATH_DATE).isEmpty() || !wanted.get(Field.DEATH_PLACE).isEmpty();
        if (hasDeathFacts && requested.containsKey(Field.DECEASED) && wanted.get(Field.DECEASED).equals("no")) {
            throw new IllegalArgumentException("deceased is 'no' but a death date or place is given");
        }
        if (hasDeathFacts) wanted.put(Field.DECEASED, "yes");
        if (wanted.get(Field.DECEASED).equals("no") && current.get(Field.DECEASED).equals("yes")) {
            var n = p.node();
            if (n.first("BURI").isPresent() || n.first("CREM").isPresent()) {
                throw new IllegalArgumentException("can't mark as living: there is a burial or cremation record");
            }
            var deat = n.first("DEAT");
            if (deat.isPresent() && deat.get().children().stream().anyMatch(c -> !List.of("DATE", "PLAC").contains(c.tag()))) {
                throw new IllegalArgumentException("can't mark as living: the death record has sources or notes; edit the file directly");
            }
        }

        var changes = new ArrayList<Change>();
        for (var f : Field.values()) {
            if (!wanted.get(f).equals(current.get(f))) changes.add(new Change(f, current.get(f), wanted.get(f)));
        }
        return changes;
    }

    private static String normalize(Field f, String value, String current) {
        var v = value.strip().replaceAll("\\s+", " ");
        return switch (f) {
            case GIVEN, SURNAME, SUFFIX -> {
                if (v.contains("/")) throw new IllegalArgumentException(f.key + " can't contain '/'");
                yield v;
            }
            case SEX -> switch (v.toUpperCase(Locale.ROOT)) {
                case "M", "MALE" -> "M";
                case "F", "FEMALE" -> "F";
                case "U", "?", "", "UNKNOWN" -> "U";
                default -> throw new IllegalArgumentException("sex must be M, F or U, not '" + v + "'");
            };
            case BIRTH_DATE, DEATH_DATE -> {
                // unchanged text stays as-is, even if the file's form is unusual
                if (v.equals(current)) yield current;
                try {
                    yield GedDate.normalize(v);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(f.key + ": " + e.getMessage());
                }
            }
            case BIRTH_PLACE, DEATH_PLACE -> v;
            case DECEASED -> switch (v.toLowerCase(Locale.ROOT)) {
                case "yes", "y", "true", "1" -> "yes";
                case "no", "n", "false", "0", "" -> "no";
                default -> throw new IllegalArgumentException("deceased must be yes or no, not '" + v + "'");
            };
        };
    }

    /// Writes `changes` into the person's GEDCOM record and returns the
    /// re-indexed person.
    public static Person apply(Genealogy g, Person p, List<Change> changes) {
        var rec = p.node();
        var after = new LinkedHashMap<Field, String>();
        for (var c : changes) after.put(c.field(), c.after());

        if (after.containsKey(Field.GIVEN) || after.containsKey(Field.SURNAME) || after.containsKey(Field.SUFFIX)) {
            var given = after.getOrDefault(Field.GIVEN, p.name().given());
            var surname = after.getOrDefault(Field.SURNAME, p.name().surname());
            var suffix = after.getOrDefault(Field.SUFFIX, p.name().suffix());
            var value = (given + " /" + surname + "/ " + suffix).strip();
            var name = rec.first("NAME").orElseGet(() -> rec.insert(0, "NAME", ""));
            name.setValue(value);
            // keep the optional name pieces consistent with the NAME line
            syncPiece(name, "GIVN", given, after.containsKey(Field.GIVEN));
            syncPiece(name, "SURN", surname, after.containsKey(Field.SURNAME));
            syncPiece(name, "NSFX", suffix, after.containsKey(Field.SUFFIX));
        }
        if (after.containsKey(Field.SEX)) {
            var v = after.get(Field.SEX);
            rec.first("SEX").ifPresentOrElse(s -> s.setValue(v), () -> rec.insertAfter(List.of("NAME"), "SEX", v));
        }
        if (after.containsKey(Field.BIRTH_DATE) || after.containsKey(Field.BIRTH_PLACE)) {
            event(rec, "BIRT", n -> n.insertAfter(List.of("NAME", "SEX"), "BIRT", ""),
                    after.get(Field.BIRTH_DATE), after.get(Field.BIRTH_PLACE), false);
        }
        var dead = after.get(Field.DECEASED);
        if ("no".equals(dead)) {
            rec.first("DEAT").ifPresent(rec::remove);
        } else if (after.containsKey(Field.DEATH_DATE) || after.containsKey(Field.DEATH_PLACE) || "yes".equals(dead)) {
            // after the life events, before links, notes and the change date
            event(rec, "DEAT", n -> n.insertBefore(TRAILING_TAGS, "DEAT", ""),
                    after.get(Field.DEATH_DATE), after.get(Field.DEATH_PLACE), true);
        }
        touch(rec);
        return g.updated(p);
    }

    private static final List<String> TRAILING_TAGS =
            List.of("FAMC", "FAMS", "ASSO", "ALIA", "NOTE", "SOUR", "OBJE", "REFN", "RIN", "CHAN", "_UID");

    private static final DateTimeFormatter CHAN_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT);
    private static final DateTimeFormatter CHAN_TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /// Updates the record's existing `CHAN` date/time, as genealogy
    /// programs do. A record without one is left without one.
    private static void touch(Node rec) {
        rec.first("CHAN").ifPresent(chan -> {
            var now = LocalDateTime.now();
            var date = chan.first("DATE").orElseGet(() -> chan.insert(0, "DATE", ""));
            date.setValue(CHAN_DATE.format(now).toUpperCase(Locale.ROOT));
            var time = date.first("TIME");
            if (time.isPresent()) time.get().setValue(CHAN_TIME.format(now));
        });
    }

    private static void syncPiece(Node name, String tag, String value, boolean changed) {
        if (!changed) return;
        var piece = name.first(tag);
        if (piece.isPresent()) {
            if (value.isEmpty()) name.remove(piece.get());
            else piece.get().setValue(value);
        }
    }

    /// Sets DATE/PLAC under an event (null = leave as is), creating the
    /// event if needed and removing it once it carries nothing at all.
    /// For death, an event with no date or place is kept as `DEAT Y`.
    private static void event(Node rec, String tag, java.util.function.Function<Node, Node> create,
                              String date, String place, boolean keepAsFlag) {
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
