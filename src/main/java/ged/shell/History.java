package ged.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/// Command history, optionally persisted one command per line.
/// Lines starting with a space are not recorded (as in bash with
/// `HISTCONTROL=ignorespace`), nor is an immediate repeat.
public final class History {
    static final int MAX = 1000;

    private final List<String> entries = new ArrayList<>();
    private final Path file;

    public History(Path file) {
        this.file = file;
        if (file == null || !Files.isReadable(file)) return;
        try {
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            entries.addAll(lines.subList(Math.max(0, lines.size() - MAX), lines.size()));
            // keep the file from growing forever
            if (lines.size() > 2 * MAX) Files.write(file, entries, StandardCharsets.UTF_8);
        } catch (IOException _) {
            // unreadable history is not worth failing over
        }
    }

    public static History inMemory() { return new History(null); }

    public List<String> entries() { return List.copyOf(entries); }

    public int size() { return entries.size(); }

    public String get(int i) { return entries.get(i); }

    public void add(String line) {
        if (line.isBlank() || line.startsWith(" ") || line.contains("\n")) return;
        if (!entries.isEmpty() && entries.getLast().equals(line)) return;
        entries.add(line);
        if (entries.size() > MAX) entries.removeFirst();
        if (file == null) return;
        try {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException _) {
            // e.g. read-only home: keep history for this session only
        }
    }
}
