package com.example.clipcc.ui.app

/** App tabs in display order. Drives both the TabRow labels and the content router so the two
 *  cannot drift apart. */
enum class AppTab(val title: String) {
    CLASSIFY("Classify"),
    BENCHMARK("Benchmark"),
    PIXEL9A("Pixel 9a"),
}
