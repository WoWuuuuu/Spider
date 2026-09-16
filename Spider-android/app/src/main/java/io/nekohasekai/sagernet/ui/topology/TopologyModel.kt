package io.nekohasekai.sagernet.ui.topology

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.PackageCache

/** 卡片的语义类型 —— 只影响配色，不影响布局 */
enum class TopologyKind {
    /** 走代理节点 */
    PROXY,

    /** 直连 / 绕过 */
    DIRECT,

    /** 拦截 */
    BLOCK,

    /** 规则没指定出站 */
    UNSET,

    /** 规则指向了一个已经不存在的节点（孤儿规则） */
    MISSING,
}

/**
 * 拓扑图里的一张卡。
 *
 * [id] 必须**稳定**：同一个规则/节点在两次刷新之间 id 不变，
 * 这样阶段 2 的落位动画才能识别出「还是那张卡，只是挪了位置」。
 */
data class TopologyCard(
    val id: String,
    val layer: Int,
    val title: String,
    val subtitle: String,
    val kind: TopologyKind,
)

/**
 * 连线属于哪一段 —— 决定配色，以及走哪个「走廊」。
 *
 * ①→② 和 ②→③ 虽然几何上都是「从上往下」，但颜色来源不同：
 * 原型里 ①→② 固定用 lavender（入站色），②→③ 才跟着出站目标变色。
 */
enum class TopologyEdgeStage { INBOUND, OUTBOUND }

/**
 * 一条连线。
 *
 * 两个 id 都是**卡片 id**（`in-<host>` / `ru-<ruleId>` / `ou-<profileId>` 等），不是数据层主键 ——
 * 这样阶段 2 的落位动画、阶段 4 的高亮都能直接拿它当稳定标识用。
 */
data class TopologyEdge(
    val fromId: String,
    val toId: String,
    /** 目标卡的类型，决定连线颜色（②→③ 跟着**出站目标**走，而不是规则类型） */
    val kind: TopologyKind,
    /** 目标节点已被删除 → 虚线 + 低透明度 */
    val dashed: Boolean = false,
    val stage: TopologyEdgeStage = TopologyEdgeStage.OUTBOUND,
)

/**
 * ① 层的一朵「入站云」—— 按 host 聚合后的实时连接。
 *
 * 阶段 3 只做**手动下拉刷新**，所以这里的 [upload] / [download] 是**累计值**，
 * 不是速率。要做真速率至少得两次采样（以及自动轮询），那是个还没定的能耗决策。
 */
data class TopologyInbound(
    val id: String,
    /** chip 主信息：域名，取不到就退回目标 IP */
    val label: String,
    /** chip 地区/出口标签（如 "US", "HK", "直连" 等） */
    val proxyLabel: String = "",
    /** 流量相对权重大小（0.80f ~ 1.25f），驱动框框整体大小变化 */
    val sizeHint: Float = 1.0f,
    /** chip 副信息前半：`host:port` */
    val endpoint: String,
    /** 进程/包名，可能为空 */
    val appPackage: String,
    val upload: Long,
    val download: Long,
    /** 命中的规则卡 id（`ru-<ruleId>`）。规则没画出来 / 没匹配上 → null，不画线 */
    val ruleCardId: String?,
    /** 命中的规则名。即使规则卡没画出来也可能有值（详情浮层要用） */
    val ruleName: String?,
    /** 从 live 规则串**文本**判断的匹配类型 —— 重建失败时也一定有值 */
    val matchKind: RuleMatchKind,
    /**
     * 这个 host 下**最早**那条连接的建立时间（RFC3339 原样）。
     * 空串表示内核没给（老版本可能没有这个字段）。
     */
    val startAt: String = "",
    /**
     * 这个 chip 合并了几条连接。
     *
     * 必须显示出来：chip 是按 **host** 聚合的，一个域名底下可能挂着好几条连接。
     * 只说「建立时间」而不说「这是 5 条里最早的一条」，就是在误导。
     */
    val connectionCount: Int = 1,
) {
    val total: Long get() = upload + download

    /**
     * 视觉展示文本：「地区·标题」格式，如 "US·google", "HK·youtube", "直连·bilibili"
     */
    val displayLabel: String
        get() {
            val main = TopologyModelHelper.extractMainDomain(label)
            return if (proxyLabel.isNotBlank()) {
                "$proxyLabel·$main"
            } else {
                main
            }
        }
}

