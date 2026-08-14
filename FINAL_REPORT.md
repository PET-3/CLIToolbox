# CLI Toolbox — Final Report

## 1. Project structure
Standard Android Studio / Gradle Kotlin DSL project: `settings.gradle.kts`, root `build.gradle.kts`,
`app/build.gradle.kts`, `gradle.properties`, Gradle Wrapper (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.properties`), `app/src/main/AndroidManifest.xml`, Kotlin sources under
`app/src/main/java/com/example/clitoolbox/`, resources under `app/src/main/res/` (incl. `values-zh-rCN/`),
unit tests under `app/src/test/`, and `.github/workflows/build.yml`. See `README.md` for the full tree.

## 2. Core architecture
Strictly layered, one-directional data flow with an explicit reverse path:

```
Tool → Analyzer → Schema → GUI → Command → Executor
                     ↑                 ↓
                     └──── Parser ─────┘
```

No layer above Analyzer is allowed to know a tool's specific identity (`if (tool == ffmpeg)` is disallowed
by design) — everything downstream only ever consumes a generic `ToolSchema`.

## 3. Tool model
`core/model/Tool.kt`: `id`, `name`, `executableName`, `version`, `architecture` (`ToolArchitecture` enum,
detected via ELF header inspection), `source` (`IMPORTED`/`BUILTIN`), `binaryPath` (app-private storage
only), `schema`, `analysisSummary`, `createdAt`/`updatedAt`.

## 4. Analyzer model
`analyzer/ToolAnalyzer.kt` defines the `supports(tool)` / `analyze(tool): ToolAnalysisResult` contract.
`GenericAnalyzer` probes `--version`/`-version`/`-v` and `--help`/`-h`/`-help`/`--usage` with a 5s timeout per
attempt (never crashes; returns `AnalysisFailed` on total failure) and regex-scans help text for
`-x, --long VALUE  description`-style lines. `FfmpegAnalyzer` and `SevenZipAnalyzer` extend it, adding
curated schemas for common flags (still only *producing a Schema*, never rendering UI directly).
`AnalyzerRegistry` picks the most specific analyzer, falling back to `GenericAnalyzer`.

## 5. Schema model
`core/schema/ToolSchema.kt` + `SchemaGroup` + `SchemaArgument` + `ArgumentType` (text, number, boolean,
select, multiSelect, file, files, directory, flag). Arguments an Analyzer can't classify are kept as
`recognized = false` entries rather than dropped. `SchemaSerializer` handles JSON export/import (`org.json`,
no extra serialization plugin needed).

## 6. GUI Generator
`ui/schema/GuiGenerator.kt` — `SchemaDrivenForm` renders one Compose widget per argument purely by `type`
(TextField / Slider-or-NumberField / Switch / ExposedDropdownMenu / FilterChips / file & directory pickers
via Storage Access Framework). No tool-specific screens exist anywhere in `ui/`.

## 7. Command Builder
`command/CommandBuilder.kt` — `Schema + SchemaState → List<String>` (argv) and a human-readable string via
`ShellTokenizer.join`. Positional (flagless) arguments are appended per `Schema.positionalOrder`. Execution
always uses the `List<String>` form — never a concatenated shell string.

## 8. Command Parser
`command/CommandParser.kt` — tokenizes a command string (`ShellTokenizer`, quote/escape-aware) and matches
tokens against the Schema's flags, updating GUI state. Unmatched tokens are preserved verbatim in
`unknownTokens` instead of causing a failure.

## 9. Executor
`core/executor/CommandExecutor.kt` — `ProcessBuilder` with the tool's directory as working dir, live
stdout/stderr streaming via a `callbackFlow`, explicit `ExecutionState` (`IDLE/RUNNING/SUCCESS/FAILED/
CANCELLED`), and `cancel()` that calls `destroy()` then `destroyForcibly()` after a grace period so no
orphan process is left behind.

## 10. FFmpeg support
`FfmpegAnalyzer` confirms the binary via `-hide_banner -version`, reads `-encoders` to populate the video/
audio codec dropdowns from the *actual build's* capabilities, and emits Input/Video/Audio/Output groups
covering `-i -ss -t -to -c:v -b:v -crf -preset -r -s -vn -c:a -b:a -an -map -f -y` plus a positional output
file. Anything beyond this curated set still round-trips through the Command tab's unknown-token handling.

## 11. 7-Zip support
`SevenZipAnalyzer` models the action (`a/x/e/l/t`) as a required SELECT positional plus `-o -p -mx -r -y -t
-sdel`, several of which use `joinedWithValue` (e.g. `-mx9`) to match 7-Zip's actual switch syntax.

## 12. Chinese support
All UI strings are externalized to `res/values/strings.xml` (English) and `res/values-zh-rCN/strings.xml`
(简体中文); the Settings screen offers System/English/简体中文 and applies it via
`AppCompatDelegate.setApplicationLocales`.

## 13. GitHub Actions
`.github/workflows/build.yml` triggers on push to `main`, on pull requests, and via manual
`workflow_dispatch`. Steps: checkout → JDK 17 (Temurin) → Gradle 8.7 setup → regenerate
`gradle-wrapper.jar` if missing → `./gradlew test` → `./gradlew assembleDebug` → locate the APK → upload as
artifact `cli-toolbox-debug`. No signing key, Keystore, or Play Store step — debug APK only, as scoped for
this phase.

## 14. APK build result
This project was authored and reviewed in a sandboxed environment without network/Android-SDK access, so
`./gradlew assembleDebug` could not be executed here to produce a physical APK file. Every source file was
written by hand (not scaffolded by an IDE), cross-checked for balanced braces/parens and consistent imports
across all 34 Kotlin files, and dependency/plugin version pairings (AGP 8.5.2 + Gradle 8.7, Kotlin 1.9.24 +
Compose compiler 1.5.14) were chosen to be mutually compatible. Actual compilation must happen the first
time this repository runs through GitHub Actions or a local `./gradlew assembleDebug` — please open an issue
with the failing step's log if it doesn't build cleanly, since that log is needed to pinpoint any remaining
error precisely.

## 15. Known limitations
See the "Known limitations" section of `README.md` — summarized: `arm64-v8a`/`armeabi-v7a` only, no bundled
tool binaries, best-effort generic help-text parsing, no accounts/cloud/marketplace features, debug build
only (no release signing).
