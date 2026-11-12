package ged.shell;

import ged.model.Genealogy;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtherNamesTest {
    private static final String GED = """
            0 HEAD
            0 @I1@ INDI
            1 NAME María José /Gómez/
            2 NICK Pepa
            1 NAME Maria Jose /Gomez/
            2 TYPE aka
            1 NAME María José /Gómez de Ruiz/
            2 TYPE married
            1 NAME Mariquita /Gómez/
            2 TYPE aka
            2 NICK Mari
            1 SEX F
            0 @I2@ INDI
            1 NAME Juan /Ruiz/
            1 SEX M
            0 @I3@ INDI
            1 NAME Ana /Sol/
            1 NAME Anita /Luz/
            2 TYPE OTHER
            3 PHRASE stage name
            1 NAME Nita /Sol/
            2 TYPE OTHER
            0 TRLR
            """;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Shell shell;

    OtherNamesTest() {
        var g = Genealogy.of(GED);
        var nav = new Navigator(new FileSystem(g), g.person("@I1@"));
        shell = new Shell(nav, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Style.PLAIN);
    }

    private String run(String line) {
        out.reset();
        err.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void showListsNicknamesAndOtherNames() {
        var s = run("show");
        assertTrue(s.contains("Nicknames       Pepa, Mari"), s);
        assertTrue(s.contains("Also known as   María José Gómez de Ruiz  (married)\n"
                + "                Mariquita Gómez\n"), s);
    }

    @Test
    void showSkipsSpellingsThatOnlyDropAccents() {
        assertFalse(run("show").contains("Maria Jose Gomez\n"));
    }

    @Test
    void showWithoutOtherNamesAddsNothing() {
        var s = run("show @I2@");
        assertFalse(s.contains("Also known as"), s);
        assertFalse(s.contains("Nickname"), s);
    }

    @Test
    void findMatchesOtherNamesAndNicknames() {
        assertTrue(run("find ruiz").contains("María José Gómez"));
        assertTrue(run("find pepa").contains("María José Gómez"));
        assertTrue(run("find mariquita").contains("María José Gómez"));
    }

    @Test
    void findSaysWhichOtherNameMatched() {
        var s = run("find ruiz");
        assertTrue(s.contains("NAME              MATCHED"), s);
        assertTrue(s.contains("María José Gómez  María José Gómez de Ruiz"), s);
        assertTrue(s.matches("(?s).*Juan Ruiz +M.*"), "a main-name match has nothing to explain: " + s);
        assertTrue(run("find pepa").contains("María José Gómez  Pepa"));
        assertTrue(run("find mari gomez").contains("María José Gómez  F"), "the main name holds both words");
        assertFalse(run("find juan").contains("MATCHED"), "no column when every match is by main name");
    }

    @Test
    void ambiguousNameSaysWhichOtherNameMatched() {
        run("cd /; cd ruiz");
        var e = err.toString(StandardCharsets.UTF_8);
        assertTrue(e.contains("I1    María José Gómez  (matched María José Gómez de Ruiz)"), e);
        assertTrue(e.contains("I2    Juan Ruiz\n"), e);
    }

    @Test
    void completionSaysWhichOtherNameMatched() {
        var c = shell.completer().complete("cd ruiz", 7).candidates();
        assertTrue(c.stream().anyMatch(x -> x.note().contains("María José Gómez  (matched María José Gómez de Ruiz)")), c.toString());
        assertTrue(c.stream().anyMatch(x -> x.note().endsWith("Juan Ruiz")), c.toString());
    }

    @Test
    void aTypeExplainedInWordsShowsThoseWords() {
        var s = run("show @I3@");
        assertTrue(s.contains("Also known as   Anita Luz  (stage name)\n"
                + "                Nita Sol\n"), s);
    }
}