/** ① 层的状态 —— 对应 plan §1.3 里跟 ① 有关的那几条降级态 */
enum class TopologyInboundState {
    /** 有活动连接 */
    OK,

    /** Clash API 没开。这是**配置状态**，界面上给一个跳设置的入口，不弹错 */
    DISABLED,

    /** 连不上（多半是内核没跑）或返回了看不懂的东西 */
    UNAVAILABLE,

    /** 连上了，但没有活动连接 */
    IDLE,
}

/**
 * 某一时刻的只读数据快照。UI 层只消费它，不直接碰数据库。
 *
 * [inbounds] 来自 Clash API（阶段 3），②③ 来自数据库（阶段 1）。
 */
data class TopologySnapshot(
    val rules: List<TopologyCard> = emptyList(),
    val ruleOverflow: Int = 0,
    val outbounds: List<TopologyCard> = emptyList(),
    val outboundOverflow: Int = 0,
    val edges: List<TopologyEdge> = emptyList(),
    val enabledRuleTotal: Int = 0,
    val nodeTotal: Int = 0,
    val clashApiEnabled: Boolean = false,
    val inbounds: List<TopologyInbound> = emptyList(),
    val inboundOverflow: Int = 0,
    val inboundState: TopologyInboundState = TopologyInboundState.DISABLED,
    /** 内核侧累计流量（所有连接），仅用于底部统计文案 */
    val uploadTotal: Long = 0L,
    val downloadTotal: Long = 0L,
) {
    val isEmpty: Boolean get() = rules.isEmpty() && outbounds.isEmpty()
}

/**
 * 只读地把现有数据层翻译成拓扑卡片。
 *
 * 这里**不写任何数据** —— 阶段 1~4 的写操作只有「长按规则卡换出口」一条，
 * 且走的是 ProfileManager + needReload()，不在这里。
 */
object TopologyModelHelper {

    fun extractMainDomain(host: String): String {
        if (host.isBlank()) return host
        if (host.firstOrNull()?.isDigit() == true || host.contains(':')) {
            return host
        }
        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size <= 2) {
            return parts.firstOrNull() ?: host
        }
        val secondToLast = parts[parts.size - 2].lowercase()
        val last = parts.last().lowercase()
        val isCcTldSub = secondToLast in setOf("com", "co", "org", "net", "edu", "gov") && last.length == 2
        return if (isCcTldSub && parts.size >= 3) {
            parts[parts.size - 3]
        } else {
            parts[parts.size - 2]
        }
    }

    fun extractRegion(name: String): String {
        if (name.isBlank()) return "节点"
        val flagMap = mapOf(
            "🇺🇸" to "US", "🇭🇰" to "HK", "🇯🇵" to "JP", "🇸🇬" to "SG", "🇹🇼" to "TW",
            "🇬🇧" to "UK", "🇰🇷" to "KR", "🇩🇪" to "DE", "🇨🇦" to "CA", "🇦🇺" to "AU",
            "🇫🇷" to "FR", "🇷🇺" to "RU", "🇮🇳" to "IN", "🇲🇾" to "MY"
        )
        for ((flag, code) in flagMap) {
            if (name.contains(flag)) return code
        }
        val bracketMatch = Regex("[\\[(【]([A-Za-z]{2,3}|[\u4e00-\u9fa5]{2})[\\])】]").find(name)
        if (bracketMatch != null) {
            return bracketMatch.groupValues[1].uppercase()
        }
        val regionCodes = listOf("US", "HK", "JP", "SG", "TW", "UK", "KR", "DE", "CA", "AU", "FR", "RU", "IN", "MY")
        for (code in regionCodes) {
            if (Regex("(^|[^A-Za-z])$code([^A-Za-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
                return code.uppercase()
            }
        }
        val cnRegions = listOf(
            "美国" to "US", "香港" to "HK", "日本" to "JP", "新加坡" to "SG",
            "台湾" to "TW", "英国" to "UK", "韩国" to "KR", "德国" to "DE"
        )
        for ((cn, code) in cnRegions) {
            if (name.contains(cn)) return code
        }
        val clean = name.trim().take(4).trim()
        return if (clean.isNotBlank()) clean else "节点"
    }
}

