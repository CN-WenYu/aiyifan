package com.aiyifan.app.feature.mine

import org.junit.Assert.*
import org.junit.Test

class HeaderRandomPlaybackTest {
    @Test fun `two rounds are independently shuffled without duplicates at the boundary`() {
        var shuffles = 0
        val state = HeaderSlideshow(4, shuffle = {
            if (shuffles++ == 0) it.reversed() else it
        })
        state.activate(0)
        state.loaded(0)
        val first = listOf(state.index) + List(3) { move(state) }
        val second = List(4) { move(state) }
        assertEquals(listOf(3, 2, 1, 0), first)
        assertEquals(listOf(1, 0, 2, 3), second)
        assertNotEquals(first.last(), second.first())
    }

    @Test fun `right swipe and forward replay follow displayed history without consuming the planned next slide`() {
        val state = HeaderSlideshow(4, shuffle = { it.reversed() })
        state.activate(0)
        state.loaded(0)
        assertNull(state.candidate(-1))
        assertEquals(2, move(state))
        assertEquals(1, state.nextCandidate)
        assertEquals(3, move(state, -1))
        assertEquals(2, move(state))
        assertEquals(1, state.nextCandidate)
    }

    @Test fun `prefetch and cancelled swipe preserve the next shuffled slide across restoration`() {
        val state = HeaderSlideshow(4, initialPaused = true, shuffle = { it.reversed() })
        state.activate(0)
        state.loaded(0)
        val next = state.nextCandidate
        assertTrue(state.requestManual(1))
        state.cancelPending()
        assertEquals(next, state.nextCandidate)
        val restored = HeaderSlideshow(4, initialPaused = state.paused, restored = state.snapshot(),
            shuffle = { it })
        assertTrue(restored.paused)
        assertEquals(state.index, restored.index)
        assertEquals(next, restored.nextCandidate)
    }

    @Test fun `failed slide is skipped and switching offline keeps the displayed image until a replacement commits`() {
        val state = HeaderSlideshow(4, shuffle = { it.reversed() })
        state.activate(0)
        state.loaded(0)
        assertTrue(state.requestManual(1))
        assertEquals(1, state.rejectNext(2))
        assertEquals(3, state.index)
        state.updatePool(listOf(0, 1))
        assertEquals(3, state.index)
        assertEquals(1, move(state))
        assertEquals(0, move(state))
        assertEquals(1, move(state))
    }

    @Test fun `restoring before the first load retains the already shuffled round`() {
        val original = HeaderSlideshow(4, shuffle = { it.reversed() })
        val restored = HeaderSlideshow(4, restored = original.snapshot(), shuffle = { it })
        assertEquals(original.index, restored.index)
        assertEquals(original.nextCandidate, restored.nextCandidate)
    }

    @Test fun `restoring during a network loss retains the already displayed remote slide`() {
        val state = HeaderSlideshow(4, shuffle = { it.reversed() })
        state.activate(0)
        state.loaded(0)
        val beforeLoss = state.snapshot()
        state.updatePool(listOf(0, 1))
        val duringLoss = state.snapshot()
        val snapshots = listOf(beforeLoss, duringLoss)
        for (snapshot in snapshots) {
            val restored = HeaderSlideshow(4, initialPool = listOf(0, 1), restored = snapshot, shuffle = { it })
            assertEquals(3, restored.index)
            assertEquals(setOf(0, 1), setOf(restored.nextCandidate, restored.snapshot().remaining.last()))
        }
    }

    @Test fun `offline connection and two consecutive remote failures have distinct retry conditions`() {
        val availability = HeaderImageAvailability(online = false)
        assertFalse(availability.useRemote)
        availability.beforeAdvance(100_000)
        assertFalse(availability.useRemote)
        availability.setOnline(true)
        assertTrue(availability.useRemote)
        availability.remoteFailed(100_000)
        availability.remoteLoaded()
        availability.remoteFailed(101_000)
        assertTrue(availability.useRemote)
        availability.remoteFailed(102_000)
        assertFalse(availability.useRemote)
        availability.beforeAdvance(161_999)
        assertFalse(availability.useRemote)
        availability.beforeAdvance(162_000)
        assertTrue(availability.useRemote)
        availability.remoteFailed(163_000)
        availability.remoteFailed(164_000)
        availability.setOnline(false)
        availability.setOnline(true)
        assertTrue(availability.useRemote)
    }

    private fun move(state: HeaderSlideshow, direction: Int = 1): Int {
        assertTrue(state.requestManual(direction))
        state.loaded(0)
        state.completeTransition(450)
        return state.index
    }
}
