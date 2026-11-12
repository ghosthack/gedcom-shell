package ged.model;

import ged.gedcom.Charsets;
import ged.gedcom.GedcomParser;
import ged.gedcom.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Stream;

/// An indexed view of one GEDCOM file and the single source of truth for
/// relationships between people.
///
/// Edits change the underlying [Node]s; the edited record is then
/// re-indexed with [#updated] and marked dirty. [#save] writes untouched
/// records exactly as they were read, so a save changes only edited lines.
public final class Genealogy {
    private final Path source;
    private final List<Node> records;
    /// Parallel to `records`: each record's text as it now stands in the
    /// file (refreshed on save), with any junk lines that trailed it.
    private final List<String> rawRecords;
    private final GedcomParser.Layout layout;
    /// The source file's size and modification time when it was last read
    /// or written here, to notice another program changing it meanwhile.
    private long diskSize = -1;
    private java.nio.file.attribute.FileTime diskTime;
    private final Set<Node> dirty = new LinkedHashSet<>();
    /// Records that were in the file and have been deleted since the last save.
    private int deleted;
    private Node header;
    private final Charsets.Decoded encoding;
    private final List<String> warnings;
    private final SequencedMap<String, Person> people = new LinkedHashMap<>();
    private final SequencedMap<String, Family> families = new LinkedHashMap<>();
    private final Map<String, Node> otherRecords = new LinkedHashMap<>();
    private final Map<String, String> searchText = new LinkedHashMap<>();

    private Genealogy(Path source, GedcomParser.Result parsed) {
        this.source = source;
        this.encoding = parsed.encoding();
        this.records = new ArrayList<>(parsed.records());
        this.rawRecords = new ArrayList<>(parsed.layout().rawRecords());
        this.layout = parsed.layout();
        this.warnings = new ArrayList<>(parsed.warnings());
        Node head = null;
        int line = 1 + lineCount(layout.prefix()); // of the record being read
        var lineOf = new java.util.HashMap<Node, Integer>();
        // Where each id was first seen. People, families and the other records
        // are looked up separately (a FAMC pointer means a family), so an id
        // is repeated only within one of the three.
        var firstAt = new java.util.HashMap<String, Integer>();
        var repeats = new ArrayList<Integer>(); // indexes of records whose id was taken
        for (int i = 0; i < records.size(); i++) {
            var r = records.get(i);
            lineOf.put(r, line);
            line += lineCount(rawRecords.get(i));
            if (r.tag().equals("HEAD")) {
                head = r;
                continue;
            }
            boolean indexed = r.tag().equals("INDI") || r.tag().equals("FAM");
            if (r.xref() == null) {
                if (indexed) warnings.add("record without id at line " + lineOf.get(r) + " ignored");
            } else if (firstAt.putIfAbsent(group(r) + r.xref(), lineOf.get(r)) == null) {
                index(r);
            } else if (indexed || RecordKind.of(r.tag()).isPresent()) {
                repeats.add(i);
            } else {
                warnings.add("duplicate id " + r.xref() + ": the record at line " + lineOf.get(r)
                        + " is ignored (the first is at line " + firstAt.get(group(r) + r.xref()) + ")");
            }
        }
        // A person, family, note, source or media record whose id another
        // record already has can't be told apart by it, so here it goes by the
        // next free id. The file keeps it as it is until the record is edited;
        // then it is written under the new id. Pointers to the shared id mean
        // the first record.
        for (int i : repeats) {
            var r = records.get(i);
            var kind = RecordKind.of(r.tag());
            var fresh = r.renamed(switch (r.tag()) {
                case "INDI" -> nextId("I", people.keySet());
                case "FAM" -> nextId("F", families.keySet());
                default -> nextId(kind.orElseThrow().prefix,
                        records(kind.get()).stream().map(Node::xref).collect(java.util.stream.Collectors.toSet()));
            });
            records.set(i, fresh);
            index(fresh);
            warnings.add("duplicate id " + r.xref() + ": the record at line " + lineOf.get(r) + " is read as " + fresh.xref()
                    + " (the first is at line " + firstAt.get(group(r) + r.xref()) + ")"
                    + (kind.isPresent() ? "" : " and is saved under that id once edited"));
        }
        this.header = head;
        people.values().forEach(p -> searchText.put(p.id(), fold(p.allNames())));
        checkLinks();
    }