object TopologyRepository {

    /** ② 层最多显示几张规则卡（收起态，超出的折成「+N 更多」） */
    const val RULE_MAX = 5

    /**
     * ② 层**展开**后最多显示几张规则卡。
     *
     * ② 层是**单排弧形**（`placeArc` 不换行），所以「展开」不是加行、而是把卡片压窄，
     * 上限由宽度反推：② 区宽 342 原型单位，`placeArc` 的间隙是 n>5 时 4 单位，
     * 单张卡宽的硬下限是 34 单位 ——
     * `(n-1)*4 + n*34 ≤ 342` → `38n ≤ 346` → **n ≤ 9**。
     *
     * 但 34 单位宽（约理想值 62 的 55%）标题只剩两三个字符，没有可读性，
     * 所以取 **8**：此时单张 ≈ 39 单位，还能认出标题前几个字。
     * 6 张是 54 单位、7 张是 45 单位，都还舒服；8 是「还能看」的边界。
     */
    const val RULE_MAX_EXPANDED = 8

    /** ③ 层最多显示几张出站卡（收起态） */
    const val OUT_MAX = 3

    /**
     * ③ 层**展开**后最多显示几张出站卡。
     *
     * 为什么是 6：③ 区在原型坐标里是 673→808（高 135 单位），卡高 46、行距 8，
     * `placeScattered` 的行数上限 = ⌊(135-46)/(46+8)⌋+1 = **2 行**；
     * 每行容量上限是 `capHi = 3` → 2×3 = **6**。
     *
     * 这两个数都是按 viewBox（366×820）的**比例**换算的，所以任何屏幕都是 2 行 × 3 张，
     * 不会因为屏幕高就排得下更多。再往上加，`placeScattered` 会画出 ③ 区外、
     * 压到屏幕底部 —— 所以 6 是硬上限，不是随手取的值。
     */
    const val OUT_MAX_EXPANDED = 6

    /**
     * ① 层最多显示几朵入站云。
     *
     * 阶段 3 定的是 18，阶段 4 想提到 26 —— **实测提不上去，22 是硬上限**。
     *
     * 原因不是「面积不够」，而是**每行放得下几张**：chip 宽度上限是 120 个原型单位
     * （`chipWidth` 封顶）× 1.15 景深预留 = 138 单位，而 ① 区宽只有 342 单位 ——
     * 最宽的标签每行只放得下 2 张。26 张就需要 13 行，而 ① 区高度
     * （367/820）按 `22×1.15 + 8` 的行距只排得下约 11 行。
     *
     * 验证（`.workbuddy-ai/validate/topology-layout/`，4 种屏幕 × 16 颗种子，按**绘制矩形**判）：
     * - 混合宽度标签：第 24 张起放不下
     * - 全宽标签（最坏情况）：第 23 张起放不下
     * → 上限取 **22**（比最坏情况再留一张余量）。
     *
     * 超出的部分折进「+N 更多」胶囊 —— 那条路径本来就有，不是新增能力。
     */
    const val IN_MAX = 22

    /** ③ 层「主代理」卡的稳定 id（对应 outbound = 0 / 当前选中 profile） */
    const val ID_MAIN = "ou-proxy"

    /** ② 层「默认路由/兜底分流」卡的稳定 id */
    const val ID_DEFAULT_RULE = "ru-default"

    private const val OUTBOUND_PROXY = 0L
    private const val OUTBOUND_BYPASS = -1L
    private const val OUTBOUND_BLOCK = -2L

