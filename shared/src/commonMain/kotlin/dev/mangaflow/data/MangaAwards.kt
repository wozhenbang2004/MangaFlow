package dev.mangaflow.data

enum class MangaAward(val id: String, val title: String, val home: String) {
    MANGA_TAISHO("manga-taisho", "漫画大赏", "https://www.mangataisho.com/archives/"),
    SHOGAKUKAN("shogakukan", "小学馆漫画赏", "https://shogakukan-comic.jp/shogakukan-mangasho-archives"),
    KODANSHA("kodansha", "讲谈社漫画赏", "https://cstation.kodansha.co.jp/mangaip/award/25"),
}

/** An award record, not a catalogue subject or a readable source identity. */
data class MangaAwardEntry(
    val award: MangaAward,
    val year: Int,
    val title: String,
    val authors: List<String>,
    val category: String,
    val sourceUrl: String,
    val edition: Int? = null,
) {
    val key: String get() = "${award.id}:$year:$category:$title"
}

/**
 * Manually verified official award results bundled with the app, not a live feed.
 * The year is the award announcement year, not the manga's publication/start year.
 * Only winning works are included; nominations and awards to people are excluded.
 * Source URLs are provenance text, not external navigation actions.
 */
object MangaAwardsCatalog {
    const val verifiedAt: String = "2026-11-18"
    const val coverageNote: String = "漫画大赏 2008–2026；小学馆、讲谈社漫画赏 2022–2026。收录已核对的获奖作品，随应用更新。"

    val entries: List<MangaAwardEntry> = listOf(
        // Manga Taisho: one grand-prize work per year, in official native titles.
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2026, "本なら売るほど", listOf("児島青"), "大赏",
            "https://www.mangataisho.com/data/2026/press20260326.pdf", 19,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2025, "ありす、宇宙までも", listOf("売野機子"), "大赏",
            "https://www.mangataisho.com/archives/2025.html", 18,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2024, "君と宇宙を歩くために", listOf("泥ノ田犬彦"), "大赏",
            "https://www.mangataisho.com/archives/2024.html", 17,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2023, "これ描いて死ね", listOf("とよ田みのる"), "大赏",
            "https://www.mangataisho.com/archives/2023.html", 16,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2022, "ダーウィン事変", listOf("うめざわしゅん"), "大赏",
            "https://www.mangataisho.com/archives/2022.html", 15,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2021, "葬送のフリーレン", listOf("山田鐘人", "アベツカサ"), "大赏",
            "https://www.mangataisho.com/archives/2021.html", 14,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2020, "ブルーピリオド", listOf("山口つばさ"), "大赏",
            "https://www.mangataisho.com/archives/2020.html", 13,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2019, "彼方のアストラ", listOf("篠原健太"), "大赏",
            "https://www.mangataisho.com/archives/2019.html", 12,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2018, "BEASTARS", listOf("板垣巴留"), "大赏",
            "https://www.mangataisho.com/archives/2018.html", 11,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2017, "響～小説家になる方法～", listOf("柳本光晴"), "大赏",
            "https://www.mangataisho.com/archives/2017.html", 10,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2016, "ゴールデンカムイ", listOf("野田サトル"), "大赏",
            "https://www.mangataisho.com/archives/2016.html", 9,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2015, "かくかくしかじか", listOf("東村アキコ"), "大赏",
            "https://www.mangataisho.com/archives/2015.html", 8,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2014, "乙嫁語り", listOf("森薫"), "大赏",
            "https://www.mangataisho.com/archives/2014.html", 7,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2013, "海街diary", listOf("吉田秋生"), "大赏",
            "https://www.mangataisho.com/archives/2013.html", 6,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2012, "銀の匙 Silver Spoon", listOf("荒川弘"), "大赏",
            "https://www.mangataisho.com/archives/2012.html", 5,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2011, "３月のライオン", listOf("羽海野チカ"), "大赏",
            "https://www.mangataisho.com/archives/2011.html", 4,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2010, "テルマエ・ロマエ", listOf("ヤマザキマリ"), "大赏",
            "https://www.mangataisho.com/archives/2010.html", 3,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2009, "ちはやふる", listOf("末次由紀"), "大赏",
            "https://www.mangataisho.com/archives/2009.html", 2,
        ),
        MangaAwardEntry(
            MangaAward.MANGA_TAISHO, 2008, "岳", listOf("石塚真一"), "大赏",
            "https://www.mangataisho.com/archives/2008.html", 1,
        ),

