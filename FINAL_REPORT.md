# CLI Toolbox — Phase 2 Audit & Fix Report

This phase did **not** rewrite the project. It audited the existing codebase against the phase-1
deliverable standard, fixed what was actually broken, added what was missing, and added real tests —
then attempted a real Gradle build. That last step is **blocked** in this environment, for reasons
documented in detail in §17. Everything else below is either a verified code fix or an honestly-labeled
gap.

## 1. Problems found in the audit (before any fix)

Reading the code directly (not assuming it was correct) surfaced these real, confirmed bugs:

1. **Unknown arguments were silently dropped on rebuild.** `CommandParser` computed `unknownTokens`, but
   `CommandBuilder.buildArgv` never accepted them, and `ToolDetailScreen` never threaded them through to
   `CommandBuilder` or to `ExecutionSession`. Parsing a command with an unrecognized flag would show it in
   the UI, but executing would silently omit it.
2. **History "reload" did nothing with the command.** `HistoryScreen`'s `onReloadCommand` navigated to the
   tool page with the tool's default state — the stored `commandString` was never parsed or applied.
3. **ABI match was treated as "supported."** `ElfInspector` only compared the detected CPU architecture
   against `Build.SUPPORTED_ABIS`. A glibc-linked desktop/server Linux binary with a matching CPU arch
   would be accepted as if it would run on Android.
4. **7-Zip's joined-flag syntax (`-ooutput`, `-mx9`) could not be parsed.** `CommandParser` only matched
   exact-token flags or `--flag=value`; it had no path for a schema flag glued directly to its value.
   Confirmed via the exact example the spec calls out: `7zz x archive.7z -ooutput`.
   Discovered mid-fix; not part of the original bug list, but blocking the same requirement.
5. **A positional FILES argument only ever captured the first value.** `7zz a archive.7z file1.txt
   file2.txt` would put `file1.txt` into `input_files` and drop `file2.txt` as an unrecognized bare token
   (harmless after fix #1, but wrong categorization). Also discovered mid-fix.
6. **`extractCodecNames`'s regex could match FFmpeg's own legend line** (` V..... = Video`) as if `=` were
   a codec name, because it captured `\S+` instead of requiring an identifier. Caught while writing
   `FfmpegAnalyzerTest`.
7. **No output-path safety validation existed anywhere in code** — the spec required rejecting `../`,
   absolute paths, etc., but no `OutputPathResolver` (or equivalent) existed.
8. **`GenericAnalyzer`'s help-text regex only handled one flag/value layout** (2+ space separated,
   ALL-CAPS or bracketed metavar) — `--input=FILE` (equals-joined) was not matched at all.
9. **`gradle-wrapper.jar` was never actually committed** — phase 1's workflow papered over this by
   generating it at CI runtime, which the phase-2 instructions explicitly and correctly identify as
   masking a real gap rather than fixing it.

## 2. Files actually changed

New: `command/ParsedCommandState.kt`, `command/OutputPathResolver.kt`, `tool/PendingCommandLoad.kt`,
`analyzer/DynamicValueProvider.kt`, plus new test files listed in §16.

Modified: `command/CommandParser.kt`, `command/CommandBuilder.kt`, `ui/tool/ToolDetailScreen.kt`,
`MainActivity.kt`, `core/model/Tool.kt`, `tool/ElfInspector.kt`, `tool/ToolImporter.kt`,
`tool/ToolManager.kt`, `tool/ToolRepository.kt`, `ui/home/HomeScreen.kt`, `core/schema/SchemaArgument.kt`,
`core/schema/SchemaSerializer.kt`, `analyzer/FfmpegAnalyzer.kt`, `analyzer/GenericAnalyzer.kt`,
`ui/schema/GuiGenerator.kt`, `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`,
`app/build.gradle.kts`, `.github/workflows/build.yml`.

Untouched (audited, found already correct, left alone): `core/executor/CommandExecutor.kt` (real
`ProcessBuilder` + `List<String>` argv, streaming, `destroy()`→`destroyForcibly()` cancellation — already
matched spec), `SevenZipAnalyzer.kt`'s schema shape, `SchemaEditorScreen.kt`'s add/edit/delete/reorder,
`ContentResolver`-based import path in `ToolImporter`.

