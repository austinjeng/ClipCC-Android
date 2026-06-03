package com.example.clipcc.engine

import ai.onnxruntime.OrtSession

/** ORT backend configurations. NNAPI is ONE EP with flags (no GPU-vs-NPU selection);
 *  the NNAPI runtime picks hardware opaquely. NNAPI lanes are capability probes only. */
enum class Backend { CPU_XNNPACK, CPU_EP, NNAPI_DEFAULT, NNAPI_CPU_DISABLED }

/** Pinned session settings, recorded in every result for fair comparison. */
data class BackendConfig(
    val intraOpThreads: Int = 4,
    val interOpThreads: Int = 1,
    val graphOpt: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT,
    val memoryPattern: Boolean = false,
)

/** Outcome of trying to build a session for a backend (for honest capability reporting). */
data class OpenOutcome(
    val backend: Backend,
    val addEpOutcome: String,       // "ok" | "threw: <msg>" | "n/a"
    val sessionCreateOutcome: String, // "ok" | "threw: <msg>"
)
