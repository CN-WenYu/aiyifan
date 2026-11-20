package com.aiyifan.app.feature.mine

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.doOnLayout
import com.aiyifan.app.R
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.createAppBottomSheet
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

internal class HeaderImageSheet : BottomSheetDialogFragment() {
    private var target: CustomViewTarget<ZoomImageView, Drawable>? = null
    private var requests: RequestManager? = null
    private var maskEnabled = true
    private var destroyingView = false
    private val saveModel by lazy { ViewModelProvider(this)[HeaderImageSaveViewModel::class.java] }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) arguments?.getString(IMAGE)?.let { saveModel.save(requireContext(), it) }
        else Toast.makeText(requireContext(), R.string.mine_image_save_permission, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maskEnabled = savedInstanceState?.getBoolean("mask_enabled", true) ?: true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = requireContext().createAppBottomSheet()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        destroyingView = false
        val context = requireDialog().context
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            applySystemBarsPadding(left = true, right = true, bottom = true)
        }
        val imageArea = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val image = ZoomImageView(context).apply {
            tag = "header_zoom_image"
            contentDescription = getString(R.string.mine_view_image)
        }
        imageArea.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val mask = View(context).apply {
            tag = "header_image_mask"
            setBackgroundColor(0xCC000000.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (maskEnabled) View.VISIBLE else View.GONE
        }
        imageArea.addView(mask, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        image.setOnClickListener {
            maskEnabled = !maskEnabled
            mask.visibility = if (maskEnabled) View.VISIBLE else View.GONE
        }
        val controls = LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val padding = resources.getDimensionPixelSize(R.dimen.dp_8)
            setPadding(padding, padding, padding, padding)
        }
        val buttonSize = resources.getDimensionPixelSize(R.dimen.dp_48)
        val close = sheetButton(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = getString(R.string.mine_close_image)
            setOnClickListener { dismiss() }
        }
        val download = sheetButton(context).apply {
            tag = "header_image_download"
            setImageResource(R.drawable.ic_download)
            contentDescription = getString(R.string.mine_save_image)
            setOnClickListener {
                val name = requireArguments().getString(IMAGE) ?: return@setOnClickListener
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else saveModel.save(context, name)
            }
        }
        controls.addView(download, LinearLayout.LayoutParams(buttonSize, buttonSize))
        controls.addView(close, LinearLayout.LayoutParams(buttonSize, buttonSize))
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val windowHeight = requireActivity().window.decorView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        root.addView(imageArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (windowHeight * 0.60f).toInt()))
        return root
    }

    private fun sheetButton(context: android.content.Context) = ImageButton(context).apply {
        val ripple = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        setBackgroundResource(ripple.resourceId)
        imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.text_primary))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val image = view.findViewWithTag<ZoomImageView>("header_zoom_image")
        val download = view.findViewWithTag<ImageButton>("header_image_download")
        val name = requireArguments().getString(IMAGE) ?: return
        requests = Glide.with(this)
        saveModel.state.observe(viewLifecycleOwner) { state ->
            download.isEnabled = state != HeaderImageSaveViewModel.State.SAVING
            download.alpha = if (download.isEnabled) 1f else 0.5f
            download.contentDescription = getString(if (download.isEnabled) R.string.mine_save_image else R.string.mine_image_saving)
            val message = when (state) {
                HeaderImageSaveViewModel.State.SAVED -> R.string.mine_image_saved
                HeaderImageSaveViewModel.State.FAILED -> R.string.mine_image_save_failed
                else -> null
            }
            if (message != null) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                saveModel.consumeResult()
            }
        }
        image.doOnLayout {
            val manager = requests ?: return@doOnLayout
            val requestTarget = object : CustomViewTarget<ZoomImageView, Drawable>(image) {
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    Toast.makeText(requireContext(), R.string.mine_image_unavailable, Toast.LENGTH_SHORT).show()
                }
                override fun onResourceCleared(placeholder: Drawable?) { image.setImageDrawable(null) }
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    image.setImageDrawable(resource)
                }
            }
            target = requestTarget
            manager.load("file:///android_asset/mine_header/$name")
                .override(image.width, image.height).fitCenter().dontAnimate()
                .skipMemoryCache(true).diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(requestTarget)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("mask_enabled", maskEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!destroyingView) parentFragmentManager.setFragmentResult(TAG, Bundle())
    }

    override fun onDestroyView() {
        destroyingView = true
        target?.let { requests?.clear(it) }
        target = null
        requests = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "header_image_sheet"
        private const val IMAGE = "header_image"

        fun newInstance(name: String) = HeaderImageSheet().apply {
            arguments = Bundle().apply { putString(IMAGE, name) }
        }
    }
}