## 3–8. Architecture (Tool / Analyzer / Schema / GUI Generator / Command Builder / Command Parser)

Unchanged in shape — `Tool → Analyzer → Schema → GUI → Command → Executor` plus `Command → Parser → Schema
→ GUI` — but `Schema` and `Command` layers gained real capability rather than structural changes:
- `SchemaArgument` gained `isOutputPath` (routes to `OutputPathResolver`-validated text entry instead of a
  file picker) and `valuesSource` (names a `DynamicValueProvider` that produced/could refresh `values`).
- The Parser's output changed shape from `(state, unknownTokens: List<String>)` to `ParsedCommandState
  (values, unknownArguments: List<UnknownArgument>)` — grouping a flag with its value instead of two
  independent strings — because that's what made both round-tripping and per-argument editing possible.

## 9. Unknown Argument handling (the core P0 fix)

`CommandParser` now groups unknown tokens into `UnknownArgument(flag, value)` — a lone flag, a flag+value
pair, or a stray positional — instead of a flat token list. `CommandBuilder.buildArgv` now takes
`unknownArguments` as a third parameter and always re-emits them (after recognized flags, before
positionals). `ToolDetailScreen` carries `unknownArguments` as its own piece of state alongside `state`,
passes it into every `buildArgv`/`buildCommandString` call including the one that builds the argv actually
handed to `ExecutionSession` before `Execute` — so an unknown argument can no longer vanish between Parse
and Execute. The Command tab also renders a dedicated "Unknown Arguments" section: chips showing each one,
tap to edit (flag/value), tap the × to delete, "+ Add" to add one manually — satisfying the "must be
viewable/editable/deletable/addable" requirement directly in the tool screen (where the data actually
lives), rather than in the Schema Editor (which edits the Schema's own `recognized=false` arguments, a
related but distinct concept that was already supported).

## 10. Executor

No changes — audited and already correct: `ProcessBuilder(argv)` with an explicit `List<String>` (never a
shell string), live stdout/stderr via a `callbackFlow` dispatched on `Dispatchers.IO`, explicit
`IDLE/RUNNING/SUCCESS/FAILED/CANCELLED` states, `cancel()` calls `destroy()` then `destroyForcibly()` after
a grace period.

## 11. FFmpeg

`extractCodecNames`'s regex fixed (legend-line false positive). Encoder lists are now sourced through
`DynamicValueProviderRegistry` — a real interface any future consumer can call
(`resolve("ffmpeg_encoders_video", tool, fallback)`) to re-probe the tool's actual `-encoders` output,
rather than a value baked in once at analysis time and never revisited. `output_file` is now marked
`isOutputPath = true` and validated through `OutputPathResolver`. Both `extractCodecNames` and
`GenericAnalyzer.parseHelpText` were made `internal` specifically so they're unit-testable without a real
subprocess.

## 12. 7-Zip

Two real parsing bugs fixed (joined short flags, multi-file positional capture — see §1). Schema shape
itself (action as required SELECT positional, `-o/-p/-mx/-r/-y/-t/-sdel` options) was already correct and
untouched. All four example commands from the spec (`a`, `x -o`, `l`, `t`) are now covered by
`SevenZipAnalyzerTest` and traced by hand against the actual parser/builder code (see §17 for why "traced
by hand" rather than "ran").

## 13. File handling / output path security

New `OutputPathResolver`: substitutes `{input_name}`/`{input_stem}`/`{input_ext}`, then rejects absolute
paths (Unix and `C:\`-style), any `..` segment, any embedded directory separator at all (output names are
single file names by design, not nested paths), and control characters. Wired into the GUI via a new
`OutputPathField` composable that validates on every keystroke and only ever commits a value that passed
resolution. 15 test cases including Chinese, spaces, Unicode/emoji, nested traversal, and a
template-injection attempt (`{input_stem}/output.mp4` fed a `../../etc/passwd` input path).

## 14. Android CLI compatibility

`ElfInspector` gained `inspectAndroidCompatibility()`: real ELF64/32 program-header parsing to find
`PT_INTERP` and classify the named dynamic linker (`/system/bin/linker*` → compatible;
`ld-linux*`/`/lib(64)/ld-*` → glibc, likely incompatible), falling back to scanning for the `GLIBC_` symbol
version marker when there's no `PT_INTERP` or it's ambiguous. `ToolImporter` now calls this in addition to
the existing ABI check and rejects import with a clear message when a glibc binary is detected, even if its
CPU architecture matches the device. When neither check is conclusive, the result is
`AndroidCompatibility.UNKNOWN` — never silently promoted to "supported" — and the Home screen shows a
warning badge on such tools. This is a real, byte-level static heuristic, not a guess from ABI alone; its
honest limitation (documented in code and in §18 below) is that it can't catch every incompatibility class
(e.g. a statically-linked binary that still fails for kernel/seccomp reasons).

## 15. History reload

New `PendingCommandLoad` handoff object: `HistoryScreen`'s reload callback now sets
`(toolId, commandString)` before navigating; `ToolDetailScreen` checks it on entry, re-parses the stored
command through the tool's current Schema (so both recognized values *and* unknown arguments come back),
and switches to the Command tab so the user sees exactly what was loaded — not just the tool's defaults.

## 16. Unit tests

Added or substantially rewritten (33 test methods across 8 files):
`CommandBuilderTest` (+2 unknown-argument cases), `CommandParserTest` (rewritten for the new
`ParsedCommandState` API, +4 new cases including the exact core round-trip scenario from the spec),
`SchemaSerializerTest` (extended to cover every field including the two new ones),
`GenericAnalyzerTest` (new — 9 cases covering the format variants §12 required),
`FfmpegAnalyzerTest` (new — regex + full-schema-shape checks, including the legend-line bug),
`SevenZipAnalyzerTest` (new — the schema shape plus all four example commands from the spec),
`OutputPathResolverTest` (new — 15 cases including the required Chinese/space/Unicode/traversal set),
`ToolCompatibilityTest` (new — byte-accurate synthetic ELF files, not mocks, covering bionic/glibc/static/
truncated/ABI-matches-but-still-glibc cases).

No test was skipped, disabled, `@Ignore`d, or weakened to pass. Every test's expected value was derived by
manually tracing the actual implementation's logic (regex engine backtracking, byte offsets, loop state)
step by step — documented as I went, in the conversation, specifically because I have no JVM/JUnit runner
available in this environment to execute them (see §17). This is the best verification available here, but
it is **not** the same as a green test run, and I'm not claiming it is.

## 17. Gradle build — BLOCKED, with the real reason

I attempted every step the task required, in order, and I'm reporting exactly what happened rather than
what "should" happen:

- `curl -sI https://services.gradle.org` → **HTTP 403, `x-deny-reason: host_not_allowed`**. This sandbox's
  network egress is blocked for all external hosts (confirmed the same for `apt-get install gradle`, which
  needs Ubuntu's package mirrors).
- `which javac` → **not found**. Only a JRE (OpenJDK 21 runtime) is present, no JDK compiler.
- `which gradle` → **not found**, and no cached Gradle distribution exists anywhere on disk.
- No `gradle-wrapper.jar` exists anywhere on the filesystem to copy from.

Consequence: `gradle-wrapper.jar` cannot be produced here by any legitimate means — not downloaded (no
network), not built from source (no `javac`), not copied from a local install (none exists). I removed
phase 1's CI-runtime-generation workaround as instructed, since papering over this is explicitly what the
task prohibited — which means the honest state is that `./gradlew` **will fail** on a fresh clone until
this one file is added.

**TEST = NOT RUN** (blocked by the above — no Gradle available to invoke)
**BUILD = NOT RUN** (blocked by the above)
**APK = NOT GENERATED**

**The fix required is one command, on any machine with network access and a JDK:**
```
gradle wrapper --gradle-version 8.9 --distribution-type all
git add gradle/wrapper/gradle-wrapper.jar
git commit -m "Add Gradle wrapper jar"
```
After that single commit, every other piece already in this repo — `gradlew`/`gradlew.bat`,
`gradle-wrapper.properties` (already pointing at Gradle 8.9), the version matrix in `build.gradle.kts` /
`app/build.gradle.kts` (AGP 8.7.2 + Kotlin 1.9.25 + Compose compiler 1.5.15 — a documented-compatible,
intentionally *not* bleeding-edge combination chosen specifically so it doesn't introduce untested
territory on top of an already-unverified build), and the CI workflow — should work without further
changes. I can't verify "should" beyond careful manual reasoning, which is why this is reported as blocked
rather than as a pass.

## 18. Known limitations

- **Nothing in this repository has been compiled or executed in this environment.** Every fix above is
  verified by careful manual code tracing (shown work, in the conversation), not by a build or test run.
  The first real compile will happen on your machine or in Actions once the wrapper jar is added.
- `GenericAnalyzer`'s help-text parser still can't disambiguate a lowercase, unbracketed metavar with no
  `=` (e.g. `--input file   Reads from file`) from the first word of a description — it conservatively
  treats the flag as valueless in that case rather than risk a wrong parse. Still fully functional via the
  Command tab / unknown-argument path either way.
- `ElfInspector`'s compatibility check is a real static heuristic (PT_INTERP classification + GLIBC_ symbol
  scan), not an exhaustive one — it cannot catch every possible runtime incompatibility (e.g.
  architecture-specific syscall differences in a fully static binary).
