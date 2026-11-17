package com.aiyifan.app.feature.hot

import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.startup.StartupActivity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.setupEdgeToEdge
import com.aiyifan.app.databinding.ActivityHotBinding

class HotActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHotBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        if (StartupSession.mode != AppMode.ONLINE) {
            super.onCreate(null)
            StartupActivity.restart(this)
            return
        }
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityHotBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(left = true, top = true, right = true, bottom = true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.hotFragmentContainer.id, HotFragment())
                .commit()
        }
    }
}