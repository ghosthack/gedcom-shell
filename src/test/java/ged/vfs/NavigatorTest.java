package ged.vfs;

import ged.Fixtures;
import ged.model.Genealogy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigatorTest {
    private Genealogy g;
    private Navigator nav;

    @BeforeEach
    void setUp() {
        g = Fixtures.cousins();
        nav = new Navigator(new FileSystem(g), g.person("I9"));
    }

    private String cd(String path) {
        nav.cd(nav.resolve(path));
        return nav.cwd().text();
    }

    private String here() {
        return nav.cwd().location().canonicalPath();
    }

    @Test
    void startsAtHome() {
        assertEquals("/people/I9", nav.cwd().text());
    }

    @Test
    void walksRelationsAndBackLogically() {
        assertEquals("/people/I9/father/father", cd("father/father"));
        assertEquals("/people/I3", here());
        assertEquals("/people/I9/father", cd(".."));
        assertEquals("/people/I7", here());
    }

    @Test
    void rowNumbersFollowLs() {
        // rows of Elena: father (Luca), mother (Sofia)
        cd("2");
        assertEquals("/people/I8", here());
        // rows of Sofia: father, mother, spouses/I7, children/I9
        cd("4");
        assertEquals("/people/I9", here());
        assertEquals("/people/I9/mother/children/I9", nav.cwd().text());
    }

    @Test
    void groupsAndIds() {
        cd("/people/I1/children/I4");
        assertEquals("/people/I4", here());
        cd("@F2@");
        assertEquals("/families/F2", nav.cwd().text());
        cd("children/1");
        assertEquals("/people/I7", here());
        cd("I1");
        assertEquals("/people/I1", nav.cwd().text());
    }

    @Test
    void nameFragmentsResolveLocallyThenGlobally() {
        cd("sofia");
        assertEquals("/people/I9/mother", nav.cwd().text());
        cd("paolo"); // Sofia's father, a local row
        assertEquals("/people/I9/mother/father", nav.cwd().text());
        cd("jose perez"); // not a relative of Paolo: global jump
        assertEquals("/people/I5", nav.cwd().text());
    }

    @Test
    void ambiguousNamesListCandidates() {
        var e = assertThrows(Navigator.NotFound.class, () -> nav.resolve("/people/rossi"));
        assertTrue(e.getMessage().contains("people match"), e.getMessage());
        assertTrue(e.getMessage().contains("Giovanni Rossi"));
    }

    @Test
    void homePreviousAndRoot() {
        cd("/families");
        cd("~/mother");
        assertEquals("/people/I9/mother", nav.cwd().text());
        cd("-");
        assertEquals("/families", nav.cwd().text());
        cd("/");
        assertInstanceOf(Location.Root.class, nav.cwd().location());
        cd("..");
        assertEquals("/", nav.cwd().text());
    }

    @Test
    void notFoundIsReported() {
        assertThrows(Navigator.NotFound.class, () -> nav.resolve("grandmother"));
        assertThrows(Navigator.NotFound.class, () -> nav.resolve("@I999@"));
        assertThrows(Navigator.NotFound.class, () -> nav.resolve("99"));
    }
}
