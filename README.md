# ged-browser

Browse a GEDCOM genealogy file the way you browse a filesystem. People and
families are directories; a person's relatives are the entries inside.

```
$ bin/ged-browser royals.ged
Nicholas II I1 › ls
 #  PATH          ID   REL       NAME                           SEX  BORN  DIED
 1  father        I8   father    Alexander III                  M    1845  1894
 2  mother        I9   mother    Maria Feodorovna of Denmark    F    1847  1928
 3  spouses/I2    I2   wife      Alexandra Feodorovna of Hesse  F    1872  1918
 4  children/I3   I3   daughter  Olga Nikolaevna Romanova       F    1895  1918
 …
Nicholas II I1 › cd 4
Olga Nikolaevna Romanova I3 › tree -a
```

Requires JDK 26 and Maven 3.9+. No runtime dependencies.

```
mvn package                         # builds target/ged-browser.jar, runs tests
bin/ged-browser FILE.ged            # interactive (line editing + history)
bin/ged-browser FILE.ged -c 'find maria; show /found/1'
echo 'tree -a -n 5' | bin/ged-browser FILE.ged
```

Options: `-c CMDS` run and exit · `-H ID` home person (default: first in
file) · `--no-color` (also honours `NO_COLOR`). Exit status is 1 if any
command failed, 2 for usage errors, which makes it scriptable.

## The filesystem

```
/
├── people/            I1, I2, …              every person, by GEDCOM id
│   └── I1/            father, mother          → the parent
│                      parents/ spouses/ children/ siblings/ families/
├── families/          F1, F2, …
│   └── F1/            husband, wife, children/
└── found/             results of the last `find`
```

A path segment can be any of:

| Segment              | Meaning                                                    |
|----------------------|------------------------------------------------------------|
| `father`, `children` | an entry name                                              |
| `3`                  | row 3 of `ls` for that directory                           |
| `@I12@` or `I12`     | a record id, from anywhere (jumps to `/people/I12`)        |
| `maria`, `"jose p"`  | a unique name match, case- and accent-insensitive; tries relatives first, then everyone |
| `..`                 | step back along the path you took (logical, like a shell)  |
| `~`, `/`, `-`        | home person, root, previous directory                      |

So `cd father/mother/children/2` walks the tree, and `cd ..` undoes the last
step rather than guessing which "parent" you meant. `pwd` shows the route;
`pwd -P` shows where you ended up.

## Commands

`help COMMAND` gives full usage for each command.

| Command | Synopsis | Notes |
|---|---|---|
| `ls` | `ls [-l] [-s KEY] [-r] [PATH...]` | At a person, lists all relatives with a cd-able `PATH`. Sort keys: `name surname id birth death birthday rel`. Row numbers stay with their rows when sorted, so `cd 3` is unambiguous. Undated people sort last in either direction. `-l` adds full dates and places. |
| `cd` | `cd [PATH]` | No argument goes home. Ambiguous names list the candidates. |
| `pwd` | `pwd [-P]` | |
| `show` | `show [-r] [PATH...]` | All facts in file order, parents/siblings, each marriage with children, notes (shared `@N@` notes resolved), age at death, last change. `-r` prints the raw GEDCOM record. `show /` summarises the file: encoding (and whether the header lied), counts, birth range, broken links. |
| `tree` | `tree [-a\|-d] [-n N\|--all] [-S] [PATH]` | Descendants by default, grouped under each partner (`∞`, with family id and marriage year); `-S` lists children directly. `-a` draws the pedigree with labels (father → grandfather → great-grandfather → 2nd great-grandfather …). Anyone reached twice (cousin marriages, pedigree collapse) is drawn once and then marked `↻`. |
| `find` | `find [OPTIONS] [NAME...]` | All NAME words must match. Filters: `-b/--born RANGE`, `-d/--died RANGE`, `-p/--place TEXT`, `-x/--sex M\|F\|U`, `--ancestors-of PATH`, `--descendants-of PATH`. RANGE: `1850`, `1800..1850`, `..1700`, `1900..`. Results go to `/found`. |

| `edit` | `edit [PATH] [FIELD=VALUE...]` | Change name, sex, birth/death date and place, deceased. See below. |
| `save` | `save [FILE]` | Write edits back. With FILE, writes a copy and leaves the original (and the unsaved state) alone. |

Aliases: `ll` = `ls -l`, `cat` = `show`, `search` = `find`, `quit` = `exit`, `vi` = `edit`, `w` = `save`.
Separate commands with `;`. `#` starts a comment. Quote names that contain spaces.

