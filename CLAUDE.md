# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app does

Android interactive-fiction app that runs a local LLM (GGUF via llama.cpp) entirely on-device. The model streams back structured XML that the app parses into story text and player choices:

```
<novel>story paragraph</novel>
<option_1>choice A</option_1>
<option_2>choice B</option_2>
<option_3>choice C</option_3>
```

The model (`test_1.gguf`, Qwen3.5-2B-based) is bundled in `app/src/main/assets/model/` and extracted to internal storage on first launch. Assets split: `assets/model/` (GGUF), `assets/prompt/` (`character_prompts.json` + four story-pool JSONs), `assets/image/` (reserved).

Flow: **`LandingActivity`** (grid of saved novels + "+" tile) → **`CharacterSelectionActivity`** (3-page Compose: `我是谁?` → `ta是谁?` → 故事种子) → **`MainActivity`** (stacked scrollable scenes). Tapping an option freezes the current scene and spawns a continuation `GameViewModel`. Long-pressing a cover on the landing screen enters config mode (drag-reorder + 删除/换色/改名).

## Build commands

```bash
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK
./gradlew installDebug           # Build + install on device
./gradlew test                   # Unit tests
./gradlew connectedAndroidTest   # Instrumented tests
./gradlew clean
```

Build requires NDK `25.1.8937393` (set in `app/build.gradle.kts`). Only `arm64-v8a` is built.

| Item | Value |
|---|---|
| Min SDK | 26 (Android 8.0) |
| Target / Compile SDK | 34 / 35 |
| Compose BOM | 2024.04.01 |
| Coroutines | 1.7.3 (explicit dep, not in `libs.versions.toml`) |
| Reorderable grid | `org.burnoutcrew.composereorderable:reorderable:0.9.6` |

## Architecture

Four layers:

**C++ JNI bridge** — `app/src/main/cpp/llama_jni.cpp`
Wraps llama.cpp with five JNI functions: `nativeInit`, `nativeLoad`, `nativeGenerate`, `nativeRequestStop`, `nativeFree`. Global state (`g_model`, `g_context`, `g_batch`, `g_templates`) persists for the process lifetime. `nativeGenerate` applies the model's chat template via `common_chat_templates_apply` with `enable_thinking=false`, runs prefill in `BATCH_SIZE=512` chunks (ctx 4096), streams tokens via `TokenCallback`. UTF-8 continuation bytes are buffered so Kotlin never sees broken sequences. When `jNovel=true`, an embedded GBNF grammar enforces XML output order.

**Cooperative cancellation contract** (non-obvious, do not break): `nativeLoad`, `nativeGenerate`, `nativeFree` are serialized by `g_engine_mutex`. `nativeGenerate` polls `std::atomic<bool> g_should_stop` at the top of both the prefill and generation loops, and the same flag is wired into `llama_context_params.abort_callback` so ggml polls it between tensor ops *inside* `llama_decode` (this is what makes a single-batch prefill cancellable). `nativeRequestStop` is lock-free. `nativeFree` sets the stop flag *before* taking the mutex so any in-flight generate observes the stop. `nativeFree` does NOT call `llama_backend_free()` — the backend stays initialized for the process lifetime.

