package ged.shell;

import ged.Fixtures;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `link` and `unlink` on the cousins fixture plus two unattached people,
/// Marta Neri (I10, F) and Bruno Neri (I11, M). Home is Elena Rossi (I9),
/// child of Luca (I7) and Sofia (I8) in F4.
class LinkShellTest {
    static final String EXTRA = """
            0 @I10@ INDI
            1 NAME Marta /Neri/
            1 SEX F
            0 @I11@ INDI
            1 NAME Bruno /Neri/
            1 SEX M
            0 TRLR
            """;

    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Genealogy g;
    private Shell shell;

    @BeforeEach
    void setUp() {
        load(Genealogy.of(Fixtures.COUSINS.replace("0 TRLR\n", EXTRA)));
    }

    private void load(Genealogy genealogy) {
        g = genealogy;
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        shell = new Shell(new Navigator(new FileSystem(g), g.person("I9")), ps, ps, Style.PLAIN);
    }

    private String run(String line) {
        out.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8);
    }

    private List<String> names(List<ged.model.Person> people) {
        return people.stream().map(ged.model.Person::key).toList();
    }

    private ged.model.Person p(String key) { return g.person(key).orElseThrow(); }

    // ---- link -------------------------------------------------------------

    @Test
    void firstChildStartsAFamily() {
        var r = run("link I10 children");
        assertTrue(r.contains("Marta Neri I10 is now a child of Elena Rossi in F5 Elena Rossi (new family)"), r);
        assertTrue(g.render().contains("0 @F5@ FAM\n1 WIFE @I9@\n1 CHIL @I10@\n0 TRLR\n"), g.render());
        assertTrue(g.render().contains("1 FAMC @F4@\n1 FAMS @F5@\n"), "Elena's FAMS follows her FAMC");
        assertTrue(g.render().contains("1 SEX F\n1 FAMC @F5@\n"), "and Marta points back");
        assertEquals(List.of("I10"), names(g.children(p("I9"))));
        assertTrue(run("ls").contains("children/I10"), "the view is refreshed");
    }

    @Test
    void partnersThenChildren() {
        assertTrue(run("link I11 spouses").contains("Bruno Neri I11 and Elena Rossi are now partners in F5"));
        assertTrue(g.render().contains("0 @F5@ FAM\n1 HUSB @I11@\n1 WIFE @I9@\n"), g.render());
        run("link I10 children");
        assertEquals(List.of("I11", "I9"), names(g.parents(p("I10"))), "one family, both parents");
        assertTrue(run("link I11 spouses").contains("already a partner"));
    }

    @Test
    void aSinglePartnersFamilyGetsTheNewPartner() {
        run("link I10 children");                      // F5: Elena + Marta
        assertTrue(run("link I11 spouses").contains("are now partners in F5"));
        assertFalse(g.family("F6").isPresent());
    }

    @Test
    void parents() {
        assertTrue(run("cd I10; link I11 father").contains("Bruno Neri I11 is now the father of Marta Neri (F5, new family)"));
        assertTrue(run("link I9 mother").contains("is now the mother of Marta Neri (F5)"));
        assertEquals(List.of("I11", "I9"), names(g.parents(p("I10"))));
        assertTrue(run("cd ~; link I11 father").contains("Elena Rossi already has a father: I7  Luca Rossi"));
        assertTrue(run("cd I10; unlink mother; link I11 mother").contains("recorded as male; link as father"));
    }

    @Test
    void siblingsAndExplicitFamilies() {
        assertTrue(run("link I10 siblings").contains("is now a sibling of Elena Rossi in F4"));
        assertEquals(List.of("I10"), names(g.siblings(p("I9"))));
        assertTrue(run("link I11 /families/F2/children").contains("is now a child in F2"));
        assertTrue(run("link I11 /families/F2/children").contains("already a child in F2"));
        assertTrue(run("link I10 /families/F3/husband").contains("F3 already has a husband"));
    }

    @Test
    void severalFamiliesMustBeNamed() {
        run("cd father; link I10 spouses");             // Luca: F4 with Sofia, F5 with Marta
        var r = run("link I11 children");
        assertTrue(r.contains("Luca Rossi has 2 families; say which, e.g. families/F4/children"), r);
        assertTrue(run("link I11 families/F5/children").contains("is now a child in F5"));
    }

    @Test
    void badTargets() {
        assertTrue(run("link I10 /people").contains("can't link to people"));
        assertTrue(run("link /families/F1 children").contains("is not a person"));
        assertTrue(run("link I10").contains("usage: link PERSON WHERE"));
        assertFalse(g.isDirty());
    }

    // ---- unlink -----------------------------------------------------------

    @Test
    void parentLeavesTheFamily() {
        var r = run("unlink father");
        assertTrue(r.contains("Luca Rossi I7 left F4 (was a partner; Sofia Verdi and 1 child remain)"), r);
        assertTrue(g.father(p("I9")).isEmpty());
        assertEquals(List.of("I8"), names(g.parents(p("I9"))));
        assertFalse(g.render().contains("1 FAMS @F4@\n0 @I8@"), "Luca's FAMS line is gone too");
        assertTrue(g.familiesAsSpouse(p("I7")).isEmpty());
        r = run("unlink mother");
        assertTrue(r.contains("(was a partner; 1 child remains)"), r);
        assertTrue(r.contains("F4 no longer connected anyone"), "only Elena was left");
    }

    @Test
    void childLeavesFromTheParentsSide() {
        run("cd father; unlink children/I9");
        assertTrue(g.parents(p("I9")).isEmpty());
        assertTrue(g.familiesAsChild(p("I9")).isEmpty());
        assertTrue(g.family("F4").isPresent(), "Luca and Sofia's family stays");
    }

    @Test
    void otherRelations() {
        run("cd father; unlink spouses/I8");
        assertTrue(g.spouses(p("I7")).isEmpty());
        assertEquals(List.of("I9"), names(g.children(p("I7"))), "the children stay with Luca");
        load(Genealogy.of(Fixtures.COUSINS.replace("0 TRLR\n", EXTRA)));
        run("cd /people/I3; unlink siblings/I4");
        assertTrue(g.siblings(p("I3")).isEmpty());
        run("cd ~; unlink families/F4");
        assertTrue(g.parents(p("I9")).isEmpty());
        run("unlink /families/F3/wife");
        assertTrue(g.familiesAsSpouse(p("I4")).isEmpty());
    }

    @Test
    void anEmptiedFamilyIsDeleted() {
        run("link I10 children");                       // F5, new: Elena and Marta
        var r = run("unlink children/I10");
        assertTrue(r.contains("F5 no longer connected anyone and was deleted"), r);
        assertFalse(g.render().contains("@F5@"), "neither the record nor Elena's FAMS remain");
        // F2 (Marco, José, Luca): an original record, so deleting it counts as a change
        run("unlink /families/F2/husband");
        assertTrue(g.family("F2").isPresent(), "José and Luca are still mother and son");
        assertTrue(run("unlink /families/F2/wife").contains("F2 no longer connected anyone"));
        assertFalse(g.render().contains("@F2@"), "no dangling pointers");
        assertTrue(g.parents(p("I7")).isEmpty());
    }

    @Test
    void familiesWithEventsAreKept() {
        run("unlink /families/F1/husband; unlink /families/F1/wife; unlink /families/F1/children/I3");
        assertFalse(run("unlink /families/F1/children/I4").contains("deleted"));
        assertTrue(g.render().contains("0 @F1@ FAM\n1 MARR\n2 DATE 1848\n"), "the marriage date is kept");
    }

    @Test
    void notARelationship() {
        assertTrue(run("unlink I10").contains("is not a relationship"));
        assertTrue(run("unlink /families/F4").contains("is not a relationship"));
        assertTrue(run("unlink children/I3").contains("not found"));
    }

    @Test
    void linksSaveAndReload() throws Exception {
        var file = dir.resolve("t.ged");
        Files.writeString(file, Fixtures.COUSINS.replace("0 TRLR\n", EXTRA));
        load(Genealogy.load(file));
        run("link I11 spouses; link I10 children; cd father; unlink children/I9; save");
        assertFalse(g.isDirty());
        var again = Genealogy.load(file);
        assertTrue(again.warnings().isEmpty(), again.warnings().toString());
        assertEquals(List.of("I11", "I9"), again.parents(again.person("I10").orElseThrow()).stream().map(ged.model.Person::key).toList());
        assertTrue(again.parents(again.person("I9").orElseThrow()).isEmpty());
        assertTrue(Files.readString(file).endsWith("0 @F5@ FAM\n1 HUSB @I11@\n1 WIFE @I9@\n1 CHIL @I10@\n0 TRLR\n"));
    }
}
