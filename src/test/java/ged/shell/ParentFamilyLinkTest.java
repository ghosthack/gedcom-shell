package ged.shell;

import ged.model.Family;
import ged.model.Genealogy;
import ged.model.Person;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `father` and `mother` come from one family (`Genealogy.parentFamily`).
/// `link`, `add` and `unlink` for father, mother and sibling have to edit
/// that same family, and the sentence they print has to be what `ls` shows next.
class ParentFamilyLinkTest {
    /// Adoptive FAMC first, birth family second. The birth family has the
    /// father and an empty mother slot; the adoptive family has the mother
    /// and an empty father slot.
    static final String ADOPTIVE_BEFORE_BIRTH = file("""
            0 @I1@ INDI
            1 NAME Lea /Child/
            1 SEX F
            1 FAMC @F1@
            2 PEDI adopted
            1 FAMC @F2@
            2 PEDI birth
            0 @I2@ INDI
            1 NAME Nina /Adopt/
            1 SEX F
            1 FAMS @F1@
            0 @I3@ INDI
            1 NAME Paul /Birth/
            1 SEX M
            1 FAMS @F2@
            0 @I4@ INDI
            1 NAME Una /Free/
            1 SEX F
            0 @I5@ INDI
            1 NAME Vic /Free/
            1 SEX M
            0 @I6@ INDI
            1 NAME Sam /Kin/
            1 SEX M
            1 FAMC @F1@
            0 @I7@ INDI
            1 NAME Rio /Kin/
            1 SEX M
            0 @F1@ FAM
            1 WIFE @I2@
            1 CHIL @I1@
            1 CHIL @I6@
            0 @F2@ FAM
            1 HUSB @I3@
            1 CHIL @I1@
            """);

    /// Empty birth FAMC first, then an adoptive family that already has
    /// both parents. Filling the empty birth family would make it the one
    /// `father` and `mother` read, and the adoptive mother would vanish.
    static final String EMPTY_BIRTH_BEFORE_ADOPTIVE = file("""
            0 @I1@ INDI
            1 NAME Lea /Child/
            1 SEX F
            1 FAMC @F1@
            2 PEDI birth
            1 FAMC @F2@
            2 PEDI adopted
            0 @I2@ INDI
            1 NAME Nina /Adopt/
            1 SEX F
            1 FAMS @F2@
            0 @I3@ INDI
            1 NAME Otto /Adopt/
            1 SEX M
            1 FAMS @F2@
            0 @I4@ INDI
            1 NAME Vic /Free/
            1 SEX M
            0 @I5@ INDI
            1 NAME Rio /Kin/
            1 SEX M
            0 @F1@ FAM
            1 CHIL @I1@
            0 @F2@ FAM
            1 HUSB @I3@
            1 WIFE @I2@
            1 CHIL @I1@
            """);

    /// Same order, but the adoptive family has only a mother, so the father
    /// slot `ls` is missing is on that family, not on the empty birth one.
    static final String EMPTY_BIRTH_OPEN_FATHER = file("""
            0 @I1@ INDI
            1 NAME Lea /Child/
            1 SEX F
            1 FAMC @F1@
            2 PEDI birth
            1 FAMC @F2@
            2 PEDI adopted
            0 @I2@ INDI
            1 NAME Nina /Adopt/
            1 SEX F
            1 FAMS @F2@
            0 @I3@ INDI
            1 NAME Vic /Free/
            1 SEX M
            0 @F1@ FAM
            1 CHIL @I1@
            0 @F2@ FAM
            1 WIFE @I2@
            1 CHIL @I1@
            """);

    /// The same two people are the parents in both families, and Sam is a
    /// child of both. `father` / `mother` show the birth family only.
    static final String BOTH_FAMILIES = file("""
            0 @I1@ INDI
            1 NAME Lea /Child/
            1 SEX F
            1 FAMC @F1@
            2 PEDI adopted
            1 FAMC @F2@
            2 PEDI birth
            0 @I2@ INDI
            1 NAME Gus /Both/
            1 SEX M
            1 FAMS @F1@
            1 FAMS @F2@
            0 @I3@ INDI
            1 NAME Quinn /Both/
            1 SEX F
            1 FAMS @F1@
            1 FAMS @F2@
            0 @I4@ INDI
            1 NAME Sam /Kin/
            1 SEX M
            1 FAMC @F1@
            1 FAMC @F2@
            0 @I5@ INDI
            1 NAME Ada /Kin/
            1 SEX F
            1 FAMC @F1@
            0 @F1@ FAM
            1 HUSB @I2@
            1 WIFE @I3@
            1 CHIL @I1@
            1 CHIL @I4@
            1 CHIL @I5@
            0 @F2@ FAM
            1 HUSB @I2@
            1 WIFE @I3@
            1 CHIL @I1@
            1 CHIL @I4@
            """);

