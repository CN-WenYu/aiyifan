package com.aiyifan.app.feature.anime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aiyifan.app.BuildConfig
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.data.anime.AnimeExploreSubject
import com.aiyifan.app.core.data.anime.AnimePage
import com.aiyifan.app.core.data.anime.AnimeScheduleDay
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.setupEdgeToEdge
import com.aiyifan.app.databinding.ActivityAnimeBrowseBinding
import com.aiyifan.app.databinding.ItemAnimeBrowseRowBinding
import com.aiyifan.app.feature.video.VideoPlayerActivity
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class AnimeBrowseActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAnimeBrowseBinding
    private val model by lazy { ViewModelProvider(this)[AnimeBrowseViewModel::class.java] }
    private val schedule by lazy { intent.getBooleanExtra(EXTRA_SCHEDULE, false) }
    private var selectedDate: String? = null
    private var scheduleDays = emptyList<AnimeScheduleDay>()
    private var positionInitialRanking = false
    private val rows = AnimeBrowseAdapter { mediaKey ->
        startActivity(VideoPlayerActivity.intent(this, mediaKey))
    }
    private val footer = AnimeLoadAdapter(::loadCurrentPage)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.ANIME_ENABLED) {
            finish()
            return
        }
        setupEdgeToEdge()
        binding = ActivityAnimeBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(left = true, top = true, right = true, bottom = true)
        binding.animeBrowseTitle.setText(if (schedule) R.string.anime_airing_schedule else R.string.anime_ranking)
        binding.animeBrowseBack.setOnClickListener { finish() }
        selectedDate = savedInstanceState?.getString(KEY_DATE)
        positionInitialRanking = !schedule && savedInstanceState == null

        if (!schedule) rows.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        val listAdapter = ConcatAdapter(rows, footer)
        binding.animeBrowseList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
            itemAnimator = null
            if (!schedule) appendOnScroll(
                itemCount = { listAdapter.itemCount },
                hasMore = { model.ranking.value.canAppend() },
                load = model::loadRanking,
            )
        }

        if (schedule) {
            binding.animeScheduleDays.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    selectedDate = tab.tag as? String
                    renderSelectedDay()
                    binding.animeBrowseList.scrollToPosition(0)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
            model.loadScheduleIfNeeded()
        } else {
            model.loadRankingIfNeeded()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (schedule) model.schedule.collect(::renderSchedule)
                else model.ranking.collect(::renderRanking)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_DATE, selectedDate)
        super.onSaveInstanceState(outState)
    }

    private fun renderRanking(state: AnimePageState<AnimeExploreSubject>) {
        val items = state.items.map { subject ->
            val rank = subject.rank?.let { getString(R.string.anime_rank_number, it) }
            AnimeBrowseRow(
                key = subject.mediaKey,
                subject = subject,
                metadata = listOfNotNull(rank, subject.metadata).joinToString(" · "),
            )
        }
        val notice = state.notice(canAppend = true)
        showStatus(notice.takeIf { state.items.isEmpty() })
        rows.submitList(items) {
            footer.show(notice.takeIf { items.isNotEmpty() })
            if (positionInitialRanking && items.isNotEmpty()) {
                positionInitialRanking = false
                (binding.animeBrowseList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
            }
        }
    }

    private fun renderSchedule(state: AnimePageState<AnimeScheduleDay>) {
        scheduleDays = state.items
        val tabs = binding.animeScheduleDays
        tabs.isVisible = scheduleDays.isNotEmpty()
        if (scheduleDays.isEmpty()) {
            rows.submitList(emptyList())
            showStatus(state.notice(empty = R.string.anime_airing_empty))
            return
        }

        selectedDate = selectedDate?.takeIf { chosen -> scheduleDays.any { it.date == chosen } } ?: model.today
        val initializeTabs = tabs.tabCount == 0
        if (initializeTabs) {
            scheduleDays.forEach { day ->
                val label = displayDate(day.date)
                tabs.addTab(
                    tabs.newTab().setTag(day.date).setText(
                        if (day.date == model.today) getString(R.string.anime_airing_today, label) else label,
                    ),
                    false,
                )
            }
        }
        val selected = scheduleDays.indexOfFirst { it.date == selectedDate }.coerceAtLeast(0)
        val tab = tabs.getTabAt(selected)
        if (tab?.isSelected == false) tab.select() else renderSelectedDay()
        if (initializeTabs) {
            tabs.doOnNextLayout {
                val position = tabs.selectedTabPosition
                if (position >= 0) tabs.setScrollPosition(position, 0f, true)
            }
        }
    }

    private fun renderSelectedDay() {
        val day = scheduleDays.firstOrNull { it.date == selectedDate }
        val items = day?.items.orEmpty().map { row ->
            val time = row.airingTime?.let(::displayTime)
            val number = row.episodeNumber?.let { getString(R.string.anime_airing_episode, it) }
            AnimeBrowseRow(
                key = row.episodeId.toString(),
                subject = row.subject,
                metadata = listOfNotNull(time, number, row.episodeTitle).joinToString(" · "),
            )
        }
        rows.submitList(items)
        showStatus(if (items.isEmpty()) AnimeNotice(R.string.anime_airing_empty) else null)
    }

    private fun showStatus(notice: AnimeNotice?) {
        val status = binding.animeBrowseStatus.root
        status.isVisible = notice != null
        if (notice != null) status.renderNotice(notice, ::loadCurrentPage)
    }

    private fun loadCurrentPage() {
        if (schedule) model.loadSchedule() else model.loadRanking()
    }

    companion object {
        private const val EXTRA_SCHEDULE = "anime_browse_schedule"
        private const val KEY_DATE = "anime_schedule_date"

        fun intent(context: Context, schedule: Boolean) =
            Intent(context, AnimeBrowseActivity::class.java).putExtra(EXTRA_SCHEDULE, schedule)
    }
}

