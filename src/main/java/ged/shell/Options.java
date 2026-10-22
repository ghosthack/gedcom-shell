package ged.shell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// getopt-style option spec and parser. Supports `-l`, bundled `-lr`,
/// `-n 5`, `-n5`, `--depth 5`, `--depth=5`, and `--` to end options.
/// Options and positionals may be interleaved.
public final class Options {
    private record Spec(String shortName, String longName, String arg, String help) {}

    private final List<Spec> specs = new ArrayList<>();

    public Options flag(String shortName, String longName, String help) {
        specs.add(new Spec(shortName, longName, null, help));
        return this;
    }

    public Options value(String shortName, String longName, String arg, String help) {
        specs.add(new Spec(shortName, longName, arg, help));
        return this;
    }

    public record Parsed(Map<String, String> values, List<String> positionals) {
        public boolean has(String longName) { return values.containsKey(longName); }
        public Optional<String> get(String longName) { return Optional.ofNullable(values.get(longName)); }
    }

    public Parsed parse(List<String> args) {
        var values = new LinkedHashMap<String, String>();
        var positionals = new ArrayList<String>();
        var byShort = new HashMap<String, Spec>();
        var byLong = new HashMap<String, Spec>();
        for (var s : specs) {
            if (s.shortName != null) byShort.put(s.shortName, s);
            byLong.put(s.longName, s);
        }
        for (int i = 0; i < args.size(); i++) {
            var a = args.get(i);
            if (a.equals("--")) {
                positionals.addAll(args.subList(i + 1, args.size()));
                break;
            }
            if (a.startsWith("--") && a.length() > 2) {
                int eq = a.indexOf('=');
                var name = eq < 0 ? a.substring(2) : a.substring(2, eq);
                var spec = byLong.get(name);
                if (spec == null) throw new CommandException("unknown option --" + name);
                if (spec.arg == null) {
                    if (eq >= 0) throw new CommandException("--" + name + " takes no value");
                    values.put(spec.longName, "");
                } else if (eq >= 0) {
                    values.put(spec.longName, a.substring(eq + 1));
                } else if (i + 1 < args.size()) {
                    values.put(spec.longName, args.get(++i));
                } else {
                    throw new CommandException("--" + name + " needs a value");
                }
            } else if (a.startsWith("-") && a.length() > 1 && !a.matches("-\\d.*")) {
                for (int j = 1; j < a.length(); j++) {
                    var name = String.valueOf(a.charAt(j));
                    var spec = byShort.get(name);
                    if (spec == null) throw new CommandException("unknown option -" + name);
                    if (spec.arg == null) {
                        values.put(spec.longName, "");
                    } else if (j + 1 < a.length()) {
                        values.put(spec.longName, a.substring(j + 1));
                        break;
                    } else if (i + 1 < args.size()) {
                        values.put(spec.longName, args.get(++i));
                    } else {
                        throw new CommandException("-" + name + " needs a value");
                    }
                }
            } else {
                positionals.add(a);
            }
        }
        return new Parsed(values, positionals);
    }

    /// Option table for `help`.
    public String describe() {
        var sb = new StringBuilder();
        for (var s : specs) {
            var flag = (s.shortName == null ? "    " : "-" + s.shortName + ", ") + "--" + s.longName
                    + (s.arg == null ? "" : " " + s.arg);
            sb.append("  ").append(String.format("%-26s", flag)).append(' ').append(s.help).append('\n');
        }
        return sb.toString();
    }
}