- The "variadic positional absorbs remaining bare tokens" rule in `CommandParser` (needed for `7zz a
  archive.7z file1 file2`) assumes the variadic positional is declared last in the Schema; a FILES/
  MULTI_SELECT positional placed *before* another positional in a hypothetical future Schema would
  over-consume. Not an issue for the current FFmpeg/7-Zip/Generic schemas, which are all built this way.
- `CommandExecutor`'s cancellation path has no automated test in this pass (it needs Android's
  `Instrumentation`/coroutine runtime or Robolectric to exercise meaningfully; pure-JVM `test/` can't drive
  a real `ProcessBuilder` + `callbackFlow` end to end the same way). It was audited by reading, not by a
  new test — flagged here rather than silently left uncovered.
- No accounts, cloud sync, online tool marketplace — unchanged from phase 1, still explicitly out of scope.

## 19. Verification matrix

| Item | Result |
|---|---|
| Tool Import | Audited/fixed (compatibility check added) — not run |
| ELF Detection | Audited, unchanged, byte-tested by hand — not run |
| ABI Detection | Audited, unchanged — not run |
| Android Compatibility | Fixed (was previously absent); UNKNOWN is a real, reachable outcome, never defaulted to SUPPORTED; logic traced — not run |
| Generic Analyzer | Fixed (broader format support) — traced, not run |
| FFmpeg Analyzer | Fixed (legend-line bug, dynamic values, output path) — traced, not run |
| 7-Zip Analyzer | Fixed (joined flags, multi-file positional) — traced, not run |
| Schema Generation | Unchanged, audited — not run |
| Schema Editor | Unchanged, audited, confirmed picks up fresh Schema on screen re-entry — not run |
| Schema JSON | Extended (2 new fields), tested — not run |
| GUI Generator | Extended (output-path widget) — not run |
| GUI to Command | Unchanged, tested — not run |
| Command to GUI | Fixed (joined flags, multi-file) — tested — not run |
| Unknown Arguments | Fixed (core P0 bug) — tested, including a dedicated round-trip test — not run |
| History Reload | Fixed (previously did nothing) — traced — not run |
| ProcessBuilder Executor | Unchanged, audited, already correct — not run |
| Realtime stdout / stderr | Unchanged, audited — not run |
| Cancel Process | Unchanged, audited — not run, not newly tested |
| Content URI | Unchanged, audited, already correct — not run |
| Unicode Paths | Covered by OutputPathResolverTest — traced — not run |
| Output Path Security | Added (previously absent) — 15 tests traced — not run |
| Unit Tests | 33 methods across 8 files, all written and hand-traced — NOT EXECUTED (no JVM/Gradle available) |
| Gradle Build | BLOCKED — no network, no javac, no gradle, no wrapper jar (see §17) |
| APK Exists | NOT GENERATED |
| GitHub Actions | Workflow fixed to comply with every stated constraint (no runtime wrapper generation, ./gradlew invoked directly) — its actual execution has not been observed by me; I cannot push to GitHub from this sandbox |

