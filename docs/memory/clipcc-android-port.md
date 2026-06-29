---
name: clipcc-android-port
description: clipCC-Android — native on-device SigLIP2 benchmark app; runtime/parity decisions
metadata:
  node_type: memory
  type: project
  originSessionId: 329a9c79-36a3-45fd-bc5f-b2c2f0d40359
---

Building a native Android app (Kotlin/Compose) to benchmark on-device SigLIP2 inference speed
for 4 models on a **Pixel 7a (Tensor G2)**, with full parity to the Python clipCC pipeline.
Decided 2026-06; specs/plans under `docs/specs` + `docs/plans`.

Load-bearing decisions (research-verified, not assumptions):
- **Runtime = ONNX Runtime Mobile + prebuilt `onnx-community/siglip2-*-ONNX` towers, XNNPACK
  CPU.** Chosen over LiteRT/ExecuTorch because SigLIP2 ONNX export already exists; LiteRT
  (ai-edge-torch) has no turnkey SigLIP2.
- **NPU is structurally unavailable on Tensor G2** for third-party custom models (NNAPI
  deprecated; LiteRT NPU delegate is Qualcomm/Intel-only; Tensor ML SDK is Pixel 10/G5-only).
  **GPU is fragile** on a ViT. So benchmark = CPU (real) + GPU/NPU as **experimental
  attempt-and-report** with actual-backend + node-coverage labeling. Don't promise NPU accel.
- **Benchmark profile `benchmark-v1`** = base-256, base-384 (fp32), large-384, so400m-384
  (**fp16** — fp32 OOMs 8 GB). NOT the Python `default_model_id` or `DEV_MODELS` preset.
- **onnx-community ships prebuilt `*_fp16.onnx`** — download per precision, no in-process
  conversion. For fp32 large/so400m it's the **TEXT** tower that carries external `.onnx_data`;
  our fp16 profile towers are all single-file (<2 GB).
- **Parity = lossless reference** (PNG frames, no 512 pre-scale); production `video.py`
  ffmpeg+JPEG path left unchanged. Two-layer: exact model-math gate on lossless frames vs
  documented tolerance for Media3 decode.
- **Resize is BILINEAR (resample=2), NOT bicubic** — all 4 models' preprocessor_config.
  Android `createScaledBitmap(filter=true)` is bilinear → no custom kernel. Residual Plan-1
  item: host export env uses slow PIL bilinear (no torchvision), server `.venv` has torchvision
  0.27.0 → may use fast `SiglipImageProcessorFast` (subtly different); pin which generates
  fixtures + set tolerance.
- **Tokenizer is CASE-SENSITIVE** (Spike 0a, resolved): shipped `tokenizer.json` has no
  Lowercase normalizer; `GemmaTokenizerFast` does not lowercase. `lowercase_applied_by =
  tokenizer_json` → **Android must NOT lowercase labels** (would break parity). HF `tokenizers`
  Rust crate via JNI on `tokenizer.json` + truncate-64/pad-0; parity 9/9.

Phase 0 host tasks (1–9) DONE on branch `feat/clipcc-android-phase0`: tooling under
`tools/android_assets/`, all 4 ONNX bundles exported from `onnx-community` (prebuilt, single-
file, fp32 base / fp16 large+so400m), golden fixtures generated, 9 tests pass. Export env venv
`.venv-export` pins transformers 4.57.6 (optimum-onnx capped <v5).

**Phase 0 COMPLETE** (device spikes run on Pixel 7a / Android 16, see `phase0-spike-results.md`):
0b — XNNPACK ~12% nodes delegated, **NNAPI 0% (CPU-only)** → no NPU accel, BackendCapabilityReport
readable from ORT profiling JSON. 0c — so400m fp16 towers load + sha256 OK. 0d — peak ~3.19 GB
both towers (fits 8 GB), **batch ≤8 (batch 32 → LMK thrash)**, **~16 s/frame so400m on CPU**.
Android project: `/Users/austin/AndroidStudioProjects/ClipCC` (`:app`, com.example.clipcc, AGP 9,
NOT git). Instrumented-test staging: read models from `/data/local/tmp`, write profiles to app
`cacheDir`. Models left at `/data/local/tmp/clipcc_spike/` (~2.6 GB).

