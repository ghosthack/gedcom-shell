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
    private final GedcomParser.Layout layout;
    private final Set<Node> dirty = new LinkedHashSet<>();
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
        this.records = parsed.records();
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

    public boolean isDirty() { return !dirty.isEmpty(); }

    public int dirtyCount() { return dirty.size(); }

    /// The whole file as text, with untouched records verbatim.
    public String render() {
        var sb = new StringBuilder(layout.prefix());
        for (int i = 0; i < records.size(); i++) {
            var r = records.get(i);
            if (dirty.contains(r)) {
                r.write(sb, layout.newline());
                // keep blank/junk lines that trailed the original record
                var raw = layout.rawRecords().get(i);
                sb.append(raw.substring(parsedLength(raw)));
            } else {
                sb.append(layout.rawRecords().get(i));
            }
        }
        if (!layout.finalNewline() && sb.toString().endsWith(layout.newline())) {
            sb.setLength(sb.length() - layout.newline().length());
        }
        return sb.toString();
    }

    /// Length of a raw record minus any blank or unparseable lines that
    /// trail it; those are kept as-is when the record itself is rewritten.
    private int parsedLength(String raw) {
        var nl = layout.newline();
        var lines = raw.split(java.util.regex.Pattern.quote(nl), -1);
        int keep = lines.length - 1; // last element is "" after the final newline
        while (keep > 1 && !lines[keep - 1].matches("\\s*\\d{1,2}\\s+\\S.*")) keep--;
        int len = 0;
        for (int i = 0; i < keep; i++) len += lines[i].length() + nl.length();
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
        if (toSource) dirty.clear();
    }

    // ---- relationships ---------------------------------------------------

    public List<Family> familiesAsChild(Person p) {
        return p.childOf().stream().flatMap(id -> family(id).stream()).toList();
    }

    public List<Family> familiesAsSpouse(Person p) {
        return p.spouseIn().stream().flatMap(id -> family(id).stream()).toList();
    }

    /// Father then mother, from the first family the person is a child of.
    public Optional<Person> father(Person p) {
        return familiesAsChild(p).stream().flatMap(f -> f.husband().stream()).findFirst().flatMap(this::person);
    }

    public Optional<Person> mother(Person p) {
        return familiesAsChild(p).stream().flatMap(f -> f.wife().stream()).findFirst().flatMap(this::person);
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
