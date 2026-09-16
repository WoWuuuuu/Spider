package io.nekohasekai.sagernet.ui.topology

import io.nekohasekai.sagernet.database.RuleEntity

/**
 * 把一条规则**按内核的方式**渲染成条件串，用来把 Clash API `/connections` 里的
 * `rule` 字段反查回 [RuleEntity]。
 *
 * ## 为什么需要它
 *
 * `/connections` 的 `rule` 是 sing-box 自己渲染的路由层文本，形如
 * `rule_set=geosite:cn port=443 => route(HK-01)`，**既不是用户给规则起的名字，也不带 rule id**。
 * 原文在 `sing-box-src/experimental/clashapi/trafficontrol/tracker.go:66-70`：
 *
 * ```go
 * if t.Rule != nil { rule = F.ToString(t.Rule, " => ", t.Rule.Action()) } else { rule = "final" }
 * ```
 *
 * 而 `Rule_DefaultOptions`（`option/rule.go:68-115`）与 `RuleActionRouteOptions`
 * （`option/rule_action.go:171-187`）**都没有可以塞标记的字段**，所以「给规则打个 id 标记」
 * 这条路走不通 —— 那需要改 `ConfigBuilder`，也就是动内核入参。
 * 用户红线是"不得影响现有功能"，所以只能在读侧把同一个串重建出来。
 *
 * ## 为什么只比「条件」不比「动作」
 *
 * 动作那半截（`=> route(HK-01)`）里的出站 tag 来自 `ConfigBuilder.selectorName()`
 * （`fmt/ConfigBuilder.kt:101-110`），那是个**带全局状态的重名去重器**：重名会追加
 * `_-1` / `_-2`，且结果依赖建配置时各分组、各节点的遍历顺序。
 * 读侧复刻它等于把整个建配置过程再模拟一遍 —— 既脆又不值当。
 *
 * 条件那半截则是 [RuleEntity] 的**纯函数**，可以精确重建。代价是：
 * 条件完全相同的两条规则（一条用户规则、一条内核自带规则）分不出来。用 [isBuiltin] 兜住。
 *
 * ## 复刻依据
 *
 * 全部用 Go 侧 ground truth 逐例验过（`sing-box` 真源码跑出来的串，见
 * `.workbuddy-ai/validate/rule-format/`），不是照着记忆写的。要点：
 *
 * - 串 = `strings.Join(F.MapToString(allItems), " ")`，分隔符是**空格**（`rule_abstract.go:178-183`）
 * - `allItems` 是**按 `rule_default.go` 里 if 分支的源码顺序**追加的切片（不是 map），
 *   所以字段顺序完全确定，不存在 Go map 随机序问题。顺序见 [render] 里的注释。
 * - 列表 >3 项截断成 `[a b c...]`。**`domain_regex` 是唯一例外：截断但不加 `...`**
 *   （`rule_item_domain_regex.go:30-36` 少了那一截，是 sing-box 自己的不一致，照抄）。
 */
object RuleSignature {

    /**
     * [render] 的纯数据输入。
     *
     * 故意**不用 [RuleEntity]**：这样验证脚本可以脱离 Room / Android 直接编译运行
     * （见 `.workbuddy-ai/validate/rule-format/Check.kt`）。
     */
    data class Spec(
        val domains: String = "",
        val ip: String = "",
        val port: String = "",
        val sourcePort: String = "",
        val network: String = "",
        val source: String = "",
        val protocol: String = "",
        val packages: Set<String> = emptySet(),
        val config: String = "",
    )

