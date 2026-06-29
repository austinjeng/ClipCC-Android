# HANDOFF — Pixel 9a selectable precision

**Date:** 2026-06-29 · **Read first:** the persistent running log at
`~/.claude/projects/-Users-austin-AndroidStudioProjects-ClipCC/memory/clipcc-pixel9a-research.md`
(every decision + measurement, chronological). This file is the short version + exact resume state.

## TL;DR

The "new version for Pixel 9a" = a **selectable per-run precision** feature (fp32/fp16/int8) in the
Classify screen. It is **implemented, device-verified, and committed** on branch
`feat/pixel9a-selectable-precision` (off `main`, **not pushed**). 78 JVM unit tests green; device gates
green; all 4 models provisioned on the phone with the full precision ladder.

## Why this feature (decision chain — do NOT re-derive)

- Researched (workflow `wf_f20047d8-677`) + ran spikes on a real Pixel 9a (Tensor G4, Android 16, 8 GB).
- **Tensor G4 = same CPU-only story as the 7a/G2.** NNAPI delegates **0%** (measured), no ORT GPU/NPU EP,
  Mali GPU measured *slower* than CPU for a ViT. The Tensor TPU is closed to third-party models (the
  Google Tensor SDK that opens it is Pixel 10 / Tensor G5 only). fp16 CPU = **1.1–1.6×** over the 7a.
- **int8 (MLAS/KleidiAI on Armv9) is a real CPU lever:** 2.8× (base) → **4.5× (so400m: ~16→3.9 s/frame)**
  on CPU_EP. No silent dequant. q4 is a trap on CPU_EP (slower).
- **int8 parity rule (the crux):** confidence = sigmoid(cosine·e^4.73 + bias), e^4.73 ≈ **113**. int8's
  0.027 cosine drift → **~3 logits** → cosmetic deep in the sigmoid tail (argmax = MEAN/MAX) but
  **catastrophic near a threshold** (TEMPORAL thr 0.5, CONTRAST thr 0.15). Measured confidence drift up
  to **0.18**. So: **int8 is parity-safe for MEAN/MAX, NOT for TEMPORAL/CONTRAST.** fp16 is exact (4e-5)
  everywhere.
- Decision → **precision follows mode** (argmax→int8, threshold→fp16), **manually overridable**, with
  disclaimers citing the measured drift. so400m **fp32 fits** 8 GB (Fp32FitTest, sequential residency).

## What's implemented (files)

- `engine/Precision.kt` (enum FP32/FP16/INT8, `key`), `engine/PrecisionPolicy.kt`
  (`recommended(thresholdMode)`, `advise(modelId, thresholdMode, precision) -> PrecisionAdvice{level,text}`).
- `engine/Manifest.kt` — **schema v2**: `precisions` map + `default_precision`, `availablePrecisions`,
  `filesFor(precision)`; **v1 fallback** (flat fields mirror default precision).
- `engine/Engine.kt` — `precision` ctor param (defaults to `manifest.defaultPrecision`) → `filesFor`.
- `data/ModelRepository.kt` — `ModelInfo.availablePrecisions` (manifest precisions ∩ files-on-disk).
- `ui/classify/ClassifyModels.kt` — `SetupState.{precision, precisionUserSet, precisionOverridden,
  precisionAdvice}`, `AggMode.isThresholdMode`, `ClassifyRequest.precision`.
- `ui/classify/ClassifyViewModel.kt` — `withDerived` tracking, `setPrecision`/`resetPrecision`,
  `effectiveRecommended`.
- `ui/classify/RealClassifier.kt` — clamps requested precision to provisioned, passes to Engine.
- `ui/classify/SetupCard.kt` — precision segmented control + reactive disclaimer + "↺ recommended".
- `ui/app/ClipCCApp.kt` — **scans `getExternalFilesDir(null)/models`** (the provisioning fix, see below).
- Tests: `PrecisionPolicyTest`, `ManifestV2Test`, `ClassifyViewModelTest` (precision behavior);
  androidTest `PrecisionEngineTest`, `Fp32FitTest`, `ModelScanTest`, `QuantProbeTest`.

## KEY behavioral contract (a bug was fixed here — don't regress it)

- `precisionUserSet` — gates **tracking** (honor the user's pick vs follow the recommendation on
  mode/model change). Persisted.
- `precisionOverridden = (precision != effectiveRecommended)` — drives the **reset button + disclaimer
  visibility**. NOT `precisionUserSet`. (Bug was: button keyed off userSet, so re-picking the recommended
  value left it showing.)
- `setPrecision(p)` sets `userSet = (p != effectiveRecommended)` → **picking the recommended value
  resumes tracking**. `effectiveRecommended` = `recommended(mode)` clamped to `availablePrecisions`.

## Provisioning (CRITICAL — how models reach the device)

