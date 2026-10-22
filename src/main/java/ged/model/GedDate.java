package ged.model;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A GEDCOM date phrase, kept verbatim for display and reduced to a
/// sortable (year, month, day) for comparisons. Handles qualifiers such as
/// `ABT 1850`, `BEF 3 MAR 1900`, `BET 1800 AND 1810`, `FROM 1914 TO 1918`
/// and calendar escapes like `@#DJULIAN@`. Unknown parts are 0.
public record GedDate(String raw, Qualifier qualifier, int year, int month, int day)
        implements Comparable<GedDate> {

    public enum Qualifier { EXACT, ABOUT, BEFORE, AFTER, BETWEEN, PERIOD, PHRASE, NONE }

    public static final GedDate EMPTY = new GedDate("", Qualifier.NONE, 0, 0, 0);

    private static final List<String> MONTHS =
            List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final Pattern DATE = Pattern.compile("(?:(\\d{1,2})\\s+)?(?:([A-Z]{3})\\s+)?(\\d{1,4})(?:/\\d{1,2})?(?:\\s*B\\.?C\\.?)?");

    public static final Comparator<GedDate> CHRONOLOGICAL =
            Comparator.comparingInt(GedDate::year).thenComparingInt(GedDate::month).thenComparingInt(GedDate::day);

    public static GedDate parse(String raw) {
        if (raw == null || raw.isBlank()) return EMPTY;
        var s = raw.strip().toUpperCase(Locale.ROOT).replaceAll("@#D[^@]*@\\s*", "");
        var first = s.split("\\s+", 2)[0];
        var q = switch (first) {
            case "ABT", "ABOUT", "CAL", "EST", "CIRCA", "C." -> Qualifier.ABOUT;
            case "BEF", "BEFORE" -> Qualifier.BEFORE;
            case "AFT", "AFTER" -> Qualifier.AFTER;
            case "BET" -> Qualifier.BETWEEN;
            case "FROM", "TO" -> Qualifier.PERIOD;
            case "INT" -> Qualifier.PHRASE;
            default -> Qualifier.EXACT;
        };
        if (q != Qualifier.EXACT) s = s.substring(first.length()).strip();
        var m = DATE.matcher(s);
        if (!m.find()) return new GedDate(raw.strip(), Qualifier.PHRASE, 0, 0, 0);
        int year = Integer.parseInt(m.group(3));
        int month = m.group(2) == null ? 0 : MONTHS.indexOf(m.group(2)) + 1;
        int day = m.group(1) == null || month == 0 ? 0 : Integer.parseInt(m.group(1));
        return new GedDate(raw.strip(), q, year, month, day);
    }

    private static final List<String> MONTH_NAMES = List.of("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
            "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER");
    private static final int[] MONTH_DAYS = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    private static final Pattern SIMPLE = Pattern.compile("(?:(\\d{1,2})\\s+)?(?:([A-Z]+)\\.?\\s+)?(\\d{3,4})(/\\d{2})?(\\s*B\\.?C\\.?)?");
    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})(?:-(\\d{1,2}))?");
    public static final String EXAMPLES = "12 JUN 1850, JUN 1850, 1850, ABT 1850, BEF 1900, AFT 1900, "
            + "BET 1800 AND 1810, FROM 1914 TO 1918, or (free text)";

    /// Validates user input and returns it in canonical GEDCOM form:
    /// `12 june 1850` → `12 JUN 1850`, `1850-06-12` → `12 JUN 1850`,
    /// `circa 1850` → `ABT 1850`. Text in parentheses is kept as a date
    /// phrase. Empty input returns `""`.
    ///
    /// @throws IllegalArgumentException if the text is not a GEDCOM date
    public static String normalize(String input) {
        var s = input.strip().replaceAll("\\s+", " ");
        if (s.isEmpty()) return "";
        if (s.startsWith("(") && s.endsWith(")")) return s;
        var calendar = "";
        var cal = Pattern.compile("^(@#D[A-Za-z ]+@) ?(.*)$").matcher(s);
        if (cal.matches()) {
            calendar = cal.group(1).toUpperCase(Locale.ROOT) + " ";
            s = cal.group(2);
        }
        var u = s.toUpperCase(Locale.ROOT);
        Matcher m;
        if ((m = Pattern.compile("^(?:BET|BETWEEN) (.+?) AND (.+)$").matcher(u)).matches()) {
            return calendar + "BET " + simple(m.group(1), input) + " AND " + simple(m.group(2), input);
        }
        if ((m = Pattern.compile("^FROM (.+?)(?: TO (.+))?$").matcher(u)).matches()) {
            return calendar + "FROM " + simple(m.group(1), input) + (m.group(2) == null ? "" : " TO " + simple(m.group(2), input));
        }
        if ((m = Pattern.compile("^TO (.+)$").matcher(u)).matches()) {
            return calendar + "TO " + simple(m.group(1), input);
        }
        if ((m = Pattern.compile("^INT (.+?) ?(\\(.*\\))$").matcher(u)).matches()) {
            return calendar + "INT " + simple(m.group(1), input) + " " + s.substring(s.indexOf('('));
        }
        if ((m = Pattern.compile("^(ABT|ABOUT|CIRCA|CA\\.?|C\\.|CAL|EST|BEF|BEFORE|AFT|AFTER) (.+)$").matcher(u)).matches()) {
            var q = switch (m.group(1)) {
                case "ABOUT", "CIRCA", "CA", "CA.", "C." -> "ABT";
                case "BEFORE" -> "BEF";
                case "AFTER" -> "AFT";
                default -> m.group(1);
            };
            return calendar + q + " " + simple(m.group(2), input);
        }
        return calendar + simple(u, input);
    }

    private static String simple(String u, String input) {
        var iso = ISO.matcher(u);
        if (iso.matches()) {
            var day = iso.group(3) == null ? "" : iso.group(3) + " ";
            return simple(day + monthAbbrev(Integer.parseInt(iso.group(2)), input) + " " + iso.group(1), input);
        }
        var m = SIMPLE.matcher(u.replace(",", ""));
        if (!m.matches()) throw new IllegalArgumentException("'" + input.strip() + "' is not a GEDCOM date; use " + EXAMPLES);
        var sb = new StringBuilder();
        if (m.group(2) != null) {
            int month = month(m.group(2));
            if (month == 0) throw new IllegalArgumentException("'" + m.group(2) + "' is not a month (JAN, FEB, ...)");
            if (m.group(1) != null) {
                int day = Integer.parseInt(m.group(1));
                if (day < 1 || day > MONTH_DAYS[month - 1]) {
                    throw new IllegalArgumentException("day " + day + " is out of range for " + MONTHS.get(month - 1));
                }
                sb.append(day).append(' ');
            }
            sb.append(MONTHS.get(month - 1)).append(' ');
        } else if (m.group(1) != null) {
            throw new IllegalArgumentException("'" + input.strip() + "' has a day but no month");
        }
        sb.append(m.group(3));
        if (m.group(4) != null) sb.append(m.group(4));
        if (m.group(5) != null) sb.append(" B.C.");
        return sb.toString();
    }

    private static String monthAbbrev(int month, String input) {
        if (month < 1 || month > 12) throw new IllegalArgumentException("'" + input.strip() + "': month " + month + " is out of range");
        return MONTHS.get(month - 1);
    }

    /// 1–12 for `JUN`, `JUNE`, `SEPT`...; 0 if not a month.
    private static int month(String word) {
        if (word.length() < 3) return 0;
        for (int i = 0; i < 12; i++) if (MONTH_NAMES.get(i).startsWith(word)) return i + 1;
        return 0;
    }

    public boolean isEmpty() { return raw.isEmpty(); }
    public boolean hasYear() { return year > 0; }

    /// Compact form for tables: `1850`, `~1850`, `<1900`, `>1900`.
    public String shortForm() {
        if (!hasYear()) return raw.isEmpty() ? "" : "?";
        var prefix = switch (qualifier) {
            case ABOUT, BETWEEN, PHRASE -> "~";
            case BEFORE -> "<";
            case AFTER -> ">";
            default -> "";
        };
        return prefix + year;
    }

    @Override
    public int compareTo(GedDate o) { return CHRONOLOGICAL.compare(this, o); }

    @Override
    public String toString() { return raw; }
}
