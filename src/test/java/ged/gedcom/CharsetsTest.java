package ged.gedcom;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharsetsTest {

    private static byte[] bytes(Object... parts) {
        var out = new ByteArrayOutputStream();
        for (var p : parts) {
            if (p instanceof String s) out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
            else out.write((Integer) p);
        }
        return out.toByteArray();
    }

    private static final String HEAD_ANSEL = "0 HEAD\n1 CHAR ANSEL\n";

    @Test
    void realAnselCombiningMarksBecomePrecomposed() {
        // "Adri´an Mu~noz" with ANSEL acute (E2) and tilde (E4) before the base letter
        var d = Charsets.decode(bytes(HEAD_ANSEL, "1 NAME Adri", 0xE2, "an /Mu", 0xE4, "noz/\n",
                "1 PLAC M", 0xE8, "unchen\n1 PLAC Fran", 0xF0, "cois\n"));
        assertEquals("ANSEL", d.detected());
        assertTrue(d.text().contains("Adrián /Muñoz/"), d.text());
        assertTrue(d.text().contains("München"));
        assertTrue(d.text().contains("François"));
        assertFalse(d.mismatch());
    }

    @Test
    void anselSpacingCharacters() {
        assertEquals("Łódź", Ansel.decode(bytes(0xA1, 0xE2, "od", 0xE2, "z")));
        assertEquals("Øster", Ansel.decode(bytes(0xA2, "ster")));
    }

    @Test
    void latin1MislabelledAsAnsel() {
        // José (é = E9), María (í = ED), Gómez (ó = F3) in ISO-8859-1
        var d = Charsets.decode(bytes(HEAD_ANSEL, "1 NAME Jos", 0xE9, " /G", 0xF3, "mez/\n",
                "1 NAME Mar", 0xED, "a\n"));
        assertEquals("windows-1252", d.detected());
        assertTrue(d.text().contains("José /Gómez/"), d.text());
        assertTrue(d.text().contains("María"));
        assertTrue(d.mismatch());
    }

    @Test
    void utf8MislabelledAsAnsel() {
        var text = HEAD_ANSEL + "1 NAME Renée /Müller/\n";
        var d = Charsets.decode(text.getBytes(StandardCharsets.UTF_8));
        assertEquals("UTF-8", d.detected());
        assertEquals(text, d.text());
    }

    @Test
    void utf8BomIsStripped() {
        var body = "0 HEAD\n1 CHAR UTF-8\n1 NAME Zoë\n".getBytes(StandardCharsets.UTF_8);
        var withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        var d = Charsets.decode(withBom);
        assertTrue(d.text().startsWith("0 HEAD"));
        assertTrue(d.text().contains("Zoë"));
        assertFalse(d.mismatch());
    }

    @Test
    void utf16WithoutBom() {
        var d = Charsets.decode("0 HEAD\n1 CHAR UNICODE\n1 NAME Ægir\n".getBytes(StandardCharsets.UTF_16LE));
        assertEquals("UTF-16LE", d.detected());
        assertTrue(d.text().contains("Ægir"));
    }

    @Test
    void plainAsciiKeepsDeclaredName() {
        var d = Charsets.decode("0 HEAD\n1 CHAR ANSEL\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals("ANSEL", d.detected());
        assertFalse(d.mismatch());
    }
}
