# ClipCC-Android

[English](README.md) | **繁體中文**

原生 Android 應用程式（Kotlin + Jetpack Compose / Material 3），**完全在裝置端執行 SigLIP2 視覺語言
分類**，並針對四個 SigLIP2 模型**測量裝置端推論速度**。它重現 Python [clipCC](https://github.com/)
流程的評分方式（每個標籤獨立的 sigmoid 信心值 + 原始 cosine 相似度），並支援全部四種彙整模式
（`mean` / `max` / `temporal` / `contrast`）搭配圖表呈現，另外提供一個效能測試面板。

**執行環境：** ONNX Runtime Mobile（`onnxruntime-android` 1.26），搭配預先建置的
`onnx-community/siglip2-*-ONNX` towers。影格解碼使用 Media3 `FrameExtractor`。Tokenizer 使用
HuggingFace `tokenizers` Rust crate 交叉編譯成的 JNI `.so`。

**目標裝置：** Pixel 7a（Google Tensor G2）。API 24 以上。

## 主要效能測試結果（Pixel 7a，純 CPU，三次取中位數）

| 模型 | 精度 | 最快通道 | ms/frame | fps |
|---|---|---|---|---|
| siglip2-base-patch16-256 | fp32 | CPU·EP（批次） | 1202 | 0.83 |
| siglip2-base-patch16-384 | fp32 | CPU·EP | 2966 | 0.34 |
| siglip2-large-patch16-384 | fp16 | CPU·XNNPACK | 10678 | 0.094 |
| siglip2-so400m-patch14-384 | fp16 | CPU·XNNPACK | 17880 | 0.056 |

- **這些自訂模型在 Tensor G2 上沒有可用的 GPU/NPU 加速：** NNAPI 委派率為 **0%**（session 可以建立，
  但每個運算都會靜默退回 CPU）。如實標示為「實驗性 / 未計時」。
- XNNPACK 只加速約 9–12% 的 vision 圖節點，其餘都在 ORT CPU EP 上執行。
- 在小型 fp32 模型上，CPU·EP 的批次處理比逐影格的 XNNPACK 快約 2 倍；在大型 fp16 模型上則大致相當。

## 架構

```
app/src/main/java/com/example/clipcc/
  engine/      # 無介面核心：OrtTower、Engine、FrameSampler、Preprocess、Resampler、
               # HfTokenizer（JNI）、Scoring（四種彙整模式）、ScoringPolicy、Manifest、Benchmark
  data/        # ModelRepository（模型 bundle 探索 + 就緒檢查）
  ui/          # Compose：Classify（Setup → Run → Results）+ Benchmark 兩個分頁、Canvas 圖表
  jniLibs/     # 預先建置的 libhftokenizer.so（arm64-v8a、x86_64）
  cpp-tokenizer/  # tokenizer JNI 函式庫的 Rust 原始碼（建置快取已被 gitignore）
```

vision tower 在每組影格執行一次（效能測試的熱路徑）；text tower 在每組標籤執行一次。評分由 L2
正規化後的 embedding 推導：`cosine = img·txtᵀ`、`confidence = sigmoid(cosine·exp(logit_scale) +
logit_bias)`（每個標籤獨立計算 — 不是 softmax）。

## 建置

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"  # 或你的 JDK 17+
./gradlew :app:assembleDebug      # 建置
./gradlew testDebugUnitTest        # JVM 單元測試（59）
./gradlew :app:installDebug        # 安裝（不會清除 App 資料）
```

預先建置的 tokenizer `.so` 已納入版控，因此全新 clone 不需要 Rust/NDK 工具鏈即可建置。
若要重新建置：`cd app/src/main/cpp-tokenizer && cargo ndk -t arm64-v8a -t x86_64 -o ../jniLibs build --release`。

## 佈署模型（目前尚無 App 內下載器）

四個模型 bundle（總共約 9 GB）**不會**打包進 APK。請將它們推送到 App 的私有儲存空間：

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
# bundle 暫存於 /data/local/tmp/clipcc_models/<id>/（manifest.json + vision/text .onnx + tokenizer.json）
for m in siglip2-base-patch16-256 siglip2-base-patch16-384 siglip2-large-patch16-384 siglip2-so400m-patch14-384; do
  $ADB shell "run-as com.example.clipcc cp -r /data/local/tmp/clipcc_models/$m files/models/$m"
done
$ADB shell am force-stop com.example.clipcc   # 重新啟動以重新掃描 files/models/
```

接著在 App 中：選擇模型 + 影片 + 標籤 + 模式 → Run。網路下載器（HF Xet / 續傳 / sha256 驗證 / 汰除）
列為日後工作。

## 測試

- **JVM 單元測試（59）：** ScoringPolicy、Manifest、ModelRepository、LabelValidation、ChartData、
  BenchmarkData、ClassifyViewModel，以及 engine 的 Resampler/Scoring。
- **裝置端儀器化測試：** Tokenizer / Preprocess / OrtBackend / **EndToEndParity**（cosine ≤ 0.01
  對比 Python golden，0 個最佳匹配翻轉）/ BackendCapability / FrameSampler / Benchmark /
  ClassifyEndToEnd 煙霧測試。

## 狀態

Engine + 效能測試 + Compose UI 皆已完成並於裝置端驗證。以測試驅動（TDD）、subagent 驅動方式開發。
