package com.aiyifan.app.feature.mine

/** A slide joins playback history when it is shown. Looking ahead keeps the same candidate. */
internal class HeaderSlideshow(
    private val count: Int,
    initialIndex: Int? = null,
    initialPaused: Boolean = false,
    initialPool: List<Int> = (0 until count).toList(),
    restored: Snapshot? = null,
    private val shuffle: (List<Int>) -> List<Int> = { it.shuffled() },
) {
    data class Snapshot(
        val pool: List<Int>,
        val index: Int,
        val history: List<Int>,
        val cursor: Int,
        val remaining: List<Int>,
        val failures: List<Int>,
    )

    private val saved = restored?.takeIf { it.index in 0 until count && it.cursor in -1..it.history.lastIndex }
    private var pool = saved?.pool ?: initialPool.toList()
    private val failures = saved?.failures?.toMutableSet() ?: mutableSetOf()
    private var remaining = saved?.remaining.orEmpty()
    private val history = saved?.history?.toMutableList() ?: mutableListOf()
    private var cursor = saved?.cursor ?: -1

    var index = saved?.index ?: startRound(initialIndex)
        private set
    var paused = initialPaused
        private set
    var pendingIndex: Int? = null
        private set
    var transitioning = false
        private set
    val exhausted get() = pool.none { it !in failures }
    val nextCandidate get() = candidate(1)
    val advanceAt get() = deadline.takeUnless { it == Long.MAX_VALUE }
    val hasDisplayedCurrent get() = history.getOrNull(cursor) == index

    private var active = false
    private var interacting = false
    private var pendingDirection = 1
    private var ready = false
    private var deadline = Long.MAX_VALUE

    init {
        updatePool(initialPool, keepCurrent = hasDisplayedCurrent)
    }

    fun snapshot() = Snapshot(pool, index, history.toList(), cursor, remaining.toList(), failures.toList())

    fun previewOrder(): List<Int> {
        val available = pool.filterNot(failures::contains).toSet()
        val forward = (history.drop(cursor + 1) + remaining)
            .filter { it != index && it in available }
            .distinct()
        val previous = history.take(cursor.coerceAtLeast(0)).asReversed()
            .filter { it != index && it in available }
            .distinct().asReversed()
        val playbackOrder = previous + index + forward
        val listed = playbackOrder.toSet()
        return playbackOrder + available.filterNot(listed::contains)
    }

    fun updatePool(images: List<Int>, keepCurrent: Boolean = ready) {
        if (pool == images) {
            if (!keepCurrent && index !in pool) index = startRound(null)
            return
        }
        pool = images.toList()
        failures.clear()
        cancelPending()
        val queued = history.drop(cursor + 1).toSet()
        remaining = shuffle(pool.filter { it != index && it !in queued })
        if (!keepCurrent && index !in pool) index = startRound(null)
    }

    fun retryFailedSlides() {
        failures.clear()
    }

    fun activate(now: Long) {
        active = true
        resetDeadline(now)
    }

    fun deactivate() {
        active = false
        interacting = false
        cancelPending()
        deadline = Long.MAX_VALUE
    }

    fun togglePause(now: Long) {
        completeTransition(now)
        paused = !paused
        resetDeadline(now)
    }

    fun loaded(now: Long) {
        if (pendingIndex != null) {
            transitioning = true
        } else {
            recordCurrent()
            ready = true
            resetDeadline(now)
        }
    }

    fun tick(now: Long): Boolean {
        if (!active || interacting || paused || !ready || pendingIndex != null || exhausted || now < deadline) return false
        return requestManual(1)
    }

    fun beginInteraction() {
        interacting = true
        cancelPending()
        deadline = Long.MAX_VALUE
    }

    fun endInteraction(now: Long) {
        interacting = false
        resetDeadline(now)
    }

    fun candidate(direction: Int): Int? {
        require(direction == -1 || direction == 1)
        historyPosition(direction)?.let { return history[it] }
        if (direction < 0) return null
        remaining.firstOrNull { it != index && it !in failures }?.let { return it }
        val available = pool.filter { it !in failures }
        if (available.none { it != index }) return null
        failures.clear()
        remaining = shuffle(pool)
        if (remaining.first() == index) {
            remaining = listOf(remaining[1], remaining[0]) + remaining.drop(2)
        }
        return remaining.first()
    }

    fun requestManual(direction: Int): Boolean {
        if (!active || !ready || exhausted || transitioning) return false
        pendingDirection = direction
        pendingIndex = candidate(direction)
        deadline = Long.MAX_VALUE
        return pendingIndex != null
    }

    fun completeTransition(now: Long) {
        if (!transitioning) return
        val next = pendingIndex ?: return
        val position = historyPosition(pendingDirection)
        if (position != null && history[position] == next) {
            cursor = position
            index = next
        } else {
            remaining = remaining.filterNot { it == next }
            index = next
            recordCurrent()
        }
        cancelPending()
        ready = true
        resetDeadline(now)
    }

    fun cancelPending() {
        pendingIndex = null
        transitioning = false
    }

    fun failed(): Boolean {
        pendingIndex?.let { return rejectNext(it, pendingDirection) != null }
        failures.add(index)
        ready = false
        val next = candidate(1)
        if (next == null) {
            deadline = Long.MAX_VALUE
            return false
        }
        val position = historyPosition(1)
        if (position != null) cursor = position
        index = next
        remaining = remaining.filterNot { it == index }
        return true
    }

    fun rejectNext(candidate: Int, direction: Int = 1): Int? {
        failures.add(candidate)
        val replacement = candidate(direction)
        if (pendingIndex == candidate) pendingIndex = replacement
        return replacement
    }

    private fun historyPosition(direction: Int): Int? {
        val positions = if (direction > 0) cursor + 1 until history.size else cursor - 1 downTo 0
        return positions.firstOrNull { history[it] != index && history[it] !in failures }
    }

    private fun recordCurrent() {
        if (history.getOrNull(cursor) == index) return
        history.subList(cursor + 1, history.size).clear()
        history.add(index)
        if (history.size > count) history.removeAt(0)
        cursor = history.lastIndex
    }

    private fun startRound(preferred: Int?): Int {
        remaining = shuffle(pool)
        val first = preferred?.takeIf { it in pool } ?: remaining.firstOrNull() ?: 0
        remaining = remaining.filterNot { it == first }
        return first
    }

    private fun resetDeadline(now: Long) {
        deadline = if (active && !interacting && ready && !paused && !exhausted && pendingIndex == null) {
            now + INTERVAL_MS
        } else Long.MAX_VALUE
    }

    companion object {
        const val INTERVAL_MS = 5_000L
        const val TRANSITION_MS = 450L
    }
}
