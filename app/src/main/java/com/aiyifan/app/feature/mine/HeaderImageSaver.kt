package com.aiyifan.app.feature.mine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import com.bumptech.glide.Glide
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Copies the original WebP bytes from the APK or Glide's data cache. */
internal object HeaderImageSaver {
    private val imageName = Regex("mine_header_[0-9]{3}\\.webp")

    @WorkerThread
    fun save(context: Context, name: String): Uri {
        require(name.matches(imageName))
        if (name !in HeaderImageSource.allNames) throw FileNotFoundException(name)
        if (HeaderImageSource.isLocal(name)) {
            return context.assets.open(HeaderImageSource.assetPath(name)).use { input ->
                saveToGallery(context, name, input)
            }
        }
        val requests = Glide.with(context.applicationContext)
        // downloadOnly reads and populates the same DATA disk cache as the viewer and carousel.
        val target = requests.downloadOnly()
            .load(HeaderImageSource.model(name))
            .timeout(HeaderImageSource.TIMEOUT_MS)
            .submit()
        try {
            val original = try {
                target.get(HeaderImageSource.TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            } catch (failure: ExecutionException) {
                throw IOException("Could not download header image", failure.cause)
            } catch (failure: TimeoutException) {
                throw IOException("Header image download timed out", failure)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Header image download was interrupted", failure)
            }
            return original.inputStream().use { input -> saveToGallery(context, name, input) }
        } finally {
            requests.clear(target)
        }
    }

    private fun saveToGallery(context: Context, name: String, input: InputStream): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return saveLegacy(context, name, input)

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${name.removeSuffix(".webp")}_${UUID.randomUUID()}.webp")
            put(MediaStore.Images.Media.MIME_TYPE, "image/webp")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Aiyifan")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create gallery item")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Could not open gallery item")
            output.use { input.copyTo(it) }
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Could not publish gallery item")
            return uri
        } catch (failure: Exception) {
            try { resolver.delete(uri, null, null) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, name: String, input: InputStream): Uri {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Aiyifan")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create pictures directory")
        val file = File.createTempFile(name.removeSuffix(".webp") + "_", ".webp", directory)
        try {
            file.outputStream().use { input.copyTo(it) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/webp"), null)
            return Uri.fromFile(file)
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }
}
