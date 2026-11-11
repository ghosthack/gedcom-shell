package ged.shell;

import ged.Fixtures;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Commands another jar adds through `META-INF/services/ged.shell.Command`
/// (here `ged.plugin.WhoCommand`, from the test classpath).
class PluginCommandTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Shell shell;

    PluginCommandTest() {
        var g = Fixtures.cousins();
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        shell = new Shell(new Navigator(new FileSystem(g), g.person("I9")), ps, ps, Style.PLAIN);
    }

    private String run(String line) {
        out.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void anAddedCommandRunsLikeABuiltIn() {
        assertEquals("Elena Rossi\n", run("who"));
        assertEquals("LUCA ROSSI\n", run("who -u father"));
        assertFalse(shell.execute("who /people"));
        assertTrue(run("who /people").contains("who: not a person"));
    }

    @Test
    void itIsListedInHelpAndCompleted() {
        assertTrue(run("help").contains("who     print the name of the person at PATH"), run("help"));
        assertTrue(run("help who").startsWith("who [-u] [PATH]"));
        var c = shell.completer();
        assertEquals(List.of("who "), c.complete("wh", 2).candidates().stream().map(x -> x.text() + x.suffix()).toList());
        assertEquals(List.of("father/"), c.complete("who fat", 7).candidates().stream().map(x -> x.text() + x.suffix()).toList());
    }

    @Test
    void aBuiltInKeepsItsName() {
        assertFalse(run("ls").contains("impostor"));
    }
}
