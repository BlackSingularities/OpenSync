package com.blacksingularities.opensync.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Progress(
    val jobId: String,
    val title: String,
    val phase: String,
    val detail: String = "",
    val fraction: Float = -1f,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val uploaded: Long = 0,
    val bytesPerSec: Long = 0,
)

/** Bieżące operacje (widoczne w interfejsie na żywo, niezależnie od WorkManagera). */
object Operations {
    private val _running = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val running: StateFlow<Map<String, Progress>> = _running.asStateFlow()

    fun set(p: Progress) = _running.update { it + (p.jobId to p) }
    fun clear(jobId: String) = _running.update { it - jobId }
}
