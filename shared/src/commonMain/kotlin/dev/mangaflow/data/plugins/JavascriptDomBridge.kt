package dev.mangaflow.data.plugins

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element

/** Context-local DOM handles; no URL loading, scripting, file reads or XML entity resolution. */
internal class JavascriptDomBridge(private val baseUrl: String) {
    private val elements = LinkedHashMap<Int, Element>()
    private val handles = HashMap<Element, Int>()
    private var operations = 0
    private var parsedChars = 0
    private var nextId = 1

    fun call(raw: String): String {
        require(++operations <= 12_000) { "漫画源 DOM 操作过多。" }
        val request = JSONObject(raw)
        val op = request.getString("op")
        if (op == "parse") {
            val html = request.getString("html")
            require(html.length <= 2 * 1024 * 1024) { "漫画源 HTML 文档过大。" }
            parsedChars += html.length
            require(parsedChars <= 4 * 1024 * 1024 && html.count { it == '<' } <= 25_000) { "漫画源 DOM 文档超出内存限制。" }
            return scriptJsonValue(handle(Ksoup.parse(html, baseUrl)))
        }
        val element = elements[request.getInt("key")] ?: throw IllegalArgumentException("漫画源 DOM 节点已失效。")
        val value: Any? = when (op) {
            "select" -> ids(element.select(selector(request)))
            "first" -> handle(element.selectFirst(selector(request)))
            "attr" -> element.attr(name(request))
            "hasAttr" -> element.hasAttr(name(request))
            "byId" -> handle(element.getElementById(name(request)))
            "byClass" -> ids(element.getElementsByClass(name(request)))
            "byTag" -> ids(element.getElementsByTag(name(request)))
            "children" -> ids(element.children())
            "siblings" -> ids(element.siblingElements())
            "parent" -> handle(element.parent())
            "next" -> handle(element.nextElementSibling())
            "previous" -> handle(element.previousElementSibling())
            "body" -> handle((element as? Document)?.body())
            "head" -> handle((element as? Document)?.head())
            "text" -> if (element.tagName() in setOf("script", "style")) element.data() else element.text()
            "ownText" -> if (element.tagName() in setOf("script", "style")) element.data() else element.ownText()
            "html" -> element.html()
            "outerHtml" -> element.outerHtml()
            "tag" -> element.tagName()
            // Mangayomi's getHref/getSrc helpers find the first matching attribute
            // in the node's own subtree. Preserve the original relative value.
            "href" -> firstAttribute(element, "href")
            "src" -> firstAttribute(element, "src")
            "img" -> firstAttribute(element, "img")
            "dataSrc" -> firstAttribute(element, "data-src")
            else -> throw IllegalArgumentException("不支持的漫画源 DOM 操作：$op")
        }
        return scriptJsonValue(value).also {
            require(it.length <= 4 * 1024 * 1024) { "漫画源 DOM 结果过大。" }
        }
    }

    private fun firstAttribute(element: Element, name: String): String =
        if (element.hasAttr(name)) element.attr(name) else element.selectFirst("[$name]")?.attr(name).orEmpty()

    private fun handle(element: Element?): Int? {
        if (element == null) return null
        handles[element]?.let { return it }
        require(elements.size < 8_192) { "漫画源 DOM 节点超出限制。" }
        val id = nextId++
        handles[element] = id
        elements[id] = element
        return id
    }

    private fun ids(values: Collection<Element>): JSONArray {
        require(values.size <= 4_096) { "漫画源 DOM 选择结果过多。" }
        return JSONArray(values.map(::handle))
    }

    private fun name(request: JSONObject): String = request.getString("name").also {
        require(it.length in 1..256) { "漫画源 DOM 属性名称无效。" }
    }

    private fun selector(request: JSONObject): String = request.getString("selector").also {
        require(it.length in 1..512) { "漫画源 CSS 选择器过长。" }
        // Native DOM regex pseudos execute outside QuickJS's interrupt handler.
        // Reject these explicitly instead of exposing unbounded native backtracking.
        require(!it.contains(":matches", true) && !REGEX_ATTRIBUTE_OPERATOR.containsMatchIn(it)) {
            "漫画源 DOM 正则选择器暂不支持。"
        }
        require(it.count { char -> char == '(' } <= 12) { "漫画源 CSS 选择器嵌套过深。" }
    }

    fun close() {
        elements.clear()
        handles.clear()
    }

    private companion object {
        val REGEX_ATTRIBUTE_OPERATOR = Regex("~\\s*=")
    }
}

/** Serialize a bounded host value without platform-specific JSON helpers. */
internal fun scriptJsonValue(value: Any?): String = JSONArray().put(value ?: JSONObject.NULL).toString().let {
    it.substring(1, it.length - 1)
}
