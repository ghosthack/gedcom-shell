package ged.gedcom;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/// One GEDCOM line and its sub-structures. `CONT`/`CONC` lines are folded
/// into [#value()] at parse time, so consumers never see them.
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

    Node(int level, String xref, String tag, String value, String sourceLine) {
        this.level = level;
        this.xref = xref;
        this.tag = tag;
        this.value = value;
        this.source = sourceLine == null ? null : new ArrayList<>(List.of(sourceLine));
    }

    public int level() { return level; }
    /// Cross-reference id of a record (e.g. `@I1@`), or `null`.
    public String xref() { return xref; }
    public String tag() { return tag; }
    public String value() { return value; }
    public List<Node> children() { return List.copyOf(children); }

    void append(String text, String sourceLine) {
        value = value + text;
        if (source != null) source.add(sourceLine);
    }

    void add(Node child) { children.add(child); }

    // ---- editing ----------------------------------------------------------

    public void setValue(String value) {
        if (value.equals(this.value)) return;
        this.value = value;
        this.source = null;
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
        children.remove(child);
    }

    // ---- queries ----------------------------------------------------------

    public boolean isPointer() {
        return value.length() > 2 && value.startsWith("@") && value.endsWith("@") && !value.startsWith("@#");
    }

    public Stream<Node> all(String tag) {
        return children.stream().filter(c -> c.tag.equals(tag));
    }

    public Optional<Node> first(String tag) {
        return all(tag).findFirst();
    }

    /// Value of the first child with `tag`, or `""`.
    public String text(String tag) {
        return first(tag).map(Node::value).orElse("");
    }

    // ---- output -----------------------------------------------------------

    /// Renders this node and its descendants as GEDCOM lines.
    public void write(StringBuilder out, String newline) {
        if (source != null) {
            for (var line : source) out.append(line).append(newline);
        } else {
            writeOwnLines(out, newline);
        }
        for (var c : children) c.write(out, newline);
    }

    private void writeOwnLines(StringBuilder out, String newline) {
        var head = new StringBuilder().append(level);
        if (xref != null) head.append(' ').append(xref);
        head.append(' ').append(tag);
        var lines = value.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            var prefix = i == 0 ? head.toString() : (level + 1) + " CONT";
            var chunks = chunks(lines[i]);
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
