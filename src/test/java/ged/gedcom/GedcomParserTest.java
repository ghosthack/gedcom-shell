package ged.gedcom;

import ged.Fixtures;
import ged.model.Genealogy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GedcomParserTest {

    @Test
    void continuationLinesFoldIntoValue() {
        var r = GedcomParser.parse("""
                0 @N1@ NOTE First line
                1 CONT second line
                1 CONC  continued
                0 TRLR
                """);
        assertEquals("First line\nsecond line continued", r.records().getFirst().value());
    }

    @Test
    void changeDateDoesNotOverwriteBirthDate() {
        // Regression for the JS implementation, where CHAN's DATE clobbered BIRT's.
        var g = Fixtures.cousins();
        var marco = g.person("I3").orElseThrow();
        assertEquals("12 JUN 1850", marco.birth().raw());
        assertEquals("16 JUN 2007", marco.node().first("CHAN").orElseThrow().text("DATE"));
    }

    @Test
    void rawRecordRoundTrips() {
        var g = Fixtures.cousins();
        var raw = g.person("I3").orElseThrow().node().toString();
        assertTrue(raw.startsWith("0 @I3@ INDI\n1 NAME Marco /Rossi/\n"), raw);
        assertTrue(raw.contains("2 DATE 16 JUN 2007\n3 TIME 17:07:01\n"));
    }

    @Test
    void malformedLinesBecomeWarnings() {
        var r = GedcomParser.parse("0 HEAD\nthis is not gedcom\n2 ORPHAN x\n0 TRLR\n");
        assertEquals(2, r.records().size());
        assertEquals(1, r.warnings().size(), r.warnings().toString());
    }

    @Test
    void danglingPointersAreReported() {
        var g = Genealogy.of("0 @I1@ INDI\n1 FAMC @F9@\n0 TRLR\n");
        assertTrue(g.warnings().getFirst().contains("@F9@"));
    }
}
