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

Requires JDK 25 and Maven 3.9+. No runtime dependencies.

```
mvn package                         # builds target/ged-browser.jar, runs tests
bin/ged-browser FILE.ged            # interactive prompt (↑/↓ history, Tab completion, line editing)
bin/ged-browser FILE.ged -c 'find maria; show /found/1'
echo 'tree -a -n 5' | bin/ged-browser FILE.ged
```

Options: `-c CMDS` run and exit · `-H ID` home person (default: first in
file) · `--no-color` (also honours `NO_COLOR`). Exit status is 1 if any
command failed, 2 for usage errors, which makes it scriptable.

## The prompt

| Key                     | Action                                   |
|-------------------------|------------------------------------------|
| ↑ / ↓ (Ctrl-P / Ctrl-N) | previous / next command                  |
| ← / → (Ctrl-B / Ctrl-F) | move; Ctrl/Alt + ← / → move by word      |
| Home / End (Ctrl-A / E) | start / end of line                      |
| Backspace / Delete      | delete before / under the cursor         |
| Ctrl-U / Ctrl-K / Ctrl-W| delete to start / to end / previous word |
| Tab                     | complete; again to list the choices      |
| Ctrl-L                  | clear the screen                         |
| Ctrl-C                  | discard the line                         |
| Ctrl-D                  | quit (on an empty line)                  |

Tab completes command names, options and their values (`chart --theme
ba` → `baroque`), and paths: `cd fa` → `cd father/`, and Tab after a `/`
lists what's inside, with who each entry is. A word that names no entry is
read as a name, as `cd` would: `cd olga` becomes `cd children/I3/` when Olga
is the only match, and when several people match it lists them.

History is kept in `~/.ged_browser_history` (last 1000 commands). Start a
command with a space to keep it out of history. The editor is built in: it
uses `stty` to switch the terminal to raw mode only while you type, so it
needs no libraries. Without `stty` (Windows) it falls back to the console's
own line input, which on Windows already recalls commands with ↑/↓.

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

