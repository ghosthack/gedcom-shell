package ged.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoTest {
    private static Genealogy with(String... lines) {
        return Genealogy.of("0 HEAD\n1 CHAR UTF-8\n" + String.join("\n", lines) + "\n0 TRLR\n");
    }

    private static List<Photo> photos(Genealogy g, String id) {
        return g.photos(g.person(id).orElseThrow());
    }

    @Test
    void oneGroupPhotoServesTwoPeopleEachWithTheirOwnCrop() {
        var g = with(
                "0 @I1@ INDI", "1 NAME Ana /Paz/", "1 OBJE @M1@",
                "2 CROP", "3 TOP 410", "3 LEFT 1220", "3 HEIGHT 300", "3 WIDTH 280",
                "0 @I2@ INDI", "1 NAME Luis /Paz/", "1 OBJE @M1@",
                "2 _CROP", "3 TOP 10", "3 LEFT 20",
                "0 @M1@ OBJE", "1 FILE photos/wedding.jpg", "2 FORM image/jpeg", "2 TITL Wedding, 1952");
        assertEquals(List.of(new Photo("photos/wedding.jpg", "Wedding, 1952", Optional.of(new Photo.Crop(1220, 410, 280, 300)))),
                photos(g, "I1"));
        assertEquals(Optional.of(new Photo.Crop(20, 10, 0, 0)), photos(g, "I2").getFirst().crop());
    }

    @Test
    void theFirstPhotoIsPreferredUnlessOneIsMarkedPrimary() {
        var g = with(
                "0 @I1@ INDI", "1 OBJE @M1@", "1 OBJE", "2 FILE b.jpg", "2 TITL Inline", "1 OBJE @M3@", "2 _PRIM Y",
                "0 @M1@ OBJE", "1 FILE a.jpg",
                "0 @M3@ OBJE", "1 FILE c.jpg");
        assertEquals(List.of("c.jpg", "a.jpg", "b.jpg"), photos(g, "I1").stream().map(Photo::file).toList());
        assertEquals("Inline", photos(g, "I1").getLast().title());
    }

    @Test
    void aPhotoCanSayWhenItWasTaken() {
        var g = with(
                "0 @I1@ INDI", "1 OBJE @M1@", "2 _DATE 1952", "1 OBJE @M2@", "1 OBJE @M3@", "1 OBJE @M4@",
                "0 @M1@ OBJE", "1 FILE a.jpg", "1 DATE 1999",
                "0 @M2@ OBJE", "1 FILE b.jpg", "2 _DATE 12 JUN 1960",
                "0 @M3@ OBJE", "1 FILE c.jpg", "1 _DATE ABT 1970",
                "0 @M4@ OBJE", "1 FILE d.jpg");
        assertEquals(List.of(1952, 1960, 1970, 0), photos(g, "I1").stream().map(p -> p.date().year()).toList());
    }

    @Test
    void linksThatLeadNowhereAreLeftOut() {
        var g = with("0 @I1@ INDI", "1 OBJE @M9@", "1 OBJE @VOID@", "1 OBJE", "2 TITL Teacher",
                "1 OBJE @M1@", "2 CROP", "3 TOP -5",
                "0 @M1@ OBJE", "1 FILE a.jpg");
        assertEquals(List.of(new Photo("a.jpg", "", Optional.empty())), photos(g, "I1"));
    }

    @Test
    void filesAreFoundBesideTheGedcom(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("photos"));
        Files.write(dir.resolve("photos/a b.jpg"), new byte[] {1});
        var ged = dir.resolve("f.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n0 @I1@ INDI\n0 TRLR\n");
        var g = Genealogy.load(ged);
        var there = dir.resolve("photos/a b.jpg").toRealPath();
        assertEquals(there, g.mediaPath("photos/a b.jpg").orElseThrow().toRealPath());
        assertEquals(there, g.mediaPath(there.toString()).orElseThrow().toRealPath());
        assertEquals(there, g.mediaPath(there.toUri().toString()).orElseThrow().toRealPath());
        assertTrue(g.mediaPath("photos/missing.jpg").isEmpty());
        assertTrue(g.mediaPath("https://example.com/a.jpg").isEmpty());
        assertTrue(with("0 @I1@ INDI").mediaPath("photos/a b.jpg").isEmpty());
    }

    @Test
    void aRecordsSeveralFilesAreOnePicture() {
        var g = with(
                "0 @I1@ INDI", "1 OBJE @M1@", "2 CROP", "3 TOP 5", "3 LEFT 6", "1 OBJE @M2@", "1 OBJE @M3@",
                "0 @M1@ OBJE", "1 FILE a.tif", "2 FORM image/tiff", "1 FILE a.jpg", "2 FORM image/jpeg",
                "0 @M2@ OBJE", "1 FILE speech.mp3", "2 FORM audio/mpeg", "1 FILE portrait.png",
                "0 @M3@ OBJE", "1 FILE letter.pdf", "1 FILE letter.txt");
        var photos = photos(g, "I1");
        assertEquals(List.of("a.tif", "portrait.png", "letter.pdf"), photos.stream().map(Photo::file).toList());
        assertEquals(Optional.of(new Photo.Crop(6, 5, 0, 0)), photos.getFirst().crop());
    }

    @Test
    void aRecordCanBeMarkedPrimaryUnlessTheLinkSaysNo() {
        var g = with(
                "0 @I1@ INDI", "1 OBJE @M1@", "1 OBJE @M2@", "1 OBJE @M3@", "2 _PRIM N",
                "0 @M1@ OBJE", "1 FILE a.jpg",
                "0 @M2@ OBJE", "1 FILE b.jpg", "1 _PRIM Y",
                "0 @M3@ OBJE", "1 FILE c.jpg", "1 _PRIM Y");
        assertEquals(List.of("b.jpg", "a.jpg", "c.jpg"), photos(g, "I1").stream().map(Photo::file).toList());
    }
}
