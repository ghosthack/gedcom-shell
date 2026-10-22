package ged.gedcom;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/// Works out how a GEDCOM file is really encoded. The header's `CHAR` tag is
/// treated as a hint only: files labelled `ANSEL` are frequently UTF-8 or
/// Latin-1 in practice.
public final class Charsets {
    private Charsets() {}

    /// Result of decoding: the text, what the header claimed, what we used,
    /// and whether a byte-order mark preceded the text.
    public record Decoded(String text, String declared, String detected, boolean bom) {
        public Decoded(String text, String declared, String detected) {
            this(text, declared, detected, false);
        }

        public boolean mismatch() {
            return !declared.isEmpty() && !normalise(declared).equals(normalise(detected));
        }

        private static String normalise(String name) {
            return switch (name.toUpperCase(Locale.ROOT).replace("-", "")) {
                case "UNICODE", "UTF8", "UTF16", "UTF16LE", "UTF16BE" -> "UNICODE";
                case "ASCII", "USASCII", "ANSI", "WINDOWS1252", "CP1252" -> "ANSI";
                case "LATIN1", "ISO88591" -> "LATIN1";
                default -> name.toUpperCase(Locale.ROOT);
            };
        }
    }

    private static final Pattern CHAR_TAG = Pattern.compile("(?m)^\\s*1\\s+CHAR\\s+(\\S+)");

    public static Decoded decode(byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), declared(bytes, 3), "UTF-8", true);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), "", "UTF-16LE", true);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), "", "UTF-16BE", true);
        }
        if (bytes.length > 1 && bytes[0] == '0' && bytes[1] == 0) {
            return new Decoded(new String(bytes, StandardCharsets.UTF_16LE), "", "UTF-16LE");
        }
        if (bytes.length > 1 && bytes[0] == 0 && bytes[1] == '0') {
            return new Decoded(new String(bytes, StandardCharsets.UTF_16BE), "", "UTF-16BE");
        }

        String declared = declared(bytes, 0);
        if (isAscii(bytes)) {
            return new Decoded(new String(bytes, StandardCharsets.US_ASCII), declared, declared.isEmpty() ? "ASCII" : declared);
        }
        // Valid multi-byte UTF-8 is overwhelmingly unlikely to be an accident.
        var utf8 = strictUtf8(bytes);
        if (utf8 != null) return new Decoded(utf8, declared, "UTF-8");

        return switch (declared.toUpperCase(Locale.ROOT)) {
            case "ANSEL" -> looksLikeAnsel(bytes)
                    ? new Decoded(Ansel.decode(bytes), declared, "ANSEL")
                    : new Decoded(new String(bytes, Charset.forName("windows-1252")), declared, "windows-1252");
            case "IBMPC" -> new Decoded(new String(bytes, Charset.forName("IBM437")), declared, "IBMPC");
            case "MACINTOSH" -> new Decoded(new String(bytes, Charset.forName("x-MacRoman")), declared, "MACINTOSH");
            case "LATIN1", "ISO-8859-1", "ISO8859-1" ->
                    new Decoded(new String(bytes, StandardCharsets.ISO_8859_1), declared, "ISO-8859-1");
            default -> new Decoded(new String(bytes, Charset.forName("windows-1252")), declared, "windows-1252");
        };
    }

    /// Encodes `text` the way the file was read: same charset, same BOM.
    /// Pure-ASCII files are written in the charset their header declares.
    /// Throws [UnmappableException] naming the first character that the
    /// file's charset cannot hold.
    public static byte[] encode(String text, Decoded how) {
        var name = how.detected().equals("ASCII") || how.detected().equals(how.declared())
                ? how.declared() : how.detected();
        var upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("ANSEL")) return Ansel.encode(text);
        Charset cs = switch (upper) {
            case "UTF-8", "UTF8", "UNICODE", "TEXT", "" -> StandardCharsets.UTF_8;
            case "UTF-16LE" -> StandardCharsets.UTF_16LE;
            case "UTF-16BE" -> StandardCharsets.UTF_16BE;
            case "IBMPC" -> Charset.forName("IBM437");
            case "MACINTOSH" -> Charset.forName("x-MacRoman");
            case "LATIN1", "ISO-8859-1", "ISO8859-1" -> StandardCharsets.ISO_8859_1;
            case "ASCII", "US-ASCII" -> StandardCharsets.US_ASCII;
            default -> Charset.forName("windows-1252");
        };
        var encoder = cs.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp >= 0x80 && !encoder.canEncode(Character.toString(cp))) throw new UnmappableException(cp, cs.name());
            i += Character.charCount(cp);
        }
        var body = text.getBytes(cs);
        if (!how.bom()) return body;
        byte[] bom = switch (upper) {
            case "UTF-16LE" -> new byte[] {(byte) 0xFF, (byte) 0xFE};
            case "UTF-16BE" -> new byte[] {(byte) 0xFE, (byte) 0xFF};
            default -> new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        };
        var out = Arrays.copyOf(bom, bom.length + body.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    public static final class UnmappableException extends RuntimeException {
        public UnmappableException(int codePoint, String charset) {
            super("the file is " + charset + ", which cannot store '" + Character.toString(codePoint)
                    + "' (U+%04X)".formatted(codePoint));
        }
    }

    /// ANSEL puts a combining mark *before* the letter it decorates, so each
    /// mark byte is followed by a plausible base letter (acute + vowel,
    /// tilde + n/a/o, ...). In Latin-1 the same bytes are accented letters
    /// followed by arbitrary text ("José ", "Gómez"). We vote per high byte.
    static boolean looksLikeAnsel(byte[] bytes) {
        int ansel = 0, latin = 0;
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) continue;
            if (b >= 0xA1 && b <= 0xC8) continue; // ANSEL spacing chars; ambiguous
            int next = i + 1 < bytes.length ? bytes[i + 1] & 0xFF : ' ';
            if (Ansel.isCombining(b) && Ansel.isCombining(next)) continue; // stacked marks
            String bases = switch (b) {
                case 0xE1, 0xE2, 0xE3, 0xE8 -> "aeiouyAEIOUYnwNWcszCSZ";
                case 0xE4 -> "anoeiuANOEIU";
                case 0xE9 -> "cdenrstzCDENRSTZ";
                case 0xEA -> "auAU";
                case 0xF0 -> "csCSgkltGKLT";
                default -> null;
            };
            if (bases != null && bases.indexOf(next) >= 0) ansel++;
            else latin++;
        }
        return ansel > 0 && latin * 10 <= ansel;
    }

    private static String declared(byte[] bytes, int offset) {
        var head = new String(bytes, offset, Math.min(bytes.length - offset, 4096), StandardCharsets.ISO_8859_1);
        var m = CHAR_TAG.matcher(head);
        return m.find() ? m.group(1) : "";
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean isAscii(byte[] bytes) {
        for (byte b : bytes) if (b < 0) return false;
        return true;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        var head = Arrays.copyOf(bytes, prefix.length);
        for (int i = 0; i < prefix.length; i++) if ((head[i] & 0xFF) != prefix[i]) return false;
        return true;
    }
}
