package ged.model;

import ged.gedcom.Node;

import java.util.Map;

/// A dated/placed fact on a person or family (birth, marriage, residence...).
public record Event(String tag, String type, GedDate date, String place, String detail, Node node) {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("BIRT", "Born"), Map.entry("CHR", "Christened"), Map.entry("BAPM", "Baptised"),
            Map.entry("DEAT", "Died"), Map.entry("BURI", "Buried"), Map.entry("CREM", "Cremated"),
            Map.entry("ADOP", "Adopted"), Map.entry("BARM", "Bar mitzvah"), Map.entry("BASM", "Bas mitzvah"),
            Map.entry("CONF", "Confirmed"), Map.entry("FCOM", "First communion"), Map.entry("ORDN", "Ordained"),
            Map.entry("NATU", "Naturalised"), Map.entry("EMIG", "Emigrated"), Map.entry("IMMI", "Immigrated"),
            Map.entry("CENS", "Census"), Map.entry("PROB", "Probate"), Map.entry("WILL", "Will"),
            Map.entry("GRAD", "Graduated"), Map.entry("RETI", "Retired"), Map.entry("RESI", "Residence"),
            Map.entry("OCCU", "Occupation"), Map.entry("EDUC", "Education"), Map.entry("RELI", "Religion"),
            Map.entry("TITL", "Title"), Map.entry("NATI", "Nationality"), Map.entry("DSCR", "Description"),
            Map.entry("PROP", "Property"), Map.entry("NCHI", "Children"), Map.entry("NMR", "Marriages"),
            Map.entry("MARR", "Married"), Map.entry("DIV", "Divorced"), Map.entry("ENGA", "Engaged"),
            Map.entry("MARB", "Banns"), Map.entry("MARC", "Marriage contract"), Map.entry("MARL", "Marriage licence"),
            Map.entry("MARS", "Marriage settlement"), Map.entry("ANUL", "Annulled"), Map.entry("DIVF", "Divorce filed"),
            Map.entry("EVEN", "Event"), Map.entry("FACT", "Fact"));

    /// Tags that are facts/events when they appear at level 1 of INDI or FAM.
    public static boolean isEventTag(String tag) {
        return LABELS.containsKey(tag);
    }

    static Event of(Node n) {
        var addr = n.first("ADDR").map(a -> joinAddress(a)).orElse("");
        var place = n.text("PLAC");
        if (place.isEmpty()) place = addr;
        return new Event(n.tag(), n.text("TYPE"), GedDate.parse(n.text("DATE")), place, n.value(), n);
    }

    private static String joinAddress(Node addr) {
        var sb = new StringBuilder(addr.value().replace('\n', ' '));
        for (var t : new String[] {"CITY", "STAE", "CTRY"}) {
            var v = addr.text(t);
            if (!v.isEmpty() && !sb.toString().contains(v)) sb.append(sb.isEmpty() ? "" : ", ").append(v);
        }
        return sb.toString();
    }

    public String label() {
        return LABELS.getOrDefault(tag, tag);
    }

    public boolean isEmpty() {
        return date.isEmpty() && place.isEmpty() && detail.isEmpty() && type.isEmpty();
    }
}
