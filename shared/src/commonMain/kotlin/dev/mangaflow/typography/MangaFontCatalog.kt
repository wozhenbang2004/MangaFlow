package dev.mangaflow.typography

internal enum class MangaFontScene(val title: String, val description: String, val sample: String) {
    INTERFACE("界面文字", "导航、章节与设置", "发现  书架  第128话"),
    WORK_TITLE("作品标题", "封面书名与漫画详情标题", "月刊少女野崎君"),
    READING("简介与评论", "文字简介与评论", "翻开下一页，故事还在继续。"),
    NUMBERS("评分与数字", "评分、热度、页码与进度", "8.7  299.4万  128/235  42%"),
}

internal data class MangaFontAsset(
    val fileName: String,
    val byteCount: Long,
    val gitBlobSha1: String,
    val urls: List<String>,
    val weight: Int? = null,
)

internal data class MangaFontOption(
    val id: String,
    val name: String,
    val scenes: Set<MangaFontScene>,
    val assets: List<MangaFontAsset>,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String,
) {
    val byteCount: Long get() = assets.sumOf { it.byteCount }
}

internal data class MangaFontFile(val path: String, val weight: Int? = null)

/** Complete upstream fonts are optional assets; no preview subset or network font is bundled. */
internal object MangaFontCatalog {
    val options: List<MangaFontOption> = listOf(
        MangaFontOption("inherit", "跟随界面", MangaFontScene.entries.filter { it != MangaFontScene.INTERFACE }.toSet(),
            emptyList(), "", "", ""),
        MangaFontOption("system", "系统字体", MangaFontScene.entries.toSet(), emptyList(), "", "", ""),
        MangaFontOption(
            id = "noto_sans_sc", name = "Noto Sans SC", scenes = setOf(MangaFontScene.INTERFACE, MangaFontScene.WORK_TITLE, MangaFontScene.READING),
            assets = listOf(MangaFontAsset("NotoSansSC.ttf", 17772300L, "fb0637bafbcd804fe32152370a1225990745b4bc",
                listOf("https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf", "https://cdn.jsdelivr.net/gh/google/fonts@29b4ec6df2522a8aee279414b78a07910b065874/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf"), null)),
            licenseName = "SIL OFL 1.1",
            licenseUrl = "https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/notosanssc/OFL.txt",
            sourceUrl = "https://github.com/notofonts/noto-cjk",
        ),
        MangaFontOption(
            id = "noto_serif_sc", name = "Noto Serif SC", scenes = setOf(MangaFontScene.WORK_TITLE, MangaFontScene.READING),
            assets = listOf(MangaFontAsset("NotoSerifSC.ttf", 25125512L, "eab063faf229160a52d3760f5555150e4eb9e5bf",
                listOf("https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/notoserifsc/NotoSerifSC%5Bwght%5D.ttf"), null)),
            licenseName = "SIL OFL 1.1",
            licenseUrl = "https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/notoserifsc/OFL.txt",
            sourceUrl = "https://github.com/notofonts/noto-cjk",
        ),
        MangaFontOption(
            id = "inter", name = "Inter", scenes = setOf(MangaFontScene.NUMBERS),
            assets = listOf(MangaFontAsset("Inter.ttf", 876576L, "047c92f6e2212473dc436020afed689527076d44",
                listOf("https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf", "https://cdn.jsdelivr.net/gh/google/fonts@29b4ec6df2522a8aee279414b78a07910b065874/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf"), null)),
            licenseName = "SIL OFL 1.1",
            licenseUrl = "https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/inter/OFL.txt",
            sourceUrl = "https://github.com/rsms/inter",
        ),
        MangaFontOption(
            id = "geist", name = "Geist", scenes = setOf(MangaFontScene.NUMBERS),
            assets = listOf(MangaFontAsset("Geist.ttf", 169056L, "f63f0afc6390323715972b6645115485f37cc9f4",
                listOf("https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/geist/Geist%5Bwght%5D.ttf", "https://cdn.jsdelivr.net/gh/google/fonts@29b4ec6df2522a8aee279414b78a07910b065874/ofl/geist/Geist%5Bwght%5D.ttf"), null)),
            licenseName = "SIL OFL 1.1",
            licenseUrl = "https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/geist/OFL.txt",
            sourceUrl = "https://github.com/vercel/geist-font",
        ),
        MangaFontOption(
            id = "wenkai", name = "霞鹜文楷", scenes = setOf(MangaFontScene.WORK_TITLE, MangaFontScene.READING),
            assets = listOf(MangaFontAsset("LXGWWenKai-Regular.ttf", 25928940L, "7f8f031dc5c6c5ba1913d93c36d4667c1e311cea",
                listOf("https://raw.githubusercontent.com/lxgw/LxgwWenKai/d20c88d9ed1570a54ef09b46f37724712bbe0203/fonts/TTF/LXGWWenKai-Regular.ttf"), 400)),
            licenseName = "SIL OFL 1.1",
            licenseUrl = "https://raw.githubusercontent.com/lxgw/LxgwWenKai/d20c88d9ed1570a54ef09b46f37724712bbe0203/OFL.txt",
            sourceUrl = "https://github.com/lxgw/LxgwWenKai",
        ),
    )

    fun find(id: String): MangaFontOption? = options.firstOrNull { it.id == id }
    fun forScene(scene: MangaFontScene): List<MangaFontOption> = options.filter { scene in it.scenes }
    fun defaultId(scene: MangaFontScene): String = when (scene) {
        MangaFontScene.INTERFACE, MangaFontScene.WORK_TITLE, MangaFontScene.READING, MangaFontScene.NUMBERS -> "system"
    }
    fun licenseText(id: String): String = MangaFontLicenses.text(id)
}
