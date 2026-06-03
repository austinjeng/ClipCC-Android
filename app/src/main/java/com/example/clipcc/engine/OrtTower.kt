package com.example.clipcc.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet
import kotlin.math.sqrt

class OrtTower private constructor(
    private val session: OrtSession,
    private val env: OrtEnvironment,
    val backend: Backend,
) : AutoCloseable {
    companion object {
        /** Build SessionOptions for [backend]+[config]. NNAPI EP-add is wrapped: on throw we record it
         *  in [outcome] and continue with whatever EPs applied (never relabel). Throws only if
         *  createSession itself fails. [profilePath] (non-null) enables ORT profiling to that base path. */
        fun open(
            path: String,
            env: OrtEnvironment,
            backend: Backend,
            config: BackendConfig = BackendConfig(),
            profilePath: String? = null,
            outcome: (OpenOutcome) -> Unit = {},
        ): OrtTower {
            var addEp = "n/a"
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(config.intraOpThreads)
                setInterOpNumThreads(config.interOpThreads)
                setOptimizationLevel(config.graphOpt)
                setMemoryPatternOptimization(config.memoryPattern)
                if (profilePath != null) enableProfiling(profilePath)
                try {
                    when (backend) {
                        Backend.CPU_XNNPACK -> {
                            addXnnpack(mapOf("intra_op_num_threads" to config.intraOpThreads.toString())); addEp = "ok"
                        }
                        Backend.CPU_EP -> addEp = "ok"  // default ORT CPU EP, no extra EP
                        Backend.NNAPI_DEFAULT -> { addNnapi(EnumSet.noneOf(NNAPIFlags::class.java)); addEp = "ok" }
                        Backend.NNAPI_CPU_DISABLED -> { addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED)); addEp = "ok" }
                    }
                } catch (t: Throwable) { addEp = "threw: ${t.message}" }
            }
            return try {
                val s = env.createSession(path, opts)
                outcome(OpenOutcome(backend, addEp, "ok"))
                OrtTower(s, env, backend)
            } catch (t: Throwable) {
                outcome(OpenOutcome(backend, addEp, "threw: ${t.message}"))
                throw t
            }
        }
    }

    private fun inputName() = session.inputNames.first()

    /** Run a [rows, *] tensor; return L2-normalized POOLED embeddings [rows][dim] (by-name pooler_output). */
    private fun runEmbed(buf: Buffer, shape: LongArray, rows: Int): Array<FloatArray> {
        val tensor = when (buf) {
            is FloatBuffer -> OnnxTensor.createTensor(env, buf, shape)
            is LongBuffer -> OnnxTensor.createTensor(env, buf, shape)
            else -> error("buffer type")
        }
        tensor.use { t ->
            session.run(mapOf(inputName() to t)).use { res ->
                val pooled = res.get("pooler_output").orElseThrow {
                    IllegalStateException("no pooler_output; outputs=${session.outputNames}")
                } as OnnxTensor
                @Suppress("UNCHECKED_CAST")
                val rowsData = pooled.value as Array<FloatArray>
                check(rowsData.size == rows) { "expected $rows rows, got ${rowsData.size}; shape=${pooled.info.shape.toList()}" }
                return Array(rows) { r ->
                    val v = rowsData[r].copyOf()
                    var n = 0f; for (x in v) n += x * x; n = sqrt(n)
                    if (n > 0f) for (i in v.indices) v[i] = v[i] / n
                    v
                }
            }
        }
    }

    /** Encode [frames] in fixed chunks of [batch] (1 for XNNPACK; the D5 map for CPU_EP).
     *  [onItem] is invoked with the running count after each chunk; [isCancelled] is checked
     *  before each chunk and throws [RunCancelledException] if true. */
    fun encodeVision(
        pixelValues: FloatBuffer, frames: Int, res: Int, batch: Int,
        onItem: ((Int) -> Unit)? = null, isCancelled: (() -> Boolean)? = null,
    ): Array<FloatArray> {
        (pixelValues as Buffer).rewind()
        val per = 3 * res * res
        val out = ArrayList<FloatArray>(frames)
        var f = 0
        while (f < frames) {
            if (isCancelled?.invoke() == true) throw RunCancelledException()
            val n = minOf(batch, frames - f)
            val chunk = ByteBuffer.allocateDirect(n * per * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val base = f * per
            for (i in 0 until n * per) chunk.put(pixelValues.get(base + i))
            (chunk as Buffer).rewind()
            for (row in runEmbed(chunk, longArrayOf(n.toLong(), 3, res.toLong(), res.toLong()), n)) out.add(row)
            f += n
            onItem?.invoke(f)
        }
        return out.toTypedArray()
    }

    /** Encode [labels] padded ids. Batched if [batch] > 1 (CPU_EP); per-label if 1 (XNNPACK). */
    fun encodeText(inputIds: LongArray, labels: Int, maxLen: Int, batch: Int): Array<FloatArray> {
        val out = ArrayList<FloatArray>(labels)
        var l = 0
        while (l < labels) {
            val n = minOf(batch, labels - l)
            val buf = ByteBuffer.allocateDirect(n * maxLen * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
            for (i in 0 until n * maxLen) buf.put(inputIds[l * maxLen + i])
            (buf as Buffer).rewind()
            for (row in runEmbed(buf, longArrayOf(n.toLong(), maxLen.toLong()), n)) out.add(row)
            l += n
        }
        return out.toTypedArray()
    }

    /** Run one frame with profiling on, end profiling, return the profile-file path (vision capability probe). */
    fun runOnceForProfile(pixelValues: FloatBuffer, res: Int): String {
        encodeVision(pixelValues, 1, res, 1)
        return session.endProfiling()
    }

    /** Run one label with profiling on (text capability probe). */
    fun runOnceForProfileText(inputIds: LongArray, maxLen: Int): String {
        encodeText(inputIds, 1, maxLen, 1)
        return session.endProfiling()
    }

    override fun close() = session.close()
}
