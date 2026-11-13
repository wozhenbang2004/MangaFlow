package dev.mangaflow.data

enum class SearchTarget { TITLE, AUTHOR, TAG }

data class MangaTag(val name: String, val path: String)

/** Map display names to real Copy identifiers; another source's native path is never reused. */
fun copyTagForName(name: String, tags: List<MangaTag> = CopySearchTags): MangaTag? {
    fun key(value: String): String = value.trim().lowercase().map { character ->
        val traditional = "愛歡樂嚮險園鬥輕懸職癒條節艦競偽熱勵轉換後偵戰歷異驚機無俠場戀"
        val simplified = "爱欢乐向险园斗轻悬职愈条节舰竞伪热励转换后侦战历异惊机无侠场恋"
        val index = traditional.indexOf(character)
        if (index >= 0) simplified[index] else character
    }.filterNot(Char::isWhitespace).joinToString("")
    val requested = when (val value = key(name)) {
        "romance", "恋爱" -> "爱情"
        "comedy" -> "搞笑"
        "fantasy" -> "奇幻"
        "schoollife" -> "校园"
        "sliceoflife" -> "生活"
        "horror" -> "恐怖"
        "mystery" -> "悬疑"
        "sci-fi", "scifi", "sciencefiction" -> "科幻"
        else -> value
    }
    return tags.firstOrNull { key(it.name) == requested }
}

/**
 * Copy theme paths from Venera's public source configuration, pinned to
 * a04e05591f7832f7166d6660546613c114d6738a / copy_manga.js category_param_dict.
 * A known directory of actual source identifiers, not romanized display text.
 * Current comic metadata can add further source-returned names/paths.
 */
val CopySearchTags: List<MangaTag> = listOf(
    MangaTag("愛情", "aiqing"),
    MangaTag("歡樂向", "huanlexiang"),
    MangaTag("冒險", "maoxian"),
    MangaTag("奇幻", "qihuan"),
    MangaTag("百合", "baihe"),
    MangaTag("校园", "xiaoyuan"),
    MangaTag("科幻", "kehuan"),
    MangaTag("東方", "dongfang"),
    MangaTag("耽美", "danmei"),
    MangaTag("生活", "shenghuo"),
    MangaTag("格鬥", "gedou"),
    MangaTag("轻小说", "qingxiaoshuo"),
    MangaTag("悬疑", "xuanyi"),
    MangaTag("其他", "qita"),
    MangaTag("神鬼", "shengui"),
    MangaTag("职场", "zhichang"),
    MangaTag("TL", "teenslove"),
    MangaTag("萌系", "mengxi"),
    MangaTag("治愈", "zhiyu"),
    MangaTag("長條", "changtiao"),
    MangaTag("四格", "sige"),
    MangaTag("节操", "jiecao"),
    MangaTag("舰娘", "jianniang"),
    MangaTag("竞技", "jingji"),
    MangaTag("搞笑", "gaoxiao"),
    MangaTag("伪娘", "weiniang"),
    MangaTag("热血", "rexue"),
    MangaTag("励志", "lizhi"),
    MangaTag("性转换", "xingzhuanhuan"),
    MangaTag("彩色", "COLOR"),
    MangaTag("後宮", "hougong"),
    MangaTag("美食", "meishi"),
    MangaTag("侦探", "zhentan"),
    MangaTag("AA", "aa"),
    MangaTag("音乐舞蹈", "yinyuewudao"),
    MangaTag("魔幻", "mohuan"),
    MangaTag("战争", "zhanzheng"),
    MangaTag("历史", "lishi"),
    MangaTag("异世界", "yishijie"),
    MangaTag("惊悚", "jingsong"),
    MangaTag("机战", "jizhan"),
    MangaTag("都市", "dushi"),
    MangaTag("穿越", "chuanyue"),
    MangaTag("恐怖", "kongbu"),
    MangaTag("C100", "comiket100"),
    MangaTag("重生", "chongsheng"),
    MangaTag("C99", "comiket99"),
    MangaTag("C101", "comiket101"),
    MangaTag("C97", "comiket97"),
    MangaTag("C96", "comiket96"),
    MangaTag("生存", "shengcun"),
    MangaTag("宅系", "zhaixi"),
    MangaTag("武侠", "wuxia"),
    MangaTag("C98", "C98"),
    MangaTag("C95", "comiket95"),
    MangaTag("FATE", "fate"),
    MangaTag("转生", "zhuansheng"),
    MangaTag("無修正", "Uncensored"),
    MangaTag("仙侠", "xianxia"),
    MangaTag("LoveLive", "loveLive"),
)
