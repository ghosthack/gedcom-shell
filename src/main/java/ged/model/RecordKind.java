package ged.model;

import java.util.List;
import java.util.Optional;

/// The records besides people and families that the shell can show: shared
/// notes, sources and multimedia objects. Each has its own directory
/// (`/notes`, `/sources`, `/media`).
public enum RecordKind {
    NOTES("notes", "note", "N", List.of("NOTE", "SNOTE")),
    SOURCES("sources", "source", "S", List.of("SOUR")),
    MEDIA("media", "media", "M", List.of("OBJE"));

    /// The directory name, e.g. `sources`.
    public final String dirName;
    /// One of them, e.g. `source`.
    public final String member;
    /// The id prefix new ids of this kind get when the file uses none.
    final String prefix;
    /// The level-0 tags that make a record of this kind.
    final List<String> tags;

    RecordKind(String dirName, String member, String prefix, List<String> tags) {
        this.dirName = dirName;
        this.member = member;
        this.prefix = prefix;
        this.tags = tags;
    }

    /// The kind of a level-0 record with `tag`, if it is one of these.
    public static Optional<RecordKind> of(String tag) {
        for (var k : values()) if (k.tags.contains(tag)) return Optional.of(k);
        return Optional.empty();
    }
}
