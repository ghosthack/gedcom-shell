package ged.shell;

import ged.Fixtures;
import ged.edit.Editor;
import ged.model.Genealogy;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditShellTest {
    @TempDir Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    /// Each queued step rewrites the form text, or returns null to cancel.
    private final Deque<UnaryOperator<String>> editorSteps = new ArrayDeque<>();
    private final List<String> formsShown = new ArrayList<>();
    private Genealogy g;
    private Shell shell;

    private void load(Genealogy genealogy) {
        g = genealogy;
        Editor fake = file -> {
            var text = Files.readString(file);
            formsShown.add(text);
            var edited = editorSteps.removeFirst().apply(text);
            if (edited == null) return false;
            Files.writeString(file, edited);
            return true;
        };
        shell = new Shell(new Navigator(new FileSystem(g), g.person("I9")),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                Style.PLAIN, fake);
    }

    private String run(String line) {
        out.reset();
        err.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private static UnaryOperator<String> set(String field, String value) {
        return form -> form.replaceAll("(?m)^" + field.replace(".", "\\.") + ":.*$", field + ": " + value);
    }

    @Test
    void assignmentsEditAndRefreshTheView() {
        load(Fixtures.cousins());
        var r = run("edit sex=M given=Elio birthplace=\"Roma, Italia\"");
        assertTrue(r.contains("Elio Rossi  I9  3 changes"), r);
        assertTrue(r.contains("sex          F → M"), r);
        assertTrue(r.contains("birth.place  (empty) → Roma, Italia"), r);
        assertTrue(r.contains("unsaved"), r);
        assertTrue(shell.prompt().startsWith("Elio Rossi I9 *"), shell.prompt());
        assertTrue(run("show").contains("Born            7 JAN 1910, Roma, Italia"));
        assertTrue(run("cd father; ls").contains("son"), "relatives see the new sex");
        assertTrue(run("find elio").contains("1 match"));
    }

    @Test
    void editorFlowAppliesTheForm() {
        load(Fixtures.cousins());
        editorSteps.add(set("death.place", "Milano").andThen(set("surname", "Rossi-Verdi"))::apply);
        var r = run("edit");
        assertTrue(formsShown.getFirst().contains("given:        Elena"), formsShown.getFirst());
        assertTrue(r.contains("Elena Rossi-Verdi  I9  2 changes"), r);
        assertTrue(g.person("I9").orElseThrow().event("DEAT").orElseThrow().place().equals("Milano"));
    }

    @Test
    void invalidInputReopensFormWithError() {
        load(Fixtures.cousins());
        editorSteps.add(set("birth.date", "31 FEB 1910"));
        editorSteps.add(form -> set("birth.date", "1910-01-08").apply(form));
        var r = run("edit");
        assertEquals(2, formsShown.size());
        assertTrue(formsShown.get(1).startsWith("# ERROR: birth.date: day 31 is out of range for FEB"), formsShown.get(1));
        assertTrue(r.contains("birth.date   7 JAN 1910 → 8 JAN 1910"), r);
    }

    @Test
    void cancellingChangesNothing() {
        load(Fixtures.cousins());
        editorSteps.add(_ -> null);          // :cq
        assertTrue(run("edit").contains("cancelled"));
        editorSteps.add(form -> form);       // :wq without changes
        assertTrue(run("edit mother").contains("cancelled"));
        assertFalse(g.isDirty());
    }

    @Test
    void errorsAndLimits() {
        load(Fixtures.cousins());
        assertTrue(run("edit children").contains("only people and families can be edited"));
        assertTrue(run("edit colour=red").contains("unknown field 'colour'"));
        assertTrue(run("edit born=yesterday").contains("is not a GEDCOM date"));
        assertTrue(run("edit sex=F").contains("no changes"));
        assertTrue(run("save").contains("no file to save to"), "in-memory trees have no source file");
    }

    @Test
    void exitGuardsUnsavedEdits() throws Exception {
        var file = dir.resolve("t.ged");
        Files.writeString(file, Fixtures.COUSINS);
        load(Genealogy.load(file));
        run("edit died=1990");
        assertTrue(run("exit").contains("unsaved record(s)"));
        assertTrue(run("save").contains("saved"));
        assertFalse(g.isDirty());
        assertTrue(Files.readString(file).contains("1 DEAT\n2 DATE 1990\n"));
    }

    @Test
    void aliasesForVimFingers() {
        load(Fixtures.cousins());
        assertTrue(run("vi sex=M").contains("1 change"));
        assertTrue(run("help edit").startsWith("edit [PATH]"));
    }

    // ---- families ---------------------------------------------------------

    @Test
    void familyMarriageByAssignment() {
        load(Fixtures.cousins());
        var r = run("edit @F4@ married=\"12 jun 1935\" marriage.place=Roma");
        assertTrue(r.contains("Luca Rossi & Sofia Verdi  F4  2 changes"), r);
        assertTrue(r.contains("marriage.date   (empty) → 12 JUN 1935"), r);
        assertTrue(g.render().contains("1 WIFE @I8@\n1 MARR\n2 DATE 12 JUN 1935\n2 PLAC Roma\n1 CHIL @I9@\n"), g.render());
        assertTrue(shell.prompt().contains("*"));
        run("edit @F4@ married= marriage.place=");
        assertFalse(g.render().contains("@F4@ FAM\n1 HUSB @I7@\n1 WIFE @I8@\n1 MARR"), "an emptied marriage is removed");
    }

    @Test
    void divorceIsKeptConsistent() {
        load(Fixtures.cousins());
        run("edit @F1@ divorce=1860");
        assertTrue(g.render().contains("1 MARR\n2 DATE 1848\n1 DIV\n2 DATE 1860\n"), g.render());
        assertTrue(run("edit @F1@ divorced=no divorce.date=1861").contains("divorced is 'no' but a divorce date"));
        assertTrue(run("edit @F1@ divorced=no").contains("clear them too: divorce.date="), "dates aren't dropped silently");
        run("edit @F1@ divorced=no divorce.date=");
        assertFalse(g.render().contains("1 DIV"));
        run("edit @F1@ divorced=yes");
        assertTrue(g.render().contains("1 DIV Y\n"), "divorced, details unknown");
    }

    @Test
    void familyFormInTheEditor() {
        load(Fixtures.cousins());
        editorSteps.add(set("marriage.date", "1934-05-02").andThen(set("marriage.place", "Genova"))::apply);
        var r = run("cd father; edit families/F4");
        var form = formsShown.getFirst();
        assertTrue(form.contains("#   husband   I7  Luca Rossi"), form);
        assertTrue(form.contains("#   child     I9  Elena Rossi"), form);
        assertTrue(form.contains("\nmarriage.date:\n"), form);
        assertTrue(r.contains("marriage.date   (empty) → 2 MAY 1934"), r);
        assertEquals("Genova", g.family("F4").orElseThrow().event("MARR").orElseThrow().place());
    }

    @Test
    void fieldsOnTheWrongKindOfRecordPointTheWay() {
        load(Fixtures.cousins());
        assertTrue(run("edit married=1930").contains("married is a family field: and Elena Rossi has no family as a partner"));
        assertTrue(run("cd father; edit married=1930").contains("married is a family field: edit families/F4 married=…"));
        assertTrue(run("edit @F4@ born=1900").contains("born is a person field"));
        assertFalse(g.isDirty());
    }

    @Test
    void familyEditSavesOnlyItsOwnLines() throws Exception {
        var file = dir.resolve("t.ged");
        Files.writeString(file, Fixtures.COUSINS);
        load(Genealogy.load(file));
        run("edit @F2@ wed=1840; save");
        var before = Fixtures.COUSINS.lines().toList();
        var after = Files.readString(file).lines().toList();
        var added = new ArrayList<>(after);
        before.forEach(added::remove);
        assertEquals(List.of("1 MARR", "2 DATE 1840"), added);
        assertEquals(before.size() + 2, after.size());
    }
}
