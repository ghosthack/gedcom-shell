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
    private final Set<Node> dirty = new LinkedHashSet<>();
    /// Records that were in the file and have been deleted since the last save.
    private int deleted;
    private final Node header;
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
        for (var r : parsed.records()) {
            switch (r.tag()) {
                case "HEAD" -> head = r;
                case "INDI" -> put(people, r.xref(), Person.of(r));
                case "FAM" -> put(families, r.xref(), Family.of(r));
                default -> { if (r.xref() != null) otherRecords.put(r.xref(), r); }
            }
        }
        this.header = head;
        people.values().forEach(p -> searchText.put(p.id(), fold(p.name().full())));
        checkLinks();
    }

    private <T> void put(Map<String, T> map, String id, T value) {
        if (id == null) warnings.add("record without id ignored");
        else if (map.putIfAbsent(id, value) != null) warnings.add("duplicate id " + id + " ignored");
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
        return new Genealogy(file, GedcomParser.parse(file));
    }

    public static Genealogy of(String gedcomText) {
        return new Genealogy(null, GedcomParser.parse(gedcomText));
    }

    // ---- records ---------------------------------------------------------

    public Optional<Path> source() { return Optional.ofNullable(source); }
    public Optional<Node> header() { return Optional.ofNullable(header); }
    public Charsets.Decoded encoding() { return encoding; }
    public List<String> warnings() { return warnings; }
    public List<Person> people() { return List.copyOf(people.values()); }
    public List<Family> families() { return List.copyOf(families.values()); }

    public Optional<Person> person(String idOrKey) {
        return Optional.ofNullable(people.get(xref(idOrKey)));
    }

    public Optional<Family> family(String idOrKey) {
        return Optional.ofNullable(families.get(xref(idOrKey)));
    }

    /// Text of a note, resolving `@N1@` pointers to shared NOTE records.
    public String noteText(String valueOrPointer) {
        if (valueOrPointer.startsWith("@") && valueOrPointer.endsWith("@")) {
            var rec = otherRecords.get(valueOrPointer);
            return rec == null ? valueOrPointer : rec.value();
        }
        return valueOrPointer;
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
        searchText.put(fresh.id(), fold(fresh.name().full()));
        dirty.add(p.node());
        return fresh;
    }

    /// Re-reads a family after its record's nodes were edited, marks the
    /// record dirty, and returns the fresh [Family].
    public Family updated(Family f) {
        var fresh = Family.of(f.node());
        families.put(fresh.id(), fresh);
        dirty.add(f.node());
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
        searchText.put(fresh.id(), fold(fresh.name().full()));
        return fresh;
    }

    /// A new level-0 record, placed before the trailer and marked dirty.
    /// Its id follows the file's own style: the prefix most of `ids` use
    /// (`I`, `P`, …; `fallback` if none), numbered after the highest in use.
    private Node newRecord(String tag, String fallback, Set<String> ids) {
        var prefixes = new LinkedHashMap<String, Integer>();
        for (var id : ids) {
            var m = ID.matcher(id);
            if (m.matches()) prefixes.merge(m.group(1), 1, Integer::sum);
        }
        var prefix = prefixes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(fallback);
        int max = 0;
        for (var r : records) {
            var m = r.xref() == null ? null : ID.matcher(r.xref());
            if (m != null && m.matches() && m.group(1).equals(prefix)) max = Math.max(max, Integer.parseInt(m.group(2)));
        }
        var node = Node.record("@" + prefix + (max + 1) + "@", tag);
        int at = records.size();
        if (at > 0 && records.get(at - 1).tag().equals("TRLR")) at--;
        records.add(at, node);
        rawRecords.add(at, "");
        dirty.add(node);
        return node;
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
        records.get(i).write(sb, layout.newline());
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
        boolean toSource = source != null && (Files.exists(target) && Files.exists(source)
                ? Files.isSameFile(source, target)
                : source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize()));
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
        for (var link : p.node().all("FAMC").toList()) {
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

    /// Lower-case and strip diacritics for forgiving comparisons.
    public static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('ł', 'l').replace('Ł', 'L').replace('ø', 'o').replace('Ø', 'O').replace("ß", "ss")
                .toLowerCase(Locale.ROOT);
    }
}
