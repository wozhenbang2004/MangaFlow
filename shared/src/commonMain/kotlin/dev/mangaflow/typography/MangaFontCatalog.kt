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
    val description: String = "",
) {
    val byteCount: Long get() = assets.sumOf { it.byteCount }
}

internal data class MangaFontFile(val path: String, val weight: Int? = null)

/** Complete upstream fonts are optional assets; no preview subset or network font is bundled. */
internal object MangaFontCatalog {
    val retiredIds: Set<String> = setOf("noto_sans_sc", "noto_serif_sc", "wenkai")

    val options: List<MangaFontOption> = listOf(
        MangaFontOption("inherit", "跟随界面", MangaFontScene.entries.filter { it != MangaFontScene.INTERFACE }.toSet(),
            emptyList(), "", "", ""),
        MangaFontOption("system", "系统字体", MangaFontScene.entries.toSet(), emptyList(), "", "", ""),
        MangaFontOption(
            id = "harmony_sans_sc", name = "HarmonyOS Sans",
            scenes = setOf(MangaFontScene.INTERFACE, MangaFontScene.WORK_TITLE, MangaFontScene.READING),
            assets = listOf(
                MangaFontAsset("HarmonyOS_Sans_SC_Regular.ttf", 8261060L, "25069dad0a3ec40ba9378cd201a34a687ce52047",
                    listOf("https://raw.githubusercontent.com/openharmony/resources/0d79cad76b37ba0f3ccb1323c83833c78e0860de/fonts/HarmonyOS_Sans_SC_Regular.ttf", "https://cdn.jsdelivr.net/gh/openharmony/resources@0d79cad76b37ba0f3ccb1323c83833c78e0860de/fonts/HarmonyOS_Sans_SC_Regular.ttf"), 400),
                MangaFontAsset("HarmonyOS_Sans_SC_Medium.ttf", 8227216L, "6814ccec713b87f623785e58c2b47b609114df8a",
                    listOf("https://raw.githubusercontent.com/openharmony/resources/0d79cad76b37ba0f3ccb1323c83833c78e0860de/fonts/HarmonyOS_Sans_SC_Medium.ttf", "https://cdn.jsdelivr.net/gh/openharmony/resources@0d79cad76b37ba0f3ccb1323c83833c78e0860de/fonts/HarmonyOS_Sans_SC_Medium.ttf"), 500),
                MangaFontAsset("HarmonyOS_Sans_SC_Bold.ttf", 8158924L, "534ddf9a7e8e89af55c161e3f8c3b081c3d91a5b",
                    listOf("https://raw.githubusercontent.com/openharmony/resources/0d79cad76b37ba0f3ccb1323c83833c78e0860de/fonts/HarmonyOS_Sans_SC_Bold.ttf", "https://cdn.jsdelivr.net/gh/openharmony/resources@0d79cad76b37ba0f3ccb1323c83833c78e0860de/fonts/HarmonyOS_Sans_SC_Bold.ttf"), 700),
            ),
            licenseName = "HarmonyOS Sans Fonts License Agreement",
            licenseUrl = "https://raw.githubusercontent.com/openharmony/resources/0d79cad76b37ba0f3ccb1323c83833c78e0860de/LICENSE_Fonts",
            sourceUrl = "https://github.com/openharmony/resources/tree/0d79cad76b37ba0f3ccb1323c83833c78e0860de/fonts",
            description = "清爽的现代黑体 · 界面与长文",
        ),
        MangaFontOption(
            id = "zcool_huangyou", name = "站酷庆科黄油体",
            scenes = setOf(MangaFontScene.WORK_TITLE),
            assets = listOf(
                MangaFontAsset("ZCOOLQingKeHuangYou-Regular.ttf", 8328684L, "982a3b80143268592f573feb196791fbc2330e17",
                    listOf("https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/zcoolqingkehuangyou/ZCOOLQingKeHuangYou-Regular.ttf", "https://cdn.jsdelivr.net/gh/google/fonts@29b4ec6df2522a8aee279414b78a07910b065874/ofl/zcoolqingkehuangyou/ZCOOLQingKeHuangYou-Regular.ttf"), 400),
            ),
            licenseName = "SIL OFL 1.1",
            licenseUrl = "https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/zcoolqingkehuangyou/OFL.txt",
            sourceUrl = "https://github.com/googlefonts/zcool-qingke-huangyou",
            description = "圆角几何字形 · 仅用于书名",
        ),
        MangaFontOption(
            id = "inter", name = "Inter",
            scenes = setOf(MangaFontScene.NUMBERS),
            assets = listOf(
                MangaFontAsset("Inter.ttf", 876576L, "047c92f6e2212473dc436020afed689527076d44",
                    listOf("https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf", "https://cdn.jsdelivr.net/gh/google/fonts@29b4ec6df2522a8aee279414b78a07910b065874/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf"), null),
            ),
            licenseName = "SIL OFL 1.1",
            licenseUrl = "https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/inter/OFL.txt",
            sourceUrl = "https://github.com/rsms/inter",
            description = "清晰规整 · 评分与页码",
        ),
        MangaFontOption(
            id = "geist", name = "Geist",
            scenes = setOf(MangaFontScene.NUMBERS),
            assets = listOf(
                MangaFontAsset("Geist.ttf", 169056L, "f63f0afc6390323715972b6645115485f37cc9f4",
                    listOf("https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/geist/Geist%5Bwght%5D.ttf", "https://cdn.jsdelivr.net/gh/google/fonts@29b4ec6df2522a8aee279414b78a07910b065874/ofl/geist/Geist%5Bwght%5D.ttf"), null),
            ),
            licenseName = "SIL OFL 1.1",
            licenseUrl = "https://raw.githubusercontent.com/google/fonts/29b4ec6df2522a8aee279414b78a07910b065874/ofl/geist/OFL.txt",
            sourceUrl = "https://github.com/vercel/geist-font",
            description = "简洁几何 · 评分与页码",
        ),
    )

    fun find(id: String): MangaFontOption? = options.firstOrNull { it.id == id }
    fun forScene(scene: MangaFontScene): List<MangaFontOption> = options.filter { scene in it.scenes }
    fun defaultId(scene: MangaFontScene): String = when (scene) {
        MangaFontScene.INTERFACE, MangaFontScene.WORK_TITLE, MangaFontScene.READING, MangaFontScene.NUMBERS -> "system"
    }
    fun licenseText(id: String): String = MangaFontLicenses.text(id)
}
