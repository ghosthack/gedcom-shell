package ged.gedcom;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/// One GEDCOM line and its sub-structures. `CONT`/`CONC` lines are folded
/// into [#value()] at parse time, so consumers never see them. (GEDCOM 7
/// dropped `CONC`; see [#write(StringBuilder, String, boolean)].)
///
/// A parsed node remembers its source lines; until it is modified,
/// [#write] reproduces them byte-for-byte, so saving touches only the
/// lines that were actually edited.
public final class Node {
    /// GEDCOM 5.5 caps lines at 255 chars; split long values well before.
    private static final int MAX_VALUE = 200;

    private final int level;
    private final String xref;
    private final String tag;
    private String value;
    private final List<Node> children = new ArrayList<>();
    /// Original own line plus its CONT/CONC lines; null once modified.
    private List<String> source;
    /// Parallel to `source`: the child line each CONT/CONC line came after
    /// in the file, or null if it came straight after this node's own lines.
    /// A file can put one after a sub-line (`2 SOUR`, then `2 CONT`); this
    /// keeps it there when the record is written back.
    private List<Node> sourceAfter;

    Node(int level, String xref, String tag, String value, String sourceLine) {
        this.level = level;
        this.xref = xref;
        this.tag = tag;
        this.value = value;
        this.source = sourceLine == null ? null : new ArrayList<>(List.of(sourceLine));
        this.sourceAfter = sourceLine == null ? null : new ArrayList<>(java.util.Collections.singletonList(null));
    }

    /// A new, empty level-0 record, e.g. `0 @F12@ FAM`.
    public static Node record(String xref, String tag) {
        return new Node(0, xref, tag, "", null);
    }

    public int level() { return level; }
    /// Cross-reference id of a record (e.g. `@I1@`), or `null`.
    public String xref() { return xref; }
    public String tag() { return tag; }
    public String value() { return value; }
    public List<Node> children() { return List.copyOf(children); }

    void append(String text, String sourceLine) {
        value = value + text;
        if (source != null) {
            source.add(sourceLine);
            sourceAfter.add(children.isEmpty() ? null : children.getLast());
        }
    }

    void add(Node child) { children.add(child); }

    // ---- editing ----------------------------------------------------------

    public void setValue(String value) {
        if (value.equals(this.value)) return;
        this.value = value;
        this.source = null;
        this.sourceAfter = null;
    }

    /// Creates a child line `level+1 TAG value` at `index` (clamped).
    public Node insert(int index, String tag, String value) {
        var child = new Node(level + 1, null, tag, value, null);
        children.add(Math.max(0, Math.min(index, children.size())), child);
        return child;
    }

    /// Inserts after the last existing child whose tag is in `after`,
    /// or first if there is none: keeps new lines in conventional order.
    public Node insertAfter(List<String> after, String tag, String value) {
        int at = 0;
        for (int i = 0; i < children.size(); i++) {
            if (after.contains(children.get(i).tag)) at = i + 1;
        }
        return insert(at, tag, value);
    }

    /// Inserts before the first child whose tag is in `before`, or last.
    public Node insertBefore(List<String> before, String tag, String value) {
        for (int i = 0; i < children.size(); i++) {
            if (before.contains(children.get(i).tag)) return insert(i, tag, value);
        }
        return insert(children.size(), tag, value);
    }

    public void remove(Node child) {
        int i = children.indexOf(child);
        if (i < 0) return;
        children.remove(i);
        if (sourceAfter != null) {
            // continuation lines that followed it now follow its predecessor
            var before = i == 0 ? null : children.get(i - 1);
            sourceAfter.replaceAll(n -> n == child ? before : n);
        }
    }

    // ---- queries ----------------------------------------------------------

    /// GEDCOM 7's explicit null pointer: `1 HUSB @VOID@` says "no one".
    public static final String VOID = "@VOID@";

    /// True for a `@VOID@` pointer, which links to nothing.
    public boolean isVoid() {
        return value.equals(VOID);
    }

    public boolean isPointer() {
        return value.length() > 2 && value.startsWith("@") && value.endsWith("@") && !value.startsWith("@#");
    }

    public Stream<Node> all(String tag) {
        return children.stream().filter(c -> c.tag.equals(tag));
    }

    public Optional<Node> first(String tag) {
        return all(tag).findFirst();
    }

    /// Children with `tag` that point somewhere: `@VOID@` pointers are left out.
    public Stream<Node> links(String tag) {
        return all(tag).filter(c -> !c.isVoid());
    }

    /// Notes in file order: `NOTE` (text, or a pointer to a NOTE record)
    /// and GEDCOM 7's `SNOTE` (a pointer to a shared note record).
    public Stream<Node> notes() {
        return children.stream().filter(c -> (c.tag.equals("NOTE") || c.tag.equals("SNOTE")) && !c.isVoid());
    }

    /// Value of the first child with `tag`, or `""`.
    public String text(String tag) {
        return first(tag).map(Node::value).orElse("");
    }

    // ---- output -----------------------------------------------------------

    /// Renders this node and its descendants as GEDCOM 5.5 lines.
    public void write(StringBuilder out, String newline) {
        write(out, newline, true);
    }

    /// Renders this node and its descendants as GEDCOM lines. With `conc`
    /// (GEDCOM 5.5/5.5.1), long regenerated values are split over `CONC`
    /// lines; without it (GEDCOM 7, which has no `CONC`), each line of a
    /// value is written whole. Unmodified lines are copied as read either way.
    public void write(StringBuilder out, String newline, boolean conc) {
        if (source == null) {
            writeOwnLines(out, newline, conc);
            for (var c : children) c.write(out, newline, conc);
            return;
        }
        writeSource(out, newline, null);
        for (var c : children) {
            c.write(out, newline, conc);
            writeSource(out, newline, c);
        }
    }

    /// The original lines placed after `after` (null: own line and the
    /// continuation lines straight after it).
    private void writeSource(StringBuilder out, String newline, Node after) {
        for (int i = 0; i < source.size(); i++) {
            if (sourceAfter.get(i) == after) out.append(source.get(i)).append(newline);
        }
    }

    private void writeOwnLines(StringBuilder out, String newline, boolean conc) {
        var head = new StringBuilder().append(level);
        if (xref != null) head.append(' ').append(xref);
        head.append(' ').append(tag);
        var lines = value.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            var prefix = i == 0 ? head.toString() : (level + 1) + " CONT";
            var chunks = conc ? chunks(lines[i]) : List.of(lines[i]);
            out.append(prefix);
            if (!chunks.getFirst().isEmpty()) out.append(' ').append(chunks.getFirst());
            out.append(newline);
            for (var c : chunks.subList(1, chunks.size())) {
                out.append(level + 1).append(" CONC ").append(c).append(newline);
            }
        }
    }

    /// Splits for CONC, never right before or after a space (some readers
    /// trim leading/trailing spaces from CONC values).
    private static List<String> chunks(String line) {
        var out = new ArrayList<String>();
        var rest = line;
        while (rest.length() > MAX_VALUE) {
            int cut = MAX_VALUE;
            while (cut > MAX_VALUE / 2 && (rest.charAt(cut) == ' ' || rest.charAt(cut - 1) == ' ')) cut--;
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        out.add(rest);
        return out;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        write(sb, "\n");
        return sb.toString();
    }
}
