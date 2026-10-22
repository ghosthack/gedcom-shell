package ged.gedcom;

import java.text.Normalizer;

/// Decoder for ANSEL (ANSI Z39.47), the GEDCOM 5.x legacy character set.
///
/// ANSEL writes combining diacritics *before* their base letter; Unicode
/// writes them after. We reorder and then NFC-normalise, so `0xE2 'a'`
/// becomes the precomposed `á`.
public final class Ansel {
    private Ansel() {}

    /// Spacing characters, indexed by byte - 0xA0. `0` = unassigned.
    private static final char[] SPACING = chars(
            0x0000, 0x0141, 0x00D8, 0x0110, 0x00DE, 0x00C6, 0x0152, 0x02B9, // A0-A7
            0x00B7, 0x266D, 0x00AE, 0x00B1, 0x01A0, 0x01AF, 0x02BC, 0x0000, // A8-AF
            0x02BB, 0x0142, 0x00F8, 0x0111, 0x00FE, 0x00E6, 0x0153, 0x02BA, // B0-B7
            0x0131, 0x00A3, 0x00F0, 0x0000, 0x01A1, 0x01B0, 0x0000, 0x0000, // B8-BF
            0x00B0, 0x2113, 0x2117, 0x00A9, 0x266F, 0x00BF, 0x00A1, 0x00DF, // C0-C7
            0x20AC, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000); // C8-CF

    /// Combining marks, indexed by byte - 0xE0. `0` = unassigned.
    private static final char[] COMBINING = chars(
            0x0309, 0x0300, 0x0301, 0x0302, 0x0303, 0x0304, 0x0306, 0x0307, // E0-E7
            0x0308, 0x030C, 0x030A, 0xFE20, 0xFE21, 0x0315, 0x030B, 0x0310, // E8-EF
            0x0327, 0x0328, 0x0323, 0x0324, 0x0325, 0x0333, 0x0332, 0x0326, // F0-F7
            0x031C, 0x032E, 0xFE22, 0xFE23, 0x0000, 0x0000, 0x0313);        // F8-FE

    private static char[] chars(int... codes) {
        var out = new char[codes.length];
        for (int i = 0; i < codes.length; i++) out[i] = (char) codes[i];
        return out;
    }

    /// Inverse of [#decode]: each base letter's combining marks are written
    /// before it. Throws [Charsets.UnmappableException] for characters
    /// ANSEL has no code for.
    public static byte[] encode(String text) {
        var nfd = Normalizer.normalize(text, Normalizer.Form.NFD);
        var out = new java.io.ByteArrayOutputStream(nfd.length());
        var marks = new java.io.ByteArrayOutputStream();
        int base = -1;
        for (int i = 0; i <= nfd.length(); ) {
            int cp = i < nfd.length() ? nfd.codePointAt(i) : -1;
            int mark = cp < 0 ? -1 : indexOf(COMBINING, cp);
            if (mark >= 0 && base >= 0) {
                marks.write(0xE0 + mark);
            } else {
                if (base >= 0) {
                    out.writeBytes(marks.toByteArray());
                    out.write(byteFor(base));
                    marks.reset();
                }
                if (cp >= 0 && mark >= 0) throw new Charsets.UnmappableException(cp, "ANSEL");
                base = cp;
            }
            i += cp < 0 ? 1 : Character.charCount(cp);
        }
        return out.toByteArray();
    }

    private static int byteFor(int cp) {
        if (cp < 0x80) return cp;
        int spacing = indexOf(SPACING, cp);
        if (spacing > 0) return 0xA0 + spacing;
        throw new Charsets.UnmappableException(cp, "ANSEL");
    }

    private static int indexOf(char[] table, int cp) {
        for (int i = 0; i < table.length; i++) if (table[i] != 0 && table[i] == cp) return i;
        return -1;
    }

    public static boolean isCombining(int b) {
        return b >= 0xE0 && b <= 0xFE && COMBINING[b - 0xE0] != 0;
    }

    public static String decode(byte[] bytes) {
        var out = new StringBuilder(bytes.length);
        var pending = new StringBuilder();
        for (byte raw : bytes) {
            int b = raw & 0xFF;
            if (isCombining(b)) {
                pending.append(COMBINING[b - 0xE0]);
                continue;
            }
            if (b < 0x80) {
                out.append((char) b);
            } else if (b >= 0xA1 && b <= 0xCF && SPACING[b - 0xA0] != 0) {
                out.append(SPACING[b - 0xA0]);
            } else {
                out.append('�');
            }
            if (!pending.isEmpty()) {
                out.append(pending);
                pending.setLength(0);
            }
        }
        out.append(pending);
        return Normalizer.normalize(out, Normalizer.Form.NFC);
    }
}
