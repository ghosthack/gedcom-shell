package ged.shell;

import ged.text.Style;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

/// A small readline: reads keys from a terminal already in raw mode and
/// keeps the edited line drawn after the prompt.
///
/// | Key                    | Action                                  |
/// |------------------------|-----------------------------------------|
/// | ↑ / ↓, Ctrl-P / Ctrl-N | previous / next command in history      |
/// | ← / →, Ctrl-B / Ctrl-F | move the cursor                         |
/// | Home / End, Ctrl-A / E | start / end of line                     |
/// | Backspace / Delete     | delete before / under the cursor        |
/// | Ctrl-U / Ctrl-K        | delete to start / end of line           |
/// | Ctrl-W                 | delete the previous word                |
/// | Ctrl-L                 | clear the screen                        |
/// | Ctrl-C                 | discard the line                        |
/// | Ctrl-D                 | end of input on an empty line           |
///
/// Lines wider than the terminal wrap; the editor tracks which row the
/// cursor is on so redraws stay in place.
public final class LineEditor {
    private final InputStream in;
    private final PrintStream out;
    private final History history;
    private final IntSupplier columns;

    // per-line state
    private final StringBuilder buf = new StringBuilder();
    private int cursor;       // index into buf (UTF-16 units, kept on code point boundaries)
    private int histIndex;    // == history.size() while editing the new line
    private String draft;     // the new line, saved while browsing history
    private String prompt;
    private int cursorRow;    // terminal row of the cursor, relative to the prompt's first row

    public LineEditor(InputStream in, PrintStream out, History history, IntSupplier columns) {
        this.in = in;
        this.out = out;
        this.history = history;
        this.columns = columns;
    }

    /// Returns the line (without newline), `""` after Ctrl-C, or `null` at
    /// end of input. The line is added to history.
    public String readLine(String prompt) throws IOException {
        this.prompt = prompt;
        buf.setLength(0);
        cursor = 0;
        histIndex = history.size();
        draft = "";
        cursorRow = 0;
        out.print(prompt);
        out.flush();

        while (true) {
            int b = in.read();
            switch (b) {
                case -1 -> {
                    if (buf.isEmpty()) return finish(null);
                    return finish(buf.toString());
                }
                case '\r', '\n' -> {
                    var line = buf.toString();
                    history.add(line);
                    return finish(line);
                }
                case 3 -> { // Ctrl-C
                    moveToEnd();
                    out.print("^C");
                    return finish("");
                }
                case 4 -> { // Ctrl-D
                    if (buf.isEmpty()) return finish(null);
                    deleteForward();
                }
                case 1 -> setCursor(0);                      // Ctrl-A
                case 5 -> setCursor(buf.length());           // Ctrl-E
                case 2 -> setCursor(prevBoundary(cursor));   // Ctrl-B
                case 6 -> setCursor(nextBoundary(cursor));   // Ctrl-F
                case 16 -> recall(-1);                       // Ctrl-P
                case 14 -> recall(+1);                       // Ctrl-N
                case 127, 8 -> deleteBackward();             // Backspace
                case 11 -> edit(() -> buf.setLength(cursor));                          // Ctrl-K
                case 21 -> edit(() -> { buf.delete(0, cursor); cursor = 0; });         // Ctrl-U
                case 23 -> edit(this::deleteWord);                                     // Ctrl-W
                case 12 -> { out.print("\u001B[H\u001B[2J"); cursorRow = 0; redraw(); } // Ctrl-L
                case 27 -> escape();
                case '\t' -> insert("  ");
                default -> {
                    if (b >= 0x20) insert(decodeUtf8(b));
                }
            }
        }
    }

    private String finish(String line) {
        moveToEnd();
        out.print("\r\n");
        out.flush();
        return line;
    }

    // ---- key handling -----------------------------------------------------

