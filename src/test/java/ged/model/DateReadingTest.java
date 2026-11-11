package ged.model;

import ged.model.GedDate.Qualifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Dates as files really write them, and input `normalize` must refuse.
class DateReadingTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            3 FEB. 1790                  | EXACT   | 1790-2-3
            MAR. 1850                    | EXACT   | 1850-3-0
            12 Sept. 1850                | EXACT   | 1850-9-12
            12 MARCH 1850                | EXACT   | 1850-3-12
            June 12, 1850                | EXACT   | 1850-6-12
            June, 1850                   | EXACT   | 1850-6-0
            @#DJULIAN@ 12 MARCH 1700     | EXACT   | 1700-3-23
            JULIAN 12 March 1700         | EXACT   | 1700-3-23
            11 FEBRUARY 1731/32          | EXACT   | 1732-2-22
            15 MARCH 44 BC               | EXACT   | -44-3-15
            15 March 144 B.C.            | EXACT   | -144-3-15
            MARCH 144 BC                 | EXACT   | -144-3-0
            @#DJULIAN@ 12 JUN            | EXACT   | 0-6-12
            BETWEEN 1800 AND 1810        | BETWEEN | 1800-0-0
            ca 1850                      | ABOUT   | 1850-0-0
            CA. 1850                     | ABOUT   | 1850-0-0
            Abt. 1850                    | ABOUT   | 1850-0-0
            BEF. 1900                    | BEFORE  | 1900-0-0
            INT 1850 (aged 12 in 1862)   | PHRASE  | 1850-0-0
            (died young, age 12)         | PHRASE  | 0-0-0
            (about 3 weeks after birth)  | PHRASE  | 0-0-0
            """)
    void reads(String raw, Qualifier qualifier, String expected) {
        var d = GedDate.parse(raw);
        assertEquals(qualifier, d.qualifier(), raw);
        assertEquals(expected, d.year() + "-" + d.month() + "-" + d.day(), raw);
    }

    @Test
    void aPhraseHasNoYear() {
        assertFalse(GedDate.parse("(lost at sea 1850)").hasYear());
        assertEquals("?", GedDate.parse("(lost at sea 1850)").shortForm());
    }

    @Test
    void betweenWrittenOutHasTwoEnds() {
        assertEquals(2, GedDate.parse("BETWEEN 1800 AND 1810").ends().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bet 1810 and 1800", "from 1918 to 1914", "bet 12 jun 1850 and 3 jun 1850",
            "11 feb 1731/45", "000", "jun 000", "int 1850"})
    void refuses(String input) {
        assertThrows(IllegalArgumentException.class, () -> GedDate.normalize(input), input);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            abt. 1850                 | ABT 1850
            bet jun 1850 and 1850     | BET JUN 1850 AND 1850
            bet 1850 and 1850         | BET 1850 AND 1850
            11 feb 1731/32            | 11 FEB 1731/32
            1 jan 1699/00             | 1 JAN 1699/00
            """)
    void normalises(String input, String expected) {
        assertEquals(expected, GedDate.normalize(input));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            15 mar 44 bc              | true  | 15 MAR 44 BCE
            15 mar 44 bc              | false | 15 MAR 44 B.C.
            @#DJULIAN@ 4 oct 1582     | true  | JULIAN 4 OCT 1582
            julian 4 oct 1582         | false | @#DJULIAN@ 4 OCT 1582
            @#DFRENCH R@ 18 brum 8    | true  | FRENCH_R 18 BRUM 8
            french_r 18 brum 8        | false | @#DFRENCH R@ 18 BRUM 8
            julian bet 1700 and 1710  | false | BET @#DJULIAN@ 1700 AND @#DJULIAN@ 1710
            """)
    void writesTheFilesVersion(String input, boolean gedcom7, String expected) {
        assertEquals(expected, GedDate.normalize(input, gedcom7));
    }

    @Test
    void gedcom7HasNoIntAndNoDualYear() {
        assertThrows(IllegalArgumentException.class, () -> GedDate.normalize("INT 1850 (from his age)", true));
        assertEquals("INT 1850 (from his age)", GedDate.normalize("INT 1850 (from his age)", false));
        assertThrows(IllegalArgumentException.class, () -> GedDate.normalize("11 feb 1731/32", true));
        assertThrows(IllegalArgumentException.class, () -> GedDate.normalize("bet 1 jan 1699/00 and 1701", true));
        assertEquals("11 FEB 1731/32", GedDate.normalize("11 feb 1731/32", false));
    }

    @Test
    void gedcom7HasNoParenthesisedPhrase() {
        assertThrows(IllegalArgumentException.class, () -> GedDate.normalize("(lost at sea)", true));
        assertEquals("(lost at sea)", GedDate.normalize("(lost at sea)", false));
    }
}
