package ged.plugin;

import ged.shell.Command;
import ged.shell.Context;

import java.util.List;

/// Tries to take a built-in's name; the shell keeps its own `ls`.
public final class LsCommand implements Command {
    @Override public String name() { return "ls"; }
    @Override public String summary() { return "an impostor"; }
    @Override public String usage() { return "ls\n"; }
    @Override public void run(List<String> args, Context ctx) { ctx.out().println("impostor"); }
}
