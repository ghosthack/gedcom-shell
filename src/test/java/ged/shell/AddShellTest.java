package ged.shell;

import ged.Fixtures;
import ged.model.Genealogy;
import ged.model.Person;
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

/// `add` on the cousins fixture (I1–I9, F1–F4), standing at Elena Rossi
/// (I9), child of Luca (I7) and Sofia (I8) in F4.
class AddShellTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Genealogy g;
    private Shell shell;

    @BeforeEach
    void setUp() {
        load(Fixtures.cousins());
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

    private Person p(String key) { return g.person(key).orElseThrow(); }

    private List<String> keys(List<Person> people) { return people.stream().map(Person::key).toList(); }

    @Test
    void childWithDetails() {
        var r = run("add child \"Maria Rossi\" sex=F born=\"12 jun 1950\" birthplace=Roma");
        assertTrue(r.contains("added Maria Rossi I10  given: Maria · surname: Rossi · sex: F · birth.date: 12 JUN 1950 · birth.place: Roma"), r);
        assertTrue(r.contains("Maria Rossi I10 is now a child of Elena Rossi in F5"), r);
        assertTrue(g.render().contains("0 @I10@ INDI\n1 NAME Maria /Rossi/\n1 SEX F\n1 BIRT\n2 DATE 12 JUN 1950\n2 PLAC Roma\n1 FAMC @F5@\n"), g.render());
        assertEquals(List.of("I10"), keys(g.children(p("I9"))));
    }

    @Test
    void parentsAreSexedAndNamesSplit() {
        run("cd I6; add father \"Pietro /De Luca/\"");     // Paolo Verdi has no parents yet
        var father = g.father(p("I6")).orElseThrow();
        assertEquals("De Luca", father.name().surname());
        assertEquals(Person.Sex.MALE, father.sex());
        run("add mother Chiara");
        var mother = g.mother(p("I6")).orElseThrow();
        assertEquals("Chiara", mother.name().given());
        assertEquals("", mother.name().surname());
        assertEquals(Person.Sex.FEMALE, mother.sex());
        assertEquals(1, g.familiesAsChild(p("I6")).size(), "the second parent joins the first one's family");
    }

    @Test
    void spousesSiblingsAndLoners() {
        assertTrue(run("add spouse \"Juan /Pérez García/\" sex=M").contains("are now partners in F5"));
        assertEquals("Pérez García", g.spouses(p("I9")).getFirst().name().surname());
        assertTrue(run("add sibling \"Paolo Rossi\"").contains("is now a sibling of Elena Rossi in F4"));
        var r = run("add person \"Ana Ruiz\"");
        assertTrue(r.contains("added Ana Ruiz I12"), r);
        assertFalse(r.contains("is now"), "not linked to anyone");
        assertTrue(run("add /families/F2/children Bea").contains("is now a child in F2"));
    }

    @Test
    void refusalsAddNothing() {
        int before = g.people().size();
        assertTrue(run("add father \"Bruno Neri\"").contains("already has a father"));
        assertTrue(run("add child Maria born=yesterday").contains("is not a GEDCOM date"));
        assertTrue(run("add mother Bruno sex=M").contains("recorded as male"));
        assertTrue(run("add cousin Maria").contains("not found"));
        assertTrue(run("add child \"Maria /Rossi\"").contains("unbalanced '/'"));
        assertTrue(run("add child Maria colour=red").contains("unknown field 'colour'"));
        assertTrue(run("add child").contains("usage: add RELATION NAME"));
        assertEquals(before, g.people().size());
        assertFalse(g.isDirty());
        assertFalse(g.render().contains("@I10@"));
        assertTrue(run("add child Maria").contains("added Maria I10"), "and the next id is still free");
    }

    @Test
    void idsFollowTheFilesStyle() {
        var text = Fixtures.COUSINS.replaceAll("@I(\\d+)@", "@P$1@").replaceAll("@F(\\d+)@", "@FAM$1@");
        load(Genealogy.of(text));
        var r = run("cd P9; add child Maria");
        assertTrue(r.contains("added Maria P10"), r);
        assertTrue(r.contains("FAM5"), r);
    }

    @Test
    void addedPeopleSaveAndReload() throws Exception {
        var file = dir.resolve("t.ged");
        Files.writeString(file, Fixtures.COUSINS);
        load(Genealogy.load(file));
        run("add spouse \"Juan /Pérez/\" sex=M; add child \"Rosa Pérez\" sex=F; save");
        var again = Genealogy.load(file);
        assertTrue(again.warnings().isEmpty(), again.warnings().toString());
        var rosa = again.person("I11").orElseThrow();
        assertEquals("Rosa Pérez", rosa.name().display());
        assertEquals(List.of("I10", "I9"), again.parents(rosa).stream().map(Person::key).toList());
        assertTrue(Files.readString(file).endsWith("0 @I10@ INDI\n1 NAME Juan /Pérez/\n1 SEX M\n1 FAMS @F5@\n"
                + "0 @F5@ FAM\n1 HUSB @I10@\n1 WIFE @I9@\n1 CHIL @I11@\n"
                + "0 @I11@ INDI\n1 NAME Rosa /Pérez/\n1 SEX F\n1 FAMC @F5@\n0 TRLR\n"), "new records go before the trailer, in order");
    }

    @Test
    void completionOffersTheRelations() {
        var c = shell.completer();
        var line = "add ch";
        assertEquals(List.of("child", "children"), c.complete(line, line.length()).candidates().stream().map(Completer.Candidate::text).toList());
    }
}
