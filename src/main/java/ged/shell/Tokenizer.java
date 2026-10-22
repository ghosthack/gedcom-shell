package ged.shell;

import java.util.ArrayList;
import java.util.List;

/// POSIX-ish word splitting: whitespace separates words, `'...'` and
/// `"..."` quote, `\` escapes, `;` separates commands, `#` starts a comment.
public final class Tokenizer {
    private Tokenizer() {}

    /// Splits a line into commands, each a list of words.
    public static List<List<String>> split(String line) {
        var commands = new ArrayList<List<String>>();
        var words = new ArrayList<String>();
        var word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && quote == '"' && i + 1 < line.length()) word.append(line.charAt(++i));
                else word.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < line.length()) {
                word.append(line.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c) || c == ';') {
                if (inWord) words.add(word.toString());
                word.setLength(0);
                inWord = false;
                if (c == ';' && !words.isEmpty()) {
                    commands.add(List.copyOf(words));
                    words.clear();
                }
            } else if (c == '#' && !inWord) {
                break;
            } else if (c == '|' || c == '>' || c == '<') {
                throw new CommandException("pipes and redirection aren't supported (quote " + c + " to use it literally)");
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (quote != 0) throw new CommandException("unterminated " + quote + " quote");
        if (inWord) words.add(word.toString());
        if (!words.isEmpty()) commands.add(List.copyOf(words));
        return commands;
    }
}
