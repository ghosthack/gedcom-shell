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

    @Test
    void aRepeatedIdSaysWhereBothRecordsAre() {
        var g = Genealogy.of("""
                0 HEAD
                0 @I1@ INDI
                1 NAME A /One/

                0 @I1@ INDI
                1 NAME B /Two/
                0 @N1@ NOTE first
                0 @N1@ NOTE second
                0 TRLR
                """);
        assertEquals(java.util.List.of(
                "duplicate id @I1@: the record at line 5 is ignored (the first is at line 2)",
                "duplicate id @N1@ at line 8 ignored"), g.warnings());
        assertEquals("A One", g.person("I1").orElseThrow().name().full());
    }
}