    /**
     * @param clash 一次 Clash API 拉取的结果。默认 [ClashResult.Disabled] ——
     *   这样只读 ②③ 的调用点（以及拿不到实时数据时）不用改。
     *   ① 层的降级态由它决定，**任何情况下都不会抛异常、不会白屏**。
     * @param expandRules ② 层是否展开。收起时最多 [RULE_MAX] 张，展开时最多
     *   [RULE_MAX_EXPANDED] 张。
     * @param expandOutbounds ③ 层是否展开。收起时最多 [OUT_MAX] 张，展开时最多
     *   [OUT_MAX_EXPANDED] 张。这只是**画多少张**的开关，不影响任何数据。
     */
    fun load(
        clash: ClashResult = ClashResult.Disabled,
        expandRules: Boolean = false,
        expandOutbounds: Boolean = false,
    ): TopologySnapshot {
        val allRules = SagerDatabase.rulesDao.allRules() /* 已 ORDER BY userOrder */
        val enabled = allRules.filter { it.enabled && DataStore.isRuleShownOnHome(it.id) }
        /* ③ 层节点池 = pill 选的分组（**不是全库**）。规则本身是全局的（内核按 userOrder
           全库匹配），但只画「指向本组节点」的 ③ 卡和 ②→③ 连线 —— 这就是「不同规则用不同
           节点，节点来自左上角 pill」的那套语义。

           查一次全表再在内存里分组，而不是 getByGroup + 再来一次 getAll：
           两个集合都要用（见下面的 allNodeIds），分两次查就是白跑一趟。 */
        val groupId = DataStore.currentGroupId()
        val allNodes = SagerDatabase.proxyDao.getAll()
        val nodes = allNodes.filter { it.groupId == groupId }
        val nodeById = nodes.associateBy { it.id }
        val allNodeIds = allNodes.mapTo(HashSet()) { it.id }

        /* 「主代理」= 当前选中的 profile（bg/BaseService.kt:317 就是这么解析的）。
           内核把 outbound=0 和 outbound=当前 profile 的 id **都**归一成 TAG_PROXY
           （ConfigBuilder.kt:577-582），所以这里必须一起合并，否则会出现两张同名的 ③ 卡。 */
        val mainId = DataStore.selectedProxy

        /* ③ 出站：主出站卡片始终常驻保底，再按「启用的规则里首次出现」的顺序收集目标，去重。 */
        val outCards = LinkedHashMap<String, TopologyCard>()
        val mainCard = outboundCard(OUTBOUND_PROXY, nodeById, mainId, allNodeIds)
        if (mainCard != null) {
            outCards[mainCard.id] = mainCard
        }
        enabled.forEach { rule ->
            val card = outboundCard(rule.outbound, nodeById, mainId, allNodeIds) ?: return@forEach
            outCards.putIfAbsent(card.id, card)
        }
        val allOut = outCards.values.toList()
        val outCap = if (expandOutbounds) OUT_MAX_EXPANDED else OUT_MAX
        val outOver = (allOut.size - outCap).coerceAtLeast(0)
        /* 溢出时最后一张位置留给「+N 更多」胶囊，所以只显示 outCap - 1 张 */
        val shownOut = if (outOver > 0) allOut.take(outCap - 1) else allOut
        val shownOutIds = shownOut.mapTo(HashSet()) { it.id }

        /* ② 规则：常驻「默认分流」卡片作为最终兜底，并展现启用的自定义规则 */
        val defaultRuleCard = TopologyCard(
            id = ID_DEFAULT_RULE,
            layer = 2,
            title = app.getString(R.string.topology_default_route),
            subtitle = app.getString(R.string.topology_default_route_sub),
            kind = TopologyKind.PROXY,
        )

        val ruleCap = if (expandRules) RULE_MAX_EXPANDED else RULE_MAX
        val customRuleCards = enabled.take((ruleCap - 1).coerceAtLeast(0)).map { rule ->
            TopologyCard(
                id = "ru-${rule.id}",
                layer = 2,
                title = rule.displayName(),
                subtitle = rule.displayOutbound(),
                kind = when (rule.outbound) {
                    OUTBOUND_BYPASS -> TopologyKind.DIRECT
                    OUTBOUND_BLOCK -> TopologyKind.BLOCK
                    OUTBOUND_PROXY -> TopologyKind.PROXY
                    else -> if (rule.outbound in allNodeIds) {
                        TopologyKind.PROXY
                    } else {
                        TopologyKind.MISSING
                    }
                },
            )
        }
        val ruleCards = customRuleCards + defaultRuleCard
        val shownRuleIds = ruleCards.mapTo(HashSet()) { it.id }

        /* ②→③ 连线：只连**两张卡都真的显示出来**的边。 */
        val outEdges = ArrayList<TopologyEdge>()
        if (mainCard != null && mainCard.id in shownOutIds) {
            outEdges.add(
                TopologyEdge(
                    fromId = ID_DEFAULT_RULE,
                    toId = mainCard.id,
                    kind = mainCard.kind,
                    dashed = mainCard.kind == TopologyKind.MISSING,
                )
            )
        }
        enabled.take((ruleCap - 1).coerceAtLeast(0)).forEach { rule ->
            val target = outboundCard(rule.outbound, nodeById, mainId, allNodeIds) ?: return@forEach
            if (target.id in shownOutIds) {
                outEdges.add(
                    TopologyEdge(
                        fromId = "ru-${rule.id}",
                        toId = target.id,
                        kind = target.kind,
                        dashed = target.kind == TopologyKind.MISSING,
                    )
                )
            }
        }

        /* ① 层：实时连接。未命中特定自定义规则的流量，自动归属并连线到默认分流卡片！ */
        val inbound = buildInbound(clash, enabled, shownRuleIds, allNodes.associateBy { it.id }, mainId)

        return TopologySnapshot(
            rules = ruleCards,
            ruleOverflow = (enabled.size - (ruleCap - 1).coerceAtLeast(0)).coerceAtLeast(0),
            outbounds = shownOut,
            outboundOverflow = outOver,
            edges = inbound.edges + outEdges,
            enabledRuleTotal = enabled.size,
            nodeTotal = nodes.size,
            clashApiEnabled = DataStore.enableClashAPI,
            inbounds = inbound.cards,
            inboundOverflow = inbound.overflow,
            inboundState = inbound.state,
            uploadTotal = inbound.uploadTotal,
            downloadTotal = inbound.downloadTotal,
        )
    }

