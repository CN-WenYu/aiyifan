package com.aiyifan.app.feature.localmedia

import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.startup.StartupActivity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import com.aiyifan.app.core.ui.setupEdgeToEdge
import com.aiyifan.app.databinding.ActivityLocalMediaBinding

class LocalMediaActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (StartupSession.mode != AppMode.LOCAL) {
            super.onCreate(null)
            StartupActivity.restart(this)
            return
        }
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        val binding = ActivityLocalMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAndRemoveTask()
        })
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.localMediaContainer.id, LocalMediaFragment())
                .commit()
        }
    }
}
