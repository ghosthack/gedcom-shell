package ged.shell;

import ged.edit.Editor;
import ged.model.Genealogy;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;

import java.io.PrintStream;

/// What a command can see: the navigator (and through it the data), the
/// output stream, the active styling, and how to launch a text editor.
public record Context(Navigator nav, PrintStream out, Style style, Editor editor) {
    public FileSystem fs() { return nav.fs(); }
    public Genealogy g() { return nav.fs().genealogy(); }
}
