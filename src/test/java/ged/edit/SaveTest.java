package ged.edit;

import ged.gedcom.Charsets;
import ged.model.Genealogy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveTest {
    @TempDir Path dir;

    private static final List<String> SAMPLES = List.of("bonaparteFamily.ged", "bourbonRoyalty.ged",
            "danishRoyalty.ged", "genealogyJohnDoe.ged", "habsburgRoyalty.ged",
            "mediciFamily.ged", "romanovRoyalty.ged", "test_divorce.ged", "windsorRoyalty.ged");

    private Path copy(String sample) throws Exception {
        var target = dir.resolve(sample);
        Files.copy(Path.of(getClass().getResource("/" + sample).toURI()), target);
        return target;
    }

    private Path write(String name, byte[] bytes) throws Exception {
        var p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private static byte[] bytes(Object... parts) {
        var out = new ByteArrayOutputStream();
        for (var p : parts) {
            if (p instanceof String s) out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
            else out.write((Integer) p);
        }
        return out.toByteArray();
    }

    /// Edits person `id` through the same path the `edit` command uses.
    private static void edit(Genealogy g, String id, Map<PersonForm.Field, String> values) {
        var p = g.person(id).orElseThrow();
        PersonForm.apply(g, p, PersonForm.diff(p, values));
    }

    @Test
    void unchangedFilesRoundTripByteForByte() throws Exception {
        for (var sample : SAMPLES) {
            var original = copy(sample);
            var before = Files.readAllBytes(original);
            var g = Genealogy.load(original);
            var out = dir.resolve("out-" + sample);
            g.save(out);
            assertArrayEquals(before, Files.readAllBytes(out), sample);
        }
    }

    @Test
    void editChangesOnlyTheEditedLines() throws Exception {
        var file = copy("romanovRoyalty.ged");
        var before = Files.readAllLines(file);
        var g = Genealogy.load(file);
        edit(g, "I1", Map.of(PersonForm.Field.BIRTH_PLACE, "Pushkin, Russia"));
        assertTrue(g.isDirty());
        g.save(file);
        assertFalse(g.isDirty());

        var after = Files.readAllLines(file);
        assertEquals(before.size(), after.size());
        var changed = new java.util.ArrayList<String>();
        for (int i = 0; i < before.size(); i++) if (!before.get(i).equals(after.get(i))) changed.add(after.get(i));
        // the PLAC line, plus the record's CHAN DATE and TIME
        assertEquals(3, changed.size(), changed.toString());
        assertEquals("2 PLAC Pushkin, Russia", changed.getFirst());
        assertEquals("Pushkin, Russia", Genealogy.load(file).person("I1").orElseThrow().event("BIRT").orElseThrow().place());
    }

    @Test
    void ansellFileStaysAnsel() throws Exception {
        var file = write("ansel.ged", bytes("0 HEAD\r\n1 CHAR ANSEL\r\n0 @I1@ INDI\r\n1 NAME Jos", 0xE2, "e /Mu", 0xE4, "noz/\r\n0 TRLR\r\n"));
        var g = Genealogy.load(file);
        assertEquals("ANSEL", g.encoding().detected());
        edit(g, "I1", Map.of(PersonForm.Field.BIRTH_PLACE, "Málaga, España"));
        g.save(file);
        var expected = bytes("0 HEAD\r\n1 CHAR ANSEL\r\n0 @I1@ INDI\r\n1 NAME Jos", 0xE2, "e /Mu", 0xE4, "noz/\r\n",
                "1 BIRT\r\n2 PLAC M", 0xE2, "alaga, Espa", 0xE4, "na\r\n0 TRLR\r\n");
        assertArrayEquals(expected, Files.readAllBytes(file));
    }

    @Test
    void latin1FileStaysLatin1() throws Exception {
        var file = write("latin.ged", bytes("0 HEAD\n1 CHAR ANSEL\n0 @I1@ INDI\n1 NAME Jos", 0xE9, " /G", 0xF3, "mez/\n0 TRLR\n"));
        var g = Genealogy.load(file);
        edit(g, "I1", Map.of(PersonForm.Field.GIVEN, "José María"));
        g.save(file);
        assertArrayEquals(bytes("0 HEAD\n1 CHAR ANSEL\n0 @I1@ INDI\n1 NAME Jos", 0xE9, " Mar", 0xED, "a /G", 0xF3, "mez/\n0 TRLR\n"),
                Files.readAllBytes(file));
    }

    @Test
    void unencodableCharacterIsRefusedAndNothingIsWritten() throws Exception {
        var original = bytes("0 HEAD\n1 CHAR ANSEL\n0 @I1@ INDI\n1 NAME Jos", 0xE9, "\n0 TRLR\n");
        var file = write("latin.ged", original);
        var g = Genealogy.load(file);
        edit(g, "I1", Map.of(PersonForm.Field.SURNAME, "Wąsowicz-李"));
        assertThrows(Charsets.UnmappableException.class, () -> g.save(file));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(g.isDirty());
    }

    @Test
    void bomCrlfMissingFinalNewlineAndJunkLinesSurvive() throws Exception {
        var text = "0 HEAD\r\n1 CHAR UTF-8\r\n0 @I1@ INDI\r\n1 NAME Zoë /Smith/\r\n\r\nstray junk\r\n0 TRLR";
        var body = text.getBytes(StandardCharsets.UTF_8);
        var withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        var file = write("bom.ged", withBom);

        var g = Genealogy.load(file);
        edit(g, "I1", Map.of(PersonForm.Field.SEX, "F"));
        g.save(file);
        var expected = "﻿0 HEAD\r\n1 CHAR UTF-8\r\n0 @I1@ INDI\r\n1 NAME Zoë /Smith/\r\n1 SEX F\r\n\r\nstray junk\r\n0 TRLR";
        assertEquals(expected, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void saveKeepsPermissionsAndCopiesDontClearDirty() throws Exception {
        var file = copy("test_divorce.ged");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        var g = Genealogy.load(file);
        edit(g, g.people().getFirst().key(), Map.of(PersonForm.Field.SEX, "U"));

        g.save(dir.resolve("copy.ged"));
        assertTrue(g.isDirty(), "a copy elsewhere doesn't save the original");
        g.save(file);
        assertFalse(g.isDirty());
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void longValuesAreSplitWithConc() {
        var g = Genealogy.of("0 @I1@ INDI\n1 NAME A /B/\n0 TRLR\n");
        edit(g, "I1", Map.of(PersonForm.Field.BIRTH_PLACE, "x".repeat(450)));
        var text = g.render();
        assertTrue(text.contains("\n3 CONC "), text);
        var reparsed = Genealogy.of(text).person("I1").orElseThrow();
        assertEquals("x".repeat(450), reparsed.event("BIRT").orElseThrow().place());
    }
}
