package ged.gedcom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
    /// including any blank or unparseable lines that followed it.
    public record Layout(String prefix, List<String> rawRecords, String newline, boolean finalNewline) {}

    public record Result(List<Node> records, Charsets.Decoded encoding, List<String> warnings, Layout layout) {}

    public static Result parse(Path file) throws IOException {
        var decoded = Charsets.decode(Files.readAllBytes(file));
        var result = parse(decoded.text());
        return new Result(result.records(), decoded, result.warnings(), result.layout());
    }

    public static Result parse(String text) {
        var records = new ArrayList<Node>();
        var warnings = new ArrayList<String>();
        var stack = new ArrayDeque<Node>();
        boolean finalNewline = text.endsWith("\n") || text.endsWith("\r");
        var all = text.split("\r\n|\r|\n", -1);
        var lines = finalNewline ? Arrays.copyOf(all, all.length - 1) : all;
        var starts = new ArrayList<Integer>();
        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            int lineNo = i + 1;
            if (line.isBlank()) continue;
            var m = LINE.matcher(line);
            if (!m.matches()) {
                warnings.add("line " + lineNo + ": unparseable: " + line.strip());
                continue;
            }
            int level = Integer.parseInt(m.group(1));
            String tag = m.group(3);
            String value = m.group(4) == null ? "" : m.group(4);

            while (!stack.isEmpty() && stack.peek().level() >= level) stack.pop();
            Node parent = stack.peek();

            if (parent != null && (tag.equals("CONT") || tag.equals("CONC"))) {
                parent.append(tag.equals("CONT") ? "\n" + value : value, line);
                continue;
            }
            if (level > 0 && parent == null) {
                warnings.add("line " + lineNo + ": orphaned level " + level + " line");
                continue;
            }
            var node = new Node(level, m.group(2), tag, value, line);
            if (parent == null) {
                records.add(node);
                starts.add(i);
            } else {
                parent.add(node);
            }
            stack.push(node);
        }
        var newline = text.contains("\r\n") ? "\r\n" : text.contains("\r") ? "\r" : "\n";
        var prefix = joined(lines, 0, starts.isEmpty() ? lines.length : starts.getFirst(), newline);
        var raw = new ArrayList<String>();
        for (int r = 0; r < starts.size(); r++) {
            raw.add(joined(lines, starts.get(r), r + 1 < starts.size() ? starts.get(r + 1) : lines.length, newline));
        }
        return new Result(records, new Charsets.Decoded(text, "", "text", false), warnings,
                new Layout(prefix, raw, newline, finalNewline));
    }

    private static String joined(String[] lines, int from, int to, String newline) {
        var sb = new StringBuilder();
        for (int i = from; i < to; i++) sb.append(lines[i]).append(newline);
        return sb.toString();
    }
}