internal class AnimeBrowseViewModel : ViewModel() {
    private val repository = AppGraph.animeExplore
    private val timeZone = TimeZone.getDefault()
    val today: String = isoDayFormat().apply { this.timeZone = this@AnimeBrowseViewModel.timeZone }.format(Date())
    private val rankingLoader = AnimePageLoader(AnimeExploreSubject::mediaKey, repository::ranking)
    private val scheduleLoader = AnimePageLoader(AnimeScheduleDay::date) {
        val days = repository.schedule(today, timeZone.id)
        val withToday = if (days.isNotEmpty() && days.none { it.date == today }) {
            (days + AnimeScheduleDay(today, emptyList())).sortedBy(AnimeScheduleDay::date)
        } else {
            days
        }
        AnimePage(withToday, null)
    }
    val ranking = rankingLoader.state
    val schedule = scheduleLoader.state

    fun loadScheduleIfNeeded() {
        if (schedule.value.isInitial()) loadSchedule()
    }

    fun loadRankingIfNeeded() {
        if (ranking.value.isInitial()) loadRanking()
    }

    fun loadSchedule() {
        viewModelScope.launch { scheduleLoader.loadNext() }
    }

    fun loadRanking() {
        viewModelScope.launch { rankingLoader.loadNext() }
    }

    private fun AnimePageState<*>.isInitial() = nextOffset == 0 && status == AnimeLoadStatus.IDLE
}

private data class AnimeBrowseRow(val key: String, val subject: AnimeExploreSubject, val metadata: String)

private class AnimeBrowseAdapter(
    private val onOpen: (String) -> Unit,
) : ListAdapter<AnimeBrowseRow, AnimeBrowseAdapter.Holder>(byKey(AnimeBrowseRow::key) { old, new -> old == new }) {
    class Holder(val binding: ItemAnimeBrowseRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAnimeBrowseRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        with(holder.binding) {
            animeBrowseRowTitle.text = row.subject.title
            animeBrowseRowMeta.text = row.metadata
            animeBrowseRowMeta.isVisible = row.metadata.isNotEmpty()
            animeBrowsePoster.loadAnimePoster(row.subject.coverUrl)
            root.setOnClickListener { onOpen(row.subject.mediaKey) }
        }
    }
}

private fun isoDayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }

private fun displayDate(day: String): String = try {
    isoDayFormat().parse(day)?.let { SimpleDateFormat("EEE M/d", Locale.getDefault()).format(it) } ?: day
} catch (_: ParseException) {
    day
}

private fun displayTime(timestamp: String): String? = try {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        .parse(timestamp.replace(FRACTIONAL_SECONDS, ""))
        ?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(it) }
} catch (_: ParseException) {
    null
}

private val FRACTIONAL_SECONDS = Regex("""\.\d+(?=Z|[+-])""")
