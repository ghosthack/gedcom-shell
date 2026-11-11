package ged.model;

import ged.gedcom.Ansel;
import ged.gedcom.Charsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamesAndRecordsTest {
    private static Genealogy with(String... lines) {
        return Genealogy.of("0 HEAD\n1 CHAR UTF-8\n" + String.join("\n", lines) + "\n0 TRLR\n");
    }

    @ParameterizedTest
    @CsvSource({"dorde", "DORĐE", "aegirsson", "Ægir", "oeuvre", "thor", "yildiz", "strasse"})
    void searchFoldsLettersWithNoAccentToStrip(String query) {
        var g = with("0 @I1@ INDI", "1 NAME Đorđe Þór Yıldız /Ægirsson Œuvre Straße/");
        assertTrue(g.nameMatches(g.person("I1").orElseThrow(), query), query);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            José María /García/ /López/ | José María | García | López  | José María García López
            John /Doe                   | John       | Doe    | ''     | John Doe
            John Smith                  | John Smith | ''     | ''     | John Smith
            John /Doe/ Jr.              | John       | Doe    | Jr.    | John Doe Jr.
            """)
    void namesWithOddSlashes(String value, String given, String surname, String suffix, String full) {
        var name = with("0 @I1@ INDI", "1 NAME " + value).person("I1").orElseThrow().name();
        assertEquals(given, name.given());
        assertEquals(surname, name.surname());
        assertEquals(suffix, name.suffix());
        assertEquals(full, name.full());
    }

    @Test
    void aPhraseOrACremationDoesNotHideTheDate() {
        var g = with("0 @I1@ INDI", "1 BIRT", "2 DATE (unknown)", "1 CHR", "2 DATE 3 MAY 1850", "1 CREM", "2 DATE 1920");
        var p = g.person("I1").orElseThrow();
        assertEquals(1850, p.birth().year());
        assertEquals(1920, p.death().year());
    }

    @Test
    void newIdsKeepTheFilesZeroPadding() {
        var g = with("0 @I0001@ INDI", "1 NAME A /B/", "0 @I0002@ INDI", "1 NAME C /B/");
        assertEquals("@I0003@", g.newPerson("D /B/", Person.Sex.UNKNOWN).id());
        var plain = with("0 @I9@ INDI", "1 NAME A /B/");
        assertEquals("@I10@", plain.newPerson("D /B/", Person.Sex.UNKNOWN).id());
    }

    @Test
    void anselKeepsItsHornLetters() {
        // Thơ, and ờ (ơ with a grave: the mark byte comes first)
        byte[] bytes = {'T', 'h', (byte) 0xBC, ' ', (byte) 0xE1, (byte) 0xBC, ' ', (byte) 0xAD, (byte) 0xAC};
        var text = Ansel.decode(bytes);
        assertEquals("Thơ ờ ƯƠ", text);
        assertArrayEquals(bytes, Ansel.encode(text));
    }

    @Test
    void aLongValueIsNotSplitInsideACharacter() {
        var g = with("0 @I1@ INDI", "1 NAME A /B/", "1 OCCU x");
        var occu = g.person("I1").orElseThrow().node().first("OCCU").orElseThrow();
        var value = "x".repeat(199) + "😀" + "y".repeat(50) + "é".repeat(120);
        occu.setValue(value);
        var sb = new StringBuilder();
        occu.write(sb, "\n");
        for (var line : sb.toString().split("\n")) {
            assertFalse(Character.isLowSurrogate(line.charAt(line.indexOf(' ', 2) + 1)), line);
            assertFalse(Character.isHighSurrogate(line.charAt(line.length() - 1)), line);
        }
        var bytes = Charsets.encode(sb.toString(), new Charsets.Decoded("", "UTF-8", "UTF-8"));
        var back = Genealogy.of("0 HEAD\n0 @I1@ INDI\n" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8) + "0 TRLR\n");
        assertEquals(value, back.person("I1").orElseThrow().node().text("OCCU"));
    }
}
