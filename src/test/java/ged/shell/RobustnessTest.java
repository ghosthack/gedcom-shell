package ged.shell;

import ged.Fixtures;
import ged.edit.Editor;
import ged.model.GedDate;
import ged.model.Genealogy;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Hostile input, odd files and refused commands: the session survives,
/// nothing half-done is left behind, and saving changes only what was edited.
class RobustnessTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Genealogy g;
    private Shell shell;

    private void load(Genealogy genealogy, String home, Editor editor) {
        g = genealogy;
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        shell = new Shell(new Navigator(new FileSystem(g), g.person(home)), ps, ps, Style.PLAIN, editor);
    }

    private void cousins() {
        load(Fixtures.cousins(), "I9", f -> false);
    }

    private String run(String line) {
        out.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8);
    }

    private Path file(String name, byte[] bytes) throws Exception {
        var p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    // ---- the session survives ------------------------------------------------

    @Test
    void absurdRowNumbersAndBadChartTargetsAreErrorsNotCrashes() {
        cousins();
        assertTrue(run("cd 99999999999").contains("no row 99999999999"), out.toString());
        assertDoesNotThrow(() -> run("chart -o /"));
        assertTrue(run("chart -o /").contains("directory"), out.toString());
        assertTrue(run("chart -o " + dir.resolve("tree.pdf")).contains(".html, .svg or .png"), out.toString());
        assertFalse(Files.exists(dir.resolve("tree.pdf")));
        assertTrue(run("pwd").contains("/people/I9"), "still running, still where we were");
    }

    // ---- editing --------------------------------------------------------------

    @Test
    void aDeathDateTypedIntoTheFormImpliesDeceased() {
        // the form lists `deceased: no` for the living; leaving it alone is not a veto
        load(Fixtures.cousins(), "I9", f -> {
            Files.writeString(f, Files.readString(f).replace("death.date:", "death.date:   1920"));
            return true;
        });
        var said = run("edit @I2@");
        assertTrue(said.contains("death.date"), said);
        var lucia = g.person("I2").orElseThrow();
        assertTrue(lucia.isDeceased());
        assertEquals(1920, lucia.death().year());
    }

    @Test
    void aDivorceDateTypedIntoTheFormImpliesDivorced() {
        load(Fixtures.cousins(), "I9", f -> {
            Files.writeString(f, Files.readString(f).replace("divorce.date:", "divorce.date:    1870"));
            return true;
        });
        run("edit @F1@");
        assertTrue(g.family("F1").orElseThrow().event("DIV").isPresent(), out.toString());
    }

    @Test
    void flippingDeceasedToNoInTheFormStillNeedsTheDateCleared() {
        load(Fixtures.cousins(), "I9", new Editor() {
            int runs;
            @Override public boolean edit(Path f) throws java.io.IOException {
                if (runs++ > 0) return false; // the form reopens with the error: give up
                Files.writeString(f, Files.readString(f).replace("deceased:     yes", "deceased:     no"));
                return true;
            }
        });
        assertTrue(run("edit @I1@").contains("deceased is 'no'"), out.toString());
        assertTrue(g.person("I1").orElseThrow().isDeceased());
    }

    @Test
    void changingAPartnersSexPointsOutTheFamilySlot() {
        cousins();
        assertTrue(run("edit @I1@ sex=F").contains("still recorded as the husband in F1"), out.toString());
    }

    @Test
    void refusedLinksLeaveNothingBehind() {
        cousins();
        run("add person \"Solo Test\"");
        var solo = g.people().getLast().key();
        int families = g.families().size();
        for (var where : new String[] {"children", "spouses", "father", "mother", "parents"}) {
            run("cd /people/" + solo + "; link . " + where);
            assertEquals(families, g.families().size(), "link . " + where + ": " + out);
            assertTrue(g.person(solo).orElseThrow().spouseIn().isEmpty(), where);
            assertTrue(g.person(solo).orElseThrow().childOf().isEmpty(), where);
        }
    }

    @Test
    void nobodyBecomesTheirOwnAncestor() {
        cousins();
        // Elena (I9) is a great-granddaughter of Giovanni (I1)
        assertTrue(run("link I1 children").contains("is an ancestor of"), out.toString());
        assertTrue(run("cd /people/I1; link I9 mother").contains("is an ancestor of"), out.toString());
        assertTrue(run("link I1 /families/F4/children").contains("is an ancestor of"), out.toString());
        assertFalse(g.isDirty(), "all three were refused before anything changed");
    }

    // ---- dates ----------------------------------------------------------------

    @Test
    void datesFilesContainButGedcomForbidsKeepTheirYear() {
        assertEquals(1900, GedDate.parse("3 MARCH 1900").year());
        assertEquals(3, GedDate.parse("3 MARCH 1900").month());
        assertEquals(3, GedDate.parse("3 MARCH 1900").day());
        assertEquals(1850, GedDate.parse("12/06/1850").year());
        assertEquals(1850, GedDate.parse("12.06.1850").year());
        assertEquals(0, GedDate.parse("12/06/1850").month(), "day/month order is anyone's guess");
        var us = GedDate.parse("June 12, 1850");
        assertEquals(java.util.List.of(1850, 6, 12), java.util.List.of(us.year(), us.month(), us.day()));
        var iso = GedDate.parse("1850-06-12");
        assertEquals(java.util.List.of(1850, 6, 12), java.util.List.of(iso.year(), iso.month(), iso.day()));
        assertFalse(GedDate.parse("12 JUN").hasYear(), "a day is not a year");
        assertEquals(6, GedDate.parse("12 JUN").month());
        // and the standard forms are as before
        assertEquals(1850, GedDate.parse("ABT 1850").year());
        assertEquals(1800, GedDate.parse("BET 1800 AND 1810").year());
        assertEquals(1914, GedDate.parse("FROM 1914 TO 1918").year());
        assertEquals(12, GedDate.parse("12 JUN 1850").day());
        assertEquals(1750, GedDate.parse("11 FEB 1750/51").year());
    }

    @Test
    void leapDayNeedsAYearThatCanHaveOne() {
        assertThrows(IllegalArgumentException.class, () -> GedDate.normalize("29 feb 1901"));
        assertEquals("29 FEB 1904", GedDate.normalize("29 feb 1904"));
        assertEquals("29 FEB 1900", GedDate.normalize("29 feb 1900"), "a leap year in the Julian calendar");
    }

    @Test
    void anUndatedBirthDoesNotHideADatedChristening() {
        var p = Genealogy.of("0 @I1@ INDI\n1 BIRT\n2 PLAC Roma\n1 CHR\n2 DATE 4 MAY 1801\n1 DEAT Y\n1 BURI\n2 DATE 1870\n0 TRLR\n")
                .person("I1").orElseThrow();
        assertEquals(1801, p.birth().year());
        assertEquals(1870, p.death().year());
    }

    // ---- saving ---------------------------------------------------------------

    @Test
    void mixedLineEndingsAndStrayLinesInsideARecordSurviveAnEdit() throws Exception {
        var before = "0 HEAD\r\n1 CHAR UTF-8\r\n0 @I1@ INDI\r\n1 NAME Ana /Ruiz/\nthis is a stray line\r\n1 SEX F\r\n"
                + "1 BIRT\r\n2 DATE 1900\r\n0 @I2@ INDI\r\n1 NAME Bo /Ruiz/\n1 SEX M\r\n0 TRLR\r\n";
        var f = file("mixed.ged", before.getBytes(StandardCharsets.UTF_8));
        load(Genealogy.load(f), "I1", x -> false);
        assertEquals(before, g.render(), "unedited, the file reads back as it is");
        run("edit @I1@ born=1901; save");
        assertEquals(before.replace("2 DATE 1900", "2 DATE 1901"), Files.readString(f), out.toString());
    }

    @Test
    void aStrayLineOutlivesTheLineItFollowed() {
        var g = Genealogy.of("0 @I1@ INDI\n1 NAME A /B/\n1 DEAT\n2 DATE 1900\nscribble\n1 NOTE x\n0 TRLR\n");
        load(g, "I1", x -> false);
        run("edit deceased=no death.date=");
        assertEquals("0 @I1@ INDI\n1 NAME A /B/\nscribble\n1 NOTE x\n0 TRLR\n", g.render());
    }

    @Test
    void bytesWindows1252LeavesUndefinedAreWrittenBackAsTheyWere() throws Exception {
        var before = "0 HEAD\n1 CHAR ANSI\n0 @I1@ INDI\n1 NAME José /Ruiz/\n1 NOTE odd \u0081 byte\n0 @I2@ INDI\n1 NAME Bo /Ruiz/\n0 TRLR\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        var f = file("cp1252.ged", before);
        load(Genealogy.load(f), "I1", x -> false);
        assertTrue(run("edit @I2@ sex=M; save").contains("saved"), out.toString());
        var expected = new String(before, StandardCharsets.ISO_8859_1).replace("/Ruiz/\n0 TRLR", "/Ruiz/\n1 SEX M\n0 TRLR")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals(expected, Files.readAllBytes(f));
    }

    @Test
    void saveRefusesToOverwriteAnotherProgramsChanges() throws Exception {
        var f = file("shared.ged", Fixtures.COUSINS.getBytes(StandardCharsets.UTF_8));
        load(Genealogy.load(f), "I9", x -> false);
        run("edit sex=U");
        var theirs = Fixtures.COUSINS.replace("Sailor", "Captain");
        Files.writeString(f, theirs);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertTrue(run("save").contains("changed by another program"), out.toString());
        assertEquals(theirs, Files.readString(f));
        assertTrue(g.isDirty());
        assertTrue(run("save " + dir.resolve("mine.ged")).contains("saved"), "a copy is always allowed");
        assertTrue(run("save -f").contains("saved"), out.toString());
        assertFalse(g.isDirty());
        run("edit sex=F");
        assertTrue(run("save").contains("saved"), "our own save is the new baseline");
    }

    // ---- charts ---------------------------------------------------------------

    private static final String HOSTILE = """
            0 HEAD
            1 CHAR UTF-8
            0 @I1@ INDI
            1 NAME {{data}}
            1 SEX M
            1 BIRT
            2 PLAC <img src=x onerror=alert(1)>
            1 NOTE <!--<script>
            1 FAMS @F1@
            0 @../../x@ INDI
            1 NAME {{svg}}
            1 SEX F
            1 FAMS @F1@
            0 @I3@ INDI
            1 NAME Ольга /Романова/
            0 @F1@ FAM
            1 HUSB @I1@
            1 WIFE @../../x@
            0 TRLR
            """;

    @Test
    void namesThatLookLikeTemplateSlotsOrMarkupStayText() throws Exception {
        load(Genealogy.of(HOSTILE), "I1", x -> false);
        var target = dir.resolve("h.html");
        run("chart --notes -o " + target);
        var html = Files.readString(target);
        assertFalse(html.contains("<img src=x"), "file text never arrives as markup");
        assertFalse(html.contains("<!--<script>"));
        assertTrue(html.contains("<h1>{{data}}</h1>"), "the title is the name, literally");
        assertEquals(1, html.split("var DATA = \\{", -1).length - 1);
        assertTrue(html.contains("\\u003cimg src=x"), "and is still there as data");
    }

    @Test
    void defaultChartNamesCannotLeaveTheDirectory() {
        var g = Genealogy.of(HOSTILE);
        var unnamed = Genealogy.of("0 @../../x@ INDI\n1 SEX F\n0 TRLR\n").people().getFirst();
        assertEquals("x", ChartCommand.slug(unnamed));
        assertEquals("ольга-романова", ChartCommand.slug(g.person("I3").orElseThrow()));
    }
}
