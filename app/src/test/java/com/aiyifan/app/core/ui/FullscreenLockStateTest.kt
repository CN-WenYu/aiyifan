package com.aiyifan.app.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FullscreenLockStateTest {

    @Test
    fun `lock tap and unlock move through the unlock prompt state`() {
        val state = FullscreenLockState()

        state.lock()
        assertEquals(FullscreenLockState.Value.LOCKED, state.value)

        state.revealUnlockPrompt()
        assertEquals(FullscreenLockState.Value.UNLOCK_PROMPT, state.value)

        state.unlock()
        assertEquals(FullscreenLockState.Value.UNLOCKED, state.value)
    }

    @Test
    fun `repeated actions and reset are safe`() {
        val state = FullscreenLockState()

        state.unlock()
        state.lock()
        state.lock()
        assertEquals(FullscreenLockState.Value.LOCKED, state.value)

        state.revealUnlockPrompt()
        state.revealUnlockPrompt()
        assertEquals(FullscreenLockState.Value.UNLOCK_PROMPT, state.value)

        state.reset()
        state.reset()
        assertEquals(FullscreenLockState.Value.UNLOCKED, state.value)
    }
}