    /// ESC [ A, ESC O A (application mode), ESC [ 3 ~, ESC [ 1 ; 5 C ...
    private void escape() throws IOException {
        int b = in.read();
        if (b != '[' && b != 'O') return; // lone ESC or Alt-key: ignore
        var params = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && (c >= '0' && c <= '9' || c == ';')) params.append((char) c);
        var p = params.toString();
        switch (c) {
            case 'A' -> recall(-1);
            case 'B' -> recall(+1);
            case 'C' -> setCursor(p.endsWith(";5") || p.endsWith(";3") ? wordRight() : nextBoundary(cursor));
            case 'D' -> setCursor(p.endsWith(";5") || p.endsWith(";3") ? wordLeft() : prevBoundary(cursor));
            case 'H' -> setCursor(0);
            case 'F' -> setCursor(buf.length());
            case '~' -> {
                switch (p) {
                    case "1", "7" -> setCursor(0);
                    case "4", "8" -> setCursor(buf.length());
                    case "3" -> deleteForward();
                    default -> { }
                }
            }
            default -> { }
        }
    }

    private String decodeUtf8(int first) throws IOException {
        int extra = first >= 0xF0 ? 3 : first >= 0xE0 ? 2 : first >= 0xC0 ? 1 : 0;
        if (first >= 0x80 && extra == 0) return "�";
        var bytes = new ByteArrayOutputStream();
        bytes.write(first);
        for (int i = 0; i < extra; i++) {
            int b = in.read();
            if (b < 0) break;
            bytes.write(b);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private void recall(int direction) {
        int target = histIndex + direction;
        if (target < 0 || target > history.size()) return;
        if (histIndex == history.size()) draft = buf.toString();
        histIndex = target;
        var text = target == history.size() ? draft : history.get(target);
        buf.setLength(0);
        buf.append(text);
        cursor = buf.length();
        redraw();
    }

    private void insert(String s) {
        buf.insert(cursor, s);
        cursor += s.length();
        redraw();
    }

    private void deleteBackward() {
        if (cursor == 0) return;
        int from = prevBoundary(cursor);
        edit(() -> { buf.delete(from, cursor); cursor = from; });
    }

    private void deleteForward() {
        if (cursor >= buf.length()) return;
        int to = nextBoundary(cursor);
        edit(() -> buf.delete(cursor, to));
    }

    private void deleteWord() {
        int from = wordLeft();
        buf.delete(from, cursor);
        cursor = from;
    }

    private int wordLeft() {
        int i = cursor;
        while (i > 0 && buf.charAt(i - 1) == ' ') i--;
        while (i > 0 && buf.charAt(i - 1) != ' ') i--;
        return i;
    }

    private int wordRight() {
        int i = cursor;
        while (i < buf.length() && buf.charAt(i) == ' ') i++;
        while (i < buf.length() && buf.charAt(i) != ' ') i++;
        return i;
    }

    private int prevBoundary(int i) {
        return i == 0 ? 0 : buf.offsetByCodePoints(i, -1);
    }

    private int nextBoundary(int i) {
        return i >= buf.length() ? buf.length() : buf.offsetByCodePoints(i, 1);
    }

    private void edit(Runnable change) {
        change.run();
        redraw();
    }

    private void setCursor(int i) {
        if (i == cursor) return;
        cursor = i;
        placeCursor();
    }

    // ---- drawing ----------------------------------------------------------

    private int cols() {
        return Math.max(10, columns.getAsInt());
    }

    /// Repaints prompt + buffer from the prompt's first row.
    private void redraw() {
        int cols = cols();
        if (cursorRow > 0) out.print("\u001B[" + cursorRow + "A");
        out.print("\r\u001B[J");
        out.print(prompt);
        out.print(buf);
        int end = Style.width(prompt) + Style.width(buf.toString());
        // At an exact multiple of the width the terminal defers wrapping;
        // force it so the cursor really is on the next row.
        if (end > 0 && end % cols == 0) out.print("\r\n");
        cursorRow = end / cols;
        placeCursor();
    }

    /// Moves the terminal cursor from wherever it is (row `cursorRow`) to
    /// the position of `cursor` in the buffer.
    private void placeCursor() {
        int cols = cols();
        int at = Style.width(prompt) + Style.width(buf.substring(0, cursor));
        int row = at / cols;
        int col = at % cols;
        if (row < cursorRow) out.print("\u001B[" + (cursorRow - row) + "A");
        if (row > cursorRow) out.print("\u001B[" + (row - cursorRow) + "B");
        out.print("\r");
        if (col > 0) out.print("\u001B[" + col + "C");
        cursorRow = row;
        out.flush();
    }

    private void moveToEnd() {
        cursor = buf.length();
        placeCursor();
    }
}