**Plan 1 (headless engine) COMPLETE 2026-06-03** — all 7 tasks, all gates passed on Pixel 7a.
Engine in `app/src/main/.../engine/` (HfTokenizer, Resampler, Preprocess, Manifest, Scoring,
OrtTower, Engine). End-to-end acceptance gate vs `scores_golden.json`: cosine_max **9.09e-5**
(≤0.01), confidence_max 6.14e-7, **0 best-match flips**. Suite green offline: unit 31/31
(Resampler 3, Manifest 1, Scoring 27) + 4 instrumented engine classes. Tokenizer toolchain
(installed): Rust 1.96 + cargo-ndk 4.1.2; NDK r28c `~/Library/Android/sdk/ndk/28.2.13676358`;
`~/.bash_profile` not writable → set PATH/JAVA_HOME explicitly. See `phase1-report.md`.

**TWO ORT facts the plan got WRONG, corrected during Plan-1 execution (load-bearing for Plan 2):**
- **Embedding = ONNX output `pooler_output`, selected BY NAME — NOT `res[0]`.** Both towers output
  `[last_hidden_state, pooler_output]`; `res[0]` = `last_hidden_state` (wrong tensor). Host check:
  normalized `pooler_output` reproduces golden cosine to 1.96e-7.
- **XNNPACK EP collapses the symbolic batch dim to 1 for BOTH towers** (N>1 → `[1,768]`, even fresh
  session). So batched inference is correct ONLY on the CPU EP. Engine runs **batch=1 per item**
  (vision per-frame, text per-label) under XNNPACK; `OrtTower.runEmbed` has a `check(rows)` guard
  that makes silent shape-collapse impossible. (Resolves the old "batched-inference correctness"
  open item: the buffers aren't batch-1-shaped — XNNPACK collapses dynamic batch.) Real-batch
  throughput would need the CPU EP — perf-vs-EP decision for Plan 2.
- Build: ONNX is `implementation` (not androidTestImplementation) so OrtTower/Engine live in
  `src/main` and the app bundles ORT native libs. `org.json:json` is a `testImplementation` dep
  (Android stubs org.json in JVM unit tests). minSdk-24 rewind gotcha: `(buf as java.nio.Buffer).rewind()`.

**Plan 2 (benchmark harness) COMPLETE 2026-06-03** — headless benchmark over real Media3-decoded
video, 4 models × {CPU_XNNPACK per-frame, CPU_EP batched} + per-model backend-capability + fp16
consistency. All gated + reviewed on Pixel 7a. Report `phase2-report.md`; raw `phase2-benchmark-result.json`.
New engine: `Backend`/`BackendConfig`, refactored `OrtTower` (backend-aware), `BackendCapability`,
`FrameSampler` (Media3 `media3-inspector-frame:1.10.1`), `Benchmark`. Findings (load-bearing for Plan 3):
- **CPU_EP batching ~2× faster than XNNPACK per-frame on small fp32** (base-256 1202 vs 2372 ms/frame);
  **marginally slower on large fp16** (large/so400m). so400m ≈ **18 s/frame** on CPU. No thermal throttle.
- **NNAPI delegates 0%** on all 4 models (`addNnapi`/`createSession` succeed but profiler shows 0 HW nodes
  → silent CPU fallback). XNNPACK delegates ~9–12% vision nodes. Reported honestly via ORT profiling JSON
  (`enable_profiling`, parse `cat=="Node"` args.provider), never relabeled.
- fp16 XNNPACK-vs-CPU_EP cosine: large 0.0024, so400m 0.0097 (≤1e-2); fp32 ~1e-7.
- **so400m OOM gotcha**: one process benchmarking all 4 models native-crashes (`shortMsg=Process crashed`)
  on so400m after ~30 min accumulated ORT memory (uncatchable). Fix = **one `am instrument` per model**
  (fresh process, `-e model <id>`), per-model JSON merged host-side. Retrieve results via `run-as`
  (`getExternalFilesDir(null)` is null on this device → internal `filesDir`).
- Build: `media3-inspector-frame:1.10.1` `implementation`. `FrameExtractor` is AutoCloseable (`close()` not
  `release()`), `getFrame(long): ListenableFuture<Frame>`, `Frame.bitmap`/`.presentationTimeMs` public fields.
- JSON serializer must use `Locale.US` floats + `JSONObject.quote()` for exception strings (NNAPI error text).

**Plan 3 (Compose UI) COMPLETE 2026-06-03** — all 17 tasks subagent-driven + final holistic review.
2-tab app (Classify live-run / read-only Benchmark). JVM unit **59/0-fail**; on-device gates green
(FrameSampler, EndToEndParity **0 flips** post-change, OrtBackend, new ClassifyEndToEnd smoke = real
ONNX inference over the clip). Report `phase3-report.md`; spec `2026-06-03-clipcc-android-ui-design.md`.
- **Engine touches were ADDITIVE & parity-neutral** (existing `scoreFrames` + Plan-1/2 tests untouched):
  `FrameSampler.sample(uri,fps,max,onFrame)` streaming overload; `OrtTower.encodeVision` gained optional
  `onItem`/`isCancelled`; `Engine` gained `encodeTextEmbeddings`/`withVisionEncoder`/`VisionEncoder` +
  `RunCancelledException`; `Manifest` reads display_name/score_semantics/bytes/sha256.
- **Decisions:** live-classify is the star; **defer the network downloader** (adb-push provisioning into
  `filesDir/models/<id>/`); **custom Canvas charts** (no lib); **app-side `ScoringPolicy` constants
  RETIRE the schema-v2 idea** (defaults are model-INDEPENDENT, pinned to Python; manifest v1 already
  carries the per-model values); ViewModel coroutine + keep-screen-on + cooperative cancel (no service).
- **Memory-bounded run:** chunked decode→encode→release (`visionChunkFor`: CPU_EP base16/large8/so400m4,
  XNNPACK 16/batch1); retain only small per-frame thumbnails. ViewModel state is platform-light
  (`videoUriString`, not `Uri`) → plain-JVM testable; `SavedStateHandle` restore via `createSavedStateHandle`.
- `ModelInfo.id = dir.name` (bundle dirs are named after model_id, so == model_id in prod).
- **1 manual step left:** per-mode RESULTS screenshots (pick `/sdcard/Download/clipcc_test.mp4` via the
  SAF picker, Run each mode) — picker not scriptable. Still deferred: downloader/Xet, fp32 fp16-drift
  goldens, longer bench clips, model eviction. See [[clipcc-android-review-discipline]].

**REPO SPLIT (2026-06-06):** Android code is now its OWN public GitHub repo **`austinjeng/ClipCC-Android`**
(local at `/Users/austin/AndroidStudioProjects/ClipCC`, now git-initialized; Rust `cpp-tokenizer/target/`
gitignored, prebuilt `jniLibs/*.so` committed). The 14 Android design docs (specs + Plan 0-3 reports)
**moved here** under `docs/specs` + `docs/plans`. The host-side export tooling `tools/android_assets/` STAYS in the
Python repo. Python repo `austinjeng/clipCC` `master` was fast-forwarded with all 76 previously-unpushed
commits (Python app dev + android_assets) and pushed — pushing master triggers the GHCR Docker-publish
workflow. Provisioned models live only in the app's `filesDir/models/` on the device (re-`run-as cp` after
any reinstall/data-wipe).
