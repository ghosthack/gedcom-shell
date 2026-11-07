package ged.model;

import static java.lang.Math.floorDiv;
import static java.lang.Math.floorMod;

/// Day arithmetic for the calendars a GEDCOM date can be written in. Days
/// are `java.time` epoch days (0 = 1 JAN 1970 Gregorian), years are
/// astronomical (1 B.C. = 0, 2 B.C. = -1), and months use GEDCOM's numbering
/// for each calendar: Hebrew from TSH (1) to ELL (13), French Republican from
/// VEND (1) to COMP (13). The arithmetic is lenient: day 31 of a 30-day
/// month is simply the first of the next.
final class CalendarMath {
    private CalendarMath() {}

    /// Julian Day Number of 1 JAN 1970 Gregorian, epoch day 0.
    private static final long JDN_EPOCH = 2440588;

    // ---- Gregorian and Julian ---------------------------------------------------

    /// The year counted from 4801 B.C. and starting in March, so the leap day
    /// is the last day of the year (the usual JDN formula, with floored division).
    static long gregorianToEpochDay(long year, int month, int day) {
        long y = marchYear(year, month);
        return day + marchDays(month) + 365 * y + floorDiv(y, 4) - floorDiv(y, 100) + floorDiv(y, 400) - 32045 - JDN_EPOCH;
    }

    static long julianToEpochDay(long year, int month, int day) {
        long y = marchYear(year, month);
        return day + marchDays(month) + 365 * y + floorDiv(y, 4) - 32083 - JDN_EPOCH;
    }

    private static long marchYear(long year, int month) { return year + 4800 - (month <= 2 ? 1 : 0); }

    /// Days in the months before `month`, counting from March.
    private static int marchDays(int month) {
        int m = month + (month <= 2 ? 9 : -3);
        return (153 * m + 2) / 5;
    }

    static boolean gregorianLeap(long year) { return floorMod(year, 4) == 0 && (floorMod(year, 100) != 0 || floorMod(year, 400) == 0); }

    static boolean julianLeap(long year) { return floorMod(year, 4) == 0; }

    static int monthLength(int month, boolean leap) {
        return switch (month) {
            case 2 -> leap ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    // ---- Hebrew -----------------------------------------------------------------
    // After Reingold & Dershowitz, "Calendrical Calculations": a year starts at
    // the molad of Tishri, postponed by the dehiyyot so that Rosh Hashanah never
    // falls on Sunday, Wednesday or Friday and years keep a legal length.

    /// 1 TSH 1 AM (7 OCT 3761 B.C. Julian) as an epoch day.
    private static final long HEBREW_EPOCH = -1373427 - 719163;

    static boolean hebrewLeap(long year) { return floorMod(7 * year + 1, 19) < 7; }

    /// Days from the epoch to the molad of Tishri of `year`, with the
    /// "not on Sunday, Wednesday or Friday" postponement.
    private static long elapsedDays(long year) {
        long months = floorDiv(235 * year - 234, 19);
        long parts = 12084 + 13753 * months;
        long days = 29 * months + floorDiv(parts, 25920);
        return floorMod(3 * (days + 1), 7) < 3 ? days + 1 : days;
    }

    /// Extra postponement that keeps this year and the last at legal lengths.
    private static int yearLengthCorrection(long year) {
        long ny0 = elapsedDays(year - 1), ny1 = elapsedDays(year), ny2 = elapsedDays(year + 1);
        if (ny2 - ny1 == 356) return 2;
        if (ny1 - ny0 == 382) return 1;
        return 0;
    }

    static long hebrewNewYear(long year) { return HEBREW_EPOCH + elapsedDays(year) + yearLengthCorrection(year); }

    static int hebrewYearLength(long year) { return (int) (hebrewNewYear(year + 1) - hebrewNewYear(year)); }

    /// Days in a Hebrew month; 0 for ADS (Adar II) in a common year.
    /// Cheshvan is long in "complete" years (355 or 385 days), Kislev short in
    /// "deficient" ones (353 or 383); Adar (Adar I in leap years) has 30 days
    /// only in leap years.
    static int hebrewMonthLength(long year, int month) {
        int length = hebrewYearLength(year);
        return switch (month) {
            case 2 -> length % 10 == 5 ? 30 : 29;
            case 3 -> length % 10 == 3 ? 29 : 30;
            case 6 -> hebrewLeap(year) ? 30 : 29;
            case 7 -> hebrewLeap(year) ? 29 : 0;
            case 4, 9, 11, 13 -> 29;
            default -> 30;
        };
    }

    static long hebrewToEpochDay(long year, int month, int day) {
        if (month == 7 && !hebrewLeap(year)) month = 6;
        long days = hebrewNewYear(year);
        for (int m = 1; m < month; m++) days += hebrewMonthLength(year, m);
        return days + day - 1;
    }

    // ---- French Republican ------------------------------------------------------
    // Twelve months of 30 days and 5 or 6 complementary days. Years III, VII
    // and XI were sextile (leap), as decreed while the calendar was in use; the
    // same four-year rule is carried on before and after.

    /// 1 VEND I (22 SEP 1792) is `FRENCH_OFFSET + 365 + 1` as a Julian Day Number.
    private static final long FRENCH_OFFSET = 2375474;

    static long frenchToEpochDay(long year, int month, int day) {
        return floorDiv(year * 1461, 4) + (month - 1) * 30L + day + FRENCH_OFFSET - JDN_EPOCH;
    }

    static boolean frenchSextile(long year) { return floorDiv((year + 1) * 1461, 4) - floorDiv(year * 1461, 4) == 366; }

    static int frenchMonthLength(long year, int month) { return month < 13 ? 30 : frenchSextile(year) ? 6 : 5; }
}
