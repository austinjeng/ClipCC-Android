package com.example.clipcc.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 1 Task 1 GATE: on-device tokenization is byte-exact vs the Python AutoProcessor golden.
 * tokenizer.json read from /data/local/tmp (app can read it); golden shipped in test assets.
 */
@RunWith(AndroidJUnit4::class)
class TokenizerParityTest {
    @Test
    fun matches_python_golden_byte_exact() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val tokBytes =
            File("/data/local/tmp/clipcc_models/siglip2-base-patch16-256/tokenizer.json").readBytes()
        val golden = JSONArray(
            ctx.assets.open("fixtures/tokenizer_golden.json").bufferedReader().readText()
        )
        HfTokenizer.fromJson(tokBytes).use { tk ->
            for (i in 0 until golden.length()) {
                val row = golden.getJSONObject(i)
                val text = row.getString("text")
                val expArr = row.getJSONArray("input_ids")
                val exp = LongArray(expArr.length()) { expArr.getLong(it) }
                val got = tk.encodePadded(text)
                assertArrayEquals("token ids for '$text'", exp, got)
            }
        }
    }
}
