package ged.edit;

import ged.gedcom.Node;
import ged.model.Genealogy;
import ged.model.Person;
import ged.text.Style;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/// The editable view of a person: a flat `field: value` form that can be
/// rendered for a text editor, parsed back, diffed, and applied to the
/// person's GEDCOM nodes. Only fields whose value actually changed are
/// written, so untouched lines keep their original formatting.
///
/// The rarer events and attributes (census, emigration, religion…) are
/// "extra" fields: the form shows them only when the record has them, and
/// lists the others in a comment so they can be added as new lines.
public final class PersonForm {
    private PersonForm() {}

    public enum Field {
        GIVEN("given"),
        SURNAME("surname"),
        SUFFIX("suffix"),
        SEX("sex"),
        BIRTH_DATE("birth.date", "BIRT", false),
        BIRTH_PLACE("birth.place", "BIRT", false),
        /// `BAPM`, or the record's `CHR` when it has no `BAPM`.
        BAPTISM_DATE("baptism.date", "BAPM", false),
        BAPTISM_PLACE("baptism.place", "BAPM", false),
        /// A `CHR` kept apart from a `BAPM`.
        CHRISTENING_DATE("christening.date", "CHR", true),
        CHRISTENING_PLACE("christening.place", "CHR", true),
        ADOPTION_DATE("adoption.date", "ADOP", true, "adopted"),
        ADOPTION_PLACE("adoption.place", "ADOP", true),
        CONFIRMATION_DATE("confirmation.date", "CONF", true, "confirmed"),
        CONFIRMATION_PLACE("confirmation.place", "CONF", true),
        COMMUNION_DATE("communion.date", "FCOM", true, "first.communion", "firstcommunion"),
        COMMUNION_PLACE("communion.place", "FCOM", true),
        BARMITZVAH_DATE("barmitzvah.date", "BARM", true, "bar.mitzvah"),
        BARMITZVAH_PLACE("barmitzvah.place", "BARM", true),
        BASMITZVAH_DATE("basmitzvah.date", "BASM", true, "bas.mitzvah", "batmitzvah", "bat.mitzvah"),
        BASMITZVAH_PLACE("basmitzvah.place", "BASM", true),
        GRADUATION_DATE("graduation.date", "GRAD", true, "graduated"),
        GRADUATION_PLACE("graduation.place", "GRAD", true),
        ORDINATION_DATE("ordination.date", "ORDN", true, "ordained"),
        ORDINATION_PLACE("ordination.place", "ORDN", true),
        EMIGRATION_DATE("emigration.date", "EMIG", true, "emigrated"),
        EMIGRATION_PLACE("emigration.place", "EMIG", true),
        IMMIGRATION_DATE("immigration.date", "IMMI", true, "immigrated"),
        IMMIGRATION_PLACE("immigration.place", "IMMI", true),
        NATURALISATION_DATE("naturalisation.date", "NATU", true, "naturalised", "naturalized", "naturalization"),
        NATURALISATION_PLACE("naturalisation.place", "NATU", true),
        CENSUS_DATE("census.date", "CENS", true),
        CENSUS_PLACE("census.place", "CENS", true),
        RETIREMENT_DATE("retirement.date", "RETI", true, "retired"),
        RETIREMENT_PLACE("retirement.place", "RETI", true),
        WILL_DATE("will.date", "WILL", true, "testament"),
        WILL_PLACE("will.place", "WILL", true),
        PROBATE_DATE("probate.date", "PROB", true, "probated"),
        PROBATE_PLACE("probate.place", "PROB", true),
        DEATH_DATE("death.date", "DEAT", false),
        DEATH_PLACE("death.place", "DEAT", false),
        BURIAL_DATE("burial.date", "BURI", false),
        BURIAL_PLACE("burial.place", "BURI", false),
        CREMATION_DATE("cremation.date", "CREM", true, "cremated"),
        CREMATION_PLACE("cremation.place", "CREM", true),
        DECEASED("deceased"),
        OCCUPATION("occupation", "OCCU", false),
        EDUCATION("education", "EDUC", true, "schooling"),
        RELIGION("religion", "RELI", true, "faith"),
        TITLE("title", "TITL", true),
        NATIONALITY("nationality", "NATI", true, "citizenship"),
        RESIDENCE_DATE("residence.date", "RESI", false),
        RESIDENCE_PLACE("residence.place", "RESI", false);

