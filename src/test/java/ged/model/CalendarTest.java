package ged.model;

import ged.model.GedDate.Calendar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarTest {

    private static String gregorian(GedDate d) {
        return d.year() + "-" + d.month() + "-" + d.day();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            @#DJULIAN@ 4 OCT 1582           | JULIAN    | 1582-10-14
            JULIAN 4 OCT 1582               | JULIAN    | 1582-10-14
            @#DJULIAN@ 15 MAR 44 B.C.       | JULIAN    | -44-3-13
            JULIAN 1 JAN 1                  | JULIAN    | -1-12-30
            @#DJULIAN@ 29 FEB 1700          | JULIAN    | 1700-3-11
            @#DGREGORIAN@ 15 OCT 1582       | GREGORIAN | 1582-10-15
            GREGORIAN 15 OCT 1582           | GREGORIAN | 1582-10-15
            @#DHEBREW@ 1 TSH 5785           | HEBREW    | 2024-10-3
            HEBREW 1 TSH 5785               | HEBREW    | 2024-10-3
            @#DHEBREW@ 29 ELL 5785          | HEBREW    | 2025-9-22
            @#DHEBREW@ 15 NSN 5784          | HEBREW    | 2024-4-23
            @#DHEBREW@ 14 ADS 5784          | HEBREW    | 2024-3-24
            @#DHEBREW@ 14 ADR 5785          | HEBREW    | 2025-3-14
            @#DHEBREW@ 1 TSH 5760           | HEBREW    | 1999-9-11
            @#DFRENCH R@ 1 VEND 1           | FRENCH_R  | 1792-9-22
            FRENCH_R 18 BRUM 8              | FRENCH_R  | 1799-11-9
            @#DFRENCH R@ 9 THER 2           | FRENCH_R  | 1794-7-27
            @#DFRENCH R@ 6 COMP 11          | FRENCH_R  | 1803-9-23
            @#DFRENCH R@ 1 VEND 14          | FRENCH_R  | 1805-9-23
            11 FEB 1731/32                  | JULIAN    | 1732-2-22
            @#DROMAN@ 12 JUN 1850           | GREGORIAN | 1850-6-12
            """)
    void convertsToGregorian(String raw, Calendar calendar, String expected) {
        var d = GedDate.parse(raw);
        assertEquals(calendar, d.calendar());
        assertEquals(expected, gregorian(d));
        assertEquals(raw, d.raw());
    }

    @Test
    void julianChangeoverDaysAreConsecutive() {
        var lastJulian = GedDate.parse("@#DJULIAN@ 4 OCT 1582");
        var firstGregorian = GedDate.parse("15 OCT 1582");
        var a = LocalDate.of(lastJulian.year(), lastJulian.month(), lastJulian.day());
        var b = LocalDate.of(firstGregorian.year(), firstGregorian.month(), firstGregorian.day());
        assertEquals(a.plusDays(1), b);
    }

    @Test
    void arithmeticAgreesWithJavaTimeDayByDay() {
        // a full Hebrew cycle of 19 years, day by day: no gaps, no overlaps
        long expected = CalendarMath.hebrewNewYear(5770);
        for (int y = 5770; y < 5789; y++) {
            var lengths = CalendarMath.hebrewLeap(y) ? List.of(383, 384, 385) : List.of(353, 354, 355);
            assertTrue(lengths.contains(CalendarMath.hebrewYearLength(y)), "year " + y);
            for (int m = 1; m <= 13; m++) {
                for (int d = 1; d <= Calendar.HEBREW.monthLength(y, m); d++) {
                    assertEquals(expected++, Calendar.HEBREW.epochDay(y, m, d), y + "-" + m + "-" + d);
                }
            }
        }
        // Gregorian arithmetic matches java.time, B.C. included
        for (var day = LocalDate.of(-500, 1, 1); day.isBefore(LocalDate.of(2100, 1, 1)); day = day.plusDays(97)) {
            assertEquals(day.toEpochDay(), Calendar.GREGORIAN.epochDay(day.getYear(), day.getMonthValue(), day.getDayOfMonth()));
        }
    }

    @Test
    void frenchSextileYearsAreThoseOfTheRepublic() {
        var sextile = new ArrayList<Integer>();
        for (int y = 1; y <= 14; y++) if (CalendarMath.frenchSextile(y)) sextile.add(y);
        assertEquals(List.of(3, 7, 11), sextile);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            @#DHEBREW@ 5785       | 2025-0-0
            @#DHEBREW@ TSH 5785   | 2024-10-0
            @#DHEBREW@ NSN 5785   | 2025-4-0
            @#DFRENCH R@ 1        | 1793-0-0
            @#DFRENCH R@ VEND 1   | 1792-10-0
            @#DFRENCH R@ COMP 1   | 1793-9-0
            @#DJULIAN@ 1700       | 1700-0-0
            @#DJULIAN@ DEC 1700   | 1700-12-0
            """)
    void partialDatesTakeTheMiddleOfTheirSpan(String raw, String expected) {
        assertEquals(expected, gregorian(GedDate.parse(raw)));
    }

    @Test
    void qualifiersAndRangesInBothSyntaxes() {
        var abt = GedDate.parse("ABT @#DJULIAN@ 1 JAN 1700");
        assertEquals(GedDate.Qualifier.ABOUT, abt.qualifier());
        assertEquals(Calendar.JULIAN, abt.calendar());
        assertEquals("1700-1-11", gregorian(abt));
        assertEquals("1700-1-11", gregorian(GedDate.parse("@#DJULIAN@ ABT 1 JAN 1700")));
        assertEquals("1700-1-11", gregorian(GedDate.parse("ABT JULIAN 1 JAN 1700")));

        var bet = GedDate.parse("BET HEBREW 1 TSH 5785 AND HEBREW 29 ELL 5785");
        assertEquals(GedDate.Qualifier.BETWEEN, bet.qualifier());
        assertEquals("2024-10-3", gregorian(bet));
        var ends = bet.ends();
        assertEquals("2024-10-3", gregorian(ends.get(0)));
        assertEquals("2025-9-22", gregorian(ends.get(1)));

        // a calendar written before the qualifier applies to both ends
        var period = GedDate.parse("@#DFRENCH R@ FROM 1 VEND 1 TO 18 BRUM 8");
        assertEquals(GedDate.Qualifier.PERIOD, period.qualifier());
        assertEquals("1792-9-22", gregorian(period.ends().get(0)));
        assertEquals("1799-11-9", gregorian(period.ends().get(1)));
        // each end may name its own
        var mixed = GedDate.parse("BET @#DJULIAN@ 1 JAN 1700 AND 1 JAN 1700").ends();
        assertEquals("1700-1-11", gregorian(mixed.get(0)));
        assertEquals("1700-1-1", gregorian(mixed.get(1)));
        assertEquals(List.of(), GedDate.parse("ABT 1700").ends());
    }

    @Test
    void datesInDifferentCalendarsSortByTheDayTheyMean() {
        var dates = new ArrayList<>(List.of(
                GedDate.parse("@#DHEBREW@ 1 TSH 5785"),      // 3 OCT 2024
                GedDate.parse("@#DFRENCH R@ 18 BRUM 8"),     // 9 NOV 1799
                GedDate.parse("2 OCT 2024"),
                GedDate.parse("@#DJULIAN@ 1 NOV 1799"),      // 12 NOV 1799
                GedDate.parse("10 NOV 1799"),
                GedDate.parse("JULIAN 25 SEP 2024")));       // 8 OCT 2024
        dates.sort(Comparator.naturalOrder());
        assertEquals(List.of("@#DFRENCH R@ 18 BRUM 8", "10 NOV 1799", "@#DJULIAN@ 1 NOV 1799",
                "2 OCT 2024", "@#DHEBREW@ 1 TSH 5785", "JULIAN 25 SEP 2024"), dates.stream().map(GedDate::raw).toList());
        assertTrue(GedDate.parse("@#DJULIAN@ 20 FEB 1700").compareTo(GedDate.parse("1 MAR 1700")) > 0);
    }

    @Test
    void displayTextIsKeptAndCalendarsCanBeStripped() {
        assertEquals("1 TSH 5785", GedDate.withoutCalendars("@#DHEBREW@ 1 TSH 5785"));
        assertEquals("BET 1 VEND 1 AND 18 BRUM 8", GedDate.withoutCalendars("BET FRENCH_R 1 VEND 1 AND FRENCH_R 18 BRUM 8"));
        assertEquals("2025", GedDate.parse("@#DHEBREW@ 5785").shortForm());
    }

    // ---- normalize ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            @#dhebrew@ 1 tsh 5785                 | @#DHEBREW@ 1 TSH 5785
            hebrew 30 csh 5785                    | HEBREW 30 CSH 5785
            hebrew 30 ksl 5785                    | HEBREW 30 KSL 5785
            hebrew 30 adr 5784                    | HEBREW 30 ADR 5784
            hebrew 29 ads 5784                    | HEBREW 29 ADS 5784
            hebrew nsn 5785                       | HEBREW NSN 5785
            hebrew 5785                           | HEBREW 5785
            @#DFRENCH R@ 18 brumaire 8            | @#DFRENCH R@ 18 BRUM 8
            french_r 1 vendémiaire 1              | FRENCH_R 1 VEND 1
            @#DFRENCH R@ 6 comp 11                | @#DFRENCH R@ 6 COMP 11
            @#DFRENCH R@ 5 comp 12                | @#DFRENCH R@ 5 COMP 12
            julian 29 feb 1700                    | JULIAN 29 FEB 1700
            gregorian 12 jun 1850                 | GREGORIAN 12 JUN 1850
            @#DJULIAN@ abt 1700                   | ABT @#DJULIAN@ 1700
            abt @#DJULIAN@ 1700                   | ABT @#DJULIAN@ 1700
            @#DJULIAN@ bet 1700 and 1710          | BET @#DJULIAN@ 1700 AND @#DJULIAN@ 1710
            bet julian 1700 and 1710              | BET JULIAN 1700 AND 1710
            from hebrew 1 tsh 5785 to 1 jan 2025  | FROM HEBREW 1 TSH 5785 TO 1 JAN 2025
            @#DFRENCH R@ from 1 vend 1 to 18 brum 8 | FROM @#DFRENCH R@ 1 VEND 1 TO @#DFRENCH R@ 18 BRUM 8
            """)
    void normalizesCalendarDates(String input, String expected) {
        assertEquals(expected, GedDate.normalize(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@#DHEBREW@ 31 TSH 5785",   // Tishri has 30 days
            "@#DHEBREW@ 30 CSH 5784",   // Cheshvan is short in 5784
            "@#DHEBREW@ 30 TVT 5785",   // Tevet always 29
            "@#DHEBREW@ 30 ADR 5785",   // Adar has 29 days in a common year
            "@#DHEBREW@ 1 ADS 5785",    // no Adar II in a common year
            "HEBREW ADS 5785",
            "HEBREW 1 JAN 5785",
            "HEBREW 1 TSH 85",
            "@#DFRENCH R@ 31 VEND 2",
            "@#DFRENCH R@ 6 COMP 12",   // year XII was not sextile
            "@#DFRENCH R@ 7 COMP 11",
            "@#DFRENCH R@ 1 JAN 2",
            "@#DFRENCH R@ 2 B.C.",
            "@#DHEBREW@ 5785/86",
            "@#DJULIAN@ 1 TSH 5785",
            "julian 30 feb 1700",
            "29 feb 1700"})
    void rejectsBadCalendarDates(String input) {
        assertThrows(IllegalArgumentException.class, () -> GedDate.normalize(input));
    }

    @Test
    void foreignMonthNamesPointToTheirCalendar() {
        var e = assertThrows(IllegalArgumentException.class, () -> GedDate.normalize("1 tsh 5785"));
        assertTrue(e.getMessage().contains("Hebrew month") && e.getMessage().contains("@#DHEBREW@"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> GedDate.normalize("18 brumaire 1799"));
        assertTrue(e.getMessage().contains("French Republican") && e.getMessage().contains("FRENCH_R"), e.getMessage());
    }

    @Test
    void normalizedDatesParseToTheSameDay() {
        for (var input : List.of("hebrew 1 tsh 5785", "@#DFRENCH R@ 18 brumaire 8", "julian 4 oct 1582")) {
            var d = GedDate.parse(GedDate.normalize(input));
            assertTrue(d.calendar() != Calendar.GREGORIAN && d.day() > 0, input);
        }
    }
}