`father` and `mother` always come from one family: the first the person
belongs to by birth (`FAMC` with no `PEDI`, or `PEDI birth`), or, if no
birth family names a parent, the first adoptive or foster one. `parents/`
lists everyone from every family. `siblings/` lists full siblings, then
half-siblings (children of a parent's other families) as `half-brother` /
`half-sister`.

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
| `chart` | `chart [-a\|-d] [--timeline] [-n N\|--all] [-S] [-o FILE] [--scale N] [-t TITLE] [--lang en\|es] [--notes] [--theme modern\|classic\|baroque\|sephirot] [--arms] [--dark\|--light] [PATH]` | Writes a family-tree picture to share: one self-contained HTML file (no internet needed) with ancestors to the left, descendants and their partners to the right, and one labelled column per generation. Click a person for dates, places and relatives; Print fits it to a landscape page, or use "Save as PDF". `-o x.png` writes a picture to text or email (drawn at 2×, or `--scale N`; huge charts are drawn smaller to keep the file manageable), and `-o x.svg` a scalable image. Default: 4 generations each way, saved as `NAME-tree.html`. Notes are left out unless you pass `--notes`. Birthplaces get a small country flag, worked out from the place text (`…, Argentina`, `…, Scotland`, `Entre Ríos`); flags are drawn as shapes, not emoji, so they show on Windows and in PNGs. Cards show initials tinted by sex, a large flag, age (at death, or today's for the living), and the record id (`I30`); partners are joined by a marriage badge. The HTML follows the viewer's light/dark setting, with a switch, and always prints light; `--dark` makes a dark PNG/SVG (or starts the page dark). `--theme classic` is the plainer warm-paper look (light only, small flags, no ages or ids). `--theme baroque` is a 19th-century engraved look on parchment: cartouche cards with corner scrollwork, the birth country's flag as a medallion on the top edge (or a crest tinted by sex when there's none; `--arms` bears it on a shield for men or a lozenge for women instead, as old heraldry did), and the id on a plate, a double-ruled page border with corner arabesques, and a script title over a flourish (all drawn as vector paths, so identical in HTML, SVG and PNG); its dark version is candlelit. `--theme sephirot` draws the tree like an old engraved Tree of Life, ivory on black (`--light` for black on cream): one round medallion per person with the birth and death set around its ring, the relation (`Grandmother`) above it and the old sign ♂/♀ beside it; ancestors rise above the person in triangles, each pair of parents joined by a channel carrying their marriage year, with rays from the person, the sun of the plate, to every ancestor from the grandparents up (except where one would pass behind another medallion), opening into the channels they cross like streets at a crossroads; descendants hang below, fanning out from under their parents. `--timeline` draws the same people on a time axis instead (`NAME-timeline.html`): one bar per life, from birth to death or today, so longer lives are longer bars; children branch off their parents' bars in the year they were born, partners are joined at their marriage year, and dates missing from the file are estimated from relatives and drawn pale. |
| `find` | `find [OPTIONS] [NAME...]` | All NAME words must match. Filters: `-b/--born RANGE`, `-d/--died RANGE`, `-p/--place TEXT`, `-x/--sex M\|F\|U`, `--ancestors-of PATH`, `--descendants-of PATH`. RANGE: `1850`, `1800..1850`, `..1700`, `1900..`. Results go to `/found`. |

| `edit` | `edit [PATH] [FIELD=VALUE...]` | Change a person's name, sex, birth, baptism, death, burial, occupation and residence; or a family's marriage and divorce. See below. |
| `add` | `add RELATION NAME [FIELD=VALUE...]` | Create a person as a child, spouse, father, mother or sibling of where you are (or unlinked: `add person`). Takes `edit`'s fields. See below. |
| `link` | `link PERSON WHERE` | Make an existing person a child, partner, parent or sibling: `link I12 children`, `link I12 father`. See below. |
| `unlink` | `unlink PATH` | Take the person PATH ends with out of the family connecting you: `unlink children/I12`. Nobody is deleted. |
| `save` | `save [FILE]` | Write edits back. With FILE, writes a copy and leaves the original (and the unsaved state) alone. |

Aliases: `ll` = `ls -l`, `cat` = `show`, `search` = `find`, `quit` = `exit`, `vi` = `edit`, `w` = `save`.
Separate commands with `;`. `#` starts a comment. Quote names that contain spaces.

## Editing

`edit` opens the person in `$VISUAL` / `$EDITOR` (default `vi`) as a short form:

```
given:           Alexei Nikolaevich
surname:         Romanov
suffix:
sex:             M

birth.date:      12 AUG 1904
birth.place:     Peterhof Palace, Russia

baptism.date:
baptism.place:

death.date:      17 JUL 1918
death.place:     Yekaterinburg, Russia
burial.date:
burial.place:
deceased:        yes

occupation:      Tsesarevich
residence.date:
residence.place: Alexander Palace, Tsarskoye Selo
```

Save and quit to apply. Quit without saving, or `:cq`, to cancel. If a value
is invalid (`31 FEB 1900`, `sex: X`), the form reopens with the error at the
top. The same fields work without an editor, for scripts:

```
edit mother sex=F born="12 jun 1850" birthplace="Genova, Italia"
bin/ged-browser tree.ged -c 'edit @I12@ died="abt 1920" deathplace=Roma; save'
```

A family opens the same way, with its marriage and divorce (the partners
and children are listed at the top for reference):

```
edit /families/F4
edit @F2@ married="12 jun 1870" marriage.place="Genova, Italia"
edit @F2@ divorced=no divorce.date= divorce.place=
```

- **Dates are checked and normalised**: `12 june 1850` and `1850-06-12`
  become `12 JUN 1850`, and `circa 1850` becomes `ABT 1850`. `BEF`, `AFT`,
  `BET … AND …`, `FROM … TO …` and `(free text)` are also accepted. A date
  you leave unchanged is never rewritten, even if its existing form is
  unusual.
- **Death and deceased are kept consistent**: a death or burial date or
  place implies `deceased: yes`. `deceased: yes` with no details writes
  `1 DEAT Y`. `deceased: no` removes the death record (and a bare burial
  flag), but refuses if either has sources or notes, or there is a
  cremation record, so nothing is lost silently.
- **Baptism** is written as `BAPM`, unless the person's record already uses
  `CHR` (christening) for it. **Occupation** and **residence** edit the
  first one recorded; any others are left alone, and an occupation with
  sources or notes can be changed but not removed.
  Divorce works the same way: a divorce date or place implies `divorced:
  yes`, which with no details writes `1 DIV Y`.
- **Edits stay in memory until `save`**. The prompt shows `*` while there
  are unsaved changes, and `exit` (or Ctrl-D) warns once before discarding them.
- **Saving produces a minimal diff.** Records you didn't edit are copied byte for
  byte, and inside an edited record only the changed lines are regenerated.
  The file keeps its encoding (UTF-8, ANSEL, Latin-1, …), line endings, BOM
  and permissions. The record's existing `CHAN` date is updated, as
  genealogy programs do. A character the file's encoding can't hold is
  refused before anything is written. The write is atomic.

### Linking people

`link PERSON WHERE` puts someone already in the file into a family, as
seen from where you are; `unlink PATH` takes them out again. Both sides of
each link are written together: the family's `HUSB`/`WIFE`/`CHIL` line and
the person's `FAMS`/`FAMC` line.

```
link I12 children              a child of the current person
link I12 spouses               a partner: a new family for the two of you
link I12 father                the father (or mother, parents)
link I12 siblings              a child of your parents' family
link I12 families/F3/children  when you have several families, say which
unlink children/I12            I12 is no longer your child
unlink father                  the father leaves your parents' family
```

- **Families are created when needed.** A first child, a new partner, or a
  parent for someone who has none starts a new family, numbered after the
  file's own ids (`F12`, or `@P…@`-style if that's what the file uses). It is
  written just before the trailer.
