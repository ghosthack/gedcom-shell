package ged.shell;

import ged.model.Genealogy;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The `CHAN` stamp an edit leaves, and what a file with a repeated id is told.
class ChangeStampTest {
    private static String file(String version) {
        return """
                0 HEAD
                1 GEDC
                2 VERS %s
                0 @I1@ INDI
                1 NAME Ana /Paz/
                1 CHAN
                2 DATE 1 JAN 2000
                3 TIME 10:00:00
                0 TRLR
                """.formatted(version);
    }

    private static String stampAfterEdit(String version) {
        var g = Genealogy.of(file(version));
        var sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        new Shell(new Navigator(new FileSystem(g), g.person("I1")), sink, sink, Style.PLAIN).execute("edit sex=F");
        return g.person("I1").orElseThrow().node().first("CHAN").orElseThrow().toString();
    }

    @Test
    void gedcom7StampsAreUtcAndSaySo() {
        var chan = stampAfterEdit("7.0");
        assertTrue(chan.matches("1 CHAN\n2 DATE \\d{1,2} [A-Z]{3} \\d{4}\n3 TIME \\d\\d:\\d\\d:\\d\\dZ\n"), chan);
        var hour = Integer.parseInt(chan.replaceFirst("(?s).*TIME (\\d\\d).*", "$1"));
        int utc = java.time.LocalTime.now(java.time.ZoneOffset.UTC).getHour();
        assertTrue(hour == utc || hour == (utc + 23) % 24, "UTC hour, allowing for the hour turning: " + chan);
    }

    @Test
    void olderFilesKeepLocalTimeWithNoMark() {
        var chan = stampAfterEdit("5.5.1");
        assertTrue(chan.matches("1 CHAN\n2 DATE \\d{1,2} [A-Z]{3} \\d{4}\n3 TIME \\d\\d:\\d\\d:\\d\\d\n"), chan);
    }

    private static final String REPEATED = """
            0 HEAD
            0 @I1@ INDI
            1 NAME A /One/
            1 FAMS @F1@

            0 @I1@ INDI
            1 NAME B /Two/
            1 SEX F
            0 @I2@ INDI
            1 NAME C /Three/
            0 @F1@ FAM
            1 HUSB @I1@
            0 @F1@ FAM
            1 WIFE @I2@
            0 @N1@ NOTE first
            0 @N1@ NOTE second
            0 TRLR
            """;

    @Test
    void aRepeatedIdIsReadUnderTheNextFreeOne() {
        var g = Genealogy.of(REPEATED);
        assertEquals(java.util.List.of(
                "duplicate id @I1@: the record at line 6 is read as @I3@ (the first is at line 2) and is saved under that id once edited",
                "duplicate id @F1@: the record at line 13 is read as @F2@ (the first is at line 11) and is saved under that id once edited",
                "duplicate id @N1@: the record at line 16 is read as @N2@ (the first is at line 15)"),
                g.warnings());
        assertEquals("A One", g.person("I1").orElseThrow().name().full());
        assertEquals("B Two", g.person("I3").orElseThrow().name().full());
        assertEquals(java.util.Optional.of("@I2@"), g.family("F2").orElseThrow().wife());
        assertEquals(REPEATED, g.render(), "nothing changes in the file until it is edited");
    }

    @Test
    void editingItWritesItUnderItsNewId() {
        var g = Genealogy.of(REPEATED);
        var sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        var shell = new Shell(new Navigator(new FileSystem(g), g.person("I1")), sink, sink, Style.PLAIN);
        assertTrue(shell.execute("cd two; edit born=1900; link I3 /families/F2/children"));
        assertEquals(REPEATED
                .replace("0 @I1@ INDI\n1 NAME B /Two/\n1 SEX F\n", "0 @I3@ INDI\n1 NAME B /Two/\n1 SEX F\n1 BIRT\n2 DATE 1900\n1 FAMC @F2@\n")
                .replace("0 @F1@ FAM\n1 WIFE @I2@\n", "0 @F2@ FAM\n1 WIFE @I2@\n1 CHIL @I3@\n"), g.render());
        var again = Genealogy.of(g.render());
        assertEquals(java.util.List.of("duplicate id @N1@: the record at line 20 is read as @N2@ (the first is at line 19)"), again.warnings());
        assertTrue(shell.execute("add person \"D /Four/\""));
        assertTrue(g.person("I4").isPresent(), "new ids steer clear of the ones handed out on reading");
    }
}
