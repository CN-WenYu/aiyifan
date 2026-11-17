package com.aiyifan.app.feature.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.aiyifan.app.R
import com.aiyifan.app.databinding.ItemHomeStatusBinding

enum class HomeStatus { CONTENT, LOADING, OFFLINE, EMPTY, ERROR }

class HomeStatusAdapter(private val onRetry: () -> Unit) : RecyclerView.Adapter<HomeStatusAdapter.Holder>() {
    var status: HomeStatus = HomeStatus.LOADING
        private set

    fun submitStatus(status: HomeStatus) {
        if (this.status == status) return
        this.status = status
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (status == HomeStatus.CONTENT) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemHomeStatusBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(status, onRetry)

    class Holder(private val binding: ItemHomeStatusBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(status: HomeStatus, onRetry: () -> Unit) {
            binding.networkIcon.isVisible = status == HomeStatus.OFFLINE
            binding.loadingIndicator.isVisible = status == HomeStatus.LOADING
            binding.statusMessage.setText(when (status) {
                HomeStatus.OFFLINE -> R.string.home_status_offline
                HomeStatus.LOADING -> R.string.home_status_loading
                HomeStatus.EMPTY -> R.string.home_status_empty
                else -> R.string.home_status_error
            })
            binding.retryButton.isVisible = status == HomeStatus.OFFLINE || status == HomeStatus.ERROR
            binding.retryButton.setOnClickListener { onRetry() }
            binding.statusMessage.setOnClickListener(if (status == HomeStatus.ERROR) android.view.View.OnClickListener { onRetry() } else null)
            binding.statusMessage.isClickable = status == HomeStatus.ERROR
        }
    }
}
