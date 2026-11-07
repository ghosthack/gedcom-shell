package ged.model;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A GEDCOM date phrase, kept verbatim for display and reduced to a
/// sortable (year, month, day) for comparisons. Handles qualifiers such as
/// `ABT 1850`, `BEF 3 MAR 1900`, `BET 1800 AND 1810`, `FROM 1914 TO 1918`.
/// Unknown parts are 0.
///
/// Dates may be written in the Julian, Hebrew or French Republican calendar,
/// marked GEDCOM 5.5.1 style (`@#DJULIAN@ 4 OCT 1582`) or GEDCOM 7 style
/// (`JULIAN 4 OCT 1582`), at the start of the phrase or of each date in it
/// (`BET @#DJULIAN@ 1700 AND @#DJULIAN@ 1710`). `calendar()` says which;
/// `year`, `month` and `day` are always the proleptic Gregorian equivalent,
/// so dates in different calendars sort and subtract correctly, while `raw()`
/// keeps the text as written. A partial date in another calendar becomes the
/// Gregorian year (and month) holding the middle of its span: Hebrew year
/// 5785 (3 OCT 2024 to 22 SEP 2025) is 2025, French year I is 1793, and
/// `TSH 5785` is OCT 2024. A dual year (`11 FEB 1731/32`) is Old Style, so
/// Julian unless marked otherwise, and a date before 25 MAR falls in the
/// later year (22 FEB 1732). Other escapes (`@#DROMAN@`, `@#DUNKNOWN@`) are
/// read as Gregorian numbers.
public record GedDate(String raw, Qualifier qualifier, Calendar calendar, int year, int month, int day)
        implements Comparable<GedDate> {

    public enum Qualifier { EXACT, ABOUT, BEFORE, AFTER, BETWEEN, PERIOD, PHRASE, NONE }

    /// The calendars GEDCOM names, with their month abbreviations in order.
    public enum Calendar {
        GREGORIAN("GREGORIAN", List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")),
        JULIAN("JULIAN", GREGORIAN.months),
        HEBREW("HEBREW", List.of("TSH", "CSH", "KSL", "TVT", "SHV", "ADR", "ADS", "NSN", "IYR", "SVN", "TMZ", "AAV", "ELL")),
        FRENCH_R("FRENCH R", List.of("VEND", "BRUM", "FRIM", "NIVO", "PLUV", "VENT", "GERM", "FLOR", "PRAI", "MESS", "THER", "FRUC", "COMP"));

        private final String escapeName;
        private final List<String> months;

        Calendar(String escapeName, List<String> months) {
            this.escapeName = escapeName;
            this.months = months;
        }

        /// GEDCOM 5.5.1 escape: `@#DFRENCH R@`.
        public String escape() { return "@#D" + escapeName + "@"; }

        /// GEDCOM 7 keyword: `FRENCH_R`.
        public String keyword() { return name(); }

        /// Month abbreviations, `JAN`… or `TSH`…`ELL` or `VEND`…`COMP`.
        public List<String> months() { return months; }

        /// The calendar an escape's name (`JULIAN`, `FRENCH R`) or a
        /// keyword (`FRENCH_R`) stands for; Gregorian for any other.
        static Calendar named(String name) {
            for (var c : values()) if (c.escapeName.equals(name) || c.name().equals(name)) return c;
            return GREGORIAN;
        }

        /// Epoch day of a (astronomical) year, month and day in this calendar.
        long epochDay(long year, int month, int day) {
            return switch (this) {
                case GREGORIAN -> CalendarMath.gregorianToEpochDay(year, month, day);
                case JULIAN -> CalendarMath.julianToEpochDay(year, month, day);
                case HEBREW -> CalendarMath.hebrewToEpochDay(year, month, day);
                case FRENCH_R -> CalendarMath.frenchToEpochDay(year, month, day);
            };
        }

        /// Days in a month of an (astronomical) year; 0 if the month doesn't
        /// exist that year (Hebrew ADS in a common year).
        public int monthLength(long year, int month) {
            return switch (this) {
                case GREGORIAN -> CalendarMath.monthLength(month, CalendarMath.gregorianLeap(year));
                case JULIAN -> CalendarMath.monthLength(month, CalendarMath.julianLeap(year));
                case HEBREW -> CalendarMath.hebrewMonthLength(year, month);
                case FRENCH_R -> CalendarMath.frenchMonthLength(year, month);
            };
        }
    }

    public static final GedDate EMPTY = new GedDate("", Qualifier.NONE, 0, 0, 0);

    private static final List<String> MONTHS = Calendar.GREGORIAN.months();
    private static final Pattern DATE = Pattern.compile("(?:(\\d{1,2})\\s+)?(?:([A-Z]{3,4})\\s+)?(\\d{1,4})(/\\d{1,2})?(\\s*B\\.?C\\.?(?:E\\.?)?)?");
    /// A calendar escape or GEDCOM 7 keyword at the start of the text; group 1
    /// or 2 is its name.
    private static final Pattern CALENDAR = Pattern.compile("^(?:@#D([^@]*)@|(GREGORIAN|JULIAN|HEBREW|FRENCH_R)(?![\\w@]))\\s*");
    private static final Pattern ANY_CALENDAR = Pattern.compile("@#D[^@]*@\\s*|\\b(?:GREGORIAN|JULIAN|HEBREW|FRENCH_R)\\b\\s*");

    public static final Comparator<GedDate> CHRONOLOGICAL =
            Comparator.comparingInt(GedDate::year).thenComparingInt(GedDate::month).thenComparingInt(GedDate::day);

    /// A Gregorian date given as Gregorian numbers.
    public GedDate(String raw, Qualifier qualifier, int year, int month, int day) {
        this(raw, qualifier, Calendar.GREGORIAN, year, month, day);
    }

    public static GedDate parse(String raw) {
        if (raw == null || raw.isBlank()) return EMPTY;
        return parse(raw.strip(), null);
    }

    /// Parses `raw` in `calendar` (null: none named yet) unless the text names its own.
    private static GedDate parse(String raw, Calendar calendar) {
        var s = raw.toUpperCase(Locale.ROOT);
        var c = CALENDAR.matcher(s);
        if (c.find()) {
            calendar = calendarOf(c);
            s = s.substring(c.end());
        }
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
        if (q != Qualifier.EXACT) {
            s = s.substring(first.length()).strip();
            if ((c = CALENDAR.matcher(s)).find()) {
                calendar = calendarOf(c);
                s = s.substring(c.end());
            }
        }
        var m = DATE.matcher(s);
        if (!m.find()) return new GedDate(raw, Qualifier.PHRASE, calendar == null ? Calendar.GREGORIAN : calendar, 0, 0, 0);
        // a dual year (11 FEB 1731/32) is Old Style: Julian, with the year starting on 25 MAR
        boolean dual = m.group(4) != null && (calendar == null || calendar == Calendar.JULIAN);
        if (calendar == null) calendar = dual ? Calendar.JULIAN : Calendar.GREGORIAN;
        int year = Integer.parseInt(m.group(3));
        if (m.group(5) != null) year = -year; // B.C.: negative, so it sorts before A.D.
        int month = m.group(2) == null ? 0 : calendar.months().indexOf(m.group(2)) + 1;
        int day = m.group(1) == null || month == 0 ? 0 : Integer.parseInt(m.group(1));
        if (dual && month > 0 && (month < 3 || month == 3 && day < 25)) year++;
        if (calendar == Calendar.GREGORIAN || year == 0) return new GedDate(raw, q, calendar, year, month, day);
        var g = toGregorian(calendar, year < 0 ? year + 1 : year, month, day);
        int gy = g.getYear() <= 0 ? g.getYear() - 1 : g.getYear();
        return new GedDate(raw, q, calendar, gy, month == 0 ? 0 : g.getMonthValue(), day == 0 ? 0 : g.getDayOfMonth());
    }

    private static Calendar calendarOf(Matcher m) {
        return Calendar.named(m.group(1) != null ? m.group(1).strip() : m.group(2));
    }

    /// The Gregorian day for a full date; for a partial one, the middle day of
    /// the month or year it names.
    private static LocalDate toGregorian(Calendar c, long year, int month, int day) {
        if (c == Calendar.HEBREW && month == 7 && !CalendarMath.hebrewLeap(year)) month = 6;
        long first, last;
        if (day > 0) {
            first = last = c.epochDay(year, month, day);
        } else if (month > 0) {
            first = c.epochDay(year, month, 1);
            last = first + c.monthLength(year, month) - 1;
        } else {
            first = c.epochDay(year, 1, 1);
            last = c.epochDay(year + 1, 1, 1) - 1;
        }
        return LocalDate.ofEpochDay(Math.floorDiv(first + last, 2));
    }

    /// The two dates of `BET … AND …` or `FROM … TO …`, each parsed on its own
    /// (a calendar named at the start applies to both); empty for any other date.
    public List<GedDate> ends() {
        if (qualifier != Qualifier.BETWEEN && qualifier != Qualifier.PERIOD) return List.of();
        var s = raw.toUpperCase(Locale.ROOT);
        Calendar calendar = null;
        var c = CALENDAR.matcher(s);
        if (c.find()) {
            calendar = calendarOf(c);
            s = s.substring(c.end());
        }
        var parts = s.replaceFirst("^(?:BET|FROM)\\s+", "").split("\\s+(?:AND|TO)\\s+");
        if (parts.length != 2) return List.of();
        return List.of(parse(parts[0].strip(), calendar), parse(parts[1].strip(), calendar));
    }

    /// The text with calendar escapes and keywords taken out, for display:
    /// `@#DJULIAN@ 4 OCT 1582` → `4 OCT 1582`.
    public static String withoutCalendars(String text) {
        return ANY_CALENDAR.matcher(text).replaceAll("").strip();
    }

    private static final List<String> MONTH_NAMES = List.of("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
            "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER");
    private static final int[] MONTH_DAYS = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    private static final Pattern SIMPLE = Pattern.compile("(?:(\\d{1,2})\\s+)?(?:(\\p{L}+)\\.?\\s+)?(\\d{1,4})(/\\d{2})?(\\s*B\\.?C\\.?(?:E\\.?)?)?");
    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})(?:-(\\d{1,2}))?");
    private static final Pattern CALENDAR_MARK = Pattern.compile("^(@#D[A-Z ]+@|GREGORIAN|JULIAN|HEBREW|FRENCH_R)(?: (.*))?$");
    public static final String EXAMPLES = "12 JUN 1850, JUN 1850, 1850, ABT 1850, BEF 1900, AFT 1900, "
            + "BET 1800 AND 1810, FROM 1914 TO 1918, @#DJULIAN@ 1 MAR 1700, HEBREW 1 TSH 5785, or (free text)";

    /// A calendar as the user wrote it (`@#DJULIAN@`, `JULIAN`, or not at all).
    private record Mark(String text, Calendar calendar) {
        static final Mark NONE = new Mark("", Calendar.GREGORIAN);

        static Mark of(String text) {
            return new Mark(text, Calendar.named(text.startsWith("@#D") ? text.substring(3, text.length() - 1) : text));
        }

        String prefix() { return text.isEmpty() ? "" : text + " "; }
    }

    /// Validates user input and returns it in canonical GEDCOM form:
    /// `12 june 1850` → `12 JUN 1850`, `1850-06-12` → `12 JUN 1850`,
    /// `circa 1850` → `ABT 1850`. Text in parentheses is kept as a date
    /// phrase. Empty input returns `""`.
    ///
    /// A calendar, as an escape (`@#DHEBREW@`) or keyword (`HEBREW`), is kept
    /// as written and moved after the qualifier onto each date, where GEDCOM
    /// puts it: `@#DJULIAN@ bet 1700 and 1710` → `BET @#DJULIAN@ 1700 AND
    /// @#DJULIAN@ 1710`. Days are checked against that calendar's months.
    ///
    /// @throws IllegalArgumentException if the text is not a GEDCOM date
    public static String normalize(String input) {
        var s = input.strip().replaceAll("\\s+", " ");
        if (s.isEmpty()) return "";
        if (s.startsWith("(") && s.endsWith(")")) return s;
        var mark = Mark.NONE;
        var cal = CALENDAR_MARK.matcher(s.toUpperCase(Locale.ROOT));
        if (cal.matches()) {
            mark = Mark.of(cal.group(1));
            s = s.substring(cal.end(1)).strip();
        }
        var u = s.toUpperCase(Locale.ROOT);
        Matcher m;
        if ((m = Pattern.compile("^(?:BET|BETWEEN) (.+?) AND (.+)$").matcher(u)).matches()) {
            return "BET " + side(m.group(1), input, mark) + " AND " + side(m.group(2), input, mark);
        }
        if ((m = Pattern.compile("^FROM (.+?)(?: TO (.+))?$").matcher(u)).matches()) {
            return "FROM " + side(m.group(1), input, mark) + (m.group(2) == null ? "" : " TO " + side(m.group(2), input, mark));
        }
        if ((m = Pattern.compile("^TO (.+)$").matcher(u)).matches()) {
            return "TO " + side(m.group(1), input, mark);
        }
        if ((m = Pattern.compile("^INT (.+?) ?(\\(.*\\))$").matcher(u)).matches()) {
            return "INT " + side(m.group(1), input, mark) + " " + s.substring(s.indexOf('('));
        }
        if ((m = Pattern.compile("^(ABT|ABOUT|CIRCA|CA\\.?|C\\.|CAL|EST|BEF|BEFORE|AFT|AFTER) (.+)$").matcher(u)).matches()) {
            var q = switch (m.group(1)) {
                case "ABOUT", "CIRCA", "CA", "CA.", "C." -> "ABT";
                case "BEFORE" -> "BEF";
                case "AFTER" -> "AFT";
                default -> m.group(1);
            };
            return q + " " + side(m.group(2), input, mark);
        }
        return side(u, input, mark);
    }

    /// One date, with its own calendar if it names one, else `inherited`.
    private static String side(String u, String input, Mark inherited) {
        var mark = inherited;
        var m = CALENDAR_MARK.matcher(u);
        if (m.matches()) {
            mark = Mark.of(m.group(1));
            u = m.group(2) == null ? "" : m.group(2);
        }
        return mark.prefix() + simple(u, input, mark.calendar());
    }

    private static String simple(String u, String input, Calendar calendar) {
        boolean western = calendar == Calendar.GREGORIAN || calendar == Calendar.JULIAN;
        var iso = ISO.matcher(u);
        if (iso.matches() && western) {
            var day = iso.group(3) == null ? "" : iso.group(3) + " ";
            return simple(day + monthAbbrev(Integer.parseInt(iso.group(2)), input) + " " + iso.group(1), input, calendar);
        }
        var m = SIMPLE.matcher(u.replace(",", ""));
        if (!m.matches()) throw new IllegalArgumentException("'" + input.strip() + "' is not a GEDCOM date; use " + EXAMPLES);
        // a one- or two-digit year is too likely a slip ("85") unless marked B.C.;
        // French Republican years are small, though (year VIII is 8)
        if (m.group(3).length() < 3 && m.group(5) == null && calendar != Calendar.FRENCH_R) throw new IllegalArgumentException(
                "'" + input.strip() + "': write the year in full (1985, not 85); for a B.C. year add BC");
        if (!western && (m.group(4) != null || m.group(5) != null)) throw new IllegalArgumentException(
                "'" + input.strip() + "': only Gregorian and Julian dates can have a dual year or B.C.");
        int year = Integer.parseInt(m.group(3));
        var sb = new StringBuilder();
        if (m.group(2) != null) {
            int month = month(m.group(2), calendar);
            if (month == 0) throw new IllegalArgumentException(notAMonth(m.group(2), calendar));
            var name = calendar.months().get(month - 1);
            int days = western ? MONTH_DAYS[month - 1] : calendar.monthLength(year, month);
            if (days == 0) throw new IllegalArgumentException(name + " (Adar II) is only in leap years, and " + year + " is not one");
            if (m.group(1) != null) {
                int day = Integer.parseInt(m.group(1));
                if (day < 1 || day > days) {
                    throw new IllegalArgumentException("day " + day + " is out of range for " + name + (western ? "" : " " + year));
                }
                if (western && month == 2 && day == 29 && m.group(5) == null) {
                    checkLeap(m.group(3), m.group(4), calendar == Calendar.JULIAN);
                }
                sb.append(day).append(' ');
            }
            sb.append(name).append(' ');
        } else if (m.group(1) != null) {
            throw new IllegalArgumentException("'" + input.strip() + "' has a day but no month");
        }
        sb.append(m.group(3));
        if (m.group(4) != null) sb.append(m.group(4));
        if (m.group(5) != null) sb.append(" B.C.");
        return sb.toString();
    }

    /// 29 FEB needs a leap year. A dual year (`1699/00`) is Old Style, so
    /// Julian, and its date falls in the later year. Julian years are leap
    /// every 4 years; Gregorian ones skip centuries not divisible by 400.
    private static void checkLeap(String yearText, String dual, boolean julian) {
        int year = Integer.parseInt(yearText) + (dual == null ? 0 : 1);
        julian |= dual != null;
        if (year % 4 == 0 && (julian || year % 100 != 0 || year % 400 == 0)) return;
        var hint = year % 4 == 0
                ? " (if this is a Julian (Old Style) date, write it as @#DJULIAN@ 29 FEB " + yearText + ")"
                : "";
        throw new IllegalArgumentException("29 FEB " + yearText + " is not a date: " + year + " was not a leap year" + hint);
    }

    private static String monthAbbrev(int month, String input) {
        if (month < 1 || month > 12) throw new IllegalArgumentException("'" + input.strip() + "': month " + month + " is out of range");
        return MONTHS.get(month - 1);
    }

    /// 1–12 for `JUN`, `JUNE`, `SEPT`...; 1–13 for Hebrew `TSH`… or French
    /// `VEND`, `VENDÉMIAIRE`…; 0 if not a month of `calendar`.
    private static int month(String word, Calendar calendar) {
        if (word.length() < 3) return 0;
        var months = calendar == Calendar.GREGORIAN || calendar == Calendar.JULIAN ? MONTH_NAMES : calendar.months();
        for (int i = 0; i < months.size(); i++) {
            var name = months.get(i);
            boolean match = switch (calendar) {
                case GREGORIAN, JULIAN -> name.startsWith(word);
                case HEBREW -> name.equals(word);
                case FRENCH_R -> word.startsWith(name);
            };
            if (match) return i + 1;
        }
        return 0;
    }

    /// Error for an unknown month, pointing to the calendar it belongs to.
    private static String notAMonth(String word, Calendar calendar) {
        for (var other : List.of(Calendar.HEBREW, Calendar.FRENCH_R)) {
            if (other != calendar && month(word, other) > 0) {
                return "'" + word + "' is a " + (other == Calendar.HEBREW ? "Hebrew" : "French Republican")
                        + " month: start the date with " + other.escape() + " (or " + other.keyword() + ")";
            }
        }
        var months = calendar.months();
        return "'" + word + "' is not a month (" + months.get(0) + ", " + months.get(1) + ", ...)";
    }

    public boolean isEmpty() { return raw.isEmpty(); }
    public boolean hasYear() { return year != 0; }

    /// The year for arithmetic: B.C. years shifted by one, since there was no
    /// year 0 (1 B.C. → 0, 44 B.C. → -43), so differences across the era are right.
    public int astronomicalYear() { return year < 0 ? year + 1 : year; }

    /// `1850`, or `44 BC` for a B.C. year.
    public String yearText() { return year < 0 ? -year + " BC" : Integer.toString(year); }

    /// Compact form for tables: `1850`, `~1850`, `<1900`, `>1900`.
    public String shortForm() {
        if (!hasYear()) return raw.isEmpty() ? "" : "?";
        var prefix = switch (qualifier) {
            case ABOUT, BETWEEN, PHRASE -> "~";
            case BEFORE -> "<";
            case AFTER -> ">";
            default -> "";
        };
        return prefix + yearText();
    }

    @Override
    public int compareTo(GedDate o) { return CHRONOLOGICAL.compare(this, o); }

    @Override
    public String toString() { return raw; }
}
