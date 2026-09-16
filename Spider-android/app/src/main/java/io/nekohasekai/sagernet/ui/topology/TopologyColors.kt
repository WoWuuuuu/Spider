package io.nekohasekai.sagernet.ui.topology

/**
 * 新 UI 色板里**不需要 Context** 的那两层（见 `docs/spider-newui-plan.md` §0.2）：
 *
 * - **底色层**（卡片/文字/区域标签）：Latte / Mocha 自带值。项目那 21 套主题其实只改了
 *   几个 accent 属性，背景与文字全是 Material DayNight 默认值 —— 没有完整色板可复用，
 *   所以这一层必须自带，它也是这套界面「好看」的来源。
 * - **语义层**（入站/规则/出站/直连/拦截）：21 套主题里根本没有这些语义色，同样自带。
 *
 * 单独抽成纯 Kotlin 对象（不依赖 Android）是为了让**预览生成器**复用同一份常量：
 * `.workbuddy-ai/validate/topology-layout/Preview.kt` 会直接编译本文件。
 * 抄一份过去迟早漂移，而漂移过的预览就不再「忠实」，也就失去了它的全部意义。
 *
 * 唯一需要 Context 的是**强调层**（`?attr/colorAccent`），仍留在 [TopologyPalette] 里。
 */
object TopologyColors {

    class Scheme(
        val cardFill: Int,
        val cardStroke: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val layerLabel: Int,
        val inbound: Int,
        val rule: Int,
        val outbound: Int,
        val direct: Int,
        val block: Int,
        val warn: Int,
    )

    /** 深色（Mocha） */
    val NIGHT = Scheme(
        cardFill = 0xFF313244.toInt(),
        cardStroke = 0xFF45475A.toInt(),
        textPrimary = 0xFFCDD6F4.toInt(),
        textSecondary = 0xFFA6ADC8.toInt(),
        textMuted = 0xFF7F849C.toInt(),
        layerLabel = 0xFF7F849C.toInt(),
        inbound = 0xFFB4BEFE.toInt(),
        rule = 0xFFCBA6F7.toInt(),
        outbound = 0xFF74C7EC.toInt(),
        direct = 0xFFA6E3A1.toInt(),
        block = 0xFFF38BA8.toInt(),
        warn = 0xFFF9E2AF.toInt(),
    )

    /** 浅色（Latte） */
    val DAY = Scheme(
        cardFill = 0xFFFFFFFF.toInt(),
        cardStroke = 0xFFDCE0E8.toInt(),
        textPrimary = 0xFF4C4F69.toInt(),
        textSecondary = 0xFF6C6F85.toInt(),
        textMuted = 0xFF8C8FA1.toInt(),
        layerLabel = 0xFF8C8FA1.toInt(),
        inbound = 0xFF7287FD.toInt(),
        rule = 0xFF8839EF.toInt(),
        outbound = 0xFF209FB5.toInt(),
        direct = 0xFF40A02B.toInt(),
        block = 0xFFD20F39.toInt(),
        warn = 0xFFDF8E1D.toInt(),
    )

    /** 深色时 accent 取不到 attr 的兜底值 */
    val NIGHT_ACCENT_FALLBACK = 0xFFCBA6F7.toInt()

    /** 浅色时 accent 取不到 attr 的兜底值 */
    val DAY_ACCENT_FALLBACK = 0xFF8839EF.toInt()
}
