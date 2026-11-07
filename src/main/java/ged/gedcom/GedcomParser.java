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

    /// The value follows a single space, or a tab, which some programs write.
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d{1,2})\\s+(?:(@[^@\\s]+@)\\s+)?(\\S+)(?:[ \\t](.*))?$");
    private static final Pattern TERMINATED = Pattern.compile("([^\\r\\n]*)(\\r\\n|\\r|\\n|$)");

    /// How the file was laid out, so it can be written back unchanged.
    /// `rawRecords` is parallel to `records`: each record's exact text,
    /// including any blank or unparseable lines that followed it, and each
    /// line's own ending (files that mix `\r\n` and `\n` keep the mix).
    /// `newline` is the file's most common ending, used for new lines.
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
        var lines = new ArrayList<String>();
        var endings = new ArrayList<String>();
        var m0 = TERMINATED.matcher(text);
        while (m0.find()) {
            if (m0.group(2).isEmpty() && m0.group(1).isEmpty()) break; // end of text
            lines.add(m0.group(1));
            endings.add(m0.group(2));
            if (m0.group(2).isEmpty()) break; // last line, no ending
        }
        boolean finalNewline = !endings.isEmpty() && !endings.getLast().isEmpty();
        var starts = new ArrayList<Integer>();
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
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
        var newline = mostCommon(endings);
        var prefix = joined(lines, endings, 0, starts.isEmpty() ? lines.size() : starts.getFirst(), newline);
        var raw = new ArrayList<String>();
        for (int r = 0; r < starts.size(); r++) {
            raw.add(joined(lines, endings, starts.get(r), r + 1 < starts.size() ? starts.get(r + 1) : lines.size(), newline));
        }
        return new Result(records, new Charsets.Decoded(text, "", "text", false), warnings,
                new Layout(prefix, raw, newline, finalNewline));
    }

    /// Lines `from..to` with their own endings. The file's last line, if
    /// unterminated, gets `newline` here; [ged.model.Genealogy#render] drops
    /// it again so the file still ends without one.
    private static String joined(List<String> lines, List<String> endings, int from, int to, String newline) {
        var sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(lines.get(i)).append(endings.get(i).isEmpty() ? newline : endings.get(i));
        }
        return sb.toString();
    }

    /// The most frequent line ending; the first one seen wins a tie, `\n` if none.
    private static String mostCommon(List<String> endings) {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (var e : endings) if (!e.isEmpty()) counts.merge(e, 1, Integer::sum);
        return counts.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse("\n");
    }
}
