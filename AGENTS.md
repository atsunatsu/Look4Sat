# Look4Sat AI Agent Instructions

This is the canonical project guide for all AI assistants working on Look4Sat.
All assistant-specific files (`CLAUDE.md`, `.github/copilot-instructions.md`) point here.

---

## Project Overview

Look4Sat is an open-source, fully offline Android satellite tracker and pass predictor. It tracks 9000+ active
satellites using TLE/OMM data from Celestrak/SatNOGS, calculates orbital positions via SGP4/SDP4 models, and displays
passes relative to the user's location. Features include polar radar visualization, SSTV image decoding, satellite
ground track mapping, and pass predictions up to 10 days ahead. No ads, no tracking, no network required after initial
data download.

## Architecture

**MVI (Model-View-Intent)** with unidirectional data flow:
- `State` data class → exposed via `StateFlow` from ViewModel
- `Action` sealed interface → user intents dispatched to ViewModel's `onAction()`
- Jetpack Compose UI observes state and recomposes reactively

**Clean Architecture layers:**

| Module               | Responsibility                                                      |
|----------------------|---------------------------------------------------------------------|
| `app`                | Entry point. Aggregates all modules                                 |
| `core:data`          | Android library. Room DB, OkHttp networking, repo implementations   |
| `core:domain`        | Pure Kotlin (JVM). Orbital math (SGP4/SDP4), models, repo contracts |
| `core:presentation`  | Android library. Compose theme, shared UI components, NavKeys       |
| `feature:map`        | OSMDroid map with ground tracks                                     |
| `feature:passes`     | Pass predictions and upcoming events                                |
| `feature:radar`      | Polar radar view of satellite positions, SSTV image decoding        |
| `feature:satellites` | Satellite list, filtering, selection                                |
| `feature:settings`   | User preferences                                                    |

- `feature:*` modules depend only on `core:domain` + `core:presentation`. Features never depend on each other.

## Build & Run

```shell
# Debug build
./gradlew assembleDebug

# Release build (minified, shrunk resources)
./gradlew assembleRelease

# Run tests
./gradlew test
```

- **Min SDK**: 24 | **Target SDK**: 36 | **JDK**: 17
- **Gradle**: Uses version catalog (`gradle/libs.versions.toml`) + convention plugins in `build-logic/`

## Dual Edition Builds (basic / jsuenhanced)

One repository publishes two APKs for every tag. They share the `applicationId`
(`cn.ba7opf.look4sat`), the signing key and the `versionCode`, so installing the other
edition is a plain update over the same app identity — that is what makes the in-app
edition switch on the update page work.

| Edition | Flavor | Sources | Release asset |
|---|---|---|---|
| `basic` | `basic` | frozen 4.4.8 feature set | `Look4Sat-<version>.apk` (bare name kept for legacy in-app updates) |
| `jsuenhanced` | `jsuenhanced` | FT4 decode, logbook, CAT/rotator control, L4S link | `Look4Sat-<version>-jsuenhanced.apk` |

```shell
./gradlew :app:assembleBasicRelease        # basic APK (also bundleBasicRelease for the AAB)
./gradlew :app:assembleJsuenhancedRelease  # FT4 native core needs the pinned Windows toolchain
```

**Source-set split (do not break this).** Files whose behaviour differs per edition live in
`src/basic/` (copy of the frozen `main` file) and `src/jsuenhanced/` (enhanced file) — never in
`src/main/`, or both editions would compile the same class twice. A change that only belongs to
one edition goes into that edition's source set; shared plumbing stays in `src/main/`. The
runtime edition is exposed as `IMainContainer.edition` (`BuildConfig.EDITION` at the app layer).

Rules that keep the split honest:

- **basic must stay byte-identical to the pre-flavour file.** Where shared code changed and the
  frozen copy no longer compiles, adapt the basic copy minimally and record the adaptation in the
  implementation log — never "fix" basic by pulling enhanced behaviour into it.
- **Enhanced-only modules are dependency-isolated**: `core:ft4-native`, `feature:ft4` and
  `feature:logbook` are `jsuenhancedImplementation` only, so the basic APK never ships
  `liblook4sat_ft4.so` (the release workflow asserts its absence).
- **Never add per-edition version bumps.** Same `versionCode`/`versionName` for both editions;
  only the asset name distinguishes them. A different versionCode would make switching editions a
  downgrade (`INSTALL_FAILED_VERSION_DOWNGRADE`).
- **Data layer stays shared** (Room schema and migrations live in `src/main/`), so switching
  editions keeps the logbook and settings; never add destructive-migration fallbacks.
- The removed `-Pl4sDevelopment` flag now fails the build on purpose: an ignored property would
  silently emit a stable-package APK that overwrites the installed app.

