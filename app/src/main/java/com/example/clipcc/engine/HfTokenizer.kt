package com.example.clipcc.engine

import org.json.JSONArray

/**
 * Wraps the HuggingFace `tokenizers` Rust engine (libhftokenizer.so) loading a model's
 * `tokenizer.json`. SigLIP2 is CASE-SENSITIVE — this wrapper does NOT lowercase. The only
 * processor behavior added on top of raw subword ids is truncate-to-64 + right-pad with 0.
 */
class HfTokenizer private constructor(private var ptr: Long) : AutoCloseable {
    companion object {
        init { System.loadLibrary("hftokenizer") }
        const val MAX_LEN = 64
        const val PAD_ID = 0L

        fun fromJson(bytes: ByteArray): HfTokenizer = HfTokenizer(createTokenizer(bytes))

        @JvmStatic private external fun createTokenizer(bytes: ByteArray): Long
        @JvmStatic private external fun tokenize(ptr: Long, text: String): String
        @JvmStatic private external fun deleteTokenizer(ptr: Long)
    }

    /** Raw subword ids from the Rust engine (case-sensitive; special tokens included). */
    fun encodeRaw(text: String): LongArray {
        check(ptr != 0L) { "tokenizer closed" }
        val arr = JSONArray(tokenize(ptr, text))
        return LongArray(arr.length()) { arr.getLong(it) }
    }

    /** AutoProcessor-equivalent: encode -> truncate to 64 -> right-pad with 0. No lowercasing. */
    fun encodePadded(text: String): LongArray {
        val ids = encodeRaw(text)
        return LongArray(MAX_LEN) { if (it < ids.size) ids[it] else PAD_ID }
    }

    override fun close() {
        if (ptr != 0L) { deleteTokenizer(ptr); ptr = 0L }
    }
}
