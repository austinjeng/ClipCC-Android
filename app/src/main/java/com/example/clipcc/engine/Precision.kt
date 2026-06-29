package com.example.clipcc.engine

/**
 * ONNX precision variant of a tower. [key] matches the manifest `precisions` map keys and the
 * onnx-community file suffix convention (`vision_model[_fp16|_int8].onnx`).
 */
enum class Precision(val key: String, val fileSuffix: String) {
    FP32("fp32", ""),
    FP16("fp16", "_fp16"),
    INT8("int8", "_int8");

    companion object {
        fun fromKey(k: String): Precision =
            entries.firstOrNull { it.key == k } ?: error("unknown precision '$k'")
    }
}