    /// A child-family that names nobody yet: the link has to use it, not
    /// start another family.
    static final String EMPTY_ONLY = file("""
            0 @I1@ INDI
            1 NAME Lea /Child/
            1 SEX F
            1 FAMC @F1@
            0 @I2@ INDI
            1 NAME Vic /Free/
            1 SEX M
            0 @I3@ INDI
            1 NAME Rio /Kin/
            1 SEX M
            0 @F1@ FAM
            1 CHIL @I1@
            """);

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Genealogy g;
    private Shell shell;

    private static String file(String records) {
        return "0 HEAD\n" + records.strip() + "\n0 TRLR\n";
    }

    private void load(String gedcom) {
        g = Genealogy.of(gedcom);
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        shell = new Shell(new Navigator(new FileSystem(g), g.person("I1")), ps, ps, Style.PLAIN);
    }

    private String run(String line) {
        out.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8);
    }

    private Person child() { return g.person("I1").orElseThrow(); }

    private Person p(String key) { return g.person(key).orElseThrow(); }

    private Family fam(String key) { return g.family(key).orElseThrow(); }

    private String ls() { return run("ls"); }

    /// A person's `ls` row whose path is `path` (for example `father`, not
    /// `parents/I2`, which can also say "father" in the relation column).
    private boolean listed(String listing, String path, String id) {
        return listing.lines().anyMatch(l -> {
            var c = l.trim().split("\\s+");
            return c.length >= 3 && c[1].equals(path) && c[2].equals(id);
        });
    }

    private void assertListed(String path, String id) {
        var listing = ls();
        assertTrue(listed(listing, path, id), listing);
    }

    private void assertNotListed(String path) {
        var listing = ls();
        assertFalse(listing.lines().anyMatch(l -> {
            var c = l.trim().split("\\s+");
            return c.length >= 2 && c[1].equals(path);
        }), listing);
    }

    @Test
    void adoptiveFamcListedBeforeBirth() {
        load(ADOPTIVE_BEFORE_BIRTH);
        assertEquals(Optional.of("I3"), g.father(child()).map(Person::key));
        assertTrue(g.mother(child()).isEmpty(), "the birth family has no wife, so ls has no mother");

        var father = run("link I5 father");
        assertTrue(father.contains("already has a father: I3  Paul Birth"), father);
        assertFalse(father.contains("is now the father"), father);
        assertTrue(fam("F1").husband().isEmpty(), "the empty adoptive husband slot is not the father");
        assertEquals(p("I3").id(), fam("F2").husband().orElseThrow());
        assertListed("father", "I3");
        assertFalse(g.isDirty());

        var mother = run("link I4 mother");
        assertTrue(mother.contains("Una Free I4 is now the mother of Lea Child"), mother);
        assertTrue(mother.contains("(F2)"), mother);
        assertFalse(mother.contains("F1"), mother);
        assertEquals(Optional.of("I4"), g.mother(child()).map(Person::key));
        assertEquals(Optional.of("I3"), g.father(child()).map(Person::key));
        assertEquals(p("I2").id(), fam("F1").wife().orElseThrow(), "Nina stays the adoptive wife");
        assertEquals(p("I4").id(), fam("F2").wife().orElseThrow());
        assertListed("mother", "I4");
        assertListed("father", "I3");

        var sibling = run("link I7 siblings");
        assertTrue(sibling.contains("Rio Kin I7 is now a sibling of Lea Child"), sibling);
        assertTrue(sibling.contains("F2"), sibling);
        assertFalse(sibling.contains("F1"), sibling);
        assertTrue(fam("F2").children().contains(p("I7").id()));
        assertFalse(fam("F1").children().contains(p("I7").id()));
        assertListed("siblings/I7", "I7");

        load(ADOPTIVE_BEFORE_BIRTH);
        int people = g.people().size();
        var addFather = run("add father \"Max Birth\"");
        assertTrue(addFather.contains("already has a father: I3  Paul Birth"), addFather);
        assertFalse(addFather.contains("added"), addFather);
        assertEquals(people, g.people().size());
        assertFalse(g.isDirty());
        assertListed("father", "I3");

        var addMother = run("add mother \"Mia Birth\"");
        assertTrue(addMother.contains("is now the mother"), addMother);
        assertTrue(addMother.contains("(F2)"), addMother);
        assertFalse(addMother.contains("F1"), addMother);
        var mia = g.mother(child()).orElseThrow();
        assertEquals("Mia Birth", mia.name().display());
        assertEquals(mia.id(), fam("F2").wife().orElseThrow());
        assertEquals(p("I2").id(), fam("F1").wife().orElseThrow());
        assertListed("mother", mia.key());
        assertListed("father", "I3");

        load(ADOPTIVE_BEFORE_BIRTH);
        var addSibling = run("add sibling \"Nia Kin\"");
        assertTrue(addSibling.contains("is now a sibling of Lea Child"), addSibling);
        assertTrue(addSibling.contains("F2"), addSibling);
        assertFalse(addSibling.contains("F1"), addSibling);
        var nia = g.siblings(child()).stream().filter(s -> s.name().display().equals("Nia Kin")).findFirst().orElseThrow();
        assertTrue(fam("F2").children().contains(nia.id()));
        assertFalse(fam("F1").children().contains(nia.id()));
        assertListed("siblings/" + nia.key(), nia.key());
    }

    @Test
    void emptyBirthFamcListedBeforeAdoptiveParents() {
        load(EMPTY_BIRTH_BEFORE_ADOPTIVE);
        assertEquals(Optional.of("F2"), g.parentFamily(child()).map(Family::key));

        var father = run("link I4 father");
        assertTrue(father.contains("already has a father: I3  Otto Adopt"), father);
        assertFalse(father.contains("is now the father"), father);
        assertTrue(fam("F1").husband().isEmpty(), "the empty birth family must not gain a husband");
        assertTrue(fam("F1").wife().isEmpty());
        assertEquals(p("I3").id(), fam("F2").husband().orElseThrow());
        assertEquals(Optional.of("I2"), g.mother(child()).map(Person::key), "the adoptive mother stays on the mother row");
        assertListed("father", "I3");
        assertListed("mother", "I2");
        assertFalse(g.isDirty());

        var sibling = run("link I5 siblings");
        assertTrue(sibling.contains("in F2"), sibling);
        assertFalse(sibling.contains("F1"), sibling);
        assertTrue(fam("F2").children().contains(p("I5").id()));
        assertFalse(fam("F1").children().contains(p("I5").id()));
        assertListed("siblings/I5", "I5");
        assertListed("mother", "I2");

        load(EMPTY_BIRTH_BEFORE_ADOPTIVE);
        int people = g.people().size();
        var addMother = run("add mother \"Ida Adopt\"");
        assertTrue(addMother.contains("already has a mother: I2  Nina Adopt"), addMother);
        assertFalse(addMother.contains("added"), addMother);
        assertEquals(people, g.people().size());
        assertTrue(fam("F1").wife().isEmpty());
        assertEquals(Optional.of("I2"), g.mother(child()).map(Person::key));
        assertListed("mother", "I2");

        // the father ls does not show is the open slot of the adoptive family
        load(EMPTY_BIRTH_OPEN_FATHER);
        assertTrue(g.father(child()).isEmpty());
        assertEquals(Optional.of("I2"), g.mother(child()).map(Person::key));
        var open = run("link I3 father");
        assertTrue(open.contains("Vic Free I3 is now the father of Lea Child"), open);
        assertTrue(open.contains("(F2)"), open);
        assertFalse(open.contains("F1"), open);
        assertEquals(Optional.of("I3"), g.father(child()).map(Person::key));
        assertEquals(Optional.of("I2"), g.mother(child()).map(Person::key));
        assertTrue(fam("F1").husband().isEmpty());
        assertTrue(fam("F1").wife().isEmpty());
        assertEquals(p("I3").id(), fam("F2").husband().orElseThrow());
        assertListed("father", "I3");
        assertListed("mother", "I2");

        load(EMPTY_BIRTH_OPEN_FATHER);
        var addFather = run("add father \"Max Adopt\"");
        assertTrue(addFather.contains("is now the father"), addFather);
        assertTrue(addFather.contains("(F2)"), addFather);
        assertFalse(addFather.contains("F1"), addFather);
        var max = g.father(child()).orElseThrow();
        assertEquals("Max Adopt", max.name().display());
        assertEquals(max.id(), fam("F2").husband().orElseThrow());
        assertEquals(Optional.of("I2"), g.mother(child()).map(Person::key));
        assertTrue(fam("F1").husband().isEmpty());
        assertListed("father", max.key());
        assertListed("mother", "I2");
    }

    @Test
    void unlinkEditsOnlyTheDisplayedFamily() {
        load(BOTH_FAMILIES);
        assertEquals(Optional.of("F2"), g.parentFamily(child()).map(Family::key));
        assertEquals(Optional.of("I2"), g.father(child()).map(Person::key));
        assertEquals(Optional.of("I3"), g.mother(child()).map(Person::key));

        var father = run("unlink father");
        assertTrue(father.contains("Gus Both I2 left F2"), father);
        assertFalse(father.contains("F1"), father);
        assertFalse(father.contains("deleted"), father);
        assertTrue(g.father(child()).isEmpty());
        assertEquals(Optional.of("I3"), g.mother(child()).map(Person::key));
        assertEquals(p("I2").id(), fam("F1").husband().orElseThrow(), "Gus stays husband of the adoptive family");
        assertTrue(fam("F2").husband().isEmpty());
        assertEquals(p("I3").id(), fam("F2").wife().orElseThrow());
        assertNotListed("father");
        assertListed("mother", "I3");
        assertListed("parents/I2", "I2");

        load(BOTH_FAMILIES);
        var mother = run("unlink mother");
        assertTrue(mother.contains("Quinn Both I3 left F2"), mother);
        assertFalse(mother.contains("F1"), mother);
        assertEquals(Optional.of("I2"), g.father(child()).map(Person::key));
        assertTrue(g.mother(child()).isEmpty());
        assertEquals(p("I3").id(), fam("F1").wife().orElseThrow());
        assertTrue(fam("F2").wife().isEmpty());
        assertListed("father", "I2");
        assertNotListed("mother");
        assertListed("parents/I3", "I3");

        load(BOTH_FAMILIES);
        var sibling = run("unlink siblings/I4");
        assertTrue(sibling.contains("Sam Kin I4 left F2"), sibling);
        assertFalse(sibling.contains("F1"), sibling);
        assertFalse(fam("F2").children().contains(p("I4").id()));
        assertTrue(fam("F1").children().contains(p("I4").id()), "Sam stays a child of the adoptive family");
        assertListed("siblings/I4", "I4");
        assertListed("father", "I2");

        // a sibling who is not in the displayed family still leaves the one they share
        var other = run("unlink siblings/I5");
        assertTrue(other.contains("Ada Kin I5 left F1"), other);
        assertFalse(fam("F1").children().contains(p("I5").id()));
        assertNotListed("siblings/I5");
        assertListed("siblings/I4", "I4");
    }

    @Test
    void emptyChildFamilyReceivesTheFirstParentAndSibling() {
        load(EMPTY_ONLY);
        assertTrue(g.parentFamily(child()).isEmpty());
        var father = run("link I2 father");
        assertTrue(father.contains("Vic Free I2 is now the father of Lea Child (F1)"), father);
        assertFalse(father.contains("new family"), father);
        assertFalse(g.family("F2").isPresent());
        assertEquals(p("I2").id(), fam("F1").husband().orElseThrow());
        assertListed("father", "I2");

        var sibling = run("link I3 siblings");
        assertTrue(sibling.contains("in F1"), sibling);
        assertTrue(fam("F1").children().contains(p("I3").id()));
        assertFalse(g.family("F2").isPresent());
        assertListed("siblings/I3", "I3");
        assertListed("father", "I2");
    }
}
