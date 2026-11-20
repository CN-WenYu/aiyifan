package com.aiyifan.app.feature.mine

internal object HeaderImageSource {
    const val TIMEOUT_MS = 5_000

    // Use the revision that still contains the complete image set.
    private const val GITHUB_ASSETS =
        "https://raw.githubusercontent.com/xiaodaitaishuai1/aiyifan/8def7857ad848c1e3f15b8a2bdbb921d4b3cbb2c/app/src/main/assets"

    val allNames: List<String> = (1..188).map { index ->
        "mine_header_${index.toString().padStart(3, '0')}.webp"
    }
    val localNames: List<String> = listOf(
        "mine_header_001.webp",
        "mine_header_095.webp",
        "mine_header_188.webp",
    )

    fun isLocal(name: String): Boolean = name in localNames

    fun model(name: String): String {
        require(name in allNames)
        val path = assetPath(name)
        return if (isLocal(name)) "file:///android_asset/$path" else "$GITHUB_ASSETS/$path"
    }

    fun assetPath(name: String): String = "mine_header/$name"
}