- **`unlink` cuts the last person in the path loose.** `unlink father`
  takes him out of your parents' family, so he is no longer the father of
  your siblings either. To detach only yourself, unlink from his side:
  `cd father; unlink children/I9`.
- **Families that connect nobody are deleted.** Once a family has one
  member or none and records nothing else (no marriage date, note or
  source), it is removed, along with the last member's pointer to it.
- `link` refuses what it can't do cleanly: a slot that's taken ("already
  has a father"), a man linked as a mother, a link that already exists.

### Adding people

`add` creates a person and links them in one step, with any of `edit`'s
fields:

```
add child "Maria Rossi" sex=F born="12 jun 1850"
add father "Giovanni /Rossi/" birthplace=Genova
add spouse "Juan /Pérez García/" sex=M
add families/F3/children Bea
add person "Ana Ruiz"                 not linked to anyone yet
```

- **Surnames go between slashes**, as in GEDCOM. Without slashes the last
  word is taken as the surname, so a compound one needs them:
  `"Juan /Pérez García/"`. The confirmation shows how the name was split.
- Fathers are recorded as male and mothers as female unless `sex=` says
  otherwise. Where the person goes follows `link`'s rules, so a new
  family is started when needed.
- **All or nothing.** If the name, a field or the link is refused, no
  record is created and the next id stays free.
- New records are numbered after the file's own ids and written just
  before the trailer.

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
ged.chart    Layout (generation columns, tidy placement), Sephirot (medallions in rows, ancestors rising), Timeline (lifelines on a year axis, date estimates), Theme (geometry, fonts, palettes), Ornaments (baroque frames, scrollwork and plate emblems as path data), ChartPage (card marks → SVG + HTML), PngChart (marks → Java2D)
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
- **Zero dependencies.** The prompt's line editor (`LineEditor`, ~250 lines)
  replaces what JLine would provide. Its key handling is separate from the
  terminal setup, so it's unit-tested with byte streams. Style and tables
  work out display width themselves, so accented and CJK names line up.

## Not (yet) supported

Editing events beyond birth, baptism, death, burial, occupation and the
first residence (census, emigration…), GEDCOM 7 specifics (`SNOTE`, `EXID`), non-Gregorian
calendar arithmetic (dates are shown verbatim), and export/convert from the
JS version.