## Key Libraries

- **Compose** (BOM 2026.05.01) + Material3 Adaptive
- **Navigation3** (type-safe, uses `@Serializable` NavKeys)
- **Room** (KSP code generation) for local satellite/TLE storage
- **OkHttp** 5.x for TLE downloads
- **OSMDroid** for map rendering
- **Kotlin Serialization** for navigation args and data parsing
- **Coroutines** + `StateFlow` for async/reactive patterns

## Conventions

- **Minimal dependencies**: Avoid adding libraries when a simple manual solution exists. Fewer deps = less maintenance.
- **DI**: Manual — ViewModels use companion `factory()` methods with `IMainContainer` interface.
- **Navigation**: Type-safe Compose Navigation3 with `@Serializable` data classes as nav keys.
- **State naming**: `<Feature>State` data class + `<Feature>Action` sealed interface per feature.
- **No feature-to-feature deps**: All cross-feature communication goes through core layers.
- **Localization**: 7 languages (en, es, ru, si, tr, uk, zh).

## Data Formats & Migration

**TLE vs. OMM/CSV format:**

Look4Sat supports both TLE and OMM (Orbit Mean-Elements Message) formats for backward compatibility:

- **TLE format**: Traditional 3-line element format (deprecated). NORAD catalog numbers are 5-digit integers, which
  are running out of space. Celestrak has signaled that TLE format will eventually be phased out.
- **OMM/CSV format**: The future standard. CSV files contain the same orbital parameters as TLE but use ISO 8601
  timestamps and support larger NORAD IDs. Celestrak and SatNOGS already provide OMM data in CSV format.

**Current implementation:**
- `DataParser.kt` handles both `parseTLEStream()` and `parseCSVStream()` seamlessly
- TLE data is downloaded from configured sources and stored in Room database
- When downloading satellite data, the app automatically detects format and parses accordingly
- Both formats produce identical `OrbitalData` objects, ensuring transparent format switching

**Migration path:**
As NORAD catalog space becomes constrained, OMM/CSV will become the primary format. Look4Sat is already positioned
to handle this transition without code changes — existing users can continue using TLE files while new sources
transition to OMM/CSV automatically.

## Code Style

- Prefer **short, focused functions** — single responsibility, easy to read.
- **Exceptions**: Composable functions and math-heavy algorithms (SGP4/SDP4) may be longer.
- Strict code style — no dead code, no unused imports, consistent formatting.

## Roadmap

- **KMP migration**: `core:domain` is to become a fully shareable KMM module. Keep it pure Kotlin/JVM.

## Gotchas

- Orbital math lives in `core:domain/predict/` — it's dense vector math (SGP4/SDP4). Tread carefully.
- TLE/OMM data must be refreshed weekly for accurate predictions (satellite orbits decay). TLE format is legacy and
  will eventually be deprecated in favor of OMM/CSV as NORAD catalog numbers approach the 5-digit limit.
- SSTV decoding in `feature:radar` is experimental; image quality depends on signal strength during satellite pass.
- `build-logic/convention/` contains all shared Gradle configuration — edit there, not in individual modules.
- ProGuard is enabled for release builds — don't add reflection-based libs or any other dependencies without asking.

## Release Notes Must Be Bilingual (BA7OPF fork convention)

Every GitHub release body carries **two sections — English first, then Chinese** (`## <tag>` each),
listing only that release's changes, in the same order with the same claims and the same numbers.
Both sections end with the verification line (which unit tests ran, APK signature / ABI check).
A single-language body is a defect: fix it before the release counts as done.

中文规则：每次 release 的说明必须**中英双语，先英文后中文**，两段一一对应（同一顺序、同一结论、
同一数字，不做自由意译），只列本版改动；两段末尾都写验证情况（跑了哪些单测、签名/ABI 校验）。
只写一种语言视为缺陷，需补齐后再算发布完成。

## 用户界面文案

- 正常界面保持简洁，只保留必要的名称、数值、连接状态和可操作的错误反馈。
- RX/TX 基准和 UV-K1/UV-K5 V3 连接区域等正常面板，不展示冗长的开发、测试、协议、能力或内部保护机制说明。
- 工程细节放入现有诊断日志及导出，不用常驻提示占据操作界面。

## Copilot Working Mode: Code-Only

- Default to code changes only. Provide explanations in chat only.
- If documentation seems useful, ask first before creating files.
- Do NOT create any `.md` documentation files unless explicitly requested.
- Do NOT add README, guides, summaries, migration notes, or how-to files unless asked.
- Prefer minimal diffs focused on requested implementation.
- Default validation is static checks (`get_errors`). Do NOT run Gradle compile/test tasks unless explicitly requested.
