package ged.shell;

import ged.edit.Links;
import ged.model.Genealogy;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// GEDCOM 7 specifics on `gedcom7.ged`: Ada Lovelace (I1), daughter of
/// George Byron (I2) in F1, whose wife is `@VOID@`; mother of Byron King
/// (I3) in F2, whose husband is `@VOID@`. Shared notes are `SNOTE` records.
class Gedcom7Test {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Path file;
    private Genealogy g;
    private Shell shell;

    @BeforeEach
    void setUp() throws Exception {
        file = dir.resolve("gedcom7.ged");
        Files.copy(Path.of(getClass().getResource("/gedcom7.ged").toURI()), file);
        g = Genealogy.load(file);
        shell = new Shell(new Navigator(new FileSystem(g), g.person("I1")),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), Style.PLAIN);
    }

    private String run(String line) {
        out.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void versionIsReadFromTheHeader() {
        assertEquals("7.0", g.gedcomVersion());
        assertTrue(g.isGedcom7());
        assertTrue(run("show /").contains("GEDCOM          7.0\n"));
        assertFalse(ged.Fixtures.cousins().isGedcom7());
    }

    @Test
    void sharedNotesAreResolvedOnPeopleFamiliesAndEvents() {
        var person = run("show @I1@");
        assertTrue(person.contains("Notes\n  Mathematician and writer,\n  known for her notes on the Analytical Engine.\n"), person);
        assertTrue(person.contains("Born            10 DEC 1815, London, England\n                Born in Piccadilly.\n"), person);
        var family = run("show @F2@");
        assertTrue(family.contains("Notes\n  Mathematician and writer,"), family);
    }

    @Test
    void externalIdsAreShownWithTheirType() {
        var person = run("show @I1@");
        assertTrue(person.contains("External ids    12345  (http://example.com/people)\n                Q7259\n"), person);
        assertTrue(run("show @F2@").contains("External ids    F-77\n"));
    }

    @Test
    void voidPointersAreNoLinkAndNoWarning() {
        assertEquals(List.of(), g.warnings(), "neither SNOTE records nor @VOID@ count as broken links");
        var f1 = g.family("F1").orElseThrow();
        assertTrue(f1.wife().isEmpty());
        var f2 = g.family("F2").orElseThrow();
        assertTrue(f2.husband().isEmpty());
        var byron = g.person("I3").orElseThrow();
        assertEquals(List.of("@F2@"), byron.childOf());
        assertEquals("I1", g.mother(byron).orElseThrow().key());
        assertTrue(g.father(byron).isEmpty());
        assertEquals(List.of(), g.spouses(g.person("I1").orElseThrow()));
    }

    @Test
    void linkingFillsAVoidSlot() throws Exception {
        var husband = g.newPerson("William /King/", ged.model.Person.Sex.MALE);
        Links.setPartner(g, g.family("F2").orElseThrow(), Links.Slot.HUSB, husband);
        assertEquals(husband.id(), g.family("F2").orElseThrow().husband().orElseThrow());
        g.save(file);
        var text = Files.readString(file);
        assertTrue(text.contains("0 @F2@ FAM\n1 HUSB " + husband.id() + "\n1 WIFE @I1@\n"), text);
        assertFalse(text.contains("1 HUSB @VOID@"), text);
    }

    @Test
    void untouchedFileSavesByteForByte() throws Exception {
        var copy = dir.resolve("copy.ged");
        g.save(copy);
        assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(copy));
    }

    @Test
    void longEditedValuesAreNotSplitWithConc() throws Exception {
        var ada = g.person("I1").orElseThrow();
        var place = "x".repeat(450);
        ada.node().first("BIRT").orElseThrow().first("PLAC").orElseThrow().setValue(place);
        ada.node().insert(0, "NOTE", "y".repeat(300) + "\nsecond line");
        g.updated(ada);
        g.save(file);

        var text = Files.readString(file);
        assertFalse(text.contains("CONC"), text);
        assertTrue(text.contains("\n2 PLAC " + place + "\n"), text);
        assertTrue(text.contains("\n1 NOTE " + "y".repeat(300) + "\n2 CONT second line\n"), text);
        var reread = Genealogy.load(file).person("I1").orElseThrow();
        assertEquals(place, reread.event("BIRT").orElseThrow().place());
    }
}