## Editing

`edit` opens the person in `$VISUAL` / `$EDITOR` (default `vi`) as a short form:

```
given:        Alexei Nikolaevich
surname:      Romanov
suffix:
sex:          M

birth.date:   12 AUG 1904
birth.place:  Peterhof Palace, Russia

death.date:   17 JUL 1918
death.place:  Yekaterinburg, Russia
deceased:     yes
```

Save and quit to apply. Quit without saving, or `:cq`, to cancel. If a value
is invalid (`31 FEB 1900`, `sex: X`), the form reopens with the error at the
top. The same fields work without an editor, for scripts:

```
edit mother sex=F born="12 jun 1850" birthplace="Genova, Italia"
bin/ged-browser tree.ged -c 'edit @I12@ died="abt 1920" deathplace=Roma; save'
```

- **Dates are checked and normalised**: `12 june 1850` and `1850-06-12`
  become `12 JUN 1850`, and `circa 1850` becomes `ABT 1850`. `BEF`, `AFT`,
  `BET … AND …`, `FROM … TO …` and `(free text)` are also accepted. A date
  you leave unchanged is never rewritten, even if its existing form is
  unusual.
- **Death and deceased are kept consistent**: a death date or place implies
  `deceased: yes`. `deceased: yes` with no details writes `1 DEAT Y`.
  `deceased: no` removes the death record, but refuses if that record has
  sources or notes, or there is a burial record, so nothing is lost silently.
- **Edits stay in memory until `save`**. The prompt shows `*` while there
  are unsaved changes, and `exit` (or Ctrl-D) warns once before discarding them.
- **Saving produces a minimal diff.** Records you didn't edit are copied byte for
  byte, and inside an edited record only the changed lines are regenerated.
  The file keeps its encoding (UTF-8, ANSEL, Latin-1, …), line endings, BOM
  and permissions. The record's existing `CHAN` date is updated, as
  genealogy programs do. A character the file's encoding can't hold is
  refused before anything is written. The write is atomic.

### Changes from the JS navigator

- `tree -a` replaces both `tree N ancestors` and `ancestors`. `ls -s birthday`
  replaces `ls -b`.
- `parents`, `children`, `siblings` and `spouses` are directories now:
  `ls children`, `cd siblings/2`.
- `stats` is `show /`.
- `cd ..` is logical (it retraces your path) instead of "go back in history".
  History is `cd -`.

## Design

```
ged.gedcom   bytes → text → Node tree     encoding detection, ANSEL, CONT/CONC
ged.model    Node tree → Person/Family    Genealogy = indexes + relationship queries
ged.vfs      Location, FileSystem, Navigator   the directory view and path resolution
ged.shell    Shell, Tokenizer, Options, one class per command
ged.edit     PersonForm (form ⇄ fields ⇄ GEDCOM nodes), Editor ($EDITOR launcher)
ged.text     Style (ANSI, width-aware), Table
```

- **Parse to a generic tree first.** The parser only knows the line grammar,
  and typed records are views over the nodes. Nothing is dropped, so
  `show -r` can print the original record. This also rules out the old
  "CHAN date overwrites BIRT date" bug, because nothing tracks a "current
  event" while reading.
- **The `CHAR` header is a hint, not the truth.** BOM → UTF-16 → strict UTF-8 →
  declared charset. For files labelled ANSEL that contain high bytes, a
  heuristic tells real ANSEL (a combining mark followed by a plausible base
  letter) from mislabelled Latin-1/Windows-1252. ANSEL marks are moved after
  their base letter and NFC-normalised, so `á` is one character.
- **Entries and rows.** `FileSystem.entries()` holds the named children a path
  walks through. `FileSystem.rows()` holds what `ls` prints and what row
  numbers index into; for a person, that's the flattened relatives.
- **One code path for relationships.** `Genealogy` answers parents, spouses,
  children, siblings, ancestors and descendants. Ancestor and descendant
  walks are cycle-safe, since malformed files can make someone their own
  ancestor.
- **Zero dependencies.** JDK 22+'s `System.console()` already provides line
  editing and history. Style and tables work out display width themselves,
  so accented and CJK names line up.

## Not (yet) supported

Editing families (marriage details, adding or linking people), editing
other events (baptism, residence…), tab completion (would need JLine), half-siblings through
separate families, GEDCOM 7 specifics (`SNOTE`, `EXID`), non-Gregorian
calendar arithmetic (dates are shown verbatim), and export/convert from the
JS version.