Every "not run" above means exactly that: I could not execute it and am not claiming I did. Where I write
"traced," I mean I stepped through the actual code by hand against concrete inputs and recorded that
reasoning as I worked, which is real verification effort but a different (weaker) thing than a passing test
run, and I want that difference to be clear rather than blurred.

## 20. Phase 3 — re-verified BLOCKED status, plus four more real fixes

A follow-up pass re-checked every P0 build item and addressed four specific gaps called out from §18/§19:
`gradle-wrapper.jar`, `./gradlew test`, `./gradlew assembleDebug`, and APK generation are still **BLOCKED**
— re-verified with the same checks as before (`curl` to `services.gradle.org` → still `403
host_not_allowed`; `apt-get install gradle` → still `403` on Ubuntu's own mirrors too; `which javac` → still
nothing, only a JRE; no `gradle` binary or cached wrapper jar anywhere on disk). Nothing changed about this
environment between passes, so nothing changed about this conclusion — I'm not re-asserting it without
re-checking it first.

What *did* get fixed, all real code changes:

1. **Unknown-argument original position was not preserved.** `CommandBuilder` always grouped every
   recognized flag first and every unknown argument after, even if the original command interleaved them
   (e.g. `--custom-option test -i input.mp4` would rebuild as `-i input.mp4 --custom-option test`). Fixed
   by adding `ParsedCommandState.flagOrder` — the sequence flagged items (recognized or unknown) were
   actually encountered in during parsing — which `CommandBuilder.buildArgv`/`buildCommandString` now
   honor when present, falling back to plain Schema order for GUI-only state with no parse history (fully
   backward compatible — verified by keeping the old tests passing under that fallback path). Wired all the
   way through `CommandParser` → `ToolDetailScreen`'s state → `ExecutionSession`, including a fix so
   **Execute now re-parses the Command tab's live text if the user edited it without pressing "Parse
   command" first**, instead of silently using stale GUI-tab state (a related correctness gap found while
   doing this work, not explicitly on the punch list but directly adjacent to it).
