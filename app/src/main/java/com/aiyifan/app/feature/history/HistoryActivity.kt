package com.aiyifan.app.feature.history

import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.startup.StartupActivity

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.model.WatchHistory
import com.aiyifan.app.core.ui.showDestructiveConfirmation
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.setupEdgeToEdge
import com.aiyifan.app.databinding.ActivitySimpleListBinding
import com.aiyifan.app.feature.video.VideoPlayerActivity

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySimpleListBinding
    private lateinit var adapter: WatchHistoryAdapter
    private lateinit var selectionBar: LinearLayout
    private var selection: MutableSet<String>? = null
    private var removeListener: (() -> Unit)? = null
    private val repository get() = AppGraph.catalogRepository
    private val selectionBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { selection = null; render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (StartupSession.mode != AppMode.ONLINE) {
            super.onCreate(null)
            StartupActivity.restart(this)
            return
        }
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(left = true, right = true, bottom = true)
        binding.topBar.applySystemBarsPadding(top = true, growHeight = true)
        onBackPressedDispatcher.addCallback(this, selectionBack)
        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.actionButton.setOnClickListener {
            if (selection == null) confirmClear() else confirmDelete(selection.orEmpty().toSet())
        }
        adapter = WatchHistoryAdapter(
            onOpen = { startActivity(VideoPlayerActivity.intent(this, it.mediaKey)) },
            onSelect = ::toggleSelection,
            onDelete = { confirmDelete(setOf(it.mediaKey)) },
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.surface))
        }
        fun addAction(label: Int, action: () -> Unit) {
            selectionBar.addView(TextView(this).apply {
                setText(label)
                setTextColor(getColor(R.color.accent))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).toInt(), 1f)
                setOnClickListener { action() }
            })
        }
        addAction(R.string.history_select_all) { selection = repository.getHistory().map { it.mediaKey }.toMutableSet(); render() }
        addAction(R.string.history_cancel_selection) { selection = null; render() }
        (binding.topBar.parent as LinearLayout).addView(selectionBar, 1)
        savedInstanceState?.getStringArrayList("selection")?.let { selection = it.toMutableSet() }
        render()
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        removeListener = repository.addHistoryListener { runOnUiThread { if (!isDestroyed) render() } }
        render()
    }

    override fun onStop() {
        removeListener?.invoke()
        removeListener = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selection?.let { outState.putStringArrayList("selection", ArrayList(it)) }
        super.onSaveInstanceState(outState)
    }

    private fun toggleSelection(record: WatchHistory) {
        val selected = selection ?: mutableSetOf<String>().also { selection = it }
        if (!selected.add(record.mediaKey)) selected.remove(record.mediaKey)
        render()
    }

    private fun confirmDelete(keys: Set<String>) {
        if (keys.isEmpty()) return
        showDestructiveConfirmation(R.string.history_delete_title, getString(R.string.history_delete_confirmation, keys.size),
            R.string.history_delete) {
            selection = null
            repository.removeHistory(keys)
            render()
        }
    }

    private fun confirmClear() {
        showDestructiveConfirmation(R.string.history_clear_title, getString(R.string.history_clear_confirmation),
            R.string.history_clear) {
            repository.clearHistory()
            render()
        }
    }

    private fun render() {
        val items = repository.getHistory()
        selection?.retainAll(items.map { it.mediaKey }.toSet())
        if (items.isEmpty()) selection = null
        adapter.submitList(items)
        adapter.showSelection(selection)
        selectionBar.isVisible = selection != null
        selectionBack.isEnabled = selection != null
        binding.title.text = selection?.let { getString(R.string.history_selected_count, it.size) }
            ?: getString(R.string.hardcoded_text_039)
        binding.actionButton.setText(if (selection == null) R.string.history_clear else R.string.history_delete)
        binding.actionButton.isEnabled = if (selection == null) items.isNotEmpty() else selection!!.isNotEmpty()
        binding.actionButton.alpha = if (binding.actionButton.isEnabled) 1f else 0.4f
        binding.emptyState.isVisible = items.isEmpty()
        binding.emptyView.setText(R.string.history_empty)
    }
}
