package com.aiyifan.app.feature.anime

import com.aiyifan.app.core.data.anime.AnimePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import java.io.IOException

internal enum class AnimeLoadStatus { IDLE, LOADING, FAILED }

internal data class AnimePageState<T>(
    val items: List<T> = emptyList(),
    val nextOffset: Int? = 0,
    val status: AnimeLoadStatus = AnimeLoadStatus.IDLE,
)

/** Called by a single UI scope. Keeps the current offset intact on failure. */
internal class AnimePageLoader<T>(
    private val key: (T) -> String,
    private val request: suspend (Int) -> AnimePage<T>,
) {
    private val mutableState = MutableStateFlow(AnimePageState<T>())
    val state = mutableState.asStateFlow()

    suspend fun loadNext() {
        val previous = state.value
        val offset = previous.nextOffset ?: return
        if (previous.status == AnimeLoadStatus.LOADING) return
        mutableState.value = previous.copy(status = AnimeLoadStatus.LOADING)
        try {
            val page = request(offset)
            currentCoroutineContext().ensureActive()
            mutableState.value = AnimePageState(
                items = (previous.items + page.items).distinctBy(key),
                nextOffset = page.nextOffset?.takeIf { it > offset },
            )
        } catch (error: CancellationException) {
            mutableState.value = previous
            throw error
        } catch (_: IOException) {
            mutableState.value = previous.copy(status = AnimeLoadStatus.FAILED)
        } catch (_: JSONException) {
            mutableState.value = previous.copy(status = AnimeLoadStatus.FAILED)
        }
    }
}