        /// What a tagged field holds: an event's date or place, or an
        /// attribute's value (`1 OCCU Sailor`). Name, sex and deceased are
        /// `OTHER`.
        enum Part { DATE, PLACE, VALUE, OTHER }

        public final String key;
        /// The GEDCOM tag the field reads and writes, or null.
        final String tag;
        final boolean extra;
        final Part part;
        private final List<String> aliases;

        Field(String key) {
            this(key, null, false);
        }

        Field(String key, String tag, boolean extra, String... aliases) {
            this.key = key;
            this.tag = tag;
            this.extra = extra;
            this.part = tag == null ? Part.OTHER
                    : key.endsWith(".date") ? Part.DATE : key.endsWith(".place") ? Part.PLACE : Part.VALUE;
            this.aliases = List.of(aliases);
        }

        /// True for the rarer events and attributes, which the form shows
        /// only when the record has them.
        public boolean isExtra() { return extra; }

        /// True for an attribute with a value (`occupation`, `religion`),
        /// false for an event's date or place.
        public boolean isAttribute() { return part == Part.VALUE; }

        /// The event or attribute the field belongs to: `census` for
        /// `census.date`, `religion` for `religion`.
        public String noun() {
            int dot = key.indexOf('.');
            return dot < 0 ? key : key.substring(0, dot);
        }

