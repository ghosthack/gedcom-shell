package ged.edit;

import ged.gedcom.Node;
import ged.model.Genealogy;
import ged.model.Person;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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
        BAPTISM_DATE("baptism.date", "Date"),
        BAPTISM_PLACE("baptism.place", "Place"),
        DEATH_DATE("death.date", "Date"),
        DEATH_PLACE("death.place", "Place"),
        BURIAL_DATE("burial.date", "Date"),
        BURIAL_PLACE("burial.place", "Place"),
        DECEASED("deceased", "yes or no"),
        OCCUPATION("occupation", "Occupation"),
        RESIDENCE_DATE("residence.date", "Date"),
        RESIDENCE_PLACE("residence.place", "Place");

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
                case "baptism", "baptised", "baptized", "christened", "christening", "bapm", "chr" -> "baptism.date";
                case "baptismplace", "baptism.place" -> "baptism.place";
                case "burial", "buried", "buri" -> "burial.date";
                case "burialplace", "buried.place" -> "burial.place";
                case "occu", "job", "profession" -> "occupation";
                case "residence", "resi", "lived", "address" -> "residence.place";
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
        var bapt = n.first(baptismTag(n));
        v.put(Field.BAPTISM_DATE, bapt.map(b -> b.text("DATE")).orElse(""));
        v.put(Field.BAPTISM_PLACE, bapt.map(b -> b.text("PLAC")).orElse(""));
        v.put(Field.DEATH_DATE, n.first("DEAT").map(d -> d.text("DATE")).orElse(""));
        v.put(Field.DEATH_PLACE, n.first("DEAT").map(d -> d.text("PLAC")).orElse(""));
        v.put(Field.BURIAL_DATE, n.first("BURI").map(b -> b.text("DATE")).orElse(""));
        v.put(Field.BURIAL_PLACE, n.first("BURI").map(b -> b.text("PLAC")).orElse(""));
        v.put(Field.DECEASED, p.isDeceased() ? "yes" : "no");
        v.put(Field.OCCUPATION, n.first("OCCU").map(Node::value).orElse("").replace('\n', ' '));
        v.put(Field.RESIDENCE_DATE, n.first("RESI").map(r -> r.text("DATE")).orElse(""));
        v.put(Field.RESIDENCE_PLACE, n.first("RESI").map(r -> r.text("PLAC")).orElse(""));
        return v;
    }

    /// Baptism is `BAPM`; a file that records christenings (`CHR`) and no
    /// baptism keeps using `CHR`.
    private static String baptismTag(Node rec) {
        return rec.first("BAPM").isEmpty() && rec.first("CHR").isPresent() ? "CHR" : "BAPM";
    }

    // ---- the text form ----------------------------------------------------

    public static String render(Person p, String error) {
        var sb = new StringBuilder();
        sb.append(Records.errorBanner(error));
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
                #   deceased  yes or no; a death or burial date or place implies yes
                #   occupation, residence  the first one recorded; any others are kept

                """);
        var values = values(p);
        Field previous = null;
        for (var e : values.entrySet()) {
            var f = e.getKey();
            if (previous != null && group(previous) != group(f)) sb.append('\n');
            sb.append(String.format("%-16s %s", f.key + ":", e.getValue()).stripTrailing()).append('\n');
            previous = f;
        }
        return sb.toString();
    }

    private static int group(Field f) {
        return switch (f) {
            case GIVEN, SURNAME, SUFFIX, SEX -> 0;
            case BIRTH_DATE, BIRTH_PLACE, BAPTISM_DATE, BAPTISM_PLACE -> 1;
            case DEATH_DATE, DEATH_PLACE, BURIAL_DATE, BURIAL_PLACE, DECEASED -> 2;
            default -> 3;
        };
    }

    /// Parses the edited form. Fields left out of the text are unchanged.
    ///
    /// @throws IllegalArgumentException on unknown or repeated fields
    public static Map<Field, String> parse(String text) {
        return Records.parseForm(text, Field::parse, f -> f.key);
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

        boolean hasDeathFacts = Stream.of(Field.DEATH_DATE, Field.DEATH_PLACE, Field.BURIAL_DATE, Field.BURIAL_PLACE)
                .anyMatch(f -> !wanted.get(f).isEmpty());
        if (hasDeathFacts && requested.containsKey(Field.DECEASED) && wanted.get(Field.DECEASED).equals("no")) {
            throw new IllegalArgumentException("deceased is 'no' but a death or burial date or place is given");
        }
        if (hasDeathFacts) wanted.put(Field.DECEASED, "yes");
        var n = p.node();
        if (wanted.get(Field.DECEASED).equals("no") && current.get(Field.DECEASED).equals("yes")) {
            var buri = n.first("BURI");
            if (n.first("CREM").isPresent() || buri.isPresent() && Records.hasDetails(buri.get())) {
                throw new IllegalArgumentException("can't mark as living: there is a burial or cremation record with details; edit the file directly");
            }
            var deat = n.first("DEAT");
            if (deat.isPresent() && Records.hasDetails(deat.get())) {
                throw new IllegalArgumentException("can't mark as living: the death record has sources or notes; edit the file directly");
            }
        }

        if (wanted.get(Field.OCCUPATION).isEmpty() && !current.get(Field.OCCUPATION).isEmpty()
                && Records.hasDetails(n.first("OCCU").orElseThrow())) {
            throw new IllegalArgumentException("can't remove the occupation: it has a date, place, sources or notes; edit the file directly");
        }

        var changes = new ArrayList<Change>();
        for (var f : Field.values()) {
            if (!wanted.get(f).equals(current.get(f))) changes.add(new Change(f, current.get(f), wanted.get(f)));
        }
        return changes;
    }

    private static String normalize(Field f, String value, String current) {
        var v = Records.clean(value);
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
            case BIRTH_DATE, BAPTISM_DATE, DEATH_DATE, BURIAL_DATE, RESIDENCE_DATE -> Records.date(f.key, v, current);
            case BIRTH_PLACE, BAPTISM_PLACE, DEATH_PLACE, BURIAL_PLACE, RESIDENCE_PLACE, OCCUPATION -> v;
            case DECEASED -> Records.yesNo(f.key, v);
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
            Records.event(rec, "BIRT", n -> n.insertAfter(List.of("NAME", "SEX"), "BIRT", ""),
                    after.get(Field.BIRTH_DATE), after.get(Field.BIRTH_PLACE), false);
        }
        if (after.containsKey(Field.BAPTISM_DATE) || after.containsKey(Field.BAPTISM_PLACE)) {
            var tag = baptismTag(rec);
            Records.event(rec, tag, n -> n.insertAfter(List.of("NAME", "SEX", "BIRT"), tag, ""),
                    after.get(Field.BAPTISM_DATE), after.get(Field.BAPTISM_PLACE), false);
        }
        var dead = after.get(Field.DECEASED);
        if ("no".equals(dead)) {
            rec.first("DEAT").ifPresent(rec::remove);
            rec.first("BURI").ifPresent(rec::remove); // only a bare flag gets this far
        } else {
            if (after.containsKey(Field.DEATH_DATE) || after.containsKey(Field.DEATH_PLACE) || "yes".equals(dead)) {
                // after the life events, before links, notes and the change date
                Records.event(rec, "DEAT", n -> n.insertBefore(TRAILING_TAGS, "DEAT", ""),
                        after.get(Field.DEATH_DATE), after.get(Field.DEATH_PLACE), true);
            }
            if (after.containsKey(Field.BURIAL_DATE) || after.containsKey(Field.BURIAL_PLACE)) {
                Records.event(rec, "BURI", n -> n.first("DEAT").isPresent()
                                ? n.insertAfter(List.of("DEAT"), "BURI", "")
                                : n.insertBefore(TRAILING_TAGS, "BURI", ""),
                        after.get(Field.BURIAL_DATE), after.get(Field.BURIAL_PLACE), false);
            }
        }
        if (after.containsKey(Field.OCCUPATION)) {
            var v = after.get(Field.OCCUPATION);
            var occu = rec.first("OCCU");
            if (v.isEmpty()) occu.ifPresent(rec::remove);
            else if (occu.isPresent()) occu.get().setValue(v);
            else rec.insertBefore(TRAILING_TAGS, "OCCU", v);
        }
        if (after.containsKey(Field.RESIDENCE_DATE) || after.containsKey(Field.RESIDENCE_PLACE)) {
            Records.event(rec, "RESI", n -> n.insertBefore(TRAILING_TAGS, "RESI", ""),
                    after.get(Field.RESIDENCE_DATE), after.get(Field.RESIDENCE_PLACE), false);
        }
        Records.touch(rec);
        return g.updated(p);
    }

    static final List<String> TRAILING_TAGS =
            List.of("FAMC", "FAMS", "ASSO", "ALIA", "NOTE", "SOUR", "OBJE", "REFN", "RIN", "CHAN", "_UID");

    private static void syncPiece(Node name, String tag, String value, boolean changed) {
        if (!changed) return;
        var piece = name.first(tag);
        if (piece.isPresent()) {
            if (value.isEmpty()) name.remove(piece.get());
            else piece.get().setValue(value);
        }
    }
}
