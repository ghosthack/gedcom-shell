package ged.model;

import ged.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {
    private final Genealogy g = Fixtures.cousins();

    private Person p(String key) { return g.person(key).orElseThrow(); }

    private static List<String> keys(List<Person> people) {
        return people.stream().map(Person::key).toList();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            12 JUN 1850          | EXACT   | 1850 | 6 | 12 | 1850
            1850                 | EXACT   | 1850 | 0 | 0  | 1850
            ABT 1890             | ABOUT   | 1890 | 0 | 0  | ~1890
            BEF MAR 1900         | BEFORE  | 1900 | 3 | 0  | <1900
            AFT 1700             | AFTER   | 1700 | 0 | 0  | >1700
            BET 1800 AND 1810    | BETWEEN | 1800 | 0 | 0  | ~1800
            FROM 1914 TO 1918    | PERIOD  | 1914 | 0 | 0  | 1914
            @#DJULIAN@ 5 OCT 1582| EXACT   | 1582 | 10| 5  | 1582
            """)
    void parsesDates(String raw, GedDate.Qualifier q, int y, int m, int d, String shortForm) {
        var date = GedDate.parse(raw);
        assertEquals(q, date.qualifier());
        assertEquals(y, date.year());
        assertEquals(m, date.month());
        assertEquals(d, date.day());
        assertEquals(shortForm, date.shortForm());
    }

    @Test
    void unparseableDateIsKeptAsPhrase() {
        var d = GedDate.parse("in the spring");
        assertFalse(d.hasYear());
        assertEquals("in the spring", d.raw());
        assertEquals("?", d.shortForm());
    }

    @Test
    void names() {
        assertEquals("José", p("I5").name().given());
        assertEquals("Pérez", p("I5").name().surname());
        assertEquals("José Pérez", p("I5").name().full());
    }

    @Test
    void relationships() {
        var luca = p("I7");
        assertEquals("I3", g.father(luca).orElseThrow().key());
        assertEquals("I5", g.mother(luca).orElseThrow().key());
        assertEquals(List.of("I8"), keys(g.spouses(luca)));
        assertEquals(List.of("I9"), keys(g.children(luca)));
        assertEquals(List.of("I4"), keys(g.siblings(p("I3"))));
        assertTrue(g.siblings(luca).isEmpty());
    }

    @Test
    void ancestorsDeduplicatePedigreeCollapse() {
        // Elena's great-grandparents Giovanni and Lucia are reachable via both parents.
        var anc = keys(g.ancestors(p("I9")));
        assertEquals(List.of("I7", "I8", "I3", "I5", "I6", "I4", "I1", "I2"), anc);
    }

    @Test
    void descendantsSurviveCycles() {
        var cyclic = Genealogy.of("""
                0 @I1@ INDI
                1 FAMS @F1@
                1 FAMC @F1@
                0 @F1@ FAM
                1 HUSB @I1@
                1 CHIL @I1@
                0 TRLR
                """);
        var me = cyclic.person("I1").orElseThrow();
        assertTrue(cyclic.descendants(me).isEmpty());
        assertTrue(cyclic.ancestors(me).isEmpty());
    }

    @Test
    void searchIgnoresCaseAndAccents() {
        assertTrue(g.nameMatches(p("I5"), "jose perez"));
        assertTrue(g.nameMatches(p("I5"), "PÉREZ"));
        assertFalse(g.nameMatches(p("I5"), "jose rossi"));
    }

    @Test
    void sharedNotesResolve() {
        assertEquals("Went to sea\nat fifteen.", g.noteText(p("I3").notes().getFirst()));
    }
}