    private void index(Node r) {
        switch (r.tag()) {
            case "INDI" -> people.put(r.xref(), Person.of(r));
            case "FAM" -> families.put(r.xref(), Family.of(r));
            default -> otherRecords.put(r.xref(), r);
        }
    }

    /// Which set of ids a record's id belongs to.
    private static String group(Node r) {
        return switch (r.tag()) {
            case "INDI", "FAM" -> r.tag() + " ";
            default -> "";
        };
    }

    private static int lineCount(String text) {
        var m = LINE_WITH_ENDING.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private void checkLinks() {
        for (var p : people.values()) {
            Stream.concat(p.childOf().stream(), p.spouseIn().stream())
                    .filter(f -> !families.containsKey(f))
                    .forEach(f -> warnings.add(p.id() + " points at missing family " + f));
        }
        for (var f : families.values()) {
            Stream.of(f.husband().stream(), f.wife().stream(), f.children().stream())
                    .flatMap(s -> s)
                    .filter(i -> !people.containsKey(i))
                    .forEach(i -> warnings.add(f.id() + " points at missing person " + i));
        }
    }

    public static Genealogy load(Path file) throws IOException {
        var g = new Genealogy(file, GedcomParser.parse(file));
        g.stamp();
        return g;
    }

    private void stamp() {
        try {
            diskSize = Files.size(source);
            diskTime = Files.getLastModifiedTime(source);
        } catch (IOException e) {
            diskSize = -1;
            diskTime = null;
        }
    }

    /// True if the source file is no longer as this program last read or
    /// wrote it: another program changed it, or it is gone.
    public boolean changedOnDisk() {
        if (source == null || diskTime == null) return false;
        try {
            return Files.size(source) != diskSize || !Files.getLastModifiedTime(source).equals(diskTime);
        } catch (IOException e) {
            return true;
        }
    }

    /// True if `target` is the file this genealogy was loaded from.
    public boolean isSource(Path target) throws IOException {
        return source != null && (Files.exists(target) && Files.exists(source)
                ? Files.isSameFile(source, target)
                : source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize()));
    }

    public static Genealogy of(String gedcomText) {
        return new Genealogy(null, GedcomParser.parse(gedcomText));
    }

    // ---- records ---------------------------------------------------------

    public Optional<Path> source() { return Optional.ofNullable(source); }
    public Optional<Node> header() { return Optional.ofNullable(header); }
    public Charsets.Decoded encoding() { return encoding; }

    /// The GEDCOM version the header declares (`HEAD.GEDC.VERS`, e.g.
    /// `5.5.1` or `7.0`), or `""` if it declares none.
    public String gedcomVersion() {
        return header().flatMap(h -> h.first("GEDC")).map(n -> n.text("VERS").strip()).orElse("");
    }

    /// True for a GEDCOM 7 file: no `CONC`, `@VOID@` pointers, `SNOTE`.
    public boolean isGedcom7() {
        return gedcomVersion().startsWith("7");
    }
    public List<String> warnings() { return warnings; }
    public List<Person> people() { return List.copyOf(people.values()); }
    public List<Family> families() { return List.copyOf(families.values()); }

    public Optional<Person> person(String idOrKey) {
        return Optional.ofNullable(people.get(xref(idOrKey)));
    }

    public Optional<Family> family(String idOrKey) {
        return Optional.ofNullable(families.get(xref(idOrKey)));
    }

    /// The notes, sources or media records, in file order.
    public List<Node> records(RecordKind kind) {
        return records.stream()
                .filter(r -> r.xref() != null && kind.tags.contains(r.tag()) && otherRecords.get(r.xref()) == r)
                .toList();
    }

