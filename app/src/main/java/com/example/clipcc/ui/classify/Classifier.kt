package com.example.clipcc.ui.classify

/** Progress callback: (stage, chunkDone, chunkTotal). */
typealias ProgressSink = (Stage, Int, Int) -> Unit

interface Classifier {
    /** Runs one classification. [isCancelled] is polled cooperatively at checkpoints.
     *  Throws RunCancelledException (engine) on cancel; other throwables surface as errors. */
    suspend fun classify(req: ClassifyRequest, onProgress: ProgressSink, isCancelled: () -> Boolean): RunResult
}
