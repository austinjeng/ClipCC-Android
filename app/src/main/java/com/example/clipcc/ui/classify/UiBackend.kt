package com.example.clipcc.ui.classify

import com.example.clipcc.engine.Backend

enum class UiBackend(val label: String, val engine: Backend, val experimental: Boolean, val note: String) {
    CPU_XNNPACK("CPU·XNNPACK", Backend.CPU_XNNPACK, false, "per-frame; partial XNNPACK delegation"),
    CPU_EP("CPU·EP", Backend.CPU_EP, false, "batched ORT CPU EP"),
    NNAPI("NNAPI", Backend.NNAPI_DEFAULT, true, "experimental — 0% delegated on Tensor G2");
}
