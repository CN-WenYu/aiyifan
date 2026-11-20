package com.aiyifan.app.feature.mine

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.aiyifan.app.R
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.load.model.stream.HttpGlideUrlLoader
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HeaderImageSaverTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: GalleryProvider

    @Before fun registerGallery() {
        provider = GalleryProvider(File(context.cacheDir, "saved-test.webp"))
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test fun `save copies packaged bytes and publishes pending media row`() {
        HeaderImageSaver.save(context, "mine_header_001.webp")
        assertArrayEquals(context.assets.open("mine_header/mine_header_001.webp").use { it.readBytes() },
            provider.file.readBytes())
        assertEquals("image/webp", provider.inserted!!.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("Pictures/Aiyifan", provider.inserted!!.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(0, provider.updated!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertFalse(provider.deleted)
    }

    @Test fun `write failure removes incomplete gallery item`() {
        shadowOf(context.contentResolver).registerOutputStream(
            Uri.parse("content://media/external/images/media/42"), object : java.io.OutputStream() {
                override fun write(value: Int) { throw IOException("Simulated full storage") }
            })
        assertThrows(IOException::class.java) { HeaderImageSaver.save(context, "mine_header_001.webp") }
        assertTrue(provider.deleted)
        assertNull(provider.updated)
    }

    @Test fun `publish failure removes incomplete gallery item`() {
        provider.failPublish = true
        assertThrows(IOException::class.java) { HeaderImageSaver.save(context, "mine_header_001.webp") }
        assertTrue(provider.deleted)
        assertFalse(provider.file.exists())
    }

    @Test fun `missing source creates no gallery item`() {
        assertThrows(IOException::class.java) { HeaderImageSaver.save(context, "mine_header_999.webp") }
        assertNull(provider.inserted)
    }

    @Test fun `remote save copies original bytes and can repeat from disk cache offline`() {
        val image = context.assets.open("mine_header/mine_header_001.webp").use { it.readBytes() }
        var online = true
        withRemoteImageLoader {
            if (online && it.endsWith("/mine_header_002.webp")) image.inputStream()
            else throw IOException("Simulated offline")
        }.use {
            val uri = saveOnWorker("mine_header_002.webp")
            assertEquals("content://media/external/images/media/42", uri.toString())
            assertArrayEquals(image, provider.file.readBytes())
            assertEquals(0, provider.updated!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))

            online = false
            assertTrue(provider.file.delete())
            saveOnWorker("mine_header_002.webp")
            assertArrayEquals(image, provider.file.readBytes())
            assertEquals(2, provider.insertCount)
        }
    }

    @Test fun `remote failure creates no gallery item`() {
        withRemoteImageLoader { throw IOException("Simulated offline") }.use {
            val error = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                saveOnWorker("mine_header_003.webp")
            }
            assertTrue(error.cause is IOException)
            assertNull(provider.inserted)
        }
    }

    private fun saveOnWorker(name: String): Uri {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<Uri> { HeaderImageSaver.save(context, name) }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun withRemoteImageLoader(open: (String) -> InputStream): AutoCloseable {
        val registry = Glide.get(context).registry
        registry.replace(GlideUrl::class.java, InputStream::class.java,
            object : ModelLoaderFactory<GlideUrl, InputStream> {
                override fun build(multiFactory: MultiModelLoaderFactory) =
                    object : ModelLoader<GlideUrl, InputStream> {
                        override fun handles(model: GlideUrl) = true
                        override fun buildLoadData(model: GlideUrl, width: Int, height: Int, options: Options) =
                            ModelLoader.LoadData(model, object : DataFetcher<InputStream> {
                                override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
                                    try {
                                        callback.onDataReady(open(model.toStringUrl()))
                                    } catch (failure: IOException) {
                                        callback.onLoadFailed(failure)
                                    }
                                }
                                override fun cleanup() = Unit
                                override fun cancel() = Unit
                                override fun getDataClass() = InputStream::class.java
                                override fun getDataSource() = DataSource.REMOTE
                            })
                    }
                override fun teardown() = Unit
            })
        return AutoCloseable {
            registry.replace(GlideUrl::class.java, InputStream::class.java, HttpGlideUrlLoader.Factory())
        }
    }

    @Test fun `save survives viewer recreation and rejects duplicate requests`() {
        provider.allowWrite = CountDownLatch(1)
        val controller = openHeaderImageActivity()
        fun button() = controller.get().findViewById<View>(R.id.header_image_download)
        try {
            controller.get().awaitImageSaveEnabled()
            button().performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(provider.writeStarted.await(5, TimeUnit.SECONDS))
            val model = ViewModelProvider(controller.get())[HeaderImageSaveViewModel::class.java]
            assertFalse(button().isEnabled)
            model.save(context, "mine_header_001.webp")
            controller.recreate()
            controller.get().layoutImageViewport()
            assertSame(model, ViewModelProvider(controller.get())[HeaderImageSaveViewModel::class.java])
            assertFalse(button().isEnabled)
            provider.allowWrite!!.countDown()
            controller.get().awaitImageSaveEnabled()
            assertEquals(1, provider.insertCount)
            assertEquals(0, provider.updated!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        } finally {
            provider.allowWrite!!.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test @Config(sdk = [28]) fun `legacy save preserves bytes in public pictures directory`() {
        val uri = HeaderImageSaver.save(context, "mine_header_001.webp")
        val file = File(uri.path!!)
        try {
            assertEquals("Aiyifan", file.parentFile!!.name)
            assertEquals("Pictures", file.parentFile!!.parentFile!!.name)
            assertArrayEquals(context.assets.open("mine_header/mine_header_001.webp").use { it.readBytes() }, file.readBytes())
        } finally { file.delete() }
    }

    @Test @Config(sdk = [28]) fun `legacy permission denial does not save an image`() {
        val controller = openHeaderImageActivity()
        try {
            controller.get().awaitImageSaveEnabled()
            controller.get().findViewById<View>(R.id.header_image_download).performClick()
            val request = shadowOf(controller.get()).lastRequestedPermission
            assertArrayEquals(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), request.requestedPermissions)
            controller.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_DENIED))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(provider.inserted)
            assertEquals(context.getString(R.string.mine_image_save_permission),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        } finally { controller.pause().stop().destroy() }
    }

    private class GalleryProvider(val file: File) : ContentProvider() {
        var inserted: ContentValues? = null
        var updated: ContentValues? = null
        var deleted = false
        var failPublish = false
        var insertCount = 0
        var allowWrite: CountDownLatch? = null
        val writeStarted = CountDownLatch(1)
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(values)
            insertCount++
            return Uri.parse("content://media/external/images/media/42")
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            writeStarted.countDown()
            if (allowWrite?.await(10, TimeUnit.SECONDS) == false) throw java.io.FileNotFoundException("Timed out")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updated = ContentValues(values)
            return if (failPublish) 0 else 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted = true
            file.delete()
            return 1
        }
        override fun getType(uri: Uri) = "image/webp"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
