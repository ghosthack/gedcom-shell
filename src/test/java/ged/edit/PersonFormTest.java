package ged.edit;

import ged.edit.PersonForm.Field;
import ged.model.GedDate;
import ged.model.Genealogy;
import ged.model.Person;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonFormTest {
    private static final String GED = """
            0 @I1@ INDI
            1 NAME Marco /Rossi/
            2 GIVN Marco
            2 SURN Rossi
            1 SEX M
            1 BIRT
            2 DATE 12 JUN 1850
            2 PLAC Genova
            1 FAMS @F1@
            1 CHAN
            2 DATE 1 JAN 2000
            3 TIME 10:00:00
            0 @I2@ INDI
            1 NAME Anna
            1 FAMC @F1@
            0 @I3@ INDI
            1 NAME Old /Man/
            1 DEAT
            2 DATE 1900
            2 SOUR @S1@
            0 TRLR
            """;

    private final Genealogy g = Genealogy.of(GED);

    private Person p(String id) { return g.person(id).orElseThrow(); }

    private Person edit(String id, Map<Field, String> values) {
        return PersonForm.apply(g, p(id), PersonForm.diff(p(id), values));
    }

    private String raw(String id) { return p(id).node().toString(); }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            12 jun 1850          | 12 JUN 1850
            12 June 1850         | 12 JUN 1850
            1850-06-12           | 12 JUN 1850
            1850-06              | JUN 1850
            sept 1850            | SEP 1850
            circa 1850           | ABT 1850
            c. 1850              | ABT 1850
            before 3 mar 1900    | BEF 3 MAR 1900
            between 1800 and 1810| BET 1800 AND 1810
            from 1914 to 1918    | FROM 1914 TO 1918
            1750/51              | 1750/51
            500 BC               | 500 B.C.
            (in the spring)      | (in the spring)
            @#DJULIAN@ 5 oct 1582| @#DJULIAN@ 5 OCT 1582
            29 feb 1904          | 29 FEB 1904
            29 feb 2000          | 29 FEB 2000
            @#DJULIAN@ 29 feb 1900| @#DJULIAN@ 29 FEB 1900
            29 feb 1699/00       | 29 FEB 1699/00
            44 bce               | 44 B.C.
            """)
    void normalizesDates(String input, String expected) {
        assertEquals(expected, GedDate.normalize(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"31 FEB 1850", "12 1850", "June 12, 1850", "yesterday", "1850-13-01", "12 XYZ 1850", "85",
            "29 FEB 1900", "29 FEB 1901", "1900-02-29", "ABT 29 FEB 1700"})
    void rejectsBadDates(String input) {
        assertThrows(IllegalArgumentException.class, () -> GedDate.normalize(input));
    }

    @Test
    void leapDayErrorSaysHowToWriteAJulianDate() {
        var e = assertThrows(IllegalArgumentException.class, () -> GedDate.normalize("29 feb 1900"));
        assertTrue(e.getMessage().contains("1900 was not a leap year"), e.getMessage());
        assertTrue(e.getMessage().contains("@#DJULIAN@ 29 FEB 1900"), e.getMessage());
    }

    @Test
    void bcDatesSortBeforeAdAndCountAcrossTheEra() {
        var bc44 = GedDate.parse("15 MAR 44 B.C.");
        var bc10 = GedDate.parse("10 BCE");
        var ad14 = GedDate.parse("19 AUG 14");
        assertEquals(-44, bc44.year());
        assertTrue(bc44.compareTo(bc10) < 0 && bc10.compareTo(ad14) < 0);
        assertEquals("44 BC", bc44.yearText());
        assertEquals("10 BC", bc10.shortForm());
        // 63 B.C. to A.D. 14 is 76 years: there was no year 0
        assertEquals(76, GedDate.parse("14").astronomicalYear() - GedDate.parse("63 B.C.").astronomicalYear());
    }

    @Test
    void renderedFormParsesBackWithoutChanges() {
        for (var id : new String[] {"I1", "I2", "I3"}) {
            var form = PersonForm.render(p(id), null);
            assertTrue(PersonForm.diff(p(id), PersonForm.parse(form)).isEmpty(), id + "\n" + form);
        }
    }

    @Test
    void parseRejectsUnknownAndDuplicateFields() {
        var e = assertThrows(IllegalArgumentException.class, () -> PersonForm.parse("# c\n\nnickname: Bob\n"));
        assertEquals("line 3: unknown field 'nickname'", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PersonForm.parse("sex: M\nsex: F\n"));
        assertThrows(IllegalArgumentException.class, () -> PersonForm.parse("just text\n"));
    }

    @Test
    void aliases() {
        assertEquals(Field.BIRTH_PLACE, Field.parse("birthplace").orElseThrow());
        assertEquals(Field.SEX, Field.parse("gender").orElseThrow());
        assertEquals(Field.DEATH_DATE, Field.parse("died").orElseThrow());
        assertEquals(Field.SURNAME, Field.parse("last").orElseThrow());
    }

    @Test
    void nameEditKeepsPiecesInSync() {
        edit("I1", Map.of(Field.GIVEN, "Marco Antonio", Field.SUFFIX, "Jr."));
        assertTrue(raw("I1").contains("1 NAME Marco Antonio /Rossi/ Jr.\n2 GIVN Marco Antonio\n2 SURN Rossi\n"), raw("I1"));
        assertTrue(g.nameMatches(p("I1"), "antonio"));
    }

    @Test
    void sexAndBirthAreCreatedInConventionalPlaces() {
        edit("I2", Map.of(Field.SEX, "female", Field.BIRTH_DATE, "1875", Field.SURNAME, "Rossi"));
        assertEquals("""
                0 @I2@ INDI
                1 NAME Anna /Rossi/
                1 SEX F
                1 BIRT
                2 DATE 1875
                1 FAMC @F1@
                """, raw("I2"));
    }

    @Test
    void clearingTheOnlyBirthFactsRemovesTheEvent() {
        edit("I1", Map.of(Field.BIRTH_DATE, "", Field.BIRTH_PLACE, ""));
        assertFalse(raw("I1").contains("BIRT"), raw("I1"));
    }

    @Test
    void deceasedFlag() {
        edit("I2", Map.of(Field.DECEASED, "yes"));
        assertTrue(raw("I2").endsWith("1 FAMC @F1@\n") && raw("I2").contains("1 DEAT Y\n"), raw("I2"));
        assertTrue(p("I2").isDeceased());

        edit("I2", Map.of(Field.DEATH_PLACE, "Roma"));
        assertTrue(raw("I2").contains("1 DEAT\n2 PLAC Roma\n"), raw("I2"));

        edit("I2", Map.of(Field.DEATH_PLACE, "", Field.DECEASED, "no"));
        assertFalse(raw("I2").contains("DEAT"), raw("I2"));
    }

    @Test
    void deathDetailsImplyDeceased() {
        var changes = PersonForm.diff(p("I2"), Map.of(Field.DEATH_DATE, "1930"));
        assertTrue(changes.stream().anyMatch(c -> c.field() == Field.DECEASED && c.after().equals("yes")));
        assertThrows(IllegalArgumentException.class,
                () -> PersonForm.diff(p("I2"), Map.of(Field.DEATH_DATE, "1930", Field.DECEASED, "no")));
    }

    @Test
    void wontDropSourcedDeathRecord() {
        var e = assertThrows(IllegalArgumentException.class, () -> PersonForm.diff(p("I3"), Map.of(Field.DEATH_DATE, "", Field.DECEASED, "no")));
        assertTrue(e.getMessage().contains("sources or notes"));
    }

    @Test
    void validationMessages() {
        assertThrows(IllegalArgumentException.class, () -> PersonForm.diff(p("I1"), Map.of(Field.SEX, "X")));
        assertThrows(IllegalArgumentException.class, () -> PersonForm.diff(p("I1"), Map.of(Field.SURNAME, "A/B")));
        var e = assertThrows(IllegalArgumentException.class, () -> PersonForm.diff(p("I1"), Map.of(Field.BIRTH_DATE, "soon")));
        assertTrue(e.getMessage().startsWith("birth.date: 'soon' is not a GEDCOM date"), e.getMessage());
    }

    @Test
    void unchangedUnusualDateIsLeftAlone() {
        var odd = Genealogy.of("0 @I1@ INDI\n1 BIRT\n2 DATE Spring of 1850\n0 TRLR\n");
        var p = odd.person("I1").orElseThrow();
        assertTrue(PersonForm.diff(p, PersonForm.parse(PersonForm.render(p, null))).isEmpty());
    }

    @Test
    void changeDateIsTouchedOnlyWhenPresent() {
        edit("I1", Map.of(Field.SEX, "F"));
        assertFalse(raw("I1").contains("2 DATE 1 JAN 2000"), raw("I1"));
        assertTrue(raw("I1").matches("(?s).*1 CHAN\n2 DATE \\d{1,2} [A-Z]{3} \\d{4}\n3 TIME \\d\\d:\\d\\d:\\d\\d\n.*"), raw("I1"));
        edit("I2", Map.of(Field.SEX, "F"));
        assertFalse(raw("I2").contains("CHAN"));
    }

    // ---- baptism, burial, occupation, residence ----------------------------

    @Test
    void otherEventsGoInConventionalPlaces() {
        var fresh = edit("I1", Map.of(Field.BAPTISM_DATE, "20 jun 1850", Field.BAPTISM_PLACE, "San Lorenzo, Genova",
                Field.OCCUPATION, "Sailor", Field.RESIDENCE_PLACE, "Livorno",
                Field.DEATH_DATE, "1920", Field.BURIAL_PLACE, "Staglieno"));
        assertEquals("""
                0 @I1@ INDI
                1 NAME Marco /Rossi/
                2 GIVN Marco
                2 SURN Rossi
                1 SEX M
                1 BIRT
                2 DATE 12 JUN 1850
                2 PLAC Genova
                1 BAPM
                2 DATE 20 JUN 1850
                2 PLAC San Lorenzo, Genova
                1 DEAT
                2 DATE 1920
                1 BURI
                2 PLAC Staglieno
                1 OCCU Sailor
                1 RESI
                2 PLAC Livorno
                1 FAMS @F1@
                """, raw("I1").replaceFirst("1 CHAN[\\s\\S]*", ""));
        assertEquals("Baptised", fresh.event("BAPM").orElseThrow().label());
        var form = PersonForm.render(fresh, null);
        assertTrue(form.contains("baptism.place:   San Lorenzo, Genova"), form);
        assertTrue(PersonForm.diff(fresh, PersonForm.parse(form)).isEmpty(), "and the form reads back unchanged");
    }

    @Test
    void aFileThatRecordsChristeningsKeepsUsingChr() {
        var g2 = Genealogy.of("0 @I1@ INDI\n1 NAME A /B/\n1 CHR\n2 DATE 1800\n0 TRLR\n");
        var p = g2.person("I1").orElseThrow();
        assertEquals("1800", PersonForm.values(p).get(Field.BAPTISM_DATE));
        PersonForm.apply(g2, p, PersonForm.diff(p, Map.of(Field.BAPTISM_PLACE, "Lyon")));
        assertEquals("0 @I1@ INDI\n1 NAME A /B/\n1 CHR\n2 DATE 1800\n2 PLAC Lyon\n", g2.person("I1").orElseThrow().node().toString());
    }

    @Test
    void aBurialImpliesDeceased() {
        var changes = PersonForm.diff(p("I2"), Map.of(Field.BURIAL_DATE, "1930"));
        assertTrue(changes.stream().anyMatch(c -> c.field() == Field.DECEASED && c.after().equals("yes")), changes.toString());
        var e = assertThrows(IllegalArgumentException.class,
                () -> PersonForm.diff(p("I2"), Map.of(Field.BURIAL_PLACE, "Roma", Field.DECEASED, "no")));
        assertTrue(e.getMessage().contains("burial"), e.getMessage());
    }

    @Test
    void markingLivingRemovesABareBurialButNotOneWithSources() {
        var g2 = Genealogy.of("0 @I1@ INDI\n1 NAME A /B/\n1 BURI Y\n0 @I2@ INDI\n1 NAME C /D/\n1 BURI\n2 SOUR @S1@\n0 TRLR\n");
        var a = g2.person("I1").orElseThrow();
        PersonForm.apply(g2, a, PersonForm.diff(a, Map.of(Field.DECEASED, "no")));
        assertFalse(g2.person("I1").orElseThrow().isDeceased());
        var c = g2.person("I2").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> PersonForm.diff(c, Map.of(Field.DECEASED, "no")));
    }

    @Test
    void occupationAndResidenceEditTheFirstAndKeepTheRest() {
        var g2 = Genealogy.of("""
                0 @I1@ INDI
                1 NAME A /B/
                1 OCCU Baker
                1 OCCU Miller
                1 RESI
                2 PLAC Pisa
                1 RESI
                2 PLAC Lucca
                0 TRLR
                """);
        var p = g2.person("I1").orElseThrow();
        assertEquals("Baker", PersonForm.values(p).get(Field.OCCUPATION));
        PersonForm.apply(g2, p, PersonForm.diff(p, Map.of(Field.OCCUPATION, "", Field.RESIDENCE_DATE, "1880")));
        assertEquals("""
                0 @I1@ INDI
                1 NAME A /B/
                1 OCCU Miller
                1 RESI
                2 DATE 1880
                2 PLAC Pisa
                1 RESI
                2 PLAC Lucca
                """, g2.person("I1").orElseThrow().node().toString());
    }

    @Test
    void anOccupationWithSourcesIsNotRemovedSilently() {
        var g2 = Genealogy.of("0 @I1@ INDI\n1 NAME A /B/\n1 OCCU Baker\n2 SOUR @S1@\n0 TRLR\n");
        var p = g2.person("I1").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> PersonForm.diff(p, Map.of(Field.OCCUPATION, "")));
        assertEquals(1, PersonForm.diff(p, Map.of(Field.OCCUPATION, "Master baker")).size(), "changing it is fine");
    }

    @Test
    void newAliases() {
        assertEquals(Field.BAPTISM_DATE, Field.parse("christened").orElseThrow());
        assertEquals(Field.BURIAL_DATE, Field.parse("buried").orElseThrow());
        assertEquals(Field.BURIAL_PLACE, Field.parse("burialplace").orElseThrow());
        assertEquals(Field.OCCUPATION, Field.parse("job").orElseThrow());
        assertEquals(Field.RESIDENCE_PLACE, Field.parse("residence").orElseThrow());
    }
}
