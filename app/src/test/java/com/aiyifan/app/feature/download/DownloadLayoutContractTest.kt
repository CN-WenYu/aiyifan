package com.aiyifan.app.feature.download

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class DownloadLayoutContractTest {

    @Test
    fun `download rows use compact poster and action dimensions`() {
        val active = root(layout("item_download"))
        assertEquals("48dp", view(active, "poster").getAttribute("android:layout_width"))
        assertEquals("66dp", view(active, "poster").getAttribute("android:layout_height"))

        val completed = root(layout("item_download_film"))
        assertEquals("62dp", view(completed, "poster").getAttribute("android:layout_width"))
        assertEquals("82dp", view(completed, "poster").getAttribute("android:layout_height"))
        assertEquals("48dp", view(completed, "playButton").getAttribute("android:layout_height"))
    }

    private fun layout(name: String): File = sequenceOf(
        File("src/main/res/layout/$name.xml"),
        File("app/src/main/res/layout/$name.xml"),
    ).first(File::isFile)

    private fun root(file: File): Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder().parse(file).documentElement

    private fun view(root: Element, id: String): Element =
        (0 until root.getElementsByTagName("*").length).asSequence()
            .map(root.getElementsByTagName("*")::item)
            .map { it as Element }
            .first { it.getAttribute("android:id").substringAfter("@+id/", "") == id }
}