    /**
     * 重建一条规则在 `/connections` 里会出现的**条件串**（` => ` 左边那半截）。
     *
     * @param uidOf 包名 → uid。主进程传 `PackageCache[pkg]`；拿不到就返回 null，
     *   对应的规则只是匹配不上（降级为「只显示匹配类型、不画线」），不会出错。
     * @return 条件串；**这条规则根本不会进内核**时返回 null（见 [countedFieldsPresent]）。
     */
    fun render(spec: Spec, uidOf: (String) -> Int? = { null }): String? {
        val parts = ArrayList<String>(8)

        /* --- 顺序 1：network（rule_default.go:88） ------------------------------------ */
        if (spec.network.isNotBlank()) parts += "network=" + spec.network

        /* --- 顺序 2：protocol（:98） ------------------------------------------------- */
        val protocols = splitList(spec.protocol)
        if (protocols.isNotEmpty()) parts += plainList("protocol", protocols)

        /* --- 顺序 3~6：domain/domain_suffix（:108）→ domain_keyword（:116）→ domain_regex（:121）
               注意 domain 和 domain_suffix 是**同一个** DomainItem，两段用空格拼在一起，
               所以它们是 parts 里的一个元素，不是两个。 ---------------------------------- */
        var ruleSet: List<String> = emptyList()
        var sawDomain = false
        var sawSuffix = false
        var sawKeyword = false
        var sawRegex = false

        if (spec.domains.isNotBlank()) {
            val domains = ArrayList<String>()
            val suffixes = ArrayList<String>()
            val regexes = ArrayList<String>()
            val keywords = ArrayList<String>()
            val sets = ArrayList<String>()

            splitList(spec.domains).forEach { entry ->
                when {
                    entry.startsWith("geosite:") -> sets += entry
                    entry.startsWith("full:") -> domains += entry.removePrefix("full:").lowercase()
                    entry.startsWith("domain:") -> suffixes += entry.removePrefix("domain:").lowercase()
                    entry.startsWith("regexp:") -> regexes += entry.removePrefix("regexp:").lowercase()
                    entry.startsWith("keyword:") -> keywords += entry.removePrefix("keyword:").lowercase()
                    else -> suffixes += entry.lowercase()
                }
            }

            val domainItem = StringBuilder()
            if (domains.isNotEmpty()) domainItem.append(truncList("domain", domains))
            if (suffixes.isNotEmpty()) {
                if (domainItem.isNotEmpty()) domainItem.append(' ')
                domainItem.append(truncList("domain_suffix", suffixes))
            }
            if (domainItem.isNotEmpty()) parts += domainItem.toString()
            if (keywords.isNotEmpty()) parts += truncList("domain_keyword", keywords)
            /* domain_regex：截断但**不加**省略号（照抄 sing-box 自己的不一致） */
            if (regexes.isNotEmpty()) parts += truncList("domain_regex", regexes, ellipsis = false)

            sawDomain = domains.isNotEmpty()
            sawSuffix = suffixes.isNotEmpty()
            sawKeyword = keywords.isNotEmpty()
            sawRegex = regexes.isNotEmpty()
            ruleSet = sets
        }

        /* --- 顺序 7：source_ip_cidr（:138）— 排在 ip_cidr **前面** --------------------- */
        val sourceCidrs = splitList(spec.source)
        if (sourceCidrs.isNotEmpty()) parts += truncList("source_ip_cidr", sourceCidrs)

        /* --- 顺序 8~9：ip_cidr（:151）→ ip_is_private（:159） ------------------------ */
        val cidrs = ArrayList<String>()
        val ipSets = ArrayList<String>()
        var ipIsPrivate = false
        splitList(spec.ip).forEach { entry ->
            if (entry.startsWith("geoip:")) {
                if (entry == "geoip:private") ipIsPrivate = true else ipSets += entry
            } else {
                cidrs += entry
            }
        }
        if (cidrs.isNotEmpty()) parts += truncList("ip_cidr", cidrs)
        if (ipIsPrivate) parts += "ip_is_private=true"

        /* ⚠ 既有怪癖，必须一起复刻：`makeSingBoxRule` 在 domains 和 ip 两条分支里**都**执行
           `rule_set = mutableListOf<String>()`（重新赋空表），而 ConfigBuilder 先调 domains、
           后调 ip（ConfigBuilder.kt:507 / :510）。所以一条同时写了 `geosite:x` 和 `geoip:y`
           的规则，**geosite 标记会被 ip 分支清掉**。
           这不是我该修的 bug —— 修了就改了内核行为。读侧照抄。 */
        if (spec.ip.isNotBlank()) ruleSet = ipSets

        /* --- 顺序 10~13：source_port（:164）→ source_port_range（:169）
                       → port（:177）→ port_range（:182） ------------------------------- */
        var sawPort = false
        var sawPortRange = false
        splitPorts(spec.sourcePort)?.let { (numbers, ranges) ->
            if (numbers.isNotEmpty()) parts += plainList("source_port", numbers)
            if (ranges.isNotEmpty()) parts += plainList("source_port_range", ranges)
        }
        splitPorts(spec.port)?.let { (numbers, ranges) ->
            if (numbers.isNotEmpty()) parts += plainList("port", numbers)
            if (ranges.isNotEmpty()) parts += plainList("port_range", ranges)
            sawPort = numbers.isNotEmpty()
            sawPortRange = ranges.isNotEmpty()
        }

        /* --- 顺序 14：user_id（:226） ------------------------------------------------ */
        /* uidList 在 ConfigBuilder 里是 `toHashSet()` 出来的，顺序依赖 rule.packages 的迭代序，
           读侧复刻不了。这里**排序后**输出，并在比对时把 live 串也排序（见 [normalize]）。 */
        val uids = spec.packages.mapNotNull(uidOf).filter { it >= 1000 }.distinct().sorted()
        if (uids.isNotEmpty()) parts += plainList("user_id", uids.map { it.toString() })

        /* --- 顺序 15：rule_set（:299）— **永远在最后** -------------------------------- */
        if (ruleSet.isNotEmpty()) parts += plainList("rule_set", ruleSet)

        /* 复刻 checkEmpty()（SingBoxOptionsUtil.kt:148-164）：内核**只会**把下面这些字段
           非空的规则加进 route.rules。所以一条只写了 network / protocol / sourcePort
           的规则根本不会出现在 /connections 里 —— 重建出来反而会造成误匹配。
           （`geoip:private` 也只设 ip_is_private，不在 checkEmpty 的名单里 → 同样会被丢掉。） */
        val counted = cidrs.isNotEmpty() || sawDomain || sawSuffix || sawKeyword || sawRegex ||
            ruleSet.isNotEmpty() || sawPort || sawPortRange || sourceCidrs.isNotEmpty() ||
            uids.isNotEmpty()
        if (!counted) return null

        return parts.joinToString(" ")
    }