        // Shogakukan abolished the four demographic categories from edition 69.
        // Edition 71's special award to Moto Hagio is not a work award.
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2026, "運命の巻戻士", listOf("木村風太"), "获奖作品",
            "https://shogakukan-comic.jp/news/65082", 71,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2026, "COSMOS", listOf("田村隆平"), "获奖作品",
            "https://shogakukan-comic.jp/news/65082", 71,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2026, "ダンダダン", listOf("龍幸伸"), "获奖作品",
            "https://shogakukan-comic.jp/news/65082", 71,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2026, "出禁のモグラ", listOf("江口夏実"), "获奖作品",
            "https://shogakukan-comic.jp/news/65082", 71,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2026, "ひらやすみ", listOf("真造圭伍"), "获奖作品",
            "https://shogakukan-comic.jp/news/65082", 71,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2025, "これ描いて死ね", listOf("とよ田みのる"), "获奖作品",
            "https://shogakukan-comic.jp/news/56138", 70,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2025, "灼熱カバディ", listOf("武蔵野創"), "获奖作品",
            "https://shogakukan-comic.jp/news/56138", 70,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2025, "夏目アラタの結婚", listOf("乃木坂太郎"), "获奖作品",
            "https://shogakukan-comic.jp/news/56138", 70,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2025, "ぷにるはかわいいスライム", listOf("まえだくん"), "获奖作品",
            "https://shogakukan-comic.jp/news/56138", 70,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2024, "葬送のフリーレン", listOf("山田鐘人", "アベツカサ"), "获奖作品",
            "https://shogakukan-comic.jp/news/48019", 69,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2024, "逃げ上手の若君", listOf("松井優征"), "获奖作品",
            "https://shogakukan-comic.jp/news/48019", 69,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2024, "数字であそぼ。", listOf("絹田村子"), "获奖作品",
            "https://shogakukan-comic.jp/news/48019", 69,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2024, "トリリオンゲーム", listOf("稲垣理一郎", "池上遼一"), "获奖作品",
            "https://shogakukan-comic.jp/news/48019", 69,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2023, "初×婚", listOf("黒崎みのり"), "儿童部门",
            "https://shogakukan-comic.jp/news/40780", 68,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2023, "よふかしのうた", listOf("コトヤマ"), "少年部门",
            "https://shogakukan-comic.jp/news/40780", 68,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2023, "青のオーケストラ", listOf("阿久井真"), "少年部门",
            "https://shogakukan-comic.jp/news/40780", 68,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2023, "明日、私は誰かのカノジョ", listOf("をのひなお"), "少女部门",
            "https://shogakukan-comic.jp/news/40780", 68,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2023, "メダリスト", listOf("つるまいかだ"), "一般部门",
            "https://shogakukan-comic.jp/news/40780", 68,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2022, "古見さんは、コミュ症です。", listOf("オダトモヒト"), "少年部门",
            "https://shogakukan-comic.jp/news/34500", 67,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2022, "消えた初恋", listOf("ひねくれ渡", "アルコ"), "少女部门",
            "https://shogakukan-comic.jp/news/34500", 67,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2022, "二月の勝者　―絶対合格の教室―", listOf("高瀬志帆"), "一般部门",
            "https://shogakukan-comic.jp/news/34500", 67,
        ),
        MangaAwardEntry(
            MangaAward.SHOGAKUKAN, 2022, "ミステリと言う勿れ", listOf("田村由美"), "一般部门",
            "https://shogakukan-comic.jp/news/34500", 67,
        ),

        // Kodansha: only the Kodansha Manga Award sections of the official IP directory.
        MangaAwardEntry(
            MangaAward.KODANSHA, 2026, "ガチアクタ", listOf("裏那圭", "晏童秀吉"), "少年部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 50,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2026, "死に戻りの魔法学校生活を、元恋人とプロローグから（※ただし好感度はゼロ）",
            listOf("白川蟻ん", "六つ花えいこ", "秋鹿ユギリ"), "少女部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 50,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2026, "ダーウィン事変", listOf("うめざわしゅん"), "综合部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 50,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2025, "バーサス", listOf("ONE", "あずま京太郎", "bose"), "少年部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 49,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2025, "恋せよまやかし天使ども", listOf("卯月ココ"), "少女部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 49,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2025, "ヒストリエ", listOf("岩明均"), "综合部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 49,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2024, "葬送のフリーレン", listOf("山田鐘人", "アベツカサ"), "少年部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 48,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2024, "きみの横顔を見ていた", listOf("いちのへ瑠美"), "少女部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 48,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2024, "メダリスト", listOf("つるまいかだ"), "综合部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 48,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2023, "シャングリラ・フロンティア ～クソゲーハンター、神ゲーに挑まんとす～",
            listOf("硬梨菜", "不二涼介"), "少年部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 47,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2023, "あの子の子ども", listOf("蒼井まもる"), "少女部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 47,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2023, "スキップとローファー", listOf("高松美咲"), "综合部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 47,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2022, "転生したらスライムだった件", listOf("伏瀬", "川上泰樹", "みっつばー"), "少年部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 46,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2022, "星降る王国のニナ", listOf("リカチ"), "少女部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 46,
        ),
        MangaAwardEntry(
            MangaAward.KODANSHA, 2022, "ハコヅメ～交番女子の逆襲～", listOf("泰三子"), "综合部门",
            "https://cstation.kodansha.co.jp/mangaip/award/25", 46,
        ),
    )

    fun years(award: MangaAward? = null): List<Int> = entries
        .filter { award == null || it.award == award }
        .map { it.year }
        .distinct()
        .sortedDescending()
}
