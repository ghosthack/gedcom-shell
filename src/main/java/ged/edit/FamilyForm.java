package ged.edit;

import ged.model.Family;
import ged.model.Genealogy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// The editable view of a family: marriage and divorce, as a `field: value`
/// form like [PersonForm]. Who belongs to the family is changed with
/// `link` and `unlink`, not here; the form lists them as comments.
public final class FamilyForm {
    private FamilyForm() {}

    public enum Field {
        MARRIAGE_DATE("marriage.date"),
        MARRIAGE_PLACE("marriage.place"),
        DIVORCE_DATE("divorce.date"),
        DIVORCE_PLACE("divorce.place"),
        DIVORCED("divorced");

        public final String key;

        Field(String key) {
            this.key = key;
        }

        /// Accepts the form key plus a few natural aliases for `FIELD=VALUE`.
        public static Optional<Field> parse(String name) {
            var n = name.strip().toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
            var alias = switch (n) {
                case "married", "marriage", "marr", "wed", "wedding", "married.date" -> "marriage.date";
                case "marriageplace", "married.place", "wedding.place" -> "marriage.place";
                case "divorce", "div", "divorcedate" -> "divorce.date";
                case "divorceplace" -> "divorce.place";
                default -> n;
            };
            return Arrays.stream(values()).filter(f -> f.key.equals(alias)).findFirst();
        }
    }

    public record Change(Field field, String before, String after) {}

    /// Current values, in form order.
    public static Map<Field, String> values(Family f) {
        var n = f.node();
        var v = new LinkedHashMap<Field, String>();
        v.put(Field.MARRIAGE_DATE, n.first("MARR").map(m -> m.text("DATE")).orElse(""));
        v.put(Field.MARRIAGE_PLACE, n.first("MARR").map(m -> m.text("PLAC")).orElse(""));
        v.put(Field.DIVORCE_DATE, n.first("DIV").map(d -> d.text("DATE")).orElse(""));
        v.put(Field.DIVORCE_PLACE, n.first("DIV").map(d -> d.text("PLAC")).orElse(""));
        v.put(Field.DIVORCED, n.first("DIV").isPresent() ? "yes" : "no");
        return v;
    }

    // ---- the text form ----------------------------------------------------

    public static String render(Genealogy g, Family f, String error) {
        var sb = new StringBuilder(Records.errorBanner(error));
        sb.append("# Editing ").append(f.key()).append('\n');
        f.husband().flatMap(g::person).ifPresent(p -> sb.append("#   husband   ").append(p.key()).append("  ").append(p.label()).append('\n'));
        f.wife().flatMap(g::person).ifPresent(p -> sb.append("#   wife      ").append(p.key()).append("  ").append(p.label()).append('\n'));
        for (var id : f.children()) {
            g.person(id).ifPresent(p -> sb.append("#   child     ").append(p.key()).append("  ").append(p.label()).append('\n'));
        }
        sb.append("""
                #
                # Change values after the colon; leave a value empty to remove it.
                # Save and quit to apply. Quit without saving (or :cq in vi) to cancel.
                # To change who is in the family, use `link` and `unlink`.
                #
                #   dates     12 JUN 1850 · JUN 1850 · 1850 · ABT 1850 · BEF 1900 · AFT 1900
                #             BET 1800 AND 1810 · FROM 1914 TO 1918 · (free text)
                #             1850-06-12 and "12 june 1850" are converted for you
                #   divorced  yes or no; a divorce date or place implies yes

                """);
        Field previous = null;
        for (var e : values(f).entrySet()) {
            var field = e.getKey();
            if (previous == Field.MARRIAGE_PLACE) sb.append('\n');
            sb.append(String.format("%-16s %s", field.key + ":", e.getValue()).stripTrailing()).append('\n');
            previous = field;
        }
        return sb.toString();
    }

    public static Map<Field, String> parse(String text) {
        return Records.parseForm(text, Field::parse, f -> f.key);
    }

    // ---- validation, diff, apply ------------------------------------------

    /// Validates and normalises `requested`; returns only real changes.
    ///
    /// @throws IllegalArgumentException with a user-facing message
    public static List<Change> diff(Family f, Map<Field, String> requested) {
        var current = values(f);
        var wanted = new LinkedHashMap<>(current);
        for (var e : requested.entrySet()) {
            var field = e.getKey();
            var v = Records.clean(e.getValue());
            wanted.put(field, switch (field) {
                case MARRIAGE_DATE, DIVORCE_DATE -> Records.date(field.key, v, current.get(field));
                case MARRIAGE_PLACE, DIVORCE_PLACE -> v;
                case DIVORCED -> Records.yesNo(field.key, v);
            });
        }

        boolean divorceFacts = !wanted.get(Field.DIVORCE_DATE).isEmpty() || !wanted.get(Field.DIVORCE_PLACE).isEmpty();
        if (divorceFacts && requested.containsKey(Field.DIVORCED) && wanted.get(Field.DIVORCED).equals("no")) {
            throw new IllegalArgumentException("divorced is 'no' but a divorce date or place is given (clear them too: divorce.date= divorce.place=)");
        }
        if (divorceFacts) wanted.put(Field.DIVORCED, "yes");
        if (wanted.get(Field.DIVORCED).equals("no") && current.get(Field.DIVORCED).equals("yes")) {
            var div = f.node().first("DIV");
            if (div.isPresent() && Records.hasDetails(div.get())) {
                throw new IllegalArgumentException("can't remove the divorce: its record has sources or notes; edit the file directly");
            }
        }
        var changes = new ArrayList<Change>();
        for (var field : Field.values()) {
            if (!wanted.get(field).equals(current.get(field))) changes.add(new Change(field, current.get(field), wanted.get(field)));
        }
        return changes;
    }

    /// Family events go after the partners and before children, notes and
    /// sources, which is where genealogy programs put them.
    private static final List<String> LEADING = List.of("HUSB", "WIFE", "MARR", "MARB", "MARC", "MARL", "MARS", "ENGA");

    /// Writes `changes` into the family's record and returns the
    /// re-indexed family.
    public static Family apply(Genealogy g, Family f, List<Change> changes) {
        var rec = f.node();
        var after = new LinkedHashMap<Field, String>();
        for (var c : changes) after.put(c.field(), c.after());

        if (after.containsKey(Field.MARRIAGE_DATE) || after.containsKey(Field.MARRIAGE_PLACE)) {
            Records.event(rec, "MARR", n -> n.insertAfter(List.of("HUSB", "WIFE"), "MARR", ""),
                    after.get(Field.MARRIAGE_DATE), after.get(Field.MARRIAGE_PLACE), false);
        }
        var divorced = after.get(Field.DIVORCED);
        if ("no".equals(divorced)) {
            rec.first("DIV").ifPresent(rec::remove);
        } else if (after.containsKey(Field.DIVORCE_DATE) || after.containsKey(Field.DIVORCE_PLACE) || "yes".equals(divorced)) {
            Records.event(rec, "DIV", n -> n.insertAfter(LEADING, "DIV", ""),
                    after.get(Field.DIVORCE_DATE), after.get(Field.DIVORCE_PLACE), true);
        }
        Records.touch(rec);
        return g.updated(f);
    }
}
