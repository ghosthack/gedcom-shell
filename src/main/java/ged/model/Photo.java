package ged.model;

import ged.gedcom.Node;

import java.util.Optional;

/// One picture linked from a person: the file it is in, its title, and the
/// part of it that shows this person (so one family photo can serve
/// everyone in it). See [Genealogy#photos(Person)].
///
/// `file` is as the GEDCOM wrote it: usually a path relative to the GEDCOM
/// file. [Genealogy#mediaPath(String)] turns it into a path on disk.
///
/// `date` is when the picture was taken, or [GedDate#EMPTY]. GEDCOM has no
/// standard place for it, so it is read from `DATE` or `_DATE` on the
/// person's link, on the `FILE`, or on the multimedia record, in that order.
public record Photo(String file, String title, Optional<Crop> crop, GedDate date) {

    public Photo(String file, String title, Optional<Crop> crop) {
        this(file, title, crop, GedDate.EMPTY);
    }

    /// The date written on the first of these that has one.
    static GedDate dateOf(Node... places) {
        for (var n : places) {
            for (var tag : new String[] {"DATE", "_DATE"}) {
                if (!n.text(tag).isBlank()) return GedDate.parse(n.text(tag).strip());
            }
        }
        return GedDate.EMPTY;
    }

    /// A rectangle of the picture, in its pixels: GEDCOM 7's `CROP`
    /// (`TOP`, `LEFT`, `HEIGHT`, `WIDTH`). A height or width of 0 means
    /// "to the picture's edge", as when GEDCOM 7 leaves them out.
    public record Crop(int left, int top, int width, int height) {

        /// Reads a `CROP` structure. Numbers that are missing or unreadable
        /// count as 0; a rectangle with a negative side is no crop at all.
        static Optional<Crop> of(Node n) {
            var c = new Crop(number(n, "LEFT"), number(n, "TOP"), number(n, "WIDTH"), number(n, "HEIGHT"));
            return c.left < 0 || c.top < 0 || c.width < 0 || c.height < 0 ? Optional.empty() : Optional.of(c);
        }

        private static int number(Node n, String tag) {
            try {
                return (int) Math.round(Double.parseDouble(n.text(tag).strip()));
            } catch (NumberFormatException e) {
                return n.text(tag).isBlank() ? 0 : -1;
            }
        }
    }

    /// The tags a crop is written under: `CROP` is GEDCOM 7's; `_CROP` is
    /// the same structure for 5.5.1 files, which have no standard one.
    static Optional<Crop> cropOf(Node link) {
        return link.first("CROP").or(() -> link.first("_CROP")).flatMap(Crop::of);
    }
}
