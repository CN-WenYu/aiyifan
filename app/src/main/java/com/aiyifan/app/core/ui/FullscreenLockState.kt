package com.aiyifan.app.core.ui

class FullscreenLockState {
    enum class Value {
        UNLOCKED,
        LOCKED,
        UNLOCK_PROMPT,
    }

    var value: Value = Value.UNLOCKED
        private set

    fun lock() {
        if (value == Value.UNLOCKED) value = Value.LOCKED
    }

    fun revealUnlockPrompt() {
        if (value == Value.LOCKED) value = Value.UNLOCK_PROMPT
    }

    fun hideUnlockPrompt() {
        if (value == Value.UNLOCK_PROMPT) value = Value.LOCKED
    }

    fun unlock() {
        value = Value.UNLOCKED
    }

    fun reset() {
        value = Value.UNLOCKED
    }

    companion object {
        const val UNLOCK_PROMPT_TIMEOUT_MS = 3_000L
    }
}