    // ------------------------------------------------------------------ ① 层

    private class InboundResult(
        val state: TopologyInboundState,
        val cards: List<TopologyInbound> = emptyList(),
        val overflow: Int = 0,
        val edges: List<TopologyEdge> = emptyList(),
        val uploadTotal: Long = 0L,
        val downloadTotal: Long = 0L,
    )

    /**
     * 把一次 Clash API 拉取翻译成 ① 层的卡片。
     *
     * ## 归属（哪条连接命中了哪条规则）
     *
     * 见 [RuleSignature] 的长注释：`/connections` 的 `rule` 里没有规则 id，
     * 只能读侧重建条件串再比对。这里额外做了两层保险：
     *
     * 1. **内置规则不认领** —— `port=53 => hijack-dns()` 这类内核自带的规则
     *    条件串可能和用户规则撞车，宁可显示"内置"也不能张冠李戴。
     * 2. **漂移保险** —— 拿到了 `/rules` 就要求重建出来的串**真的在内核规则表里**。
     *    万一以后 sing-box 改了渲染格式，重建串对不上 → 静默不匹配，
     *    而不是把连接挂到一条错误的规则上（fail closed）。
     *
     * ## 聚合
     *
     * 按 **host** 合并（同一站点的多个连接算一朵云）。
     * 一个 host 可能命中多条规则（比如同 IP 不同端口），取**流量最大**的那条当代表
     * —— 这比拆成多朵同名的云好读，也比不画线有用。
     */
    private fun buildInbound(
        clash: ClashResult,
        enabled: List<RuleEntity>,
        shownRuleIds: Set<String>,
        nodeById: Map<Long, ProxyEntity> = emptyMap(),
        mainId: Long = 0L,
    ): InboundResult {
        when (clash) {
            is ClashResult.Disabled -> return InboundResult(TopologyInboundState.DISABLED)
            is ClashResult.Unavailable -> return InboundResult(TopologyInboundState.UNAVAILABLE)
            is ClashResult.Ok -> Unit
        }
        clash as ClashResult.Ok

        val connections = clash.snapshot.connections
        if (connections.isEmpty()) {
            return InboundResult(
                state = TopologyInboundState.IDLE,
                uploadTotal = clash.snapshot.uploadTotal,
                downloadTotal = clash.snapshot.downloadTotal,
            )
        }

        /* 「条件串 → 规则」表。按 userOrder 建、putIfAbsent 保住第一条 ——
           内核也是按同一顺序取第一条命中，所以这不是猜，是同一套语义。 */
        val uidOf = uidLookup()
        val signatureToRule = LinkedHashMap<String, RuleEntity>()
        enabled.forEach { rule ->
            val signature = RuleSignature.render(rule.toSpec(), uidOf) ?: return@forEach
            signatureToRule.putIfAbsent(RuleSignature.normalize(signature), rule)
        }
        val ruleById = enabled.associateBy { it.id }

        /* 漂移保险：/rules 拿不到就是 null，那就退回「只挡内置规则」。 */
        val corePayloads = clash.rules?.mapTo(HashSet()) { RuleSignature.normalize(it.payload) }

        class Acc {
            var upload = 0L
            var download = 0L
            var endpoint = ""
            var app = ""
            var fallbackKind = RuleMatchKind.UNKNOWN
            /** 最早那条连接的建立时间。RFC3339 同格式下字符串比较即时间比较。 */
            var earliestStart = ""
            var count = 0
            var chainTag = ""
            /** ruleId → 累计流量，用来挑「代表规则」 */
            val trafficByRule = HashMap<Long, Long>()
            /** ruleId → 匹配类型。规则匹配上了就用它，比 fallbackKind 更准 */
            val kindByRule = HashMap<Long, RuleMatchKind>()
        }

        val accs = LinkedHashMap<String, Acc>()
        connections.forEach { conn ->
            val host = conn.host
            if (host.isBlank()) return@forEach /* 连目标都没有的连接画不出来，跳过 */
            val acc = accs.getOrPut(host) {
                Acc().apply {
                    endpoint = conn.endpoint
                    app = conn.metadata.appPackage
                }
            }
            acc.count++
            acc.upload += conn.upload
            acc.download += conn.download
            if (acc.app.isBlank()) acc.app = conn.metadata.appPackage
            if (acc.endpoint.isBlank()) acc.endpoint = conn.endpoint
            val chain = conn.chains.lastOrNull().orEmpty()
            if (acc.chainTag.isBlank() && chain.isNotBlank()) {
                acc.chainTag = chain
            }
            /* RFC3339 都是定长且零填充的，同格式下字典序 = 时间序，不用解析成 Date。
               拿不到 start 的连接（空串）直接跳过，否则空串会「比谁都早」。 */
            if (conn.start.isNotBlank() &&
                (acc.earliestStart.isBlank() || conn.start < acc.earliestStart)
            ) {
                acc.earliestStart = conn.start
            }

            val conditions = RuleSignature.conditionsOf(conn.rule)
            val kind = RuleMatchKind.of(conditions)
            val rule = matchRule(conditions, signatureToRule, corePayloads)
            if (rule == null) {
                /* 没匹配上也要留下「命中了哪类条件」——这是从 live 串文本直接读出来的，
                   不依赖重建，所以一定拿得到。 */
                acc.fallbackKind = kind
            } else {
                val id = rule.id
                acc.trafficByRule[id] = (acc.trafficByRule[id] ?: 0L) + conn.total
                acc.kindByRule[id] = kind
            }
        }

        val all = accs.map { (host, acc) ->
            val dominantId = acc.trafficByRule.maxByOrNull { it.value }?.key
            val rule = dominantId?.let { ruleById[it] }
            val ruleCardId = if (rule != null && "ru-${rule.id}" in shownRuleIds) {
                "ru-${rule.id}"
            } else if (ID_DEFAULT_RULE in shownRuleIds) {
                ID_DEFAULT_RULE
            } else {
                null
            }

            val ruleOutbound = rule?.outbound ?: if (ID_DEFAULT_RULE in shownRuleIds) OUTBOUND_PROXY else null
            val proxyLabel = when {
                ruleOutbound == OUTBOUND_BYPASS || acc.chainTag.equals("direct", ignoreCase = true) -> "直连"
                ruleOutbound == OUTBOUND_BLOCK || acc.chainTag.equals("block", ignoreCase = true) || acc.chainTag.equals("reject", ignoreCase = true) -> "拦截"
                ruleOutbound == OUTBOUND_PROXY || ruleOutbound == 0L -> {
                    val node = nodeById[mainId]
                    TopologyModelHelper.extractRegion(node?.displayName() ?: acc.chainTag)
                }
                ruleOutbound != null && ruleOutbound > 0L -> {
                    val node = nodeById[ruleOutbound]
                    TopologyModelHelper.extractRegion(node?.displayName() ?: acc.chainTag)
                }
                acc.chainTag.isNotBlank() -> TopologyModelHelper.extractRegion(acc.chainTag)
                else -> ""
            }

            TopologyInbound(
                id = "in-$host",
                label = host,
                proxyLabel = proxyLabel,
                sizeHint = 1.0f,
                endpoint = acc.endpoint,
                appPackage = acc.app,
                upload = acc.upload,
                download = acc.download,
                ruleCardId = ruleCardId,
                ruleName = rule?.displayName() ?: app.getString(R.string.topology_default_route),
                matchKind = dominantId?.let { acc.kindByRule[it] } ?: acc.fallbackKind,
                startAt = acc.earliestStart,
                connectionCount = acc.count,
            )
        }.sortedByDescending { it.total }

        val maxTraffic = all.firstOrNull()?.total ?: 0L
        val shown = all.take(IN_MAX).map { inbound ->
            val sizeHint = if (maxTraffic > 0L) {
                val ratio = (kotlin.math.ln(1.0 + inbound.total) / kotlin.math.ln(1.0 + maxTraffic)).toFloat()
                (0.80f + 0.45f * ratio).coerceIn(0.80f, 1.25f)
            } else {
                1.0f
            }
            inbound.copy(sizeHint = sizeHint)
        }

        return InboundResult(
            state = TopologyInboundState.OK,
            cards = shown,
            overflow = (all.size - IN_MAX).coerceAtLeast(0),
            /* 只给**画出来了的**云连边，且目标规则卡也必须画出来了 */
            edges = shown.mapNotNull { inbound ->
                val target = inbound.ruleCardId ?: return@mapNotNull null
                TopologyEdge(
                    fromId = inbound.id,
                    toId = target,
                    kind = TopologyKind.PROXY, /* INBOUND 段的颜色不取 kind，见 drawEdges */
                    stage = TopologyEdgeStage.INBOUND,
                )
            },
            uploadTotal = clash.snapshot.uploadTotal,
            downloadTotal = clash.snapshot.downloadTotal,
        )
    }

