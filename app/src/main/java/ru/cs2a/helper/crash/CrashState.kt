package ru.cs2a.helper.crash

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CrashState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _status = MutableStateFlow("Остановлено")
    val status: StateFlow<String> = _status
    fun set(running: Boolean, status: String) {
        _running.value = running
        _status.value = status
    }
}
