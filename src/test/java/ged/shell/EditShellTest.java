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
        assertTrue(run("edit @F1@").contains("only people can be edited"));
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
}