**Sampler chain — never call `llama_sampler_accept` after `llama_sampler_sample`.** In this vendored llama.cpp (`src/llama-sampler.cpp:870`), `llama_sampler_sample` already calls `llama_sampler_accept` internally before returning the chosen token. Calling accept a second time from `nativeGenerate` double-accepts every token; the grammar sampler crashes on the second call (the stack has already advanced past the token's chars, so re-walking them kills all parse paths) and throws `Unexpected empty grammar stack after accepting piece: …`. The JNI has no `try`/`catch` around the generation loop, so the uncaught C++ exception crosses the JNI boundary and SIGABRTs the process. Older llama.cpp docs and tutorials tell you to call accept manually after sample — that guidance no longer applies in this version. Verified by source inspection 2026-05-18 against vendored commit `4515559`.

**Kotlin singleton** — `LlamaEngine.kt`
Thin wrapper over JNI. Two flags: public `isLoaded` (gate for `generate()`, flipped synchronously by `markUnloaded()`) and private `nativeLoaded` (tracks whether `nativeFree` is still owed). `free()` runs on a bg thread (never Main) and is idempotent.

**Teardown sequence (`MainActivity.onDestroy`)**: `requestStop()` → `markUnloaded()` → `super.onDestroy()` → `Thread({ free() }).start()`. The synchronous `markUnloaded()` closes a race where a recreated `MainActivity` could otherwise see stale `isLoaded=true` while bg `free()` was still mid-flight. The named "engine-teardown" thread keeps Main from blocking on `nativeFree`.

**Character / prompt layer** — pure Kotlin, no JNI/Compose dep
- `CharacterModel.kt` — sealed `Gender` (`Female`/`Male`/`Custom(name, description)`), sealed `Personality` (`Preset(label)`/`Custom(...)`), `Character`, `CharacterPair`. JSON via `org.json`. Exports `FEMALE_PERSONALITIES` (10), `MALE_PERSONALITIES` (10), `UNION_PERSONALITIES` (14).
- `PromptTemplates.kt` — singleton loading `assets/prompt/character_prompts.json` (idempotent `load(context)`). All Chinese prompt fragments live in JSON, not code. Lookups throw on miss (no silent fallback).
- `SystemPromptBuilder.kt` — `build(pair, random)` is the only public surface. Assembles: genre opener → custom-gender clause → personality clauses → probabilistic height clauses (re-rolled per call) → relative-height clause → format spec. Re-rolling is what makes the height tags fire fresh on every turn.
- `StoryPools.kt` + `assets/prompt/{ff,mm,fm,mf}.json` — singleton with `pickRandom(me, ta, random): Entry`. Pool selection: trivial gender pairs → matching pool; Custom side → randomly pick from pools where the non-Custom side matches. Placeholders `{name}` → me, `{p_name}` → ta. Counts: ff~231, mm~68, fm~35, mf~19.

**ViewModel + UI** — `GameViewModel.kt` / `MainActivity.kt` / `CharacterSelectionActivity.kt` / `LandingActivity.kt` / `NovelIndex.kt` / `PromptEditor.kt` / `MemoryRecord.kt` (defines `object MemoryReader` — file name doesn't match the object) / `TutorialBanner.kt` / `Loaders.kt` (Compose-Canvas `HeartsLoader` for prefill spinner + `OptionPulseLoader` for per-option waits, ported from SVG-Loaders since SMIL doesn't render on Android)

### Key flows

**Startup** — `LandingActivity` loads `PromptTemplates`, `StoryPools`, `NovelIndex`, renders the grid. Tapping "+" → `CharacterSelectionActivity`; on 开始, calls `NovelIndex.create(...)` (appends an `Entry` + writes `novel_index.json`), `MemoryReader.writeSeedStub(...)` (writes id=0 record with empty `story`/`options`), then launches `MainActivity` with `MEMORY_FILE_KEY` + `NOVEL_ID_KEY`.

**MainActivity** reads the memory file (single source of truth for `pair`/`bg`/`beg`/`act`), builds a frozen VM per completed record, then a live VM for the last record. If the last record is a stub (empty `options`), it calls `initialize(...)`; otherwise `resume(...)`.

**Prompt provider pattern** — `MainActivity` holds `pair: CharacterPair`, `random: Random`, and a `promptProvider: () -> String = { SystemPromptBuilder.build(pair, random) }` lambda. Every `runGeneration` invokes the provider at the top, giving each turn a fresh re-roll of probabilistic clauses. Same pattern for temperature: `temperatureProvider: () -> Float = { NovelIndex.find(novelId)?.temperature ?: 0.6f }`.

**Model-load entry points** — `initialize`, `resume`, and `quickStart` all call `ensureModelLoaded()` before generating. **Every entry point that reaches `runGeneration` must do this.** `LlamaEngine.isLoaded` is the Kotlin-visible source of truth; there is no companion-scope `modelLoaded` flag in `GameViewModel`.

### Token streaming

```
IO thread  (LlamaEngine.generate → nativeGenerate)
  └─ JNI TokenCallback → tokenQueue.offer(piece)   [ConcurrentLinkedQueue<String>]

Main thread  (runDisplayLoop)
  └─ polls tokenQueue → ParseState machine → novelText / _options
```

A `@Volatile modelFinished` flag signals IO-thread completion. `ParseState` (`IDLE → IN_TAG → IN_NOVEL / IN_OPTION`) routes char-by-char into `novelText` (mutableStateOf, Main-thread writes for streaming recomposition) and `_options` (MutableStateFlow). `optionsTappable` is the **persist-before-tap gate**: flipped true only after `MemoryReader.upsertMemory` returns; reset in `runGeneration` and `freeze()`.

### Multi-VM / continuation

`allVMs: SnapshotStateList<GameViewModel>` — one VM per scene. Only the last VM receives live callbacks. `spawnNextTurn(currentVm, action)`:
- Turn 1: `nextBg = lastBackground + \n + lastBeginning + \n + lastAction`; turn ≥2: `nextBg = lastBackground + \n + lastAction`.
- `nextBeginning = currentVm.novelText`.
- Freezes the current VM, sync-flushes `currentVm.draftInput.value` to disk (`options[3]`), then creates a new VM with `recordId = prev + 1` and calls `quickStart(...)`.

### Memory persistence

Per-novel file: `getExternalFilesDir(null)/memory_<timestamp>.json`. Schema: `{id, character, system_prompt, background, beginning, input_action, story, options[]}`. `options` is `[]` for the seed stub or size **4** otherwise (`[0..2]` = model tiles, `[3]` = user's fourth-option text). `system_prompt` reflects the *actual* re-rolled string sent on that turn.

`attachMemoryListener` upserts on `isLoading: true → false` then flips `optionsTappable=true`. `attachDraftListener` autosaves `draftInput` with `.drop(1).debounce(2000L)` → `MemoryReader.updateOption4`. On submission `spawnNextTurn` sync-flushes the latest snapshot to handle the <2 s case.

**Legacy migration**: pre-schema records have `options.size == 3` + sibling `draft_input`; `MemoryReader.read` appends `optString("draft_input", "")` so in-memory rows are always size 4. The next `upsertMemory`/`updateOption4` drops the legacy field.

To inspect: `adb pull /sdcard/Android/data/com.example.ppo/files/`.

### Novel library

`NovelIndex` (singleton, `SnapshotStateList<Entry>` backed by `novel_index.json`). Each `Entry`: `id` (UUID), `memoryFile`, `name`, `coverColorSeed`, `createdAt`, `pairJson`, `seedBg/Beg/Act`, optional `systemPromptTemplate: String?`, optional `temperature: Float?`. Nullable fields are skipped when null so unmodified novels keep the index byte-identical. API: `load`, `all`, `find`, `create`, `rename`, `rerollCover`, `updateTemplate`, `updateTemperature`, `delete`, `reorder`.

**Legacy handling**: if `novel_index.json` is missing, `autoImport` scans for `memory_*.json` and synthesizes entries from the first record of each. If entries lack `pair_json`/`seed_*`, `parse` back-fills from the memory file.

Cover tiles: 2:3 box, `Color.hsl((seed % 360 + 360) % 360, 0.45f, 0.6f)`, contrast title via luminance threshold. Config mode (long-press): scales to 0.92, reveals 删除/换色/改名 overlays, enables `detectReorderAfterLongPress`. Drag bounds are `novels.indices` so the dragged tile can't land on the "+" cell.

### Live-scene gestures

The last (live) scene in the stack is the only VM that accepts gestures: tap an option tile to commit it and `spawnNextTurn`; **long-press the novel text to `regenerate()`** the current turn in place (re-rolls system-prompt clauses + re-runs generation, no new scene). Frozen scenes ignore both. The 编辑 (top-right) icon also targets the live VM.

### Tutorial banners (`TutorialBanner.kt`)

Inline `TutorialBanner(text, prefKey)` composable + `object TutorialPrefs` (SharedPreferences-backed, prefs file `ppo_tutorials`). Two dismissal actions: 关闭 (session-only) and 不再显示 (persisted). Seven stable keys at the bottom of the file (`TUTORIAL_LANDING`, `TUTORIAL_CHARACTER_STORY`, `TUTORIAL_FIRST_GENERATION`, `TUTORIAL_LONG_PRESS_REGENERATE`, `TUTORIAL_EDIT_TEMPLATE`, `TUTORIAL_EDIT_MEMORY`, `TUTORIAL_EDIT_TEMPERATURE`) — **renaming any of these re-shows the banner to existing users**, since dismissal state is keyed on the string.

### Prompt-editor sheet (`PromptEditor.kt`)

Top-right edit icon opens a `ModalBottomSheet` with three sections:
1. **系统提示** — the system-prompt template (per-novel `Entry.systemPromptTemplate`), with `ProbabilityVisualTransformation` highlighting `⟦pN:…⟧` spans. AssistChips 25/50/75 wrap the selection; 清除 strips an enclosing span.
2. **模型记忆** — the live record's `background` field.
3. **模型温度** — Slider 0–1.0, step 0.05.

Open-time snapshot via three `remember(showPromptEditor)` blocks so concurrent re-rolls / aborts don't shift editor state mid-edit. If opened while `isLoading`, dispatches `abortGeneration()` and resumes via `regenerate()` on save/cancel.

Save flow: `NovelIndex.updateTemplate` → `NovelIndex.updateTemperature` (0.6 normalizes to `null` to keep the index byte-identical) → `MemoryReader.updateBackground` (IO) → `liveVm.setLastBackground(memory)` synchronously on Main (so the next `regenerate`/`spawnNextTurn` sees the edit immediately, not after restart).

Validation: `validateTemplate` flags unmatched/nested `⟦…⟧`. Memory and temperature are unvalidated.

### Model extraction

`test_1.gguf` is copied from `assets/model/` to `app.filesDir` on first launch. Skipped if present; partial files are deleted on failure.

## IDE diagnostics

The IDE's clang language server will show false errors in `llama_jni.cpp` (`'android/log.h' file not found`, `Unknown type name 'llama_model'`, etc.). These are not real — the file only compiles correctly through the Gradle/NDK toolchain. Use `./gradlew assembleDebug` as the source of truth.

## CMake / llama.cpp

`app/src/main/cpp/CMakeLists.txt` adds llama.cpp as a subdirectory and links it statically. Flags from `CMakeLists.txt` + `app/build.gradle.kts`:
- `GGML_NATIVE=OFF` — mandatory for cross-compilation
- `GGML_BACKEND_DL=OFF`, `BUILD_SHARED_LIBS=OFF` — single `.so`, no dynamic backends
- `LLAMA_BUILD_COMMON=ON` — chat templates, samplers, tokenization helpers
- `GGML_CPU_ALL_VARIANTS=OFF`, `GGML_OPENMP=OFF` — keep the Android build simple

`androidResources { noCompress += listOf("gguf") }` stores the model uncompressed in the APK so first-launch asset copy is fast.
