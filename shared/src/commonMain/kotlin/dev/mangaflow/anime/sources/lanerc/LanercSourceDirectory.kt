package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.anime.sources.CYCITY_HOME
import dev.mangaflow.anime.sources.CYCITY_SOURCE_ID

/** Reference directory observed in the public config v8 (2026-11-05); never a playability claim. */
enum class LanercSourceKind { VIDEO, BROWSE, COMIC, DANMAKU, UNKNOWN }

/** Reasons established by the package audit or an anonymous connection check. */
internal fun lanercAccessNote(id: String): String? = when (id) {
    "anime-lanerc-kumanxing", "anime-lanerc-yulu" -> "目录和读页需要来源方提供授权接口。"
    "anime-lanerc-xfy" -> "目录要求设备验证，读页要求账号及会员资格。"
    "anime-lanerc-zhg" -> "公开目录连接未收到响应，读页需要来源方提供图链。"
    "anime-lanerc-jcy" -> "发布包使用 ENC1 格式，需来源方提供兼容包。"
    else -> null
}

data class LanercSourceDescriptor(
    val id: String,
    val name: String,
    val kind: LanercSourceKind,
    val scriptUrl: String,
    val homeUrl: String,
    val aliases: List<String>,
    val functions: Set<String>,
    val observedDigest: String,
    val paginationNote: String,
)

data class LanercSourceListing(
    val id: String,
    val name: String,
    val kind: LanercSourceKind,
    val scriptUrl: String,
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val version: String = "",
    val digest: String = "",
    val previousVersion: String? = null,
    val error: String? = null,
    val aliases: List<String> = emptyList(),
    val capabilities: Set<String> = emptySet(),
    val optionsJson: String = "{}",
    val installable: Boolean = true,
    val revision: String? = null,
    val hasPrivateOptions: Boolean = false,
    val automaticPreparation: Boolean = false,
    val preparationUnavailableReason: String? = null,
    val bundledAlternativeId: String? = null,
)

internal data class LanercInstalledPackage(
    val id: String,
    val code: String,
    val digest: String,
    val version: String,
    val enabled: Boolean,
    val revision: String,
    val optionsJson: String = "{}",
    val previousCode: String? = null,
    val previousDigest: String? = null,
    val previousVersion: String? = null,
    val optionsKey: String = "",
)