- The app scans **`/sdcard/Android/data/com.example.clipcc/files/models`** (`getExternalFilesDir/models`).
- **Provision with a plain push** — NO run-as, NO streaming (run-as can't read /sdcard → Permission
  denied; background `exec-out run-as cat` writes nothing):
  `adb push <bundle-dir> /sdcard/Android/data/com.example.clipcc/files/models/`
- All 4 models are provisioned as **v2 multi-precision [fp32, fp16, int8]**. Verify with:
  `adb shell am instrument -w -e class com.example.clipcc.ModelScanTest <runner>` then
  `adb logcat -d | grep -E 'SCAN|ready='`.
- A v2 bundle dir = `vision_model[_fp16|_int8].onnx` + `text_model[_fp16|_int8].onnx` + `tokenizer.json`
  + `manifest.json` (v2). **fp32 keeps the base filename** (its external-data ref is by name);
  large/so400m **fp32 TEXT needs `text_model.onnx_data`** alongside. Assembler:
  `<scratchpad>/assemble_v2.py` (symlinks HF-cache blobs; push **per-file** to resolve symlinks).
- External dir survives `installDebug`. Re-push only after a data-wipe.

## Environment / commands

- adb: `~/Library/Android/sdk/platform-tools/adb` (NOT on PATH). Device: Pixel 9a `4C081JEBF03962`.
- JDK: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` (JBR 21).
- Build/test: `./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest`
  (JVM, 78 tests); device: `:app:installDebug :app:installDebugAndroidTest` then
  `adb shell am instrument -w -e class <FQCN> com.example.clipcc.test/androidx.test.runner.AndroidJUnitRunner`.
- v1 bundles + golden fixtures (laptop): `/Users/austin/MITAC/clipCC/build/android_assets/`.
- Host python (parity/assembly): `/Users/austin/MITAC/clipCC/.venv-export/bin/python`
  (ort 1.26, transformers 4.57.6, huggingface_hub). onnx-community repos hold the precision ladders.
- Instrumented-test staging on device: `/data/local/tmp/clipcc_models` (v1), `clipcc_models_v2` (base-256
  v2), `clipcc_quant` (int8 vision probes), `clipcc_fp32fit/so400m` (fp32 fit), `clipcc_bench/test.mp4`.

## What's LEFT (prioritized, actionable)

1. **Push branch / open PR** — user committed but hasn't asked to push. Branch `feat/pixel9a-selectable-precision`.
2. **README** (`README.md` + `README.zh-TW.md`) — document the selectable-precision feature + the new
   `adb push` → `getExternalFilesDir/models` provisioning path. (README currently mentions precision only
   in the benchmark sense.)
3. **Host export tooling** (Python repo `austinjeng/clipCC`, `tools/android_assets/export_models.py`) —
   emit v2 multi-precision bundles + manifests so provisioning isn't hand-assembled.
4. **Manual UI screenshot** — SAF-picker run exercising the precision control + disclaimers (picker not
   scriptable; same manual step as Plan 3).
5. **Optional UX** (user deferred): a subtle "recommended" hint when on the default (currently silent);
   WARN disclaimer color (Material `error` red vs a softer amber).
6. **`.idea/` → `.gitignore`** (still untracked, IDE cruft).
7. **Device cleanup** (optional): `/data/local/tmp/clipcc_*` test staging (~10+ GB) is reclaimable.
- **Deferred by design:** broader per-model int8 parity goldens — only matters if int8 were used in
  threshold modes, which it isn't by default (it's disclaimed).

## Gotchas

- run-as can't read /sdcard; background streaming into internal filesDir writes nothing → **use adb push
  to the external dir**.
- so400m benchmarking all-models-in-one-process OOMs (native, uncatchable) → **one `am instrument` per
  model**.
- Embedding = ONNX output **`pooler_output` selected BY NAME**, not `res[0]`.
- **XNNPACK EP collapses dynamic batch to 1**; only CPU_EP batches. Engine runs batch=1 under XNNPACK.
- minSdk-24: `(buf as java.nio.Buffer).rewind()`.
- The device dropped USB once mid-session; replug + re-accept the debugging prompt if `adb devices` is empty.

## Pointers

- Persistent log: `…/memory/clipcc-pixel9a-research.md` (read first).
- Spec: `docs/specs/2026-06-29-clipcc-android-pixel9a-precision-design.md`.
- Plan + report: `docs/plans/2026-06-29-clipcc-android-pixel9a-precision-{plan,report}.md`.
- Spikes: `docs/plans/spikeA-pixel9a-g4-cpu-baseline.md`, `spikeB-host-parity.md`, `spikeB-device-latency.md`.
- Commits: `c2b16b0` (feat), `ea9bf24` (docs) on `feat/pixel9a-selectable-precision`.
