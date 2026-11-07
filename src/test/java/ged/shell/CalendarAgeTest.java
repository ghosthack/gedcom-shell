package ged.shell;

import ged.model.Genealogy;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Ages count real days when birth and death are in different calendars.
class CalendarAgeTest {
    private static Optional<String> age(String born, String died) {
        var g = Genealogy.of("""
                0 HEAD
                0 @I1@ INDI
                1 NAME A /B/
                1 BIRT
                2 DATE %s
                1 DEAT
                2 DATE %s
                0 TRLR
                """.formatted(born, died));
        return ShowCommand.age(g.person("I1").orElseThrow());
    }

    @Test
    void agesAcrossCalendars() {
        // Julian 20 FEB 1700 is Gregorian 2 MAR 1700: not yet 50 on 1 MAR 1750
        assertEquals(Optional.of("49 at death"), age("@#DJULIAN@ 20 FEB 1700", "1 MAR 1750"));
        assertEquals(Optional.of("50 at death"), age("@#DJULIAN@ 20 FEB 1700", "2 MAR 1750"));
        // 18 BRUM 8 is 9 NOV 1799
        assertEquals(Optional.of("20 at death"), age("FRENCH_R 18 BRUM 8", "9 NOV 1819"));
        assertEquals(Optional.of("19 at death"), age("FRENCH_R 18 BRUM 8", "8 NOV 1819"));
        // 1 TSH 5785 is 3 OCT 2024; Hebrew years are not Gregorian years
        assertEquals(Optional.of("1 at death"), age("@#DHEBREW@ 1 TSH 5785", "@#DHEBREW@ 29 ELL 5786"));
        assertEquals(Optional.of("~1 at death"), age("@#DHEBREW@ 5785", "2026"));
        // Old Style dual year: 11 FEB 1731/32 is 22 FEB 1732
        assertEquals(Optional.of("67 at death"), age("11 FEB 1731/32", "14 DEC 1799"));
    }
}