    /**
     * 从 live 的 `rule` 串里切出条件部分。
     *
     * 用 `lastIndexOf`：条件里是 `key=value` token，理论上不会出现 ` => `，
     * 但出站 tag 是用户可控的名字（可能带空格），从右边切更稳。
     */
    fun conditionsOf(liveRule: String): String {
        val i = liveRule.lastIndexOf(SEPARATOR)
        return if (i >= 0) liveRule.substring(0, i) else liveRule
    }

    /** `/connections` 里没有任何规则命中时，tracker 直接写死这个（不是规则名） */
    const val FINAL = "final"

    private const val SEPARATOR = " => "

    /**
     * 内核自带的规则 —— 它们不来自任何 [RuleEntity]，条件串却可能和用户规则撞车。
     *
     * 来源 `ConfigBuilder.kt:687-707`（都是 `route.rules.add` 直接构造的）：
     * `port=53`、`protocol=dns`、`ip_is_private=true`（bypassLanInCore 开时）、
     * 组播拦截。链式代理还会加 `inbound=<tag>`。
     */
    private val BUILTIN = setOf(
        "port=53",
        "protocol=dns",
        "ip_is_private=true",
        "source_ip_cidr=[224.0.0.0/3 ff00::/8] ip_cidr=[224.0.0.0/3 ff00::/8]",
    )

    /**
     * 这条 live 规则是不是内核自带的？是的话就**不要**归到某条用户规则头上
     * —— 宁可显示「内置规则」也不能张冠李戴。
     */
    fun isBuiltin(conditions: String): Boolean =
        conditions in BUILTIN || conditions.startsWith("inbound=")

    /**
     * 把 `user_id=[...]` 里的 uid 排序，让比对不受 Set 迭代序影响。
     *
     * 只处理 user_id —— 其他列表都来自用户在输入框里敲的字符串，顺序是用户自己定的，
     * 两边会自然一致，不需要也不应该动。
     */
    fun normalize(conditions: String): String {
        val start = conditions.indexOf("user_id=[")
        if (start < 0) return conditions
        val end = conditions.indexOf(']', start)
        if (end < 0) return conditions
        val inner = conditions.substring(start + "user_id=[".length, end)
        val sorted = inner.split(' ').filter { it.isNotEmpty() }.sorted()
        return conditions.substring(0, start) + "user_id=[" + sorted.joinToString(" ") +
            conditions.substring(end)
    }

