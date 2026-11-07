package ged.shell;

import ged.model.Genealogy;
import ged.model.Person;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pietro had Anna and Marco with Lucia (F1), then Giulia with Rosa (F2).
class HalfSiblingTest {
    static final String GED = """
            0 HEAD
            0 @I1@ INDI
            1 NAME Pietro /Conti/
            1 SEX M
            1 FAMS @F1@
            1 FAMS @F2@
            0 @I2@ INDI
            1 NAME Lucia /Bassi/
            1 SEX F
            1 FAMS @F1@
            0 @I3@ INDI
            1 NAME Rosa /Galli/
            1 SEX F
            1 FAMS @F2@
            0 @I4@ INDI
            1 NAME Anna /Conti/
            1 SEX F
            1 FAMC @F1@
            0 @I5@ INDI
            1 NAME Marco /Conti/
            1 SEX M
            1 FAMC @F1@
            0 @I6@ INDI
            1 NAME Giulia /Conti/
            1 SEX F
            1 FAMC @F2@
            0 @F1@ FAM
            1 HUSB @I1@
            1 WIFE @I2@
            1 CHIL @I4@
            1 CHIL @I5@
            0 @F2@ FAM
            1 HUSB @I1@
            1 WIFE @I3@
            1 CHIL @I6@
            0 TRLR
            """;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Genealogy g;
    private Shell shell;

    @BeforeEach
    void setUp() {
        g = Genealogy.of(GED);
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        shell = new Shell(new Navigator(new FileSystem(g), g.person("I4")), ps, ps, Style.PLAIN);
    }

    private String run(String line) {
        out.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8);
    }

    private Person p(String key) { return g.person(key).orElseThrow(); }

    private List<String> keys(List<Person> people) { return people.stream().map(Person::key).toList(); }

    @Test
    void halfSiblingsShareOneParentButNotAFamily() {
        assertEquals(List.of("I5"), keys(g.siblings(p("I4"))));
        assertEquals(List.of("I6"), keys(g.halfSiblings(p("I4"))));
        assertEquals(List.of("I4", "I5"), keys(g.halfSiblings(p("I6"))));
        assertTrue(g.siblings(p("I6")).isEmpty());
        assertTrue(g.halfSiblings(p("I1")).isEmpty(), "no parents, no half-siblings");
    }

    @Test
    void lsListsThemAfterFullSiblings() {
        var r = run("ls siblings");
        assertTrue(r.contains("brother") && r.contains("Marco"), r);
        assertTrue(r.contains("half-sister") && r.contains("Giulia"), r);
        assertTrue(r.indexOf("Marco") < r.indexOf("Giulia"), r);
        assertTrue(run("ls").contains("half-sister"), "and in the person's own listing");
        run("cd siblings/I6");
        assertTrue(run("pwd -P").contains("I6"));
    }

    @Test
    void showHasAHalfSiblingsSection() {
        var r = run("show");
        assertTrue(r.contains("Half-siblings"), r);
        assertTrue(r.contains("half-sister") && r.contains("Giulia"), r);
        assertFalse(run("show /people/I1").contains("Half-siblings"), "only when there are some");
    }

    @Test
    void unlinkingAHalfSiblingRemovesThemFromTheirFamily() {
        run("unlink siblings/I6");
        assertTrue(g.halfSiblings(p("I4")).isEmpty());
        assertTrue(g.familiesAsChild(p("I6")).isEmpty(), "Giulia left F2");
        assertTrue(g.family("F2").isPresent(), "Pietro and Rosa's family stays");
        assertEquals(List.of("I5"), keys(g.siblings(p("I4"))), "full siblings untouched");
    }
}
