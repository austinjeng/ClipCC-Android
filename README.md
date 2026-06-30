# ClipCC-Android

**English** | [繁體中文](README.zh-TW.md)

Native Android app (Kotlin + Jetpack Compose / Material 3) that runs **SigLIP2 vision-language
classification entirely on-device** and **benchmarks on-device inference speed** for four SigLIP2
models. It reproduces the Python [clipCC](https://github.com/austinjeng/clipCC) pipeline's scoring (per-label sigmoid
confidence + raw cosine similarity) and all four aggregation modes — `mean` / `max` / `temporal` /
`contrast` — with charts, plus a benchmark panel.

**Runtime:** ONNX Runtime Mobile (`onnxruntime-android` 1.26) on prebuilt `onnx-community/siglip2-*-ONNX`
towers. Frame decode via Media3 `FrameExtractor`. Tokenizer via the HuggingFace `tokenizers` Rust crate
cross-compiled to a JNI `.so`.

**Target device:** Pixel 7a (Google Tensor G2) for the benchmark numbers below; also runs and is
self-tested on Pixel 9a (Google Tensor G4). API 24+.

## Headline benchmark finding (Pixel 7a, CPU-only, median-of-3)

| Model | precision | best lane | ms/frame | fps |
|---|---|---|---|---|
| siglip2-base-patch16-256 | fp32 | CPU·EP (batched) | 1202 | 0.83 |
| siglip2-base-patch16-384 | fp32 | CPU·EP | 2966 | 0.34 |
| siglip2-large-patch16-384 | fp16 | CPU·XNNPACK | 10678 | 0.094 |
| siglip2-so400m-patch14-384 | fp16 | CPU·XNNPACK | 17880 | 0.056 |

- **No GPU/NPU acceleration is available** for these custom models on Tensor G2: NNAPI delegates **0%**
  (session builds, every op silently falls back to CPU). Reported honestly as "experimental / not timed".
- XNNPACK accelerates only ~9–12% of vision graph nodes; the rest run on the ORT CPU EP.
- CPU·EP batching is ~2× faster than per-frame XNNPACK on the small fp32 models; roughly even on the
  large fp16 ones.

## On-device classification (Classify tab)

Pick a **model**, a **precision**, a **video**, some **labels**, and an **aggregation mode**, then
Run — all inference is local.

- **Precision** — each bundle ships `fp32` / `fp16` / `int8` towers; choose per run. The manifest's
  `default_precision` is shown as *recommended*. ⚠️ `int8` can shift threshold decisions (measured up
  to 0.18 confidence / 0.03 margin drift vs the reference) — detections/verdicts may differ from the
  Python pipeline; use `fp16` for threshold parity.
- **Labels** — add/edit inline (editor capped at 50 rows; any extra imported labels still run), or
  **Import CSV** (one label per row; ≤ 256 KB, ≤ 1000 labels, ≤ 256 chars/label, strict UTF-8,
  deduped; Replace or Append). A **Remove duplicates** button repairs a list in place.
- **Modes** — `mean` / `max` / `temporal` (timeline + segments + per-label summaries, with
  threshold / gap-tolerance / min-duration controls) / `contrast`. **`contrast` stays hidden until
  you tap the `temporal` chip 20 times** (it needs positive/negative label groups).
- **Results** (rendered below the form — scroll down) — a best-match card plus a ranked list of
  **relative confidence meters**: bar fill = `confidence / max`, matching Python's UI. SigLIP2's
  absolute sigmoid confidence is near-zero, so the *bars* (not the `%`) carry the ranking. Tap a row
  to expand its raw cosine and peak-frame thumbnail. Temporal and contrast modes add their own
  charts / verdict.

## Architecture

```
app/src/main/java/com/example/clipcc/
  engine/      # headless: OrtTower, Engine, FrameSampler, Preprocess, Resampler,
               # HfTokenizer (JNI), Scoring (4 aggregation modes), ScoringPolicy, Manifest, Benchmark
  data/        # ModelRepository (bundle discovery + readiness)
  ui/          # Compose: Classify (Setup → Run → Results) + Benchmark tabs, Canvas charts
  jniLibs/     # prebuilt libhftokenizer.so (arm64-v8a, x86_64)
  cpp-tokenizer/  # Rust source for the tokenizer JNI lib (build cache is gitignored)
```

The vision tower runs once per frame-set (the benchmark hot path); the text tower runs once per
label-set. Scores derive from L2-normalized embeddings: `cosine = img·txtᵀ`,
`confidence = sigmoid(cosine·exp(logit_scale) + logit_bias)` (per-label, independent — not softmax).

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"  # or your JDK 17+
./gradlew :app:assembleDebug      # build
./gradlew testDebugUnitTest        # JVM unit tests (123)
./gradlew :app:installDebug        # install (does NOT wipe app data)
```

The prebuilt tokenizer `.so` is committed, so a fresh clone builds without the Rust/NDK toolchain.
To rebuild it: `cd app/src/main/cpp-tokenizer && cargo ndk -t arm64-v8a -t x86_64 -o ../jniLibs build --release`.

## Provisioning models (no in-app downloader yet)

The model bundles are **not** in the APK. Each bundle is a directory named after its `model_id`
holding `manifest.json` (schema v2), `tokenizer.json`, and per-precision towers —
`vision_model{,_fp16,_int8}.onnx` + `text_model{,_fp16,_int8}.onnx`. The app reads from
`getExternalFilesDir(null)/models/<id>/`, falling back to internal `filesDir/models/<id>/`.

On devices where external app storage is available (e.g. Pixel 9a), `adb push` straight in:

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
DST=/sdcard/Android/data/com.example.clipcc/files/models
# local bundles under ./clipcc_models/<id>/
for m in siglip2-base-patch16-256 siglip2-base-patch16-384 siglip2-large-patch16-384 siglip2-so400m-patch14-384; do
  $ADB push "clipcc_models/$m" "$DST/"
done
$ADB shell am force-stop com.example.clipcc   # relaunch so it re-scans models/
```

If `getExternalFilesDir(null)` is null on your device (it was on the Pixel 7a), copy into internal
storage via `run-as` instead:

```bash
$ADB shell "run-as com.example.clipcc cp -r /data/local/tmp/clipcc_models/$m files/models/$m"
```

Then in the app: pick a model + precision + a video + labels + mode → Run. A network downloader
(HF Xet / resume / sha256 verify / eviction) is deferred future work.

## Tests

- **JVM unit (123):** ScoringPolicy, Manifest, ModelRepository, LabelValidation, LabelCsv, ChartData,
  BenchmarkData, ClassifyViewModel, ScoreView, plus the engine's Resampler/Scoring.
- **Instrumented (device):** Tokenizer / Preprocess / OrtBackend / **EndToEndParity** (cosine ≤ 0.01 vs
  Python golden, 0 best-match flips) / BackendCapability / FrameSampler / Benchmark / ClassifyEndToEnd smoke.

## Status

Engine + benchmark + interactive Classify UI (selectable precision, CSV label import, charted
relative-meter results) complete and verified on-device. Built test-driven, subagent-driven.
