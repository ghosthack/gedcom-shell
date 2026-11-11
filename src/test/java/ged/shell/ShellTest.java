package ged.shell;

import ged.Fixtures;
import ged.model.Genealogy;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellTest {
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private Shell shell;

    @BeforeEach
    void setUp() {
        load(Fixtures.cousins(), "I9");
    }

    private void load(Genealogy g, String home) {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        var nav = new Navigator(new FileSystem(g), g.person(home));
        shell = new Shell(nav, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Style.PLAIN);
    }

    private String run(String line) {
        out.reset();
        err.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8);
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void lsListsRelativesWithAddressablePaths() {
        var ls = run("ls");
        assertTrue(ls.contains("1  father  I7  father  Luca Rossi"), ls);
        assertTrue(ls.contains("2  mother  I8  mother  Sofia Verdi"), ls);
        assertTrue(ls.contains("groups: parents/ (2)"), ls);
    }

    @Test
    void lsSortKeepsRowNumbers() {
        var ls = run("ls /people -s birth -r");
        var lines = ls.lines().toList();
        assertTrue(lines.get(1).startsWith("9  I9"), ls);       // Elena, born 1910, first when reversed
        assertTrue(lines.getLast().contains("Paolo Verdi"), ls); // undated stays last even reversed
    }

    @Test
    void lsRootShowsDirectories() {
        var ls = run("ls /");
        assertTrue(ls.contains("people/"));
        assertTrue(ls.contains("9"), ls);
    }

    @Test
    void cdAndPwd() {
        assertEquals("/people/I9/father/father\n", run("cd father/father; pwd"));
        assertEquals("/people/I3\n", run("pwd -P"));
        assertEquals("/people/I9/father\n", run("cd ..; pwd"));
        assertEquals("/people/I9\n", run("cd; pwd"));
    }

    @Test
    void showPerson() {
        var s = run("show father/father");
        assertTrue(s.startsWith("Marco Rossi  I3\n"), s);
        assertTrue(s.contains("Born            12 JUN 1850, Genova, Italia"), s);
        assertTrue(s.contains("Occupation      Sailor"), s);
        assertTrue(s.contains("father        I1     Giovanni Rossi (1820–~1890)"), s);
        assertTrue(s.contains("sister        I4     Anna Rossi (b. 1852)"), s);
        assertTrue(s.contains("Went to sea\n  at fifteen."), s);
        assertTrue(s.contains("changed 16 JUN 2007 17:07:01"), s);
    }

    @Test
    void showAgeOnlyWhenComputable() {
        assertTrue(run("show").contains("Age             79 at death"));
        assertTrue(run("show @I1@").contains("~70 at death"));
        assertFalse(run("show @I3@").contains("Age"));
    }

    @Test
    void showRawAndFamily() {
        assertTrue(run("show -r @F4@").startsWith("0 @F4@ FAM\n1 HUSB @I7@\n"));
        var fam = run("show @F1@");
        assertTrue(fam.startsWith("Giovanni Rossi & Lucia Bianchi  F1"), fam);
        assertTrue(fam.contains("Married         1848"), fam);
    }

    @Test
    void showOnListingIsAnError() {
        run("show /people");
        assertTrue(errors().contains("is a listing; use ls"), errors());
    }

    @Test
    void treeDescendantsMarksRepeats() {
        var t = run("tree --all @I1@");
        assertTrue(t.startsWith("Giovanni Rossi (1820–~1890)  I1\n└── ∞ Lucia Bianchi (b. 1825)  I2  [F1 m. 1848]\n"), t);
        // Elena descends from Giovanni via both children, so appears once fully and once as ↻
        assertEquals(2, t.lines().filter(l -> l.contains("Elena Rossi")).count(), t);
        assertTrue(t.lines().anyMatch(l -> l.contains("Elena Rossi") && l.endsWith("↻")), t);
    }

    @Test
    void treeDepthLimits() {
        var t = run("tree -n 1 -S @I1@");
        assertEquals(3, t.lines().count(), t);
    }

    @Test
    void treeAncestorsLabelsGenerations() {
        var t = run("tree -a -n 3");
        assertTrue(t.contains("├── father: Luca Rossi"), t);
        assertTrue(t.contains("grandmother: José Pérez"), t);
        assertTrue(t.contains("great-grandfather: Giovanni Rossi"), t);
        assertTrue(t.contains("great-grandfather: Giovanni Rossi (1820–~1890)  I1 ↻"), t);
    }

    @Test
    void findByNameAndFilters() {
        var f = run("find rossi -b 1800..1860");
        assertTrue(f.contains("Giovanni Rossi") && f.contains("Marco Rossi") && f.contains("Anna Rossi"), f);
        assertFalse(f.contains("Luca"), f);
        assertTrue(f.contains("3 matches"), f);

        assertTrue(run("find perez").contains("José Pérez"));
        assertTrue(run("find -p espana").contains("José Pérez"));
        assertTrue(run("find -x F --descendants-of @I1@").contains("Sofia Verdi"));
        assertTrue(run("find --ancestors-of ~ -b ..1830").contains("2 matches"));
        assertTrue(run("find zzz").contains("no matches"));
    }

    @Test
    void foundIsBrowsable() {
        run("find rossi -s birth");
        assertEquals("/people/I1\n", run("cd /found/1; pwd -P"));
        assertTrue(run("ls /found").contains("Elena Rossi"));
    }

    @Test
    void yearRanges() {
        var d = ged.model.GedDate.parse("1850");
        assertTrue(FindCommand.range("1850").test(d));
        assertTrue(FindCommand.range("1800..1850").test(d));
        assertTrue(FindCommand.range("..1850").test(d));
        assertTrue(FindCommand.range("1850..").test(d));
        assertTrue(FindCommand.range("1800-1900").test(d));
        assertFalse(FindCommand.range("1851..").test(d));
        assertThrows(CommandException.class, () -> FindCommand.range("1900..1800"));
        assertThrows(CommandException.class, () -> FindCommand.range("abc"));
    }

    @Test
    void errorsAreReportedNotThrown() {
        assertFalse(shell.execute("frobnicate"));
        assertTrue(errors().contains("command not found"));
        assertFalse(shell.execute("cd nowhere"));
        assertTrue(errors().contains("cd: nowhere: not found"), errors());
        assertFalse(shell.execute("ls --bogus"));
        assertTrue(errors().contains("unknown option --bogus"));
        assertFalse(shell.execute("ls | head"));
        assertTrue(errors().contains("pipes"));
    }

    @Test
    void helpCoversEveryCommand() {
        var h = run("help");
        for (var c : List.of("ls", "cd", "pwd", "show", "tree", "find")) {
            assertTrue(h.contains("  " + c + " "), c);
            assertTrue(run("help " + c).startsWith(c), c);
        }
    }

    @Test
    void tokenizer() {
        assertEquals(List.of(List.of("cd", "john doe"), List.of("ls", "-l")), Tokenizer.split("cd \"john doe\"; ls -l # comment"));
        assertEquals(List.of(List.of("find", "o'brien")), Tokenizer.split("find o\\'brien"));
        assertEquals(List.of(List.of("find", "a|b")), Tokenizer.split("find 'a|b'"));
    }

    @Test
    void realSampleFiles() throws Exception {
        for (var name : List.of("habsburgRoyalty.ged", "romanovRoyalty.ged",
                "mediciFamily.ged", "genealogyJohnDoe.ged", "test_divorce.ged")) {
            var file = Path.of(getClass().getResource("/" + name).toURI());
            var g = Genealogy.load(file);
            load(g, g.people().getFirst().key());
            assertTrue(shell.execute("ls; show; tree -n 4; tree -a -n 4; ls /people -s birthday; show /"), name + ": " + errors());
            assertFalse(out.toString(StandardCharsets.UTF_8).contains("�"), name + " has decoding errors");
        }
    }
}
