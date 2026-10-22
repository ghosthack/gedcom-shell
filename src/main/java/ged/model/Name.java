package ged.model;

import ged.gedcom.Node;

/// A personal name. GEDCOM marks the surname with slashes: `John /Doe/ Jr.`
public record Name(String given, String surname, String suffix) {
    public static final Name EMPTY = new Name("", "", "");

    static Name of(Node n) {
        var v = n.value().strip();
        int a = v.indexOf('/');
        int b = a < 0 ? -1 : v.indexOf('/', a + 1);
        String given, surname, suffix;
        if (a >= 0 && b > a) {
            given = v.substring(0, a).strip();
            surname = v.substring(a + 1, b).strip();
            suffix = v.substring(b + 1).strip();
        } else {
            given = v.replace("/", "").strip();
            surname = "";
            suffix = "";
        }
        if (given.isEmpty()) given = n.text("GIVN");
        if (surname.isEmpty()) surname = n.text("SURN");
        if (suffix.isEmpty()) suffix = n.text("NSFX");
        return new Name(given, surname, suffix);
    }

    public String full() {
        var sb = new StringBuilder(given);
        if (!surname.isEmpty()) sb.append(sb.isEmpty() ? "" : " ").append(surname);
        if (!suffix.isEmpty()) sb.append(sb.isEmpty() ? "" : " ").append(suffix);
        return sb.toString();
    }

    public String display() {
        var f = full();
        return f.isEmpty() ? "(unnamed)" : f;
    }
}
