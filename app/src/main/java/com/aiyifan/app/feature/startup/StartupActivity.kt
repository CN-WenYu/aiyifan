package com.aiyifan.app.feature.startup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.setupEdgeToEdge
import com.aiyifan.app.databinding.ActivityStartupBinding
import com.aiyifan.app.feature.localmedia.LocalMediaActivity
import com.aiyifan.app.feature.main.MainActivity
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class StartupActivity : AppCompatActivity() {
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        val binding = ActivityStartupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(left = true, top = true, right = true, bottom = true)
        val model = ViewModelProvider(this)[StartupViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.mode.filterNotNull().collect { mode ->
                    if (!navigated) {
                        navigated = true
                        StartupSession.mode = mode
                        val destination = if (mode == AppMode.ONLINE) MainActivity::class.java else LocalMediaActivity::class.java
                        startActivity(Intent(this@StartupActivity, destination)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        finish()
                    }
                }
            }
        }
    }

    companion object {
        fun restart(activity: Activity) {
            activity.startActivity(Intent(activity, StartupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity.finish()
        }
    }
}
