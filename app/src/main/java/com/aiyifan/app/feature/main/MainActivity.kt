package com.aiyifan.app.feature.main

import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.startup.StartupActivity

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.R
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.setupEdgeToEdge
import com.aiyifan.app.databinding.ActivityMainBinding
import com.aiyifan.app.feature.download.DownloadLibraryFragment
import com.aiyifan.app.feature.home.HomeFragment
import com.aiyifan.app.feature.shortdrama.ShortDramaFragment
import com.aiyifan.app.feature.mine.MineFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var downloadRecovery: Job? = null

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        downloadRecovery = lifecycleScope.launch {
            try { AppGraph.downloadRepository.recoverFromForeground() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                android.widget.Toast.makeText(this@MainActivity, R.string.download_operation_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStop() {
        downloadRecovery?.cancel()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (StartupSession.mode != AppMode.ONLINE) {
            super.onCreate(null)
            StartupActivity.restart(this)
            return
        }
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Mine's header handles its own top inset.
        ViewCompat.setOnApplyWindowInsetsListener(binding.fragmentContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(
                left = bars.left,
                top = if (binding.bottomTabs.selectedTabPosition == TAB_MINE) 0 else bars.top,
                right = bars.right,
            )
            insets
        }
        binding.bottomTabs.applySystemBarsPadding(left = true, right = true, bottom = true, growHeight = true)

        val selectedTabPosition = (savedInstanceState?.getInt(KEY_SELECTED_TAB) ?: 0).takeIf {
            it in 0 until binding.bottomTabs.tabCount
        } ?: 0
        for (index in 0 until binding.bottomTabs.tabCount) {
            val tab = binding.bottomTabs.getTabAt(index) ?: continue
            tab.customView = TextView(this).apply {
                text = tab.text
                gravity = Gravity.CENTER
                maxLines = 1
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        binding.bottomTabs.getTabAt(selectedTabPosition)?.select()
        updateTabTextSizes(selectedTabPosition)
        if (savedInstanceState == null) {
            show(fragmentFor(selectedTabPosition))
        }
        binding.bottomTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                updateTabTextSizes(tab.position)
                show(fragmentFor(tab.position))
                ViewCompat.requestApplyInsets(binding.fragmentContainer)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit

            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
    }

    private fun updateTabTextSizes(selectedPosition: Int) {
        for (index in 0 until binding.bottomTabs.tabCount) {
            val textView = binding.bottomTabs.getTabAt(index)?.customView as? TextView
            textView?.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(if (index == selectedPosition) R.dimen.sp_16 else R.dimen.sp_14),
            )
            textView?.setTextColor(getColor(if (index == selectedPosition) R.color.accent else R.color.text_secondary))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::binding.isInitialized) outState.putInt(KEY_SELECTED_TAB, binding.bottomTabs.selectedTabPosition)
        super.onSaveInstanceState(outState)
    }

    private fun fragmentFor(position: Int): Fragment {
        val type = when (position) {
            1 -> ShortDramaFragment::class.java
            TAB_DOWNLOADS -> DownloadLibraryFragment::class.java
            TAB_MINE -> MineFragment::class.java
            else -> HomeFragment::class.java
        }
        supportFragmentManager.findFragmentByTag(type.name)?.let { return it }
        return when (position) {
            1 -> ShortDramaFragment()
            TAB_DOWNLOADS -> DownloadLibraryFragment()
            TAB_MINE -> MineFragment()
            else -> HomeFragment.newInstance(intent.getStringExtra(EXTRA_CATEGORY_ID))
        }
    }

    private fun show(fragment: Fragment) {
        val transaction = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments.filter { it.id == R.id.fragmentContainer && it !== fragment }.forEach {
            transaction.hide(it).setMaxLifecycle(it, Lifecycle.State.STARTED)
        }
        if (fragment.isAdded) transaction.show(fragment)
        else transaction.add(R.id.fragmentContainer, fragment, fragment.javaClass.name)
        transaction.setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            .setPrimaryNavigationFragment(fragment)
            .commitNow()
    }

    fun selectHome() {
        binding.bottomTabs.getTabAt(0)?.select()
    }

    companion object {
        internal const val TAB_DOWNLOADS = 2
        internal const val TAB_MINE = 3
        const val KEY_SELECTED_TAB = "selected_tab"
        const val EXTRA_CATEGORY_ID = "categoryId"

        fun intent(context: android.content.Context, categoryId: String): android.content.Intent =
            android.content.Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_CATEGORY_ID, categoryId)
    }
}
