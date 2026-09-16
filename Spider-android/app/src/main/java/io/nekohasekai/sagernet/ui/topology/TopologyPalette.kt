package io.nekohasekai.sagernet.ui.topology

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

/**
 * 新 UI 的色板 —— 按 `docs/spider-newui-plan.md` §0.2 的**三层归属**来组织：
 *
 * 1. **底色层：自带**（Latte / Mocha 两套）。项目那 21 套主题其实只改了 5 个 accent 属性，
 *    背景/卡片/文字全是 Material DayNight 的默认值 —— 没有完整色板可复用，所以这一层必须自带，
 *    它也是这套界面「好看」的来源。
 * 2. **强调层：跟随项目主题**。只接一根线：`?attr/colorAccent`。用户换主题，激活态跟着换。
 * 3. **语义层：自带**。入站/规则/出站/直连/拦截 —— 21 套主题里根本没有这些语义色。
 *
 * 明暗只跟随 [Theme.usingNightMode]（即 App 的夜间模式设置），不跟随那 21 套 accent。
 */
class TopologyPalette(context: Context) {

    val night: Boolean = Theme.usingNightMode()

    /** 卡片填充 */
    val cardFill: Int

    /** 卡片描边 */
    val cardStroke: Int

    val textPrimary: Int
    val textSecondary: Int
    val textMuted: Int

    /** 三层区域的小标签（刻意压得很淡，用的时候几乎感觉不到） */
    val layerLabel: Int

    /** 强调色 = 项目主题的 colorAccent；取不到就退回自带色 */
    val accent: Int

    /* ---- 语义色 ---- */
    val inbound: Int
    val rule: Int
    val outbound: Int
    val direct: Int
    val block: Int
    val warn: Int

    init {
        /* 底色层与语义层是纯常量，放在 TopologyColors 里 ——
           预览生成器要编译同一份文件，抄过去会漂移。 */
        val scheme = if (night) TopologyColors.NIGHT else TopologyColors.DAY
        cardFill = scheme.cardFill
        cardStroke = scheme.cardStroke
        textPrimary = scheme.textPrimary
        textSecondary = scheme.textSecondary
        textMuted = scheme.textMuted
        layerLabel = scheme.layerLabel
        inbound = scheme.inbound
        rule = scheme.rule
        outbound = scheme.outbound
        direct = scheme.direct
        block = scheme.block
        warn = scheme.warn

        /* 强调层：唯一的「跟随主题」入口。
           用 runCatching 兜底 —— 万一主题链上取不到 colorAccent，也不能让整个页面挂掉。 */
        accent = runCatching { context.getColorAttr(R.attr.colorAccent) }
            .getOrElse {
                if (night) TopologyColors.NIGHT_ACCENT_FALLBACK else TopologyColors.DAY_ACCENT_FALLBACK
            }
    }

    /** 语义类型 → 描边色。孤儿规则用 warn 而不是红，因为它不是错误、只是没配好 */
    fun strokeOf(kind: TopologyKind): Int = when (kind) {
        TopologyKind.PROXY -> rule
        TopologyKind.DIRECT -> direct
        TopologyKind.BLOCK -> block
        TopologyKind.UNSET -> warn
        TopologyKind.MISSING -> warn
    }

    /** 出站层单独一套：节点用 outbound 色，直连/拦截沿用语义色 */
    fun outStrokeOf(kind: TopologyKind): Int = when (kind) {
        TopologyKind.PROXY -> outbound
        TopologyKind.DIRECT -> direct
        TopologyKind.BLOCK -> block
        else -> warn
    }
}
