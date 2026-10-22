package ged.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineEditorTest {
    static final String UP = "\u001B[A", DOWN = "\u001B[B", LEFT = "\u001B[D", RIGHT = "\u001B[C";
    static final String HOME = "\u001B[H", END = "\u001B[F", DEL = "\u001B[3~", BS = "\u007F";
    static final String CTRL_A = "\u0001", CTRL_E = "\u0005", CTRL_C = "\u0003", CTRL_D = "\u0004";
    static final String CTRL_U = "\u0015", CTRL_K = "\u000B", CTRL_W = "\u0017";

    @TempDir Path dir;
    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    /// Feeds `keys` and returns every line read until end of input.
    private List<String> type(History history, int columns, String keys) throws Exception {
        var editor = new LineEditor(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(screen, true, StandardCharsets.UTF_8), history, () -> columns);
        var lines = new ArrayList<String>();
        String line;
        while ((line = editor.readLine("> ")) != null) lines.add(line);
        return lines;
    }

    private List<String> type(String keys) throws Exception {
        return type(History.inMemory(), 80, keys);
    }

    @Test
    void upAndDownWalkHistory() throws Exception {
        var h = History.inMemory();
        // history before the last line: ls, cd mother, pwd, cd mother (the repeated pwd is stored once)
        // ↑↑↑↑ reaches "ls", ↓↓ comes back down to "pwd"
        assertEquals(List.of("ls", "cd mother", "pwd", "pwd", "cd mother", "pwd"),
                type(h, 80, "ls\rcd mother\rpwd\r" + UP + "\r" + UP + UP + "\r" + UP + UP + UP + UP + DOWN + DOWN + "\r"));
        assertEquals(List.of("ls", "cd mother", "pwd", "cd mother", "pwd"), h.entries());
    }

    @Test
    void downReturnsToTheUnfinishedLine() throws Exception {
        var h = History.inMemory();
        h.add("show");
        assertEquals(List.of("tree -a"), type(h, 80, "tree -a" + UP + DOWN + "\r"));
    }

    @Test
    void upStopsAtOldestAndDownAtNewest() throws Exception {
        var h = History.inMemory();
        h.add("first");
        h.add("second");
        assertEquals(List.of("first", ""), type(h, 80, UP + UP + UP + UP + "\r" + DOWN + DOWN + "\r"));
    }

    @Test
    void cursorMovementAndEditing() throws Exception {
        assertEquals(List.of("show father"), type("show fther" + LEFT + LEFT + LEFT + LEFT + "a\r"));
        assertEquals(List.of("xshow"), type("show" + CTRL_A + "x" + CTRL_E + "\r"));
        assertEquals(List.of("show"), type("shoow" + LEFT + LEFT + BS + "\r"));
        assertEquals(List.of("show"), type("showx" + HOME + RIGHT + RIGHT + RIGHT + RIGHT + DEL + END + "\r"));
        assertEquals(List.of("ls"), type("cd mother" + CTRL_U + "ls\r"));
        assertEquals(List.of("cd "), type("cd mother" + LEFT + LEFT + LEFT + LEFT + LEFT + LEFT + CTRL_K + "\r"));
        assertEquals(List.of("cd "), type("cd mother" + CTRL_W + "\r"));
        assertEquals(List.of("tree -a"), type("tree -x" + "\u001B[1;5D" + "\u001B[1;5C" + BS + "a\r"));
    }

    @Test
    void ctrlCDiscardsAndCtrlDEnds() throws Exception {
        assertEquals(List.of("", "pwd"), type("cd somewh" + CTRL_C + "pwd\r" + CTRL_D + "ignored\r"));
        assertTrue(screen.toString(StandardCharsets.UTF_8).contains("^C"));
        // Ctrl-D on a non-empty line deletes under the cursor instead
        assertEquals(List.of("ab"), type("abc" + LEFT + CTRL_D + "\r"));
    }

    @Test
    void endOfInputMidLineReturnsTheLine() throws Exception {
        assertEquals(List.of("pwd"), type("pwd"));
        assertNull(new LineEditor(new ByteArrayInputStream(new byte[0]),
                new PrintStream(screen, true, StandardCharsets.UTF_8), History.inMemory(), () -> 80).readLine("> "));
    }

    @Test
    void multiByteCharactersEditAsOneKey() throws Exception {
        assertEquals(List.of("find José"), type("find Joséx" + BS + "\r"));
        assertEquals(List.of("find Jos"), type("find José" + BS + "\r"));
        assertEquals(List.of("find Jxé"), type("find Jé" + LEFT + "x\r"));
    }

    @Test
    void wrappedLinesRedrawFromTheirFirstRow() throws Exception {
        // 10 columns: "> " + 12 chars spans two rows; moving home goes up one row
        type(History.inMemory(), 10, "abcdefghijkl" + CTRL_A + "\r");
        var out = screen.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("\u001B[1A\r\u001B[2C"), "cursor moved up a row to column 2: " + out.replace("\u001B", "ESC"));
    }

    @Test
    void historyPersistsAndSkipsSpacePrefixed() throws Exception {
        var file = dir.resolve("hist");
        var h = new History(file);
        type(h, 80, "ls\r secret\rpwd\r");
        assertEquals(List.of("ls", "pwd"), Files.readAllLines(file));
        assertEquals(List.of("ls", "pwd"), new History(file).entries());
        assertEquals(List.of("pwd"), type(new History(file), 80, UP + "\r"));
    }

    @Test
    void historyFileIsTrimmed() throws Exception {
        var file = dir.resolve("hist");
        var lines = new ArrayList<String>();
        for (int i = 0; i < 2 * History.MAX + 5; i++) lines.add("cmd " + i);
        Files.write(file, lines);
        var h = new History(file);
        assertEquals(History.MAX, h.size());
        assertEquals("cmd " + (2 * History.MAX + 4), h.get(History.MAX - 1));
        assertEquals(History.MAX, Files.readAllLines(file).size());
    }
}