        /// Accepts the form key plus a few natural aliases for `FIELD=VALUE`.
        /// An extra event also answers to its noun, its GEDCOM tag and its
        /// verb (`census`, `emig`, `emigrated` for the date; `censusplace`,
        /// `emigrated.place` for the place).
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
                case "baptismplace", "christeningplace", "christened.place" -> "baptism.place";
                case "burial", "buried", "buri" -> "burial.date";
                case "burialplace", "buried.place" -> "burial.place";
                case "occu", "job", "profession" -> "occupation";
                case "residence", "resi", "lived", "address" -> "residence.place";
                case "dead" -> "deceased";
                default -> n;
            };
            var exact = Arrays.stream(values()).filter(f -> f.key.equals(alias)).findFirst();
            if (exact.isPresent()) return exact;
            var base = alias.replaceFirst("\\.date$", "");
            var lead = Arrays.stream(values()).filter(f -> f.extra && f.part != Part.PLACE && f.answersTo(base)).findFirst();
            if (lead.isPresent() || !alias.endsWith("place")) return lead;
            var event = alias.replaceFirst("\\.?place$", "");
            return Arrays.stream(values()).filter(f -> f.extra && f.part == Part.DATE && f.answersTo(event)).findFirst()
                    .map(f -> of(f.tag, Part.PLACE));
        }

        private boolean answersTo(String name) {
            return noun().equals(name) || tag.toLowerCase(Locale.ROOT).equals(name) || aliases.contains(name);
        }

        /// The field for `tag`'s `part`, such as `CENSUS_PLACE` for `CENS`.
        static Field of(String tag, Part part) {
            return Arrays.stream(values()).filter(f -> tag.equals(f.tag) && f.part == part).findFirst().orElseThrow();
        }
    }

    public record Change(Field field, String before, String after) {}

    /// Current values of every field, in form order.
    public static Map<Field, String> values(Person p) {
        var v = new LinkedHashMap<Field, String>();
        for (var f : Field.values()) v.put(f, value(p, f));
        return v;
    }

    private static String value(Person p, Field f) {
        return switch (f) {
            case GIVEN -> p.name().given();
            case SURNAME -> p.name().surname();
            case SUFFIX -> p.name().suffix();
            case SEX -> p.sex() == Person.Sex.UNKNOWN ? "U" : p.sex().code;
            case DECEASED -> p.isDeceased() ? "yes" : "no";
            default -> recorded(p.node(), f).map(ev -> switch (f.part) {
                case DATE -> ev.text("DATE");
                case PLACE -> ev.text("PLAC");
                default -> ev.value().replace('\n', ' ');
            }).orElse("");
        };
    }

    /// The node a tagged field edits: the first one with its tag.
    private static Optional<Node> recorded(Node rec, Field f) {
        return switch (f.tag) {
            case "BAPM" -> rec.first(baptismTag(rec));
            case "CHR" -> christeningIsBaptism(rec) ? Optional.empty() : rec.first("CHR");
            default -> rec.first(f.tag);
        };
    }

    /// Baptism is `BAPM`; a file that records christenings (`CHR`) and no
    /// baptism keeps using `CHR`.
    private static String baptismTag(Node rec) {
        return christeningIsBaptism(rec) ? "CHR" : "BAPM";
    }

    /// A `CHR` with no `BAPM` is edited as the baptism, so the christening
    /// fields only reach a `CHR` kept apart from a `BAPM`.
    private static boolean christeningIsBaptism(Node rec) {
        return rec.first("BAPM").isEmpty() && rec.first("CHR").isPresent();
    }

    // ---- the text form ----------------------------------------------------

    public static String render(Person p, String error) {
        var rec = p.node();
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
                #   deceased  yes or no; a death, burial or cremation implies yes
                #   occupation, residence and the events and facts below edit the first
                #             one recorded; any others are kept
                """);
        sb.append(addable(rec)).append('\n');
        var shown = values(p).entrySet().stream().filter(e -> shown(rec, e.getKey())).toList();
        int width = Math.max(16, shown.stream().mapToInt(e -> e.getKey().key.length() + 1).max().orElse(0));
        Field previous = null;
        for (var e : shown) {
            var f = e.getKey();
            if (previous != null && group(previous) != group(f)) sb.append('\n');
            sb.append(String.format("%-" + width + "s %s", f.key + ":", e.getValue()).stripTrailing()).append('\n');
            previous = f;
        }
        return sb.toString();
    }

    /// The rarer fields appear only when the record has them.
    private static boolean shown(Node rec, Field f) {
        return !f.extra || recorded(rec, f).isPresent();
    }

    /// Comment lines naming the rarer events and attributes the record
    /// doesn't have yet, which can be added as new lines.
    private static String addable(Node rec) {
        var events = new ArrayList<String>();
        var attributes = new ArrayList<String>();
        for (var f : Field.values()) {
            if (!f.extra || f.part == Field.Part.PLACE || shown(rec, f)) continue;
            if (f == Field.CHRISTENING_DATE && christeningIsBaptism(rec)) continue; // it's the baptism here
            (f.isAttribute() ? attributes : events).add(f.noun());
        }
        var sb = new StringBuilder();
        if (!events.isEmpty()) {
            sb.append("#   to add an event, add NAME.date: and NAME.place: lines for any of\n")
                    .append(Style.wrap(events, "#             ", 79)).append('\n');
        }
        if (!attributes.isEmpty()) {
            sb.append("#   to add a fact, add a NAME: line for any of\n")
                    .append(Style.wrap(attributes, "#             ", 79)).append('\n');
        }
        return sb.toString();
    }

    private static int group(Field f) {
        return switch (f) {
            case GIVEN, SURNAME, SUFFIX, SEX -> 0;
            case BIRTH_DATE, BIRTH_PLACE, BAPTISM_DATE, BAPTISM_PLACE, CHRISTENING_DATE, CHRISTENING_PLACE,
                 ADOPTION_DATE, ADOPTION_PLACE -> 1;
            case DEATH_DATE, DEATH_PLACE, BURIAL_DATE, BURIAL_PLACE, CREMATION_DATE, CREMATION_PLACE, DECEASED -> 3;
            case OCCUPATION, EDUCATION, RELIGION, TITLE, NATIONALITY, RESIDENCE_DATE, RESIDENCE_PLACE -> 4;
            default -> 2; // the events of a life
        };
    }

    /// Parses the edited form. Fields left out of the text are unchanged.
    ///
    /// @throws IllegalArgumentException on unknown or repeated fields
    public static Map<Field, String> parse(String text) {
        return Records.parseForm(text, Field::parse, f -> f.key);
    }

    // ---- validation, diff, apply ------------------------------------------

    private static final List<Field> DEATH_FACTS = List.of(Field.DEATH_DATE, Field.DEATH_PLACE,
            Field.BURIAL_DATE, Field.BURIAL_PLACE, Field.CREMATION_DATE, Field.CREMATION_PLACE);

    /// Validates and normalises `requested` against the person's current
    /// values; returns only real changes, in form order.
    ///
    /// @throws IllegalArgumentException with a user-facing message
    public static List<Change> diff(Person p, Map<Field, String> requested) {
        var n = p.node();
        requested = christeningAsBaptism(n, requested);
        var current = values(p);
        var wanted = new LinkedHashMap<>(current);
        for (var e : requested.entrySet()) wanted.put(e.getKey(), normalize(e.getKey(), e.getValue(), current.get(e.getKey())));

        boolean hasDeathFacts = DEATH_FACTS.stream().anyMatch(f -> !wanted.get(f).isEmpty());
        if (hasDeathFacts && requested.containsKey(Field.DECEASED) && wanted.get(Field.DECEASED).equals("no")) {
            throw new IllegalArgumentException("deceased is 'no' but a death, burial or cremation date or place is given");
        }
        if (hasDeathFacts) wanted.put(Field.DECEASED, "yes");
        if (wanted.get(Field.DECEASED).equals("no") && current.get(Field.DECEASED).equals("yes")) {
            if (Stream.of("BURI", "CREM").map(n::first).anyMatch(ev -> ev.isPresent() && Records.hasDetails(ev.get()))) {
                throw new IllegalArgumentException("can't mark as living: there is a burial or cremation record with details; edit the file directly");
            }
            var deat = n.first("DEAT");
            if (deat.isPresent() && Records.hasDetails(deat.get())) {
                throw new IllegalArgumentException("can't mark as living: the death record has sources or notes; edit the file directly");
            }
        }

        for (var f : Field.values()) {
            if (f.isAttribute() && wanted.get(f).isEmpty() && !current.get(f).isEmpty()
                    && !n.first(f.tag).orElseThrow().children().isEmpty()) {
                throw new IllegalArgumentException("can't remove the " + f.key
                        + ": it has a date, place, sources or notes; edit the file directly");
            }
        }

        var changes = new ArrayList<Change>();
        for (var f : Field.values()) {
            if (!wanted.get(f).equals(current.get(f))) changes.add(new Change(f, current.get(f), wanted.get(f)));
        }
        return changes;
    }

    /// Where the record's only `CHR` is shown as its baptism, christening
    /// fields edit that baptism.
    private static Map<Field, String> christeningAsBaptism(Node rec, Map<Field, String> requested) {
        if (!christeningIsBaptism(rec)) return requested;
        var out = new LinkedHashMap<>(requested);
        moveTo(out, Field.CHRISTENING_DATE, Field.BAPTISM_DATE);
        moveTo(out, Field.CHRISTENING_PLACE, Field.BAPTISM_PLACE);
        return out;
    }

    private static void moveTo(Map<Field, String> fields, Field from, Field to) {
        if (!fields.containsKey(from)) return;
        var v = fields.remove(from);
        var other = fields.putIfAbsent(to, v);
        if (other != null && !Records.clean(other).equals(Records.clean(v))) {
            throw new IllegalArgumentException(from.key + ": this record's christening (CHR) is its baptism; change "
                    + to.key + " instead");
        }
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
            case DECEASED -> Records.yesNo(f.key, v);
            default -> f.part == Field.Part.DATE ? Records.date(f.key, v, current) : v;
        };
    }

    /// Tags written by their own code in [#apply]; the other tagged
    /// fields are written generically.
    private static final Set<String> SPECIAL_TAGS = Set.of("BIRT", "BAPM", "DEAT", "BURI", "CREM");

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
            // only bare flags or bare dates and places get this far
            Stream.of("DEAT", "BURI", "CREM").forEach(tag -> rec.first(tag).ifPresent(rec::remove));
        } else {
            if (after.containsKey(Field.DEATH_DATE) || after.containsKey(Field.DEATH_PLACE) || "yes".equals(dead)) {
                // after the life events, before links, notes and the change date
                Records.event(rec, "DEAT", n -> n.insertBefore(TRAILING_TAGS, "DEAT", ""),
                        after.get(Field.DEATH_DATE), after.get(Field.DEATH_PLACE), true);
            }
            if (after.containsKey(Field.BURIAL_DATE) || after.containsKey(Field.BURIAL_PLACE)) {
                Records.event(rec, "BURI", n -> create(n, "BURI", ""),
                        after.get(Field.BURIAL_DATE), after.get(Field.BURIAL_PLACE), false);
            }
            if (after.containsKey(Field.CREMATION_DATE) || after.containsKey(Field.CREMATION_PLACE)) {
                Records.event(rec, "CREM", n -> create(n, "CREM", ""),
                        after.get(Field.CREMATION_DATE), after.get(Field.CREMATION_PLACE), false);
            }
        }
        var done = new HashSet<>(SPECIAL_TAGS);
        for (var f : Field.values()) {
            if (f.tag == null || !after.containsKey(f) || !done.add(f.tag)) continue;
            if (f.isAttribute()) {
                attribute(rec, f.tag, after.get(f));
            } else {
                Records.event(rec, f.tag, n -> create(n, f.tag, ""), after.get(Field.of(f.tag, Field.Part.DATE)),
                        after.get(Field.of(f.tag, Field.Part.PLACE)), false);
            }
        }
        Records.touch(rec);
        return g.updated(p);
    }

    /// Sets the value of the first `tag` attribute, creating it if needed
    /// and removing it when emptied (diff refuses that if it has details).
    private static void attribute(Node rec, String tag, String value) {
        var node = rec.first(tag);
        if (value.isEmpty()) node.ifPresent(rec::remove);
        else if (node.isPresent()) node.get().setValue(value);
        else create(rec, tag, value);
    }

    static final List<String> TRAILING_TAGS = List.of("FAMC", "FAMS", "ASSO", "ALIA", "NOTE", "SNOTE", "SOUR",
            "OBJE", "REFN", "RIN", "EXID", "CHAN", "_UID");

    /// Where the events of a life go: before death and the trailing tags.
    private static final List<String> BEFORE_DEATH =
            Stream.concat(Stream.of("DEAT", "BURI", "CREM", "PROB"), TRAILING_TAGS.stream()).toList();

    /// Inserts a new `tag` line where genealogy programs put it: early-life
    /// events after the baptism, burial, cremation and probate after the
    /// death, attributes before the trailing tags, other events before the
    /// death.
    private static Node create(Node rec, String tag, String value) {
        return switch (tag) {
            case "CHR", "ADOP" -> rec.insertAfter(List.of("NAME", "SEX", "BIRT", "BAPM", "CHR"), tag, value);
            case "BURI" -> afterOrTrailing(rec, List.of("DEAT"), tag, value);
            case "CREM" -> afterOrTrailing(rec, List.of("DEAT", "BURI"), tag, value);
            case "PROB" -> afterOrTrailing(rec, List.of("DEAT", "BURI", "CREM"), tag, value);
            case "OCCU", "EDUC", "RELI", "TITL", "NATI", "RESI" -> rec.insertBefore(TRAILING_TAGS, tag, value);
            default -> rec.insertBefore(BEFORE_DEATH, tag, value);
        };
    }

    private static Node afterOrTrailing(Node rec, List<String> after, String tag, String value) {
        return after.stream().anyMatch(t -> rec.first(t).isPresent())
                ? rec.insertAfter(after, tag, value)
                : rec.insertBefore(TRAILING_TAGS, tag, value);
    }

    private static void syncPiece(Node name, String tag, String value, boolean changed) {
        if (!changed) return;
        var piece = name.first(tag);
        if (piece.isPresent()) {
            if (value.isEmpty()) name.remove(piece.get());
            else piece.get().setValue(value);
        }
    }
}
