package ged.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/// Fixed-width text table. Columns size to their widest cell; a column can
/// be capped with a max width, in which case cells are truncated with `…`.
public final class Table {
    public record Column(String header, boolean right, int max, UnaryOperator<String> style) {
        public static Column left(String header) { return new Column(header, false, 0, UnaryOperator.identity()); }
        public static Column right(String header) { return new Column(header, true, 0, UnaryOperator.identity()); }
        public Column max(int max) { return new Column(header, right, max, style); }
        public Column style(UnaryOperator<String> style) { return new Column(header, right, max, style); }
    }

    private final List<Column> columns;
    private final List<String[]> rows = new ArrayList<>();

    public Table(Column... columns) {
        this.columns = List.of(columns);
    }

    public Table row(String... cells) {
        if (cells.length != columns.size()) throw new IllegalArgumentException("expected " + columns.size() + " cells");
        var copy = cells.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) copy[i] = "";
            if (columns.get(i).max() > 0) copy[i] = Style.truncate(copy[i], columns.get(i).max());
        }
        rows.add(copy);
        return this;
    }

    public boolean isEmpty() { return rows.isEmpty(); }

    public String render(Style style) {
        int n = columns.size();
        int[] widths = new int[n];
        for (int i = 0; i < n; i++) widths[i] = Style.width(columns.get(i).header());
        for (var r : rows) for (int i = 0; i < n; i++) widths[i] = Math.max(widths[i], Style.width(r[i]));

        var out = new StringBuilder();
        var header = new String[n];
        for (int i = 0; i < n; i++) header[i] = columns.get(i).header();
        line(out, header, widths, true, style);
        for (var r : rows) line(out, r, widths, false, style);
        return out.toString();
    }

    private void line(StringBuilder out, String[] cells, int[] widths, boolean header, Style style) {
        var sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            var c = columns.get(i);
            boolean last = i == cells.length - 1;
            var text = c.right() ? Style.padLeft(cells[i], widths[i]) : last ? cells[i] : Style.pad(cells[i], widths[i]);
            if (i > 0) sb.append("  ");
            sb.append(header ? style.bold(text) : style.enabled() ? c.style().apply(text) : text);
        }
        out.append(sb.toString().stripTrailing()).append('\n');
    }
}
