package com.aiyifan.app.feature.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupHttpFetcher
import com.aiyifan.app.core.startup.StartupModeResolver
import com.aiyifan.app.core.startup.StartupSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StartupViewModel : ViewModel() {
    private val resolvedMode = MutableStateFlow<AppMode?>(null)
    val mode = resolvedMode.asStateFlow()

    init {
        StartupSession.mode = null
        viewModelScope.launch {
            resolvedMode.value = StartupModeResolver(StartupHttpFetcher()).resolve()
        }
    }
}