/** Only public script URLs and semantic metadata are packaged; third-party script code is not bundled. */
val LANERC_SOURCE_DIRECTORY: List<LanercSourceDescriptor> = listOf(
    LanercSourceDescriptor(CYCITY_SOURCE_ID, "次元城 · 内建", LanercSourceKind.VIDEO, CYCITY_HOME, CYCITY_HOME,
        emptyList(), setOf("search", "detail", "play", "latest", "login"), "", "公开目录和剧集；播放需要本人次元城账号"),
    LanercSourceDescriptor("anime-native-jinpai", "金牌 · 内建", LanercSourceKind.VIDEO, "https://y2s52n7.com/", "https://y2s52n7.com/", emptyList(), setOf("search", "detail", "play", "latest"), "", "匿名签名协议；真实动漫更新、剧集 ID 与清晰度；关键词仅第一页"),
    LanercSourceDescriptor("anime-native-yzx", "yzx · 内建", LanercSourceKind.VIDEO, "https://js.trgfd.cn/", "https://js.trgfd.cn/", emptyList(), setOf("search", "detail", "play", "latest"), "", "优可 3.0 公开协议；实际分页、独立设备会话与真实线路；平台解码能力以实际结果为准"),
    LanercSourceDescriptor("anime-native-ani", "AniCh · 内建", LanercSourceKind.VIDEO, "https://anich.sends.eu.org/", "https://anich.sends.eu.org/", emptyList(), setOf("search", "detail", "play"), "", "匿名原生协议；逐集实际线路，不推断未返回的集数或替换选定线路"),
    // Original native adapters. Distinct IDs retain legacy script title/group/episode ownership.
    LanercSourceDescriptor("anime-native-akianime", "AkiAnime · 内建", LanercSourceKind.VIDEO, "https://www.akianime.com/", "https://www.akianime.com/", emptyList(), setOf("search", "detail", "play"), "", "仅第一页关键词搜索；匿名公开线路，验证或加密线路不保证可用"),
    LanercSourceDescriptor("anime-native-dmbus", "动漫巴士 · 内建", LanercSourceKind.VIDEO, "https://dmbus.cc/", "https://dmbus.cc/", emptyList(), setOf("search", "detail", "play"), "", "关键词 page 分页；匿名公开线路，动态加密线路不保证可用"),
    LanercSourceDescriptor("anime-lanerc-douban", "豆瓣发现", LanercSourceKind.BROWSE, "https://js.z1i.cn/js/douban.js", "https://m.douban.com/", listOf("AuvFun"), setOf("config","categories","homeSections","search","searchFiltered","detail","play"), "c9ab5e9fe51147ceae6d0811cef52c359b21df1908405ac262a0a6ddb263c7d7", "主列表start/count分页；仅第一页空回退旧API；关键词按tag查询"),
    LanercSourceDescriptor("anime-lanerc-lanerc", "Lanerc", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/lanerc.js", "https://v3.yyghk.com/", listOf("lanerc","Auv源1"), setOf("config","homeSections","search","categories","searchFiltered","detail","related","danmu","play","guestCaptcha","guestBind","login"), "5a039992049d974a1e3c197475a1f301d18f0d9d2b4defa6c204601347b986b5", "关键词page未传给HTTP；分类page参数；真实翻页未请求"),
    LanercSourceDescriptor("anime-lanerc-jinpai", "金牌", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/jinpaiapp.js", "https://y2s52n7.com/", listOf("Auv源2"), setOf("config","categories","search","searchFiltered","homeSections","detail","play"), "6f924a30e23fac63681e3d4d23ee5adb5ad76c10459b2e063d442a7a96286dfe", "关键词固定pageNum=1/pageSize=8；列表pageNum参数"),
    LanercSourceDescriptor("anime-lanerc-guazi", "瓜子", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/guazi.js", "https://apinew.uozvr.com/", listOf("Auv源3"), setOf("homeSections","categories","search","searchFiltered","detail","play"), "b1de6ca1f34b47d11d351ee540445da321d547902b08502edfe062fa501e2be9", "关键词page>1返回空；分类page/pageSize参数"),
    LanercSourceDescriptor("anime-lanerc-sx", "师兄", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/%E5%B8%88%E5%85%84.js", "https://dsxys.com/", listOf("Auv源4"), setOf("config","categories","homeSections","search","searchFiltered","detail","play"), "53873698838e6ebde92978c52f87667b93e7f8938d8d17aeb8388a1470cd4e02", "关键词页码实际传入 AppV7 search.pg；返回空页作为结束，未实机验证"),
    LanercSourceDescriptor("anime-lanerc-yzx", "yzx", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/yzx.js", "https://ss.trgfd.cn/", listOf("Auv源5"), setOf("categories","homeSections","search","searchFiltered","detail","play"), "6056afbb33ffbc7bbb71e2632a31c66fe6d575389983b4f2076dcae599a14e16", "关键词 page 实际传入搜索接口；首页/分类使用原页码"),
    LanercSourceDescriptor("anime-lanerc-jcy", "囧次元", LanercSourceKind.UNKNOWN, "https://js.z1i.cn/js/jiong.js", "https://js.z1i.cn/", listOf("Auv源6"), setOf(), "4f05dc3ecce026120ce5a80035e9adf13a6df56a30a3d50054fda9172c2a3993", "原稿是 ENC1 加密包；解包协议未接入，不冒充已内置视频源"),
    LanercSourceDescriptor("anime-lanerc-akianime", "AkiAnime", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/akianime.js", "https://www.akianime.com/", listOf("Auv源7"), setOf("categories","searchFiltered","search","homeSections","detail","play"), "8dc50db5f712e0ea942c2532d64634500201ba9c5ab9856da9473a14dfa85417", "关键词仅第一页；分类列表page参数"),
    LanercSourceDescriptor("anime-lanerc-lmm85", "LMM85 · 路漫漫", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/lmm85.js", "https://www.lmm85.com/", listOf("Auv源8", "路漫漫在线动漫"), setOf("homeSections","categories","search","searchFiltered","detail","related","play"), "05203f71d6672d7d10bf4d946268fc43a4f8f48be307c120c8a44e025f91d787", "有page>1分支；本轮不将分支自动等同“支持/不支持分页”"),
    LanercSourceDescriptor("anime-lanerc-gugu", "柚子", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/%E6%9F%9A%E5%AD%90.js", "https://js.z1i.cn/", listOf("Auv源9"), setOf("config","categories","homeSections","search","searchFiltered","detail","play"), "323079886b1ffb0b7023545471360cb2851c19af524a0f33bcb655f133376e1f", "关键词页码实际传入 AppV7 search.pg；返回空页作为结束，未实机验证"),
    LanercSourceDescriptor("anime-lanerc-dmbus", "DMBus", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/dmbus.js", "https://dmbus.cc/", listOf("Auv源10"), setOf("categories","homeSections","search","searchFiltered","detail","play"), "1268c4dce7b4e79d36a0516331f69eb472694f0591954eff9d621370ec7fdeb3", "有page>1分支；本轮不将分支自动等同“支持/不支持分页”"),
    LanercSourceDescriptor("anime-lanerc-manguo", "追剧", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/%E8%BF%BD%E5%89%A7.js", "https://js.z1i.cn/", listOf("Auv源11"), setOf("config","categories","homeSections","search","searchFiltered","detail","play"), "177ecf39473b12aa042cdbbad203af47e3cfeacb54520d55da9a88b704dd546c", "关键词页码实际传入 AppV7 search.pg；返回空页作为结束，未实机验证"),
    LanercSourceDescriptor("anime-lanerc-23", "23", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/23.js", "https://js.z1i.cn/", listOf("Auv源12"), setOf("categories","search","searchFiltered","homeSections","detail","play"), "33140ab5c9ef1a79c3aa08f9ada6aaacdc158b8a9d99916af5acf479e6f9b29d", "独立会话与 protobuf 请求；关键词实际 page 分页，固定 HTTP API"),
    LanercSourceDescriptor("anime-lanerc-kumanxing", "酷漫星", LanercSourceKind.COMIC, "https://js.z1i.cn/comic/kumanxing.js", "https://d9zfb53b.lstool.xyz/", listOf("Auv漫画"), setOf("config","categories","homeSections","search","searchFiltered","detail","related","play"), "295ff0dcd5980e8511c0b8469872e4afbe5f74071702965984deb85184a70dfd", "有page>1分支；本轮不将分支自动等同“支持/不支持分页”"),
    LanercSourceDescriptor("anime-lanerc-danmu_b61mt3", "弹弹play", LanercSourceKind.DANMAKU, "https://js.z1i.cn/danmu/dandanplay_danmu.js", "https://dandan.emmmm.eu.org/", listOf("dd"), setOf("danmu","search"), "e903d7ac7deff1bbfc8b4093ccac61d24f112cfeab39ff4c72fd1bc4a5888790", "未知；未从本轮有限语义索引确认分页"),
    LanercSourceDescriptor("anime-lanerc-xfy", "咸番鸭", LanercSourceKind.COMIC, "https://js.z1i.cn/comic/xfy.js", "https://gurgle-unvalued.qrqrs.com/", listOf("Auv漫画2"), setOf("config","homeSections","categories","searchFiltered","search","detail","play"), "a7ec4c245501dd7bbc823ee9191b6f726ae429b77950e36a8f2c78105c1888d8", "有page>1分支；本轮不将分支自动等同“支持/不支持分页”"),
    LanercSourceDescriptor("anime-lanerc-yulu", "yulu", LanercSourceKind.COMIC, "https://js.z1i.cn/comic/yulu.js", "https://js.z1i.cn/", listOf("Auv漫画3"), setOf("config","categories","searchFiltered","search","homeSections","detail","play"), "5f4679920bf19c3777d5a2af1a9229d86f1969fcbd06550cc2717eb0e381d914", "有page>1分支；本轮不将分支自动等同“支持/不支持分页”"),
    LanercSourceDescriptor("anime-lanerc-ani", "AniCh", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/anich.js", "https://anich.sends.eu.org/", listOf("Auv源13"), setOf("config","categories","homeSections","search","searchFiltered","detail","play","danmu"), "2bbbebb9a01b83ec9762018badc32b51dff54487060f915198dd2fc0c8673027", "skip=(page-1)*30；筛选同skip，未验证每页数量"),
    LanercSourceDescriptor("anime-lanerc-ain", "Cain", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/cain.js", "https://js.z1i.cn/", listOf("Auv源14"), setOf("categories","homeSections","search","searchFiltered","detail","play"), "5b34560f7ffb4b17be9877bf21579a10afef75139c13cc56324b91f835044e8a", "关键词实际 page/size=27；公开 HTTP 目录引导，业务地址仍要求 HTTPS"),
    LanercSourceDescriptor("anime-lanerc-nmeng", "柠檬", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/%E6%9F%A0%E6%AA%AC.js", "https://js.z1i.cn/", listOf("Auv源15"), setOf("config","categories","homeSections","search","searchFiltered","detail","play"), "c00719bb8aca5d66a8aea123269bcdd2860fe6f1329e9b2f7cfc7ab5bd9e19df", "关键词页码实际传入 AppV7 search.pg；返回空页作为结束，未实机验证"),
    LanercSourceDescriptor("anime-lanerc-sd", "闪电", LanercSourceKind.VIDEO, "https://js.z1i.cn/js/%E9%97%AA%E7%94%B5.js", "https://u.yyxdmn.com/", listOf("Auv源16"), setOf("categories","search","searchFiltered","detail","play","config"), "985c0542acefc3afd13519dde396268fab4bab667ee407e9d4ef688d97138fbf", "search对象有pn字段；页码映射/尾页语义未进一步确认"),
    LanercSourceDescriptor("anime-lanerc-zhg", "漫时光", LanercSourceKind.COMIC, "https://js.z1i.cn/comic/manshiguang.js", "https://comicoss.oss-cn-shenzhen.aliyuncs.com/", listOf("Auv漫画4"), setOf("config","categories","homeSections","search","searchFiltered","detail","play"), "adef1dd772a81fe914ca41b189fd8f0d518d96efd1dc7f4d23c118be13b69121", "有分页参数引用；参数和尾页具体语义未知"),
    LanercSourceDescriptor("anime-lanerc-07", "零七", LanercSourceKind.VIDEO, "https://js.z1i.cn/ek/%E9%9B%B6%E4%B8%83.js", "https://js.z1i.cn/", listOf("Auv源16"), setOf("config","categories","homeSections","search","searchFiltered","detail","play"), "7505abdae0dcdab1fad683e8c028bb41e4c5986c2c2ebafff88640e83ce3ecd6", "关键词页码实际传入 AppV7 search.pg；返回空页作为结束，未实机验证"),
)
