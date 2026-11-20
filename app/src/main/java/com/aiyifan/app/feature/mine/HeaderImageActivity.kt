package com.aiyifan.app.feature.mine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.aiyifan.app.R
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.databinding.ActivityHeaderImageBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import java.io.File

internal class HeaderImageActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHeaderImageBinding
    private lateinit var images: List<ImagePage>
    private lateinit var requests: RequestManager
    private var cacheOnly = false
    private var started = false
    private var selectedPosition = 0
    private var browseDirection = 1
    private var preloadName: String? = null
    private var preloadTarget: Target<File>? = null
    private val pageStates = mutableMapOf<ImagePage, PageState>()
    private var maskEnabled = true
    private var pendingPermissionImage: String? = null
    private val saveModel by lazy { ViewModelProvider(this)[HeaderImageSaveViewModel::class.java] }

    private val pages = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (position != selectedPosition) {
                browseDirection = if (position > selectedPosition) 1 else -1
                selectedPosition = position
                if (preloadName != nextPreloadName()) clearPreload()
            }
            updateControls()
            updatePreload()
        }

        override fun onPageScrollStateChanged(state: Int) {
            updateControls()
            updatePreload()
        }
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val name = pendingPermissionImage
        pendingPermissionImage = null
        if (name != null) {
            if (granted) saveModel.save(this, name)
            else Toast.makeText(this, R.string.mine_image_save_permission, Toast.LENGTH_LONG).show()
        }
        updateControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val names = intent.getStringArrayListExtra(EXTRA_IMAGES).orEmpty()
        if (names.isEmpty() || names.any { it !in HeaderImageSource.allNames }) {
            finish()
            return
        }
        images = names.mapIndexed { index, name -> ImagePage(index.toLong(), name) }
        requests = Glide.with(this)
        cacheOnly = intent.getBooleanExtra(EXTRA_CACHE_ONLY, false)
        configureSystemBars()
        maskEnabled = savedInstanceState?.getBoolean(STATE_MASK, true) ?: true
        pendingPermissionImage = savedInstanceState?.getString(STATE_PERMISSION_IMAGE)
        binding = ActivityHeaderImageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.headerImageControls.applySystemBarsPadding(left = true, top = true, right = true)
        binding.headerImageClose.setOnClickListener { finish() }
        binding.headerImageDownload.setOnClickListener { saveSelectedImage() }
        updateMask()

        selectedPosition = (savedInstanceState?.getInt(STATE_POSITION)
            ?: intent.getIntExtra(EXTRA_POSITION, 0)).coerceIn(images.indices)
        browseDirection = savedInstanceState?.getInt(STATE_DIRECTION, 1) ?: 1
        binding.headerImagePager.apply {
            offscreenPageLimit = 1
            adapter = ImagePages(
                images, requests, cacheOnly,
                isSelected = { it == images.getOrNull(currentItem) },
                onImageTap = {
                    maskEnabled = !maskEnabled
                    updateMask()
                },
                onState = { page, state ->
                    if (state == null) pageStates.remove(page) else pageStates[page] = state
                    if (page == images.getOrNull(currentItem)) {
                        updateControls()
                        updatePreload()
                    }
                },
            )
            registerOnPageChangeCallback(pages)
            setCurrentItem(selectedPosition, false)
        }
        updateControls()
        saveModel.state.observe(this) { state ->
            updateControls()
            val message = when (state) {
                HeaderImageSaveViewModel.State.SAVED -> R.string.mine_image_saved
                HeaderImageSaveViewModel.State.FAILED -> R.string.mine_image_save_failed
                else -> null
            }
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                saveModel.consumeResult()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        updatePreload()
    }

    override fun onStop() {
        started = false
        clearPreload()
        super.onStop()
    }

    private fun nextPreloadName(): String? {
        if (cacheOnly) return null
        val position = selectedPosition + 2 * browseDirection
        val page = images.getOrNull(position) ?: return null
        if (HeaderImageSource.isLocal(page.name)) return null
        // The same image can appear twice where two shuffled rounds meet.
        return page.name.takeUnless { name ->
            (-1..1).any { offset -> images.getOrNull(selectedPosition + offset)?.name == name }
        }
    }

    private fun updatePreload() {
        if (!started || !::binding.isInitialized) return
        val pager = binding.headerImagePager
        if (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE ||
            pager.currentItem != selectedPosition || pageStates[images[selectedPosition]] == null
        ) return
        val name = nextPreloadName()
        if (name == preloadName) return
        clearPreload()
        if (name != null) {
            preloadName = name
            // Warm Glide's shared original-data cache without decoding another page.
            preloadTarget = requests.downloadOnly()
                .load(HeaderImageSource.model(name))
                .timeout(HeaderImageSource.TIMEOUT_MS)
                .preload()
        }
    }

    private fun clearPreload() {
        preloadTarget?.let(requests::clear)
        preloadTarget = null
        preloadName = null
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun updateMask() {
        binding.headerImageMask.visibility = if (maskEnabled) View.VISIBLE else View.GONE
    }

    private fun selectedImageForSave(): String? {
        val pager = binding.headerImagePager
        val page = images.getOrNull(pager.currentItem) ?: return null
        if (pageStates[page] != PageState.READY || pager.scrollState != ViewPager2.SCROLL_STATE_IDLE ||
            saveModel.state.value == HeaderImageSaveViewModel.State.SAVING || pendingPermissionImage != null
        ) return null
        return page.name
    }

    private fun updateControls() {
        if (!::binding.isInitialized) return
        val pager = binding.headerImagePager
        val enabled = selectedImageForSave() != null
        binding.headerImageDownload.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            contentDescription = getString(
                if (saveModel.state.value == HeaderImageSaveViewModel.State.SAVING) R.string.mine_image_saving
                else R.string.mine_save_image,
            )
        }
        val selected = images.getOrNull(pager.currentItem)
        binding.headerImageError.visibility =
            if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE && pageStates[selected] == PageState.FAILED) {
                View.VISIBLE
            } else View.GONE
    }

    private fun saveSelectedImage() {
        val name = selectedImageForSave() ?: return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermissionImage = name
            updateControls()
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveModel.save(this, name)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::binding.isInitialized) {
            outState.putInt(STATE_POSITION, binding.headerImagePager.currentItem)
            outState.putInt(STATE_DIRECTION, browseDirection)
            outState.putBoolean(STATE_MASK, maskEnabled)
            outState.putString(STATE_PERMISSION_IMAGE, pendingPermissionImage)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            clearPreload()
            binding.headerImagePager.unregisterOnPageChangeCallback(pages)
            binding.headerImagePager.adapter = null
        }
        super.onDestroy()
    }

    private enum class PageState { READY, FAILED }

    private data class ImagePage(val id: Long, val name: String)

    private class ImagePages(
        private val images: List<ImagePage>,
        private val requests: RequestManager,
        private val cacheOnly: Boolean,
        private val isSelected: (ImagePage) -> Boolean,
        private val onImageTap: () -> Unit,
        private val onState: (ImagePage, PageState?) -> Unit,
    ) : RecyclerView.Adapter<ImagePages.Holder>() {
        init { setHasStableIds(true) }

        override fun getItemCount(): Int = images.size

        override fun getItemId(position: Int): Long = images[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ZoomImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                tag = "header_zoom_image"
                contentDescription = context.getString(R.string.mine_view_image)
                setOnClickListener { onImageTap() }
            }
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            clear(holder)
            val image = holder.image
            val page = images[position]
            val name = page.name
            val isLocal = HeaderImageSource.isLocal(name)
            val target = object : CustomViewTarget<ZoomImageView, Drawable>(image) {
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    image.setImageDrawable(null)
                    onState(page, PageState.FAILED)
                }

                override fun onResourceCleared(placeholder: Drawable?) {
                    image.setImageDrawable(null)
                    onState(page, null)
                }

                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    image.setImageDrawable(resource)
                    onState(page, PageState.READY)
                }
            }
            holder.target = target
            requests.load(HeaderImageSource.model(name))
                .priority(if (isSelected(page)) Priority.HIGH else Priority.NORMAL)
                .fitCenter().dontAnimate().skipMemoryCache(true)
                .timeout(HeaderImageSource.TIMEOUT_MS)
                .onlyRetrieveFromCache(cacheOnly && !isLocal)
                .diskCacheStrategy(if (isLocal) DiskCacheStrategy.NONE else DiskCacheStrategy.DATA)
                .into(target)
        }

        override fun onViewRecycled(holder: Holder) = clear(holder)

        private fun clear(holder: Holder) {
            holder.target?.let(requests::clear)
            holder.target = null
        }

        class Holder(val image: ZoomImageView) : RecyclerView.ViewHolder(image) {
            var target: CustomViewTarget<ZoomImageView, Drawable>? = null
        }
    }

    companion object {
        private const val EXTRA_IMAGES = "header_images"
        private const val EXTRA_POSITION = "header_initial_position"
        private const val EXTRA_CACHE_ONLY = "header_cache_only"
        private const val STATE_POSITION = "header_position"
        private const val STATE_DIRECTION = "header_browse_direction"
        private const val STATE_MASK = "header_mask"
        private const val STATE_PERMISSION_IMAGE = "header_permission_image"

        fun intent(context: Context, names: List<String>, initialPosition: Int, cacheOnly: Boolean = false): Intent =
            Intent(context, HeaderImageActivity::class.java)
                .putStringArrayListExtra(EXTRA_IMAGES, ArrayList(names))
                .putExtra(EXTRA_POSITION, initialPosition)
                .putExtra(EXTRA_CACHE_ONLY, cacheOnly)
    }
}