    private fun signatureOf(rule: RuleEntity, uidOf: (String) -> Int?): String =
        RuleSignature.render(rule.toSpec(), uidOf).orEmpty()

    /**
     * 把 live 的条件串映射回一条规则。
     *
     * @param corePayloads 内核规则表的条件串集合；null 表示 `/rules` 没拿到，
     *   这时只挡内置规则，不做漂移校验。
     */
    private fun matchRule(
        conditions: String,
        signatureToRule: Map<String, RuleEntity>,
        corePayloads: Set<String>?,
    ): RuleEntity? {
        if (conditions.isBlank()) return null
        if (RuleSignature.isBuiltin(conditions)) return null
        val normalized = RuleSignature.normalize(conditions)
        /* fail closed：重建出来的串必须真的在内核规则表里，否则说明格式漂移了 */
        if (corePayloads != null && normalized !in corePayloads) return null
        return signatureToRule[normalized]
    }

    /**
     * 包名 → uid。
     *
     * `PackageCache.loaded` 是个「初始锁定、`register()` 里解锁」的 Mutex，
     * 所以 `isLocked` 就是「包列表还没读完」。这时**刻意不去调 `awaitLoadSync()`**
     * —— 那是 `runBlocking`，万一 `register()` 中途抛异常就会永久卡住后台线程。
     * 退化成「这一轮拿不到 uid」即可：效果只是「按应用分流」的规则这轮匹配不上，下次刷新就好。
     */
    private fun uidLookup(): (String) -> Int? = runCatching {
        if (PackageCache.loaded.isLocked) return@runCatching { _: String -> null }
        { packageName: String -> PackageCache[packageName] }
    }.getOrElse { { _: String -> null } }

