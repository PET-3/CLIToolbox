# CLI Toolbox

**CLI Toolbox** turns command-line executables (FFmpeg, 7-Zip, ImageMagick, yt-dlp, or anything else) into
graphical tools automatically. Import a binary, the app analyzes it and generates a Schema describing its
arguments, and a GUI is generated from that Schema — no per-tool UI code required.

```
Tool → Analyzer → Schema → GUI → Command → Executor
                     ↑                 ↓
                     └──── Parser ─────┘
```

- **Tool** — a CLI executable you imported (or a builtin).
- **Analyzer** — inspects a Tool (`--version`, `--help`, etc.) and produces a **Schema**. `GenericAnalyzer`
  handles unknown tools; `FfmpegAnalyzer` / `SevenZipAnalyzer` add tool-specific knowledge, but only the
  Analyzer layer is allowed to know a tool's identity — nothing downstream special-cases a tool by name.
- **Schema** — the single data model shared by the GUI Generator, Command Builder, and Command Parser.
- **GUI Generator** — renders Compose widgets purely from a Schema's argument `type` (text/number/boolean/
  select/multiSelect/file/files/directory/flag).
- **Command Builder** — turns GUI state into a real argv (`List<String>`) and a human-readable command string.
- **Command Parser** — the reverse: turns a typed/pasted command string back into GUI state, preserving any
  flags it doesn't recognize instead of dropping them.
- **Executor** — runs the argv with `ProcessBuilder` (never a shell string), streams stdout/stderr live, and
  supports cancellation without leaving orphan processes.

## Using the app

1. **Import a tool** — tap **+ Import Tool** on the home screen and pick an executable file (e.g. an
   arm64-v8a `ffmpeg` binary). It's copied into app-private storage, checked (ELF format, CPU ABI vs. this
   device), and marked executable.
2. **Analysis runs automatically** — you'll see progress ("Detecting version → Analyzing help → Generating
   schema") and a summary of how many arguments were recognized vs. left as "unknown".
3. **Edit the Schema (optional)** — tap **Schema** on a tool to add, remove, reorder, or retype arguments,
   including promoting an "unknown" argument into a real typed one. Schemas can be exported/imported as JSON
   for sharing.
4. **Use the tool** — the tool screen has two tabs:
   - **Graphical** — the generated form; editing it live-updates the command preview.
   - **Command** — type or paste a full command line, tap **Parse command** to push it back into the form.
     Unrecognized tokens are shown but preserved, not discarded.
5. **Execute** — tap **Execute** to run the real process. Output streams live; **Cancel** kills it cleanly.
6. **History** — every run is logged (tool, command, result, time); tap an entry to reopen that tool.

Settings lets you switch theme (System/Light/Dark) and language (System/English/简体中文).

## Local build

Requirements: JDK 17, Android SDK (compileSdk 34).

**⚠ One-time setup required before this will build:** `gradle/wrapper/gradle-wrapper.jar` is **not**
committed to this repository — it's a binary file that could not be produced in the environment this
project was developed in (no network access to download it, no local JDK/Gradle to generate it from). Run
this once, from any machine with a JDK and either a local Gradle install or network access:

```bash
gradle wrapper --gradle-version 8.9 --distribution-type all
git add gradle/wrapper/gradle-wrapper.jar
git commit -m "Add Gradle wrapper jar"
```

After that one-time commit, `./gradlew` works for everyone who clones the repo from then on — this is not
a per-checkout step. See `FINAL_REPORT.md` §17 for the full explanation.

```bash
git clone <this-repo>
cd CLIToolbox
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run unit tests only:

```bash
./gradlew test
```

## GitHub Actions

`.github/workflows/build.yml` runs on every push to `main` and every pull request (plus manual
`workflow_dispatch`): checkout → JDK 17 → Gradle 8.9 setup → `./gradlew test` → `./gradlew assembleDebug` →
locate the APK → upload `cli-toolbox-debug.apk` as a build artifact. No signing key, Keystore, or Play
Store step — debug APK only, as scoped for this phase. **This workflow will fail until the one-time
`gradle-wrapper.jar` setup above is committed** — see `FINAL_REPORT.md` §17.

## Project layout

```
app/src/main/java/com/example/clitoolbox/
├── core/
│   ├── model/       Tool, ToolArchitecture, AndroidCompatibility, ToolSource
│   ├── schema/       ToolSchema, SchemaGroup, SchemaArgument, ArgumentType, JSON (de)serialization
│   ├── parser/       ShellTokenizer (quote-aware command-line tokenizing)
│   └── executor/     ProcessRunner (short probes), CommandExecutor (streaming, cancellable)
├── tool/             ToolManager, ToolImporter (SAF import + ELF/ABI/glibc-compatibility checks),
│                     ToolRepository (JSON persistence), HistoryRepository, ExecutionSession,
│                     PendingCommandLoad (History → GUI reload handoff)
├── analyzer/         ToolAnalyzer, GenericAnalyzer, FfmpegAnalyzer, SevenZipAnalyzer, AnalyzerRegistry,
│                     DynamicValueProvider (extension point for tool-build-dependent value lists)
├── command/          CommandBuilder (Schema+state+unknownArgs → argv), CommandParser (command string →
│                     ParsedCommandState), OutputPathResolver (safe output filename resolution)
├── ui/               home/, tool/, schema/ (GUI Generator + Schema Editor), execute/, history/, settings/
└── MainActivity.kt   Navigation-Compose host wiring all screens together
```

## Known limitations

- Only `arm64-v8a` and `armeabi-v7a` are targeted; no bundled tool binaries ship with the app — the user
  supplies their own.
- `GenericAnalyzer`'s help-text scanner handles several common layouts (`--flag FILE`, `--flag <FILE>`,
  `--flag=FILE`, short/long combined via comma) but still can't disambiguate a lowercase, unbracketed
  metavar with no `=` from the first word of a description — it conservatively treats the flag as valueless
  in that case. Nothing is ever dropped either way — unmatched flags still work via the Command tab's
  unknown-argument handling.
- `ElfInspector`'s Android-compatibility check (PT_INTERP classification + GLIBC_ symbol-version scan) is a
  real static heuristic, not exhaustive — it can't catch every possible runtime incompatibility.
- No accounts, cloud sync, online tool marketplace, or plugin marketplace — intentionally out of scope.
- Release signing / Play Store packaging is not set up; only `assembleDebug` is wired into CI.
- **`gradle-wrapper.jar` is not committed** — see the one-time setup step above and `FINAL_REPORT.md` §17
  for why, and exactly what to run once to fix it.
- This codebase has not been compiled or test-run in the environment it was developed in (no network/JDK/
  Gradle available there) — see `FINAL_REPORT.md` for the full, honest verification status of every piece.
