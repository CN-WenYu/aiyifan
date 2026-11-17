package com.aiyifan.app.feature.localmedia

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

class LocalVideoPlayerLayoutTest {

    @Test
    fun `full screen local player exposes lock controls and unlock overlay`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layoutFile())
        val topBar = viewWithId(document, "localPlayerTopBar")!!
        val overlay = viewWithId(document, "localLockOverlay")!!

        assertNotNull(viewWithId(topBar, "localFullScreenLockButton"))
        assertEquals("localUnlockButton", viewWithId(overlay, "localUnlockButton")!!.idName())
    }

    private fun layoutFile(): File = sequenceOf(
        File("src/main/res/layout/activity_local_video_player.xml"),
        File("app/src/main/res/layout/activity_local_video_player.xml"),
    ).first(File::isFile)

    private fun viewWithId(document: org.w3c.dom.Document, id: String): Element? =
        viewWithId(document.documentElement, id)

    private fun viewWithId(root: Element, id: String): Element? =
        (0 until root.getElementsByTagName("*").length).asSequence()
            .map(root.getElementsByTagName("*")::item)
            .map { it as Element }
            .firstOrNull { it.idName() == id }

    private fun Element.idName(): String? =
        getAttribute("android:id").substringAfter("@+id/", missingDelimiterValue = "")
            .ifBlank { null }
}
