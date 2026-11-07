package ged.shell;

import ged.Fixtures;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tab completion on the cousins fixture, standing at Elena Rossi (I9):
/// father Luca Rossi (I7), mother Sofia Verdi (I8); Luca's parents are
/// Marco Rossi (I3) and José Pérez (I5).
class CompletionTest {
    static final String TAB = "\t", LEFT = "\u001B[D";

    private Shell shell;
    private Completer completer;
    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        var g = Fixtures.cousins();
        var sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        shell = new Shell(new Navigator(new FileSystem(g), g.person("I9")), sink, sink, Style.PLAIN);
        completer = shell.completer();
    }

    /// Candidates for the end of `line`, as `text` + `suffix`.
    private List<String> complete(String line) {
        return completer.complete(line, line.length()).candidates().stream()
                .map(c -> c.text() + c.suffix()).toList();
    }

    /// Lines read by the editor (with completion) from `keys`.
    private List<String> type(String keys) throws Exception {
        var editor = new LineEditor(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(screen, true, StandardCharsets.UTF_8), History.inMemory(), () -> 80, completer);
        var lines = new ArrayList<String>();
        String line;
        while ((line = editor.readLine("> ")) != null) lines.add(line);
        return lines;
    }

    private String screen() {
        return screen.toString(StandardCharsets.UTF_8);
    }

    @Test
    void commandNames() {
        assertEquals(List.of("tree "), complete("tr"));
        assertEquals(List.of("cat ", "cd ", "chart "), complete("c"));
        assertTrue(complete("").containsAll(List.of("ls ", "ll ", "help ", "exit ", "save ")));
        assertEquals(List.of("tree "), complete("help tr"));
        assertEquals(List.of("ls "), complete("pwd; ls"), "after ;, a new command");
    }

    @Test
    void entriesOfTheCurrentAndNamedDirectories() {
        assertEquals(List.of("father/", "families/"), complete("cd f"));
        assertEquals(List.of("father/children/"), complete("cd father/ch"));
        assertEquals(List.of("/people/"), complete("ls /pe"));
        assertEquals(List.of("~/"), complete("cd ~"));
        assertEquals(List.of("father/../"), complete("cd father/.."));
        assertTrue(complete("cd father/").containsAll(List.of("father/father/", "father/mother/", "father/spouses/")));
        assertEquals(List.of("/people/I1/"), complete("show /people/i1"));
        assertEquals(List.of("father/"), complete("ll fat"), "aliases complete like their command");
        assertEquals(List.of(), complete("cd nowhere/"));
    }

    @Test
    void namesCompleteToTheirPath() {
        assertEquals(List.of("mother/"), complete("cd sofia"));
        assertEquals(List.of("father/"), complete("cd rossi"), "relatives first: only the father among them");
        assertEquals(List.of("I5/"), complete("cd jose"), "then everyone, accent-insensitive");
        assertEquals(List.of("father/mother/"), complete("show father/perez"));
        assertEquals(List.of("I5/"), complete("cd 'jos"));
        assertEquals(List.of("F1/"), complete("cd f1"), "ids from anywhere");
    }

    @Test
    void optionsAndTheirValues() {
        assertEquals(List.of("--theme "), complete("chart --th"));
        assertEquals(List.of("baroque "), complete("chart --theme ba"));
        assertEquals(List.of("--theme=sephirot "), complete("chart --theme=s"));
        assertEquals(List.of("en ", "es "), complete("chart --lang "));
        assertEquals(List.of("birth ", "birthday "), complete("ls -s bi"));
        assertEquals(List.of("birth ", "birthday "), complete("ls -rs bi"));
        assertEquals(List.of(), complete("tree -n "), "a number: nothing to offer");
        assertEquals(List.of("father/"), complete("find --ancestors-of fat"));
        assertEquals(List.of(), complete("find fa"), "find takes names, not paths");
        assertEquals(List.of(), complete("# cd fa"));
    }

    @Test
    void tabCompletesInTheEditor() throws Exception {
        assertEquals(List.of("cd father/", "chart --theme baroque "), type("cd fat" + TAB + "\rchart --theme ba" + TAB + "\r"));
        assertEquals(List.of("cd I5/"), type("cd jose" + TAB + "\r"));
    }

    @Test
    void tabExtendsAsFarAsTheChoicesAgreeThenLists() throws Exception {
        assertEquals(List.of("ls -s birth"), type("ls -s bi" + TAB + "\r"));
        screen.reset();
        type("ls -s birth" + TAB + "\r");
        assertTrue(screen().matches("(?s).*\\nbirth +birthday\\r\\n.*"), screen());
        screen.reset();
        type("cd father/" + TAB + "\r");
        assertTrue(screen().matches("(?s).*\\nmother/ +I5  José Pérez.*\\nchildren/\\r\\n.*"), screen());
    }

    @Test
    void tabMidLineKeepsWhatFollows() throws Exception {
        assertEquals(List.of("cd father/mother"), type("cd fat/mother" + LEFT.repeat(7) + TAB + "\r"));
    }

    @Test
    void noMatchRingsTheBell() throws Exception {
        assertEquals(List.of("cd zz"), type("cd zz" + TAB + "\r"));
        assertTrue(screen().contains("\u0007"));
    }
}
