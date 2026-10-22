package com.andyanderso.copilot

import kotlinx.coroutines.flow.MutableStateFlow

/** What the assistant is doing right now. Drives the orb color and status text. */
enum class Phase {
    OFF,        // service not running
    IDLE,       // running, but no wake word (turned off or no model installed) — tap to talk
    WAITING,    // listening for "Hey Claude"
    LISTENING,  // recording your question
    THINKING,   // waiting on Claude
    SPEAKING,   // reading the answer aloud
    ERROR
}

/** One line in the on-screen transcript. */
data class Line(
    val fromUser: Boolean,
    val text: String,
    val confidence: String? = null,      // "high" | "moderate" | "low"
    val sources: List<Source> = emptyList()
)

/**
 * Shared state between the background service (which does the work) and the screen
 * (which only displays it). Both run in the same process, so a singleton is enough.
 */
object AssistantBus {
    val phase = MutableStateFlow(Phase.OFF)
    val hint = MutableStateFlow("")
    val lines = MutableStateFlow<List<Line>>(emptyList())
    val lastModel = MutableStateFlow<String?>(null)

    fun add(line: Line) {
        lines.value = (lines.value + line).takeLast(60)
    }

    fun clear() {
        lines.value = emptyList()
        lastModel.value = null
    }
}
