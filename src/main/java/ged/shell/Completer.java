package ged.shell;

import java.util.List;

/// Supplies Tab completions to the [LineEditor].
@FunctionalInterface
public interface Completer {

    /// What the word before `cursor` in `line` could become. `start` is
    /// where that word begins in `line` (including any opening quote) and
    /// `word` is its text as the shell will read it (quotes and escapes
    /// removed).
    Result complete(String line, int cursor);

    record Result(int start, String word, List<Candidate> candidates) {
        public static Result none(int cursor) { return new Result(cursor, "", List.of()); }
    }

    /// `text` replaces the word; `suffix` follows it once the choice is
    /// unique (`/` to keep walking a path, a space to start the next word).
    /// `display` is how it is listed when there is a choice to make, and
    /// `note` an optional explanation printed after it.
    record Candidate(String text, String suffix, String display, String note) {
        public Candidate(String text, String suffix) { this(text, suffix, text + suffix, ""); }
    }

    /// Backslash-escapes characters the [Tokenizer] would otherwise read
    /// as quoting, separators or comments.
    static String escape(String s) {
        var sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (" \t'\"\\;#|<>".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