    /// A note, source or media record by id (`S1` or `@S1@`, any case).
    public Optional<Node> record(String idOrKey) {
        var x = idOrKey.startsWith("@") ? idOrKey : "@" + idOrKey + "@";
        var exact = otherRecords.get(x);
        if (exact != null) return Optional.of(exact).filter(r -> RecordKind.of(r.tag()).isPresent());
        return otherRecords.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(x) && RecordKind.of(e.getValue().tag()).isPresent())
                .map(Map.Entry::getValue).findFirst();
    }

    /// The record a pointer such as `@R1@` leads to, of any kind but a
    /// person or family (a repository, a submitter, a note...).
    public Optional<Node> pointed(String pointer) {
        return Optional.ofNullable(otherRecords.get(pointer));
    }

    /// A one-line name for a note, source or media record: a note's first
    /// line, a source's title, a picture's title or file.
    public static String recordTitle(Node r) {
        var title = switch (r.tag()) {
            case "NOTE", "SNOTE" -> r.value().strip().lines().findFirst().orElse("");
            case "SOUR" -> firstOf(r.text("TITL"), r.text("ABBR"), r.text("AUTH"));
            case "OBJE" -> firstOf(r.text("TITL"), r.first("FILE").map(f -> firstOf(f.text("TITL"), f.value())).orElse(""));
            default -> firstOf(r.text("NAME"), r.value());
        };
        title = title.strip().replaceAll("\\s+", " ");
        if (title.length() > 60) title = title.substring(0, 59).strip() + "…";
        return title.isEmpty() ? "(untitled " + RecordKind.of(r.tag()).map(k -> k.member).orElse("record") + ")" : title;
    }

    private static String firstOf(String... texts) {
        for (var t : texts) if (!t.isBlank()) return t;
        return "";
    }

    /// The records that point at `r` anywhere inside them (a person citing a
    /// source in their birth, a source's picture), in file order.
    public List<Node> usersOf(Node r) {
        if (usedBy == null) {
            usedBy = new java.util.HashMap<>();
            for (var rec : records) {
                if (rec.xref() == null || !isCurrent(rec)) continue;
                var targets = new LinkedHashSet<String>();
                pointers(rec, targets);
                for (var t : targets) usedBy.computeIfAbsent(t, _ -> new ArrayList<>()).add(rec);
            }
        }
        if (otherRecords.get(r.xref()) != r) return List.of(); // a repeat: pointers to its old id mean the first
        return List.copyOf(usedBy.getOrDefault(r.xref(), List.of()));
    }

    /// Pointers to a record's id, from the people, families and records that use it.
    private Map<String, List<Node>> usedBy;

    private boolean isCurrent(Node rec) {
        return switch (rec.tag()) {
            case "INDI" -> person(rec.xref()).map(Person::node).filter(n -> n == rec).isPresent();
            case "FAM" -> family(rec.xref()).map(Family::node).filter(n -> n == rec).isPresent();
            default -> otherRecords.get(rec.xref()) == rec;
        };
    }

    private static void pointers(Node n, Set<String> into) {
        for (var c : n.children()) {
            if (c.isPointer() && !c.isVoid()) into.add(c.value());
            pointers(c, into);
        }
    }

    /// Text of a note, resolving `@N1@` pointers to shared NOTE records
    /// (and GEDCOM 7 `SNOTE` records, which have the same shape).
    public String noteText(String valueOrPointer) {
        if (valueOrPointer.startsWith("@") && valueOrPointer.endsWith("@")) {
            var rec = otherRecords.get(valueOrPointer);
            return rec == null ? valueOrPointer : rec.value();
        }
        return valueOrPointer;
    }

    /// The person's pictures, in file order: the first is the preferred one
    /// (GEDCOM 7's rule; one marked `_PRIM Y`, as 5.5.1 programs write, goes
    /// ahead of it: on the person's link, or on the multimedia record unless
    /// the link says otherwise). Both forms of `OBJE` are read: a pointer to
    /// a multimedia record, and 5.5.1's embedded form with the `FILE` inline.
    /// The crop sits on the person's own link, so people sharing one group
    /// photo each have their own. Links without a file are left out.
    ///
    /// Each link is one picture. A record's several `FILE`s are the same
    /// object in other formats, so the first that is an image stands for it
    /// (the first of all, if none says it is one).
    public List<Photo> photos(Person p) {
        var first = new ArrayList<Photo>();
        var rest = new ArrayList<Photo>();
        p.node().links("OBJE").forEach(link -> {
            var rec = link.isPointer() ? otherRecords.get(link.value()) : link;
            if (rec == null) return;
            var crop = Photo.cropOf(link);
            var title = link.text("TITL").isBlank() ? rec.text("TITL") : link.text("TITL");
            var prim = link.first("_PRIM").or(() -> rec.first("_PRIM")).map(Node::value).orElse("");
            boolean primary = prim.strip().equalsIgnoreCase("Y");
            var files = rec.all("FILE").filter(f -> !f.value().isBlank()).toList();
            if (files.isEmpty()) return;
            var f = files.stream().filter(Photo::isImage).findFirst().orElse(files.getFirst());
            var t = title.isBlank() ? f.text("TITL") : title;
            (primary ? first : rest).add(new Photo(f.value().strip(), t.strip(), crop, Photo.dateOf(link, f, rec)));
        });
        first.addAll(rest);
        return List.copyOf(first);
    }

    /// Where a `FILE` value is on disk: an absolute path or `file:` URL as
    /// it stands, anything else relative to the GEDCOM file's folder.
    /// Empty for web addresses, for a file that is not there, and for a
    /// genealogy that was not loaded from a file and a relative path.
    public Optional<Path> mediaPath(String file) {
        try {
            Path path;
            if (file.regionMatches(true, 0, "file:", 0, 5)) path = Path.of(java.net.URI.create(file.replace(" ", "%20")));
            else if (file.matches("(?i)[a-z][a-z0-9+.-]+://.*")) return Optional.empty();
            else path = Path.of(file.replace('\\', java.io.File.separatorChar));
            if (!path.isAbsolute()) {
                if (source == null) return Optional.empty();
                var dir = source.toAbsolutePath().getParent();
                path = dir == null ? path : dir.resolve(path);
            }
            return Files.isRegularFile(path) ? Optional.of(path.normalize()) : Optional.empty();
        } catch (RuntimeException e) { // a path this system cannot express
            return Optional.empty();
        }
    }

    /// `@I1@` → `I1`
    public static String key(String xref) {
        return xref.length() > 2 && xref.startsWith("@") && xref.endsWith("@") ? xref.substring(1, xref.length() - 1) : xref;
    }

    /// `I1` or `@I1@` → `@I1@`, case-insensitively matched against known ids.
    private String xref(String idOrKey) {
        var x = idOrKey.startsWith("@") ? idOrKey : "@" + idOrKey + "@";
        if (people.containsKey(x) || families.containsKey(x)) return x;
        for (var k : people.keySet()) if (k.equalsIgnoreCase(x)) return k;
        for (var k : families.keySet()) if (k.equalsIgnoreCase(x)) return k;
        return x;
    }

    // ---- editing ---------------------------------------------------------

    /// Re-reads a person after their record's nodes were edited, marks the
    /// record dirty, and returns the fresh [Person].
    public Person updated(Person p) {
        var fresh = Person.of(p.node());
        people.put(fresh.id(), fresh);
        searchText.put(fresh.id(), fold(fresh.allNames()));
        dirty.add(p.node());
        usedBy = null;
        return fresh;
    }

    /// Re-reads a family after its record's nodes were edited, marks the
    /// record dirty, and returns the fresh [Family].
    public Family updated(Family f) {
        var fresh = Family.of(f.node());
        families.put(fresh.id(), fresh);
        dirty.add(f.node());
        usedBy = null;
        return fresh;
    }

    /// Adds a family with no members yet.
    public Family newFamily() {
        var fresh = Family.of(newRecord("FAM", "F", families.keySet()));
        families.put(fresh.id(), fresh);
        return fresh;
    }

    /// Adds a person with a NAME line (`Maria /Rossi/`) and SEX.
    public Person newPerson(String name, Person.Sex sex) {
        var rec = newRecord("INDI", "I", people.keySet());
        rec.insert(0, "NAME", name);
        rec.insert(1, "SEX", sex == Person.Sex.UNKNOWN ? "U" : sex.code);
        var fresh = Person.of(rec);
        people.put(fresh.id(), fresh);
        searchText.put(fresh.id(), fold(fresh.allNames()));
        return fresh;
    }

    /// A new level-0 record, placed before the trailer and marked dirty.
    /// Its id follows the file's own style: the prefix most of `ids` use
    /// (`I`, `P`, …; `fallback` if none), numbered after the highest in use.
    private Node newRecord(String tag, String fallback, Set<String> ids) {
        var node = Node.record(nextId(fallback, ids), tag);
        if (records.isEmpty()) startFile();
        int at = records.size();
        if (at > 0 && records.get(at - 1).tag().equals("TRLR")) at--;
        records.add(at, node);
        rawRecords.add(at, "");
        dirty.add(node);
        usedBy = null;
        return node;
    }

    /// An id no record has, in the file's own style (see [#newRecord]).
    private String nextId(String fallback, Set<String> ids) {
        var prefixes = new LinkedHashMap<String, Integer>();
        for (var id : ids) {
            var m = ID.matcher(id);
            if (m.matches()) prefixes.merge(m.group(1), 1, Integer::sum);
        }
        var prefix = prefixes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(fallback);
        int max = 0;
        int width = 0; // of zero-padded ids (`I0042`), which new ones follow
        for (var r : records) {
            var m = r.xref() == null ? null : ID.matcher(r.xref());
            if (m == null || !m.matches() || !m.group(1).equals(prefix)) continue;
            max = Math.max(max, Integer.parseInt(m.group(2)));
            if (m.group(2).startsWith("0")) width = Math.max(width, m.group(2).length());
        }
        var number = width > 0 ? ("%0" + width + "d").formatted(max + 1) : Integer.toString(max + 1);
        return "@" + prefix + number + "@";
    }

    /// Gives a file that had no records the header and trailer every
    /// GEDCOM file needs, so the first record added to it is readable elsewhere.
    private void startFile() {
        header = Node.record(null, "HEAD");
        var gedc = header.insert(0, "GEDC", "");
        gedc.insert(0, "VERS", "5.5.1");
        gedc.insert(1, "FORM", "LINEAGE-LINKED");
        header.insert(1, "CHAR", "UTF-8");
        for (var rec : List.of(header, Node.record(null, "TRLR"))) {
            records.add(rec);
            rawRecords.add("");
            dirty.add(rec);
        }
    }

    private static final java.util.regex.Pattern LINE_WITH_ENDING =
            java.util.regex.Pattern.compile("([^\\r\\n]*)(\\r\\n|\\r|\\n)");
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("@([A-Za-z_]*)(\\d{1,9})@");

    /// Deletes a family record (used when unlinking leaves it empty).
    public void removeFamily(Family f) {
        int i = records.indexOf(f.node());
        if (i < 0) return;
        boolean wasInFile = !rawRecords.get(i).isEmpty();
        records.remove(i);
        rawRecords.remove(i);
        dirty.remove(f.node());
        families.remove(f.id());
        usedBy = null;
        if (wasInFile) deleted++;
    }

    /// Deletes a person record that nothing points at any more (used to
    /// undo an `add` that couldn't be completed).
    public void removePerson(Person p) {
        int i = records.indexOf(p.node());
        if (i < 0) return;
        boolean wasInFile = !rawRecords.get(i).isEmpty();
        records.remove(i);
        rawRecords.remove(i);
        dirty.remove(p.node());
        people.remove(p.id());
        usedBy = null;
        searchText.remove(p.id());
        if (wasInFile) deleted++;
    }

    public boolean isDirty() { return !dirty.isEmpty() || deleted > 0; }

    public int dirtyCount() { return dirty.size() + deleted; }

    /// The whole file as text, with untouched records verbatim.
    public String render() {
        var sb = new StringBuilder(layout.prefix());
        for (int i = 0; i < records.size(); i++) sb.append(recordText(i));
        if (!layout.finalNewline() && sb.toString().endsWith(layout.newline())) {
            sb.setLength(sb.length() - layout.newline().length());
        }
        return sb.toString();
    }

    /// Record `i` as it will be written: verbatim unless edited.
    private String recordText(int i) {
        var raw = rawRecords.get(i);
        if (!dirty.contains(records.get(i))) return raw;
        var sb = new StringBuilder();
        records.get(i).write(sb, layout.newline(), !isGedcom7());
        // keep blank/junk lines that trailed the original record
        return sb.append(raw.substring(parsedLength(raw))).toString();
    }

    /// Length of a raw record minus any blank or unparseable lines that
    /// trail it; those are kept as-is when the record itself is rewritten.
    private int parsedLength(String raw) {
        // each line keeps its own ending, so they can differ within a record
        var m = LINE_WITH_ENDING.matcher(raw);
        int len = 0;
        while (m.find()) {
            if (len == 0 || m.group(1).matches("\\s*\\d{1,2}\\s+\\S.*")) len = m.end();
        }
        return len;
    }

    /// Writes the file atomically (temp file + rename) in its original
    /// encoding, line endings and BOM. Saving to the source file clears the
    /// dirty state; saving a copy elsewhere does not.
    ///
    /// @throws Charsets.UnmappableException if an edit introduced a
    ///         character the file's encoding cannot represent
    public void save(Path target) throws IOException {
        var bytes = Charsets.encode(render(), encoding);
        boolean toSource = isSource(target);
        if (Files.exists(target)) target = target.toRealPath(); // write through symlinks
        var dir = target.toAbsolutePath().getParent();
        var tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, bytes);
            if (Files.exists(target)) {
                try {
                    Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target));
                } catch (UnsupportedOperationException _) {
                    // non-POSIX filesystem: keep defaults
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (toSource) {
            // what's on disk now is the baseline for later saves
            for (int i = 0; i < records.size(); i++) rawRecords.set(i, recordText(i));
            dirty.clear();
            deleted = 0;
            stamp();
        }
    }

    // ---- relationships ---------------------------------------------------

    public List<Family> familiesAsChild(Person p) {
        return p.childOf().stream().flatMap(id -> family(id).stream()).toList();
    }

    public List<Family> familiesAsSpouse(Person p) {
        return p.spouseIn().stream().flatMap(id -> family(id).stream()).toList();
    }

    /// Father then mother, both from [#parentFamily], so they are never
    /// taken from two different families.
    public Optional<Person> father(Person p) {
        return parentFamily(p).flatMap(Family::husband).flatMap(this::person);
    }

    public Optional<Person> mother(Person p) {
        return parentFamily(p).flatMap(Family::wife).flatMap(this::person);
    }

    /// The family a person's father and mother come from: the first family
    /// they are a child of by birth (`FAMC` with no `PEDI`, or `PEDI birth`)
    /// that names a parent; failing that, the first adoptive, foster or
    /// other family that does.
    public Optional<Family> parentFamily(Person p) {
        Optional<Family> other = Optional.empty();
        for (var link : p.node().links("FAMC").toList()) {
            var f = family(link.value());
            if (f.isEmpty() || f.get().husband().flatMap(this::person).isEmpty()
                    && f.get().wife().flatMap(this::person).isEmpty()) continue;
            var pedi = link.text("PEDI").strip();
            if (pedi.isEmpty() || pedi.equalsIgnoreCase("birth")) return f;
            if (other.isEmpty()) other = f;
        }
        return other;
    }

    public List<Person> parents(Person p) {
        return resolve(familiesAsChild(p).stream()
                .flatMap(f -> Stream.concat(f.husband().stream(), f.wife().stream())));
    }

    public List<Person> spouses(Person p) {
        return resolve(familiesAsSpouse(p).stream().flatMap(f -> f.partnerOf(p.id()).stream()));
    }

    public List<Person> children(Person p) {
        return resolve(familiesAsSpouse(p).stream().flatMap(f -> f.children().stream()));
    }

    /// Children of the same family (full siblings, or half-siblings where
    /// the file models them in a shared family).
    public List<Person> siblings(Person p) {
        return resolve(familiesAsChild(p).stream()
                .flatMap(f -> f.children().stream())
                .filter(id -> !id.equals(p.id())));
    }

    /// Children of a parent's other families: they share one parent with
    /// `p` but not a family. Full siblings (and `p`) are left out.
    public List<Person> halfSiblings(Person p) {
        var own = p.childOf();
        var full = siblings(p);
        return resolve(parents(p).stream()
                .flatMap(parent -> familiesAsSpouse(parent).stream())
                .filter(f -> !own.contains(f.id()))
                .flatMap(f -> f.children().stream())
                .filter(id -> !id.equals(p.id())))
                .stream().filter(x -> !full.contains(x)).toList();
    }

    public List<Person> members(Family f) {
        return resolve(Stream.of(f.husband().stream(), f.wife().stream(), f.children().stream()).flatMap(s -> s));
    }

    /// Everyone reachable through parent links, nearest generation first.
    /// Cycle-safe (malformed files can make a person their own ancestor).
    public List<Person> ancestors(Person p) {
        return closure(p, this::parents);
    }

    public List<Person> descendants(Person p) {
        return closure(p, this::children);
    }

    private List<Person> closure(Person start, java.util.function.Function<Person, List<Person>> next) {
        var seen = new LinkedHashSet<Person>();
        var queue = new java.util.ArrayDeque<>(next.apply(start));
        while (!queue.isEmpty()) {
            var p = queue.poll();
            if (!p.equals(start) && seen.add(p)) queue.addAll(next.apply(p));
        }
        return List.copyOf(seen);
    }

    private List<Person> resolve(Stream<String> ids) {
        return ids.collect(LinkedHashSet<String>::new, LinkedHashSet::add, LinkedHashSet::addAll)
                .stream().flatMap(id -> person(id).stream()).toList();
    }

    // ---- search ----------------------------------------------------------

    /// True if every whitespace-separated term occurs in the person's name,
    /// ignoring case and accents ("jose" matches "José").
    public boolean nameMatches(Person p, String query) {
        var hay = searchText.getOrDefault(p.id(), "");
        for (var term : fold(query).split("\\s+")) {
            if (!term.isEmpty() && !hay.contains(term)) return false;
        }
        return true;
    }

    /// The other names and nicknames that made `p` match `query`, when the
    /// main name alone would not have: `find ruiz` lists María Gómez as
    /// `María Gómez de Ruiz`. Empty when the main name holds every term.
    public String matchedAs(Person p, String query) {
        var main = fold(p.name().full());
        var missing = new ArrayList<String>();
        for (var term : fold(query).split("\\s+")) {
            if (!term.isEmpty() && !main.contains(term)) missing.add(term);
        }
        if (missing.isEmpty()) return "";
        var others = new ArrayList<String>();
        p.otherNames().forEach(o -> others.add(o.name().full()));
        others.addAll(p.nicknames());
        var used = new ArrayList<String>();
        for (var other : others) {
            var folded = fold(other);
            if (missing.removeIf(folded::contains)) used.add(other);
            if (missing.isEmpty()) break;
        }
        return String.join(", ", used);
    }

    /// Lower-case and strip diacritics for forgiving comparisons.
    public static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                // letters Unicode doesn't take apart into a base and a mark
                .replace('ł', 'l').replace('ø', 'o').replace('đ', 'd').replace('ð', 'd').replace('ı', 'i')
                .replace("ß", "ss").replace("æ", "ae").replace("œ", "oe").replace("þ", "th");
    }
}
