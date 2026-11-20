package com.aiyifan.app.feature.download

import android.content.Context
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.ui.createOptionsSheet

internal fun Context.showDownloadCountDialog(onSelected: () -> Unit) {
    val repository = AppGraph.downloadRepository
    val counts = 1..3
    createOptionsSheet(
        getString(R.string.download_parallel_title),
        counts.map { getString(R.string.download_parallel_option, it) },
        selectedIndex = counts.indexOf(repository.parallelDownloads),
    ) { index ->
        repository.setParallelDownloads(counts.first + index)
        onSelected()
    }.show()
}
