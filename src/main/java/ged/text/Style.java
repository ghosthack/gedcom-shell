package ged.text;

/// ANSI styling that degrades to plain text. Disabled when output is not a
/// terminal, when `NO_COLOR` is set, or with `--no-color`.
public record Style(boolean enabled) {
    public static final Style PLAIN = new Style(false);

    public static Style auto() {
        return new Style(System.console() != null && System.console().isTerminal() && System.getenv("NO_COLOR") == null);
    }

    private String wrap(String code, String s) {
        return enabled && !s.isEmpty() ? "\u001B[" + code + "m" + s + "\u001B[0m" : s;
    }

    public String bold(String s) { return wrap("1", s); }
    public String dim(String s) { return wrap("2", s); }
    public String red(String s) { return wrap("31", s); }
    public String green(String s) { return wrap("32", s); }
    public String yellow(String s) { return wrap("33", s); }
    public String blue(String s) { return wrap("34", s); }
    public String cyan(String s) { return wrap("36", s); }
    public String heading(String s) { return wrap("1;36", s); }

    /// Visible width of `s`: ignores ANSI escapes and combining marks,
    /// counts East Asian wide characters as two columns.
    public static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp == 0x1B) {
                int end = s.indexOf('m', i);
                i = end < 0 ? s.length() : end + 1;
                continue;
            }
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK && type != Character.FORMAT) {
                w += isWide(cp) ? 2 : 1;
            }
            i += Character.charCount(cp);
        }
        return w;
    }

    private static boolean isWide(int cp) {
        return cp >= 0x1100 && (cp <= 0x115F || cp >= 0x2E80 && cp <= 0xA4CF || cp >= 0xAC00 && cp <= 0xD7A3
                || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0xFF00 && cp <= 0xFF60 || cp >= 0x1F300 && cp <= 0x1FAFF
                || cp >= 0x20000 && cp <= 0x3FFFD);
    }

    public static String pad(String s, int width) {
        int w = width(s);
        return w >= width ? s : s + " ".repeat(width - w);
    }

    public static String padLeft(String s, int width) {
        int w = width(s);
        return w >= width ? s : " ".repeat(width - w) + s;
    }

    /// Cuts `s` to at most `max` visible columns, ending with `…`.
    /// Only for plain (unstyled) text.
    public static String truncate(String s, int max) {
        if (width(s) <= max) return s;
        var sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = width(Character.toString(cp));
            if (w + cw > max - 1) break;
            sb.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        return sb.append('…').toString();
    }
}