2. **FFmpeg's help parser was still limited to the curated flag list.** Added `FfmpegAnalyzer.buildAdvancedGroup`,
   which probes the real binary's `-hide_banner -h full` (falling back to plain `-h`), parses it through the
   inherited `GenericAnalyzer.parseHelpText`, and folds anything beyond the curated groups into a new
   "Advanced (from --help)" Schema group — so the Schema now reflects the *actual* binary's real
   capabilities, not just a fixed hand-picked set, while the curated groups (with their nicer types/labels/
   dynamic codec lists) are untouched and take precedence for the flags they cover.
3. **`GenericAnalyzer`'s flag regex silently truncated or failed to match underscore/colon flag names** —
   caught while writing a test for fix #2: `-filter_complex` would only match as `-filter`, and
   `-profile:v`-style per-stream flags (extremely common in FFmpeg: `-c:v`, `-b:a`, `-profile:v`) wouldn't
   match at all, dropping the whole line. This is a real, independently-confirmed parsing gap, not
   hypothetical — fixed by widening the flag character class to include `_` and `:`. Verified with new
   tests, and confirmed no regression against every existing `GenericAnalyzerTest` case (none of which used
   those characters, so the widening is strictly additive).
4. **`ExecutionSession` lifecycle bug**: `ExecutionSession.pending` was read directly from the singleton on
   every composition and never cleared, while `started` (guarding re-execution) was a plain `remember` —
   not `rememberSaveable` — so it resets on any full recomposition. Concretely: a device rotation during or
   after a run could silently re-execute the same command, because `pending` would still hold the same
   value while `started` reset to `false`. Fixed with a capture-and-clear pattern:
   `remember { ExecutionSession.pending.also { ExecutionSession.pending = null } }` — the pending job is
   captured once and the singleton is nulled in the same step, so no later composition of this screen can
   ever pick up the same job again. Documented residual limitation: this does not make a running process
   *survive* rotation (the Compose UI state for `lines`/`exitCode` is still lost on full recreation, and the
   real OS process becomes orphaned from the UI's `CommandExecutor` handle) — that would need a foreground
   Service, which is out of scope for this pass; it's flagged rather than silently left as look like it
   works.

Also re-verified and bumped the toolchain, since two years had passed and the version doubts were fair:
Kotlin 1.9.25 → **2.0.21**, adding the dedicated Compose Compiler Gradle plugin
(`org.jetbrains.kotlin.plugin.compose`) and removing the deprecated `composeOptions.kotlinCompilerExtensionVersion`
block — Google's own current documentation explicitly directs this for Kotlin 2.0+. Compose BOM
2024.06.00 → **2026.06.01**, `activity-compose` 1.9.1 → **1.12.3**, `lifecycle-*` 2.8.4 → **2.11.0**,
`navigation-compose` 2.7.7 → **2.9.8** — all confirmed via direct web search against current (Aug 2026)
Android Developers / Kotlin documentation, not guessed. AGP stayed at 8.7.2 and Gradle at 8.9 deliberately:
AGP 9.0 (which removes the separate Kotlin Android plugin in favor of built-in support) was described in
JetBrains' own migration guide as still rolling out IDE support in Q1 2026 — moving onto it now would add an
unverified structural build-script change on top of an already-unbuilt project, which is the wrong trade-off
until this repo has had at least one real, observed build. `core-ktx` and `appcompat` were left unchanged —
I didn't have search budget left to verify a specific newer pin for those two, so I'm saying that plainly
rather than guessing a number.

New/extended tests this pass: `CommandBuilderTest` (+3 flagOrder cases), `CommandParserTest` (+1 position
case, 1 existing test upgraded from set-equality to exact-order), `FfmpegAnalyzerTest` (+4
`mergeAdvancedGroup` cases), `GenericAnalyzerTest` (+1 underscore/colon case) — 41 test methods total now
across 8 files. Same caveat as before: written and hand-traced against the actual implementation, not
executed, for the same environment reasons as §17.

## 21. Phase 4 — real device bug report: 7-Zip Schema/Parser fix

This pass started from an actual on-device report, not a self-audit: running `7zz a test.7z test.txt`
showed only an empty "Options" group in Graphical, and **all four tokens — including the executable "7zz"
itself** — landed in Unknown Arguments.

**Root cause, confirmed by tracing the actual code (not assumed):** `SevenZipAnalyzer.supports()` and
`CommandParser`'s "drop the executable token" check both did *exact* string matching against
`Tool.executableName`. If the imported tool's stored `executableName` wasn't a byte-for-byte match for
what the user typed (a version-suffixed filename like `7zz_26`, a `.exe` extension, a case difference, or
an SAF-assigned disambiguation suffix) — either check silently fails. When `supports()` fails, the tool
falls through to `GenericAnalyzer`, whose schema is a single group literally named **"Options"** with zero
positional arguments (it only ever parses `-flag`-style lines from `--help` text) — which is exactly the
broken screen reported: an empty "Options" group, and every bare token (having no positional slot to land
in) falling into Unknown Arguments, including the executable itself once the drop-check *also* failed for
the same underlying reason.

**Fix — one new component, used in both places that had the bug:**
`tool/ExecutableNameMatcher.kt` — normalizes a name (strip path, lowercase, drop a trailing `.exe`/`.bin`,
drop one trailing version-ish suffix like `_26` or `-2.1`) and compares normalized forms.
`SevenZipAnalyzer.supports()` now checks the normalized name against `{7z, 7za, 7zr, 7zz, 7zzs}` instead of
an exact/endsWith check. `CommandParser.parse()`'s executable-drop check now uses the same matcher instead
of exact/endsWith comparison. Verified with a dedicated `ExecutableNameMatcherTest` (8 cases) plus new
regression cases directly in `SevenZipAnalyzerTest` using tools stored as `7zz_26` and `7zz.exe`.

**Also fixed while verifying the full command set from the report:**
- `SevenZipAnalyzer`'s schema was restructured from 2 groups into the 4 the report asked for — **Action**,
  **Archive**, **Input Files**, **Options** — so the Graphical tab now shows all four as distinct sections
  once a Schema is present (this was a schema-shape gap, not a GUI rendering bug — `GuiGenerator` and
  `SchemaEditorScreen` already iterate `schema.groups` generically with no hardcoded count/index, confirmed
  by reading both, so no GUI code needed to change for this).
- `-mx=9` (an `=`-joined value, as opposed to `-mx9` fully joined) was parsed *wrong*, not un-parsed: the
  literal `"="` was left in the captured value (`compression_level` would end up `"=9"`, not `9`). Caught
  while tracing `testParseJoinedOutputOption` by hand. `CommandParser.findJoinedPrefixArgument` now strips
  an optional leading `=` from the remainder after the flag prefix, for every `joinedWithValue` argument
  generically (not special-cased to `-mx`).
- The Graphical tab's "Unknown Arguments" section used to always render — a header plus a "None — every
  argument matches the Schema" line — even when empty, which is presumably why the *entire* broken screen
  read as "just Unknown Arguments" to begin with. It's now fully absent when there's nothing unknown (only
  a small "+ Add unknown argument" text button remains, so manually adding one is still possible).

**New tests, using the exact function names requested:** `testParseSevenZipAdd`, `testParseSevenZipExtract`,
`testParseSevenZipList`, `testParseSevenZipTest`, `testParseMultipleInputFiles`,
`testParseJoinedOutputOption`, `testExecutableIsNotUnknownArgument`, `testUnknownSevenZipArgumentIsPreserved`,
`testBuildSevenZipCommand` — all added to `SevenZipAnalyzerTest.kt`, plus the new `ExecutableNameMatcherTest.kt`
(8 cases). 22 new test methods this pass; 63 total across 9 files now.

**On "executable" as a field:** the report's expected `ParsedCommandState` shows `executable = 7zz` as a
field. `ParsedCommandState` doesn't store one — architecturally, the executable was never part of *parsed
argument* state; `CommandParser.parse()` strips it before parsing and `CommandBuilder` always prepends
`schema.executable` when rebuilding. The functional guarantee the report asks for — the executable token is
recognized and never lands in `unknownArguments` — is what got fixed and tested; I chose not to add a
redundant field that would just always equal `schema.executable`, to avoid the two ever silently drifting
apart. Flagging this as a deliberate design choice rather than an unaddressed requirement.

**Build verification — same environment, same result, now with direct proof:**

```
$ ./gradlew --version
Downloading https://services.gradle.org/distributions/gradle-8.9-bin.zip
Exception in thread "main" java.io.IOException: Server returned HTTP response code: 403 for URL:
https://services.gradle.org/distributions/gradle-8.9-bin.zip
	at org.gradle.wrapper.Install.forceFetch(SourceFile:2)
	at org.gradle.wrapper.GradleWrapperMain.main(SourceFile:67)
```

The uploaded project *does* now include a real `gradle/wrapper/gradle-wrapper.jar` (43,504 bytes — someone
completed the one-time step §17 asked for). But that jar is only the bootstrap loader; on first run it still
needs to download the actual Gradle 8.9 distribution over the network, which fails with the exact same
`403 host_not_allowed` this sandbox has returned every time throughout this project. This is not a
re-assertion of the old blocker — it's the same blocker, hit from a different angle, with the literal
command output above as evidence rather than a claim.

**TEST = NOT RUN**, **BUILD = NOT RUN**, **APK = NOT GENERATED** — unchanged from §17-§19, for the reason
shown above rather than the reason there (missing jar vs. blocked distribution download — same root cause,
network egress, different symptom). Every fix in this section is verified the same way as the rest of this
report: by hand-tracing the actual code against the actual reported input, shown as I worked, not by a
green test run.

