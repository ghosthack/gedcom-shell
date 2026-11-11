package ged.shell;

import ged.model.Genealogy;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestFixesTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private Shell shell(Genealogy g) {
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        return new Shell(new Navigator(new FileSystem(g), g.person("I1")), ps, ps, Style.PLAIN);
    }

    private String said() { return out.toString(StandardCharsets.UTF_8); }

    @Test
    void editingANameWithoutASurnameAddsNoSlashes() {
        var g = Genealogy.of("0 HEAD\n0 @I1@ INDI\n1 NAME John Smith\n0 TRLR\n");
        shell(g).execute("edit given=\"Jon Smith\"");
        assertEquals("Jon Smith", g.person("I1").orElseThrow().node().text("NAME"));
    }

    @Test
    void aSpanishChartOfSomeoneUnnamedIsTitledInSpanish() throws Exception {
        var g = Genealogy.of("0 HEAD\n0 @I1@ INDI\n1 BIRT\n2 DATE 1900\n0 TRLR\n");
        var file = dir.resolve("x.html");
        shell(g).execute("chart --lang es -o " + file);
        var html = Files.readString(file);
        assertTrue(html.contains("(sin nombre)"), said());
        assertFalse(html.contains("(unnamed)"));
    }

    @Test
    void tildeIsTheHomeDirectory() {
        var home = System.getProperty("user.home");
        assertEquals(home + "/x.html", Options.home("~/x.html"));
        assertEquals(home, Options.home("~"));
        assertEquals("~x/y.html", Options.home("~x/y.html"));
        assertEquals("a/~/b", Options.home("a/~/b"));
    }

    @Test
    void chartWritesBesideADotDotPath() throws Exception {
        var g = Genealogy.of("0 HEAD\n0 @I1@ INDI\n1 NAME A /B/\n1 BIRT\n2 DATE 1900\n0 TRLR\n");
        Files.createDirectories(dir.resolve("sub"));
        shell(g).execute("chart -o " + dir + "/sub/../up.html");
        assertTrue(Files.exists(dir.resolve("up.html")), said());
    }

    @Test
    void aSmallScaleAskedForIsNotWarnedAbout() {
        var g = Genealogy.of("0 HEAD\n0 @I1@ INDI\n1 NAME A /B/\n1 BIRT\n2 DATE 1900\n0 TRLR\n");
        assertTrue(shell(g).execute("chart --scale 0.5 -o " + dir + "/small.png"), said());
        assertFalse(said().contains("hard to read"), said());
    }

    @Test
    void anArrowKeyArrivingInTwoPiecesIsStillAnArrowKey() throws Exception {
        // a slow link: the rest of the sequence shows up a moment after Esc
        var keys = new ByteArrayInputStream("ab\u001B[Dx\r".getBytes(StandardCharsets.UTF_8)) {
            private int asked;
            @Override public synchronized int available() { return asked++ < 3 ? 0 : super.available(); }
        };
        assertEquals("axb", line(keys, 80, new ByteArrayOutputStream()));
    }

    private String line(java.io.InputStream in, int columns, ByteArrayOutputStream screen) throws Exception {
        return new LineEditor(in, new PrintStream(screen, true, StandardCharsets.UTF_8), History.inMemory(), () -> columns).readLine("> ");
    }

    @Test
    void aKeyAfterALoneEscIsKept() throws Exception {
        // a terminal: nothing more is waiting after Esc
        var keys = new ByteArrayInputStream("a\u001Bxb\r".getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int available() { return 0; }
        };
        assertEquals("axb", line(keys, 80, new ByteArrayOutputStream()));
    }

    @Test
    void aCharacterCutShortDoesNotEatEnter() throws Exception {
        var keys = new ByteArrayInputStream(new byte[] {'a', (byte) 0xC3, '\r', 'b', '\r'});
        assertEquals("a�", line(keys, 80, new ByteArrayOutputStream()));
    }

    @Test
    void aWideCharacterWrapsWholeAndTheCursorFollows() throws Exception {
        var screen = new ByteArrayOutputStream();
        // 10 columns: "> abcdefg" fills 9 cells, so 中 moves to the next row and 文 follows it
        var keys = new ByteArrayInputStream("abcdefg中文\u001B[D\r".getBytes(StandardCharsets.UTF_8));
        assertEquals("abcdefg中文", line(keys, 10, screen));
        var drawn = screen.toString(StandardCharsets.UTF_8).replace("\u001B", "ESC");
        // after ←, the cursor sits on 文: row 1, column 2
        assertTrue(drawn.contains("\rESC[2C"), drawn);
        assertFalse(drawn.contains("ESC[1A\rESC[9C"), drawn);
    }
}
