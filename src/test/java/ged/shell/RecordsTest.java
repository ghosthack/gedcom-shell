package ged.shell;

import ged.model.Genealogy;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Notes, sources and media as directories: `/sources/S1` and who uses it.
class RecordsTest {
    static final String GED = """
            0 HEAD
            1 GEDC
            2 VERS 5.5.1
            0 @I1@ INDI
            1 NAME Ana /Paz/
            1 BIRT
            2 DATE 1900
            2 SOUR @S1@
            3 PAGE p. 12
            1 OBJE @M1@
            1 NOTE @N1@
            1 FAMS @F1@
            0 @I2@ INDI
            1 NAME Luis /Paz/
            1 SOUR @S2@
            1 FAMS @F1@
            0 @F1@ FAM
            1 HUSB @I2@
            1 WIFE @I1@
            1 MARR
            2 SOUR @S1@
            0 @S1@ SOUR
            1 TITL Parish register of San Juan, 1880-1920
            1 AUTH Parroquia de San Juan
            1 REPO @R1@
            2 CALN 12/4
            1 OBJE @M2@
            1 NOTE Microfilmed in 1975.
            0 @S2@ SOUR
            1 ABBR Census 1910
            0 @S1@ SOUR
            1 TITL Second source with a taken id
            0 @M1@ OBJE
            1 FILE photos/wedding.jpg
            2 FORM jpeg
            2 TITL Wedding, 1925
            0 @M2@ OBJE
            1 FILE https://example.org/page.png
            0 @N1@ NOTE Ana kept the family Bible.
            1 CONT It burned in 1950.
            0 @N1@ NOTE A second note with a taken id
            0 @R1@ REPO
            1 NAME Archivo Diocesano
            0 @R1@ REPO
            1 NAME Another archive
            0 @X1@ INDI
            1 NAME Eva /Sol/
            0 @X1@ FAM
            1 HUSB @X1@
            0 TRLR
            """;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Genealogy g = Genealogy.of(GED);
    private final Shell shell = new Shell(new Navigator(new FileSystem(g), g.person("I1")),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), Style.PLAIN);

    private String run(String line) {
        out.reset();
        err.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void eachKindIsADirectoryWithWhatUsesEachRecord() {
        assertTrue(run("ls /").contains("4  notes/              2\n5  sources/            3\n6  media/              2\n"), out.toString());
        assertEquals("""
                #  ID  TITLE                                   USED BY
                1  S1  Parish register of San Juan, 1880-1920        2
                2  S2  Census 1910                                   1
                3  S3  Second source with a taken id                 0
                """, run("ls /sources"));
        var users = run("ls /sources/S1");
        assertTrue(users.contains("1  I1    person         Ana Paz"), users);
        assertTrue(users.contains("2  F1    family         Luis Paz & Ana Paz"), users);
        assertTrue(run("ls /media/M2").contains("1  S1  source  Parish register"), out.toString());
        assertEquals("/people/I1\n", run("cd S1; cd 1; pwd -P"));
    }

    @Test
    void showGivesASourcesFieldsAndWhoCitesIt() {
        var s = run("show @S1@");
        assertTrue(s.startsWith("Parish register of San Juan, 1880-1920  S1\n"), s);
        assertTrue(s.contains("Author          Parroquia de San Juan\n"), s);
        assertTrue(s.contains("Repository      R1 Archivo Diocesano\n  Call number   12/4\n"), s);
        assertTrue(s.contains("Media           M2 https://example.org/page.png\n"), s);
        assertTrue(s.contains("Notes\n  Microfilmed in 1975.\n"), s);
        assertTrue(s.contains("Used by\n  person        I1     Ana Paz (b. 1900)\n  family        F1     Luis Paz & Ana Paz\n"), s);
        assertFalse(s.contains("Title "), "the title is the heading: " + s);
    }

    @Test
    void showGivesANotesTextAndAPicturesFile() {
        assertEquals("""
                Note  N1
                ────────────────────
                Ana kept the family Bible.
                It burned in 1950.

                Used by
                  person        I1     Ana Paz (b. 1900)
                """, run("show /notes/N1"));
        var m1 = run("show M1");
        assertTrue(m1.contains("File            photos/wedding.jpg  (jpeg)\n                Wedding, 1925  (not found)\n"), m1);
        assertFalse(run("show M2").contains("not found"), "a web address isn't looked for on disk");
        assertTrue(run("show -r S2").equals("0 @S2@ SOUR\n1 ABBR Census 1910\n"), out.toString());
    }

    @Test
    void aRepeatedRecordIdIsReadUnderTheNextFreeOne() {
        assertEquals(List.of(
                "duplicate id @R1@: the record at line 44 is ignored (the first is at line 42)",
                "duplicate id @S1@: the record at line 31 is read as @S3@ (the first is at line 22)",
                "duplicate id @N1@: the record at line 41 is read as @N2@ (the first is at line 39)"), g.warnings());
        assertTrue(run("show S3").contains("Used by\n  nobody\n"), "pointers to @S1@ mean the first");
        assertEquals(GED, g.render(), "the file is not changed by reading it");
    }

    @Test
    void aPersonAndAFamilyMayShareAnId() {
        assertEquals("Eva Sol", g.person("X1").orElseThrow().name().full());
        assertEquals(java.util.Optional.of("@X1@"), g.family("X1").orElseThrow().husband());
        assertTrue(run("ls /families").contains("X1"), out.toString());
    }

    @Test
    void summaryCountsThemAndTheyCanBeSortedAndCompleted() {
        var s = run("show /");
        assertTrue(s.contains("Notes           2\nSources         3\nMedia           2\n"), s);
        assertTrue(run("ls -s title /media").matches("(?s).*2  M2.*1  M1.*"), out.toString());
        var c = shell.completer().complete("cd S1", 5).candidates();
        assertTrue(c.stream().anyMatch(x -> x.text().equals("S1") && x.note().contains("Parish register")), c.toString());
        assertTrue(run("edit /sources/S1").contains("only people and families can be edited"), out.toString());
    }
}
