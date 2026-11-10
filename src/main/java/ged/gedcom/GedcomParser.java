package ged.gedcom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/// Turns GEDCOM text into a forest of [Node]s, one root per level-0 record.
///
/// This layer knows nothing about people or families; it only understands
/// the line grammar `LEVEL [@XREF@] TAG [VALUE]`. Keeping the full tree
/// means no data is dropped and the raw record can always be shown.
public final class GedcomParser {
    private GedcomParser() {}

    private static final Pattern LINE = Pattern.compile("^\\s*(\\d{1,2})\\s+(?:(@[^@\\s]+@)\\s+)?(\\S+)(?: (.*))?$");

    /// How the file was laid out, so it can be written back unchanged.
    /// `rawRecords` is parallel to `records`: each record's exact text,
    /// every line with its own line ending, including any blank or
    /// unparseable lines that followed it. `trailers` (also parallel) is
    /// just those trailing lines, which stay at the end of the record when
    /// it is rewritten. `newline` is the ending new lines are written with.
    public record Layout(String prefix, List<String> rawRecords, List<String> trailers, String newline, boolean finalNewline) {}

    public record Result(List<Node> records, Charsets.Decoded encoding, List<String> warnings, Layout layout) {}

    public static Result parse(Path file) throws IOException {
        var decoded = Charsets.decode(Files.readAllBytes(file));
        var result = parse(decoded.text());
        return new Result(result.records(), decoded, result.warnings(), result.layout());
    }

    private static final Pattern EOL = Pattern.compile("\\r\\n|\\r|\\n");

    public static Result parse(String text) {
        var records = new ArrayList<Node>();
        var warnings = new ArrayList<String>();
        var stack = new ArrayDeque<Node>();
        boolean finalNewline = text.endsWith("\n") || text.endsWith("\r");
        var newline = text.contains("\r\n") ? "\r\n" : text.contains("\r") ? "\r" : "\n";

        // Each line keeps its own ending, so a file that mixes them is not
        // rewritten wholesale; a last line without one gets `newline`,
        // which `Genealogy.render` takes off again.
        var lines = new ArrayList<String>();
        var whole = new ArrayList<String>();
        var eol = EOL.matcher(text);
        int from = 0;
        while (eol.find()) {
            lines.add(text.substring(from, eol.start()));
            whole.add(text.substring(from, eol.end()));
            from = eol.end();
        }
        if (from < text.length()) {
            lines.add(text.substring(from));
            whole.add(text.substring(from) + newline);
        }

        var starts = new ArrayList<Integer>();
        // blank or unparseable lines seen since the last good line, and that line's node
        var junk = new ArrayList<String>();
        Node last = null;
        var lastGood = new ArrayList<Integer>(); // per record: index of its last parsed line
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            int lineNo = i + 1;
            if (line.isBlank()) {
                junk.add(whole.get(i));
                continue;
            }
            var m = LINE.matcher(line);
            if (!m.matches()) {
                warnings.add("line " + lineNo + ": unparseable: " + line.strip());
                junk.add(whole.get(i));
                continue;
            }
            int level = Integer.parseInt(m.group(1));
            String tag = m.group(3);
            String value = m.group(4) == null ? "" : m.group(4);

            while (!stack.isEmpty() && stack.peek().level() >= level) stack.pop();
            Node parent = stack.peek();

            if (level > 0 && parent == null) {
                warnings.add("line " + lineNo + ": orphaned level " + level + " line");
                junk.add(whole.get(i));
                continue;
            }
            // Junk in the middle of a record stays where it was, after the
            // line it followed; junk after a record's last line trails the record.
            if (level > 0 && last != null) junk.forEach(last::addJunk);
            junk.clear();

            if (parent != null && (tag.equals("CONT") || tag.equals("CONC"))) {
                parent.append(tag.equals("CONT") ? "\n" + value : value, whole.get(i));
                last = parent;
                lastGood.set(lastGood.size() - 1, i);
                continue;
            }
            var node = new Node(level, m.group(2), tag, value, whole.get(i));
            if (parent == null) {
                records.add(node);
                starts.add(i);
                lastGood.add(i);
            } else {
                parent.add(node);
                lastGood.set(lastGood.size() - 1, i);
            }
            stack.push(node);
            last = node;
        }
        var prefix = String.join("", whole.subList(0, starts.isEmpty() ? whole.size() : starts.getFirst()));
        var raw = new ArrayList<String>();
        var trailers = new ArrayList<String>();
        for (int r = 0; r < starts.size(); r++) {
            int end = r + 1 < starts.size() ? starts.get(r + 1) : whole.size();
            raw.add(String.join("", whole.subList(starts.get(r), end)));
            trailers.add(String.join("", whole.subList(lastGood.get(r) + 1, end)));
        }
        return new Result(records, new Charsets.Decoded(text, "", "text", false), warnings,
                new Layout(prefix, raw, trailers, newline, finalNewline));
    }
}
