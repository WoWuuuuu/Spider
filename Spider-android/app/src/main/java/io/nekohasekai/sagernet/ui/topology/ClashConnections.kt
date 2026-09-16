package io.nekohasekai.sagernet.ui.topology

import org.json.JSONArray
import org.json.JSONObject

/**
 * Clash API 的只读数据模型。
 *
 * 字段名**逐字**来自 sing-box 的 `MarshalJSON`，不是照 Clash 文档猜的：
 *
 * - `/connections`：`experimental/clashapi/trafficontrol/manager.go:175-182`（外层）
 *   与 `trafficontrol/tracker.go:42-86`（每条连接）
 * - `/rules`：`experimental/clashapi/rules.go:21-25`
 *
 * ⚠ 这个 API 是**内核进程**里的，主进程只能走 HTTP（见 `docs/spider-newui-plan.md` §0.1）。
 */

/** `GET /connections` 的响应 */
data class ClashSnapshot(
    val uploadTotal: Long,
    val downloadTotal: Long,
    val memory: Long,
    val connections: List<ClashConnection>,
) {
    companion object {
        val EMPTY = ClashSnapshot(0L, 0L, 0L, emptyList())
    }
}

/** `/connections` 里的一条连接 */
data class ClashConnection(
    val id: String,
    val upload: Long,
    val download: Long,
    /** RFC3339，tracker 直接写 `time.Time`。阶段 3 只透传，详情浮层在阶段 4 解析 */
    val start: String,
    /** 出站链路（tag 列表）。链式代理时长度 > 1 */
    val chains: List<String>,
    /** `"<条件> => <动作>"`，没命中任何规则时是 `"final"` */
    val rule: String,
    val metadata: ClashMetadata,
) {
    /** ① chip 主信息：有域名用域名，否则退回目标 IP（与原型 `inView()` 一致） */
    val host: String get() = metadata.host.ifBlank { metadata.destinationIp }

    /** 端点文案 `host:port` */
    val endpoint: String
        get() = if (metadata.destinationPort.isBlank()) host else "$host:${metadata.destinationPort}"

    val total: Long get() = upload + download
}

data class ClashMetadata(
    val network: String,
    /** `type` 字段是 `"<InboundType>/<Inbound>"`，无 Inbound 时只有前半截 */
    val inboundType: String,
    val sourceIp: String,
    val destinationIp: String,
    val sourcePort: String,
    val destinationPort: String,
    val host: String,
    /** tracker 会写成 `"com.foo.bar (10123)"` 或裸 uid（tracker.go:49-63） */
    val processPath: String,
) {
    /** 只要包名那截 —— 括号里是 uid / 用户名，对用户没意义 */
    val appPackage: String get() = processPath.substringBefore(" (").trim()
}

/** `GET /rules` 的一条 —— 顺序**就是内核的匹配优先级** */
data class ClashRuleEntry(
    val type: String,
    /** 条件串（`rule.String()`），与 `/connections` 里 ` => ` 左边那半截相同 */
    val payload: String,
    /** 动作串，如 `route(HK-01)` / `reject()` / `hijack-dns()` */
    val proxy: String,
)

/**
 * 解析。**一律不抛异常** —— 内核返回什么形状的 JSON 都不该让新 UI 崩掉，
 * 解析失败就当作"拿不到数据"，走降级态。
 */
object ClashJson {

    fun parseSnapshot(body: String): ClashSnapshot? = runCatching {
        val root = JSONObject(body)
        val array = root.optJSONArray("connections") ?: JSONArray()
        val list = ArrayList<ClashConnection>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            list += ClashConnection(
                id = item.str("id"),
                upload = item.optLong("upload", 0L),
                download = item.optLong("download", 0L),
                start = item.str("start"),
                chains = item.optJSONArray("chains").stringList(),
                rule = item.str("rule"),
                metadata = parseMetadata(item.optJSONObject("metadata")),
            )
        }
        ClashSnapshot(
            uploadTotal = root.optLong("uploadTotal", 0L),
            downloadTotal = root.optLong("downloadTotal", 0L),
            memory = root.optLong("memory", 0L),
            connections = list,
        )
    }.getOrNull()

    fun parseRules(body: String): List<ClashRuleEntry>? = runCatching {
        val array = JSONObject(body).optJSONArray("rules") ?: return@runCatching emptyList()
        val list = ArrayList<ClashRuleEntry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            list += ClashRuleEntry(
                type = item.str("type"),
                payload = item.str("payload"),
                proxy = item.str("proxy"),
            )
        }
        list
    }.getOrNull()

    private fun parseMetadata(obj: JSONObject?): ClashMetadata = ClashMetadata(
        network = obj.str("network"),
        inboundType = obj.str("type").substringBefore('/'),
        sourceIp = obj.str("sourceIP"),
        destinationIp = obj.str("destinationIP"),
        sourcePort = obj.str("sourcePort"),
        destinationPort = obj.str("destinationPort"),
        host = obj.str("host"),
        processPath = obj.str("processPath"),
    )

    /**
     * `org.json` 的 `optString` 对**显式 JSON null** 会返回字符串 `"null"`，
     * 所以这里统一抹掉，免得界面上冒出 "null"。
     */
    private fun JSONObject?.str(name: String): String {
        if (this == null) return ""
        val value = optString(name, "")
        return if (value == "null") "" else value
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        val list = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i, "")
            if (value.isNotEmpty() && value != "null") list += value
        }
        return list
    }
}