    /**
     * 把一条规则的 `outbound` 翻译成 ③ 层的出站卡。
     *
     * **语义必须跟内核对齐**（`ConfigBuilder.kt:577-582`）：
     * `0` = 主代理、`-1` = 绕过、`-2` = 拦截、`>0` = 某个具体节点；
     * 而且「`outbound` 恰好等于当前选中 profile」在内核里也被当成主代理。
     *
     * **返回 null = 「本组视图里不该出现这张卡」**，调用方据此同时跳过 ②→③ 的连线。
     * 只有一种情况会返回 null：目标节点还在、但属于**别的分组**。
     * 「节点真的被删了」是另一种情况，照旧画 ghost 卡（[TopologyKind.MISSING]）。
     *
     * @param nodeById 本组节点池（pill 选的那个分组）
     * @param allNodeIds 全库节点 id，用来区分「别组节点」和「已删除」
     */
    private fun outboundCard(
        outbound: Long,
        nodeById: Map<Long, ProxyEntity>,
        mainId: Long,
        allNodeIds: Set<Long>,
    ): TopologyCard? {
        /* 走主代理 —— 不是 null。新建规则的 outbound 默认就是 0，
           如果这里丢掉，③ 层会整层空掉（用户装了 App 什么都没配时的默认观感）。
           能解析出当前节点就显示节点名，解析不出就退化成「走代理」文案。 */
        if (outbound == OUTBOUND_PROXY || (mainId != 0L && outbound == mainId)) {
            val main = nodeById[mainId]
            /* 一个节点都没选时（mainId == 0），这张卡是**新主页唯一的选节点入口**
               （③ 层是路由图、不是节点列表，见 `TopologyFragment.pickMainNode`）。
               标题直接写成动作 —— 否则它叫「走代理」，用户看不出它能点。 */
            val title = main?.safeDisplayName()
                ?: if (mainId == 0L) {
                    app.getString(R.string.topology_pick_node)
                } else {
                    app.getString(R.string.route_proxy)
                }
            return TopologyCard(
                id = ID_MAIN,
                layer = 3,
                title = title,
                subtitle = main?.delayText().orEmpty(),
                kind = TopologyKind.PROXY,
            )
        }

        return when (outbound) {
            OUTBOUND_BYPASS -> TopologyCard(
                id = "ou-bypass",
                layer = 3,
                title = app.getString(R.string.route_bypass),
                subtitle = "",
                kind = TopologyKind.DIRECT,
            )

            OUTBOUND_BLOCK -> TopologyCard(
                id = "ou-block",
                layer = 3,
                title = app.getString(R.string.route_block),
                subtitle = "",
                kind = TopologyKind.BLOCK,
            )

            else -> {
                val node = nodeById[outbound]
                if (node == null) {
                    TopologyCard(
                        id = "ou-$outbound",
                        layer = 3,
                        title = app.getString(R.string.error_title),
                        subtitle = app.getString(R.string.topology_node_missing),
                        kind = TopologyKind.MISSING,
                    )
                } else {
                    TopologyCard(
                        id = "ou-$outbound",
                        layer = 3,
                        title = node.safeDisplayName(),
                        subtitle = node.delayText(),
                        kind = TopologyKind.PROXY,
                    )
                }
            }
        }
    }

    /**
     * [ProxyEntity.displayName] 内部会 `requireBean()`，bean 为空或 type 未定义时**直接抛异常**。
     * 数据库里存在脏行的可能性不为零，而拓扑图是一屏画几十张卡 —— 一张卡炸掉整个页面不值得。
     */
    private fun ProxyEntity.safeDisplayName(): String =
        runCatching { displayName() }.getOrElse { "#$id" }

    /** 与旧主页保持同一套语义：status 1 = 可用（显示延迟），其余一律显示占位符 */
    private fun ProxyEntity.delayText(): String = when (status) {
        1 -> app.getString(R.string.available, ping)
        else -> app.getString(R.string.topology_delay_unknown)
    }
}
