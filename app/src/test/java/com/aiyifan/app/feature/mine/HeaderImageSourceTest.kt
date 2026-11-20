package com.aiyifan.app.feature.mine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HeaderImageSourceTest {
    @Test fun `catalog preserves all original image names in numeric order`() {
        val names = HeaderImageSource.allNames
        assertEquals(188, names.size)
        assertEquals(188, names.toSet().size)
        assertEquals(names.sorted(), names)
        assertEquals("mine_header_001.webp", names.first())
        assertEquals("mine_header_188.webp", names.last())
    }

    @Test fun `bundled fallbacks use asset URLs and other images use the pinned GitHub revision`() {
        assertEquals(
            listOf("mine_header_001.webp", "mine_header_095.webp", "mine_header_188.webp"),
            HeaderImageSource.localNames,
        )
        for (name in HeaderImageSource.localNames) {
            assertEquals("file:///android_asset/mine_header/$name", HeaderImageSource.model(name))
        }
        val remoteName = "mine_header_002.webp"
        assertFalse(HeaderImageSource.isLocal(remoteName))
        assertEquals(
            "https://raw.githubusercontent.com/xiaodaitaishuai1/aiyifan/8def7857ad848c1e3f15b8a2bdbb921d4b3cbb2c/" +
                "app/src/main/assets/mine_header/$remoteName",
            HeaderImageSource.model(remoteName),
        )
        assertThrows(IllegalArgumentException::class.java) { HeaderImageSource.model("../other.webp") }
    }
}