    // ---------------------------------------------------------------- 渲染小工具

    /**
     * 复刻 `moe.matsuri.nb4a.utils.KotlinUtil.listByLineOrComma()`：
     * `this.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }`
     *
     * 这里重写而不是直接调，是为了让本文件**不依赖 Android**，
     * 从而能被纯 JVM 的验证脚本编译。行为一致性由验证脚本兜底。
     */
    private fun splitList(value: String): List<String> =
        value.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }

    /** `port` / `sourcePort`：带 `:` 的进 range，纯数字进 port（ConfigBuilder.kt:515-536） */
    private fun splitPorts(value: String): Pair<List<String>, List<String>>? {
        if (value.isBlank()) return null
        val numbers = ArrayList<String>()
        val ranges = ArrayList<String>()
        splitList(value).forEach {
            if (it.contains(':')) {
                ranges += it
            } else if (it.toIntOrNull() != null) {
                numbers += it
            }
        }
        return numbers to ranges
    }

    /** 单值/多值都用 `[a b]`，**不截断**（PortItem / NetworkItem / ProtocolItem / UserIdItem） */
    private fun plainList(key: String, values: List<String>): String =
        if (values.size == 1) "$key=${values[0]}" else "$key=[" + values.joinToString(" ") + "]"

    /** 单值裸写、多值 `[a b]`、超过 3 项 `[a b c...]`（DomainItem / IPCIDRItem / DomainKeywordItem） */
    private fun truncList(key: String, values: List<String>, ellipsis: Boolean = true): String =
        when {
            values.size == 1 -> "$key=${values[0]}"
            values.size > 3 -> "$key=[" + values.take(3).joinToString(" ") + if (ellipsis) "...]" else "]"
            else -> "$key=[" + values.joinToString(" ") + "]"
        }
}

/**
 * [RuleEntity] → [RuleSignature.Spec]。
 *
 * ⚠ 必须放在**文件顶层**，不能放进 `object RuleSignature` 里 ——
 * 放进去就变成"成员扩展函数"，只有 `RuleSignature` 作用域内才看得见，
 * `TopologyRepository` 里 `rule.toSpec()` 会直接编译不过。
 */
fun RuleEntity.toSpec(): RuleSignature.Spec = RuleSignature.Spec(
    domains = domains,
    ip = ip,
    port = port,
    sourcePort = sourcePort,
    network = network,
    source = source,
    protocol = protocol,
    packages = packages,
    config = config,
)

/** ① chip 副信息里「命中了哪一类条件」 —— 纯文本判断，重建失败时也能显示 */
enum class RuleMatchKind {
    GEOSITE,
    GEOIP,
    DOMAIN,
    APP,
    IP,
    PORT,
    NETWORK,
    PROTOCOL,
    BUILTIN,
    UNKNOWN,
    ;

    companion object {
        /**
         * 按**优先级**取一个最具体的类别，而不是「有几种就报混合」。
         *
         * 规则通常同时有多个条件（`geosite:cn port=443`），报「混合」对用户毫无信息量；
         * 报「域名集匹配」才是他真正想知道的那件事。
         */
        fun of(conditions: String): RuleMatchKind {
            if (conditions.isEmpty()) return UNKNOWN
            if (RuleSignature.isBuiltin(conditions)) return BUILTIN
            /* geosite:/geoip: 只可能出现在 rule_set 的值里，所以不用管 `rule_set=`
               是单值还是 `[a b]` 形式 —— 直接找前缀最稳。 */
            return when {
                conditions.contains("geosite:") -> GEOSITE
                conditions.contains("geoip:") -> GEOIP
                conditions.contains("domain") -> DOMAIN
                conditions.contains("user_id=") -> APP
                conditions.contains("ip_cidr=") || conditions.contains("ip_is_private=") -> IP
                conditions.contains("port=") || conditions.contains("port_range=") -> PORT
                conditions.contains("network=") -> NETWORK
                conditions.contains("protocol=") -> PROTOCOL
                else -> UNKNOWN
            }
        }
    }
}
