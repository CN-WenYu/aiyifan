package com.aiyifan.app.feature.mine

import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderPreviewOrderTest {
    @Test fun `preview starts at the current slide and follows history then shuffled candidates`() {
        val state = HeaderSlideshow(5, shuffle = { it.reversed() })
        state.activate(0)
        state.loaded(0)
        repeat(2) {
            state.requestManual(1)
            state.loaded(0)
            state.completeTransition(450)
        }
        val before = state.snapshot()
        assertEquals(listOf(4, 3, 2, 1, 0), state.previewOrder())
        assertEquals(2, state.previewOrder().indexOf(state.index))
        assertEquals(before, state.snapshot())
    }

    @Test fun `next round prefetch keeps the actual previous and next slides around the current page`() {
        val state = HeaderSlideshow(3, shuffle = { it })
        state.activate(0)
        state.loaded(0)
        repeat(2) {
            state.requestManual(1)
            state.loaded(0)
            state.completeTransition(450)
        }
        assertEquals(0, state.nextCandidate)
        assertEquals(listOf(0, 1, 2, 0, 1), state.previewOrder())
    }

    @Test fun `offline preview includes local choices and the cached current slide`() {
        val state = HeaderSlideshow(5, shuffle = { it.reversed() })
        state.activate(0)
        state.loaded(0)
        state.updatePool(listOf(0, 2, 3))
        assertEquals(listOf(4, 3, 2, 0), state.previewOrder())
    }
}
