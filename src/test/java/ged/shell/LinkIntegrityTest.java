package ged.shell;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Links stay two-sided and act on the family the user means, whatever
/// route led to the current directory. The John Doe fixture has John (I0)
/// and a sibling, children of John Senior (I2) and Jane (I3) in F0; Jane is
/// a child of Robert (I4) and Elizabeth (I5) in F1.
class LinkIntegrityTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Path file;
    private Genealogy g;
    private Shell shell;

    @BeforeEach
    void setUp() throws Exception {
        file = dir.resolve("doe.ged");
        try (var in = getClass().getResourceAsStream("/genealogyJohnDoe.ged")) {
            Files.write(file, in.readAllBytes());
        }
        g = Genealogy.load(file);
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        shell = new Shell(new Navigator(new FileSystem(g), g.person("I0")), ps, ps, Style.PLAIN);
    }

    private String run(String line) {
        out.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aDeletedFamilyCantBeLinkedInto() throws Exception {
        run("add person \"A A\" sex=M; add person \"B B\" sex=F; cd I6; link I7 spouses; cd /families/F2");
        assertTrue(run("unlink wife").contains("was deleted"));
        assertEquals("/families\n", run("pwd"), "the deleted family is no place to stay");
        run("link I4 children; save");
        var text = Files.readString(file);
        assertFalse(text.contains("@F2@"), "nothing points at the deleted family");
    }

    @Test
    void unlinkUsesTheFamilyThatConnectsNow() {
        // arrive at John Senior as John's father, then give John a new father and make John Senior's child again
        run("cd I0; cd father; add person \"New Dad\" sex=M; unlink /people/I0/father; link I6 /people/I0/father");
        run("link I0 children");
        var said = run("unlink children/I0");
        var john = g.person("I0").orElseThrow();
        assertEquals(java.util.List.of("@F0@"), john.childOf(), said);
        assertEquals("I6", g.father(john).orElseThrow().key());
    }

    @Test
    void relativeUnlinkKeepsWorkingAfterTheRouteIsCut() {
        run("cd I0; cd father");
        run("unlink children/I0");
        assertEquals("/people/I2\n", run("pwd"));
        assertTrue(run("unlink spouses/I3").contains("left F0"));
        assertTrue(run("cd ..; pwd").endsWith("/people\n"));
    }

    @Test
    void husbandAndWifeOfAPersonArePartnersNotParents() {
        run("cd I4");
        assertTrue(run("add person \"Zed Zed\" sex=F; link I6 wife").contains("use spouses"));
        assertTrue(g.familiesAsChild(g.person("I4").orElseThrow()).isEmpty());
        var said = run("add husband \"Bob Bob\"");
        assertTrue(said.contains("are now partners"), said);
        assertTrue(g.familiesAsChild(g.person("I4").orElseThrow()).isEmpty());
    }

    @Test
    void fatherOfAFamilyIsItsHusband() {
        run("add person \"Pa Pa\" sex=M; cd /families/F0; unlink husband");
        var said = run("link I6 /families/F0/father");
        assertTrue(said.contains("is now the husband in"), said);
        assertEquals("@I6@", g.family("F0").orElseThrow().husband().orElseThrow());
    }

    @Test
    void aPlaceIsNotARelationship() {
        run("cd I4; cd children/I3");
        for (var path : java.util.List.of(".", "-", "..", "father/..")) {
            assertTrue(run("unlink " + path).contains("is not a relationship"), path);
        }
        assertFalse(g.isDirty());
    }

    @Test
    void exitEndsAScript() {
        shell.scripted(true);
        var said = run("edit I0 sex=F; exit; ls /families");
        assertFalse(said.contains("HUSBAND"), said);
        out.reset();
        shell.warnIfDirty();
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("not saved"));
    }

    @Test
    void saveRefusesUnknownOptions() {
        assertTrue(run("save -x").contains("unknown option -x"));
        assertTrue(run("save --froce").contains("unknown option"));
    }
}
