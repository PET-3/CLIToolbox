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

```bash
git clone <this-repo>
cd CLIToolbox
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

> **Note on the Gradle wrapper jar:** `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, and
> `gradlew.bat` are committed. If your checkout is missing the binary `gradle-wrapper.jar` (some git hosts/
> zip exports strip binaries), regenerate it once with a local Gradle install: `gradle wrapper --gradle-version 8.7 --distribution-type all`.
> The CI workflow does this automatically on every run, so GitHub Actions builds are unaffected either way.

Run unit tests only:

```bash
./gradlew test
```

## GitHub Actions

`.github/workflows/build.yml` runs on every push to `main` and every pull request (plus manual
`workflow_dispatch`): checkout → JDK 17 → Gradle setup → regenerate the wrapper jar if absent → `./gradlew
test` → `./gradlew assembleDebug` → upload `cli-toolbox-debug.apk` as a build artifact. No signing key or
Keystore is required — this is a debug-only build for now.

## Project layout

```
app/src/main/java/com/example/clitoolbox/
├── core/
│   ├── model/       Tool, ToolArchitecture, ToolSource
│   ├── schema/       ToolSchema, SchemaGroup, SchemaArgument, ArgumentType, JSON (de)serialization
│   ├── parser/       ShellTokenizer (quote-aware command-line tokenizing)
│   └── executor/     ProcessRunner (short probes), CommandExecutor (streaming, cancellable)
├── tool/             ToolManager, ToolImporter (SAF import + ELF/ABI checks), ToolRepository (JSON
│                     persistence), HistoryRepository, ExecutionSession
├── analyzer/         ToolAnalyzer, GenericAnalyzer, FfmpegAnalyzer, SevenZipAnalyzer, AnalyzerRegistry
├── command/          CommandBuilder (Schema+state → argv), CommandParser (command string → state)
├── ui/               home/, tool/, schema/ (GUI Generator + Schema Editor), execute/, history/, settings/
└── MainActivity.kt   Navigation-Compose host wiring all screens together
```

## Known limitations (first phase)

- Only `arm64-v8a` and `armeabi-v7a` are targeted; no bundled tool binaries ship with the app — the user
  supplies their own.
- `GenericAnalyzer`'s help-text scanner is a best-effort regex over common `-x, --long VALUE  description`
  layouts; unusual help formats will surface more arguments as "unknown" (which is still safe — nothing is
  dropped, and the Schema Editor can fix it up).
- No accounts, cloud sync, online tool marketplace, or plugin marketplace — intentionally out of scope for
  this phase.
- Release signing / Play Store packaging is not set up; only `assembleDebug` is wired into CI.
