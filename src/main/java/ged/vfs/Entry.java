package ged.vfs;

import java.util.List;

/// One row of a listing: where it leads, the relative path segments to get
/// there from the listed directory, and how it relates (e.g. "father").
public record Entry(List<String> path, Location target, String relation) {

    public Entry(String name, Location target, String relation) {
        this(List.of(name), target, relation);
    }

    public String name() { return path.getLast(); }

    /// Relative path as typed by the user, with a trailing `/` for groups.
    public String displayPath() {
        var p = String.join("/", path);
        return switch (target) {
            case Location.PersonDir _, Location.FamilyDir _ -> p;
            default -> p + "/";
        };
    }
}
