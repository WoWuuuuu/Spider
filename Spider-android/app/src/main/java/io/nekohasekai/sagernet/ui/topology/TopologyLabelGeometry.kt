package io.nekohasekai.sagernet.ui.topology

/**
 * 层标签的几何 —— **绘制和命中判定共用同一份**。
 *
 * 单独抽成不依赖 Android 的纯对象，有两个理由：
 *
 * 1. **消除手工重复**。标签的基线和命中区原来在 `drawLayerLabels` / `labelAt` 里
 *    各算一遍，改一处忘一处就会出现「看得见但点不到」或者「点得到但看不见」——
 *    两种都不会报错、也不会崩，只会让人以为是「偶尔点不中」。
 * 2. **可验证**。真机 / 模拟器都拿不到，唯一能查的就是纯几何。
 *    `.workbuddy-ai/validate/topology-layout` 会直接编译本文件。
 *
 * 数值来源：`docs/spider-ui-prototype-v6.html` 的 `.layer-label` 样式
 * （`font-size:9.5px; padding:2.5px 9px; opacity:.34`，点一下 `.show` → `.95`）。
 */
object TopologyLabelGeometry {

    /** 标签字号（原型 `font-size:9.5px`） */
    const val SIZE_DP = 9.5f

    /**
     * 常态透明度。原型是 `.34`，这里提到 `.55`。
     *
     * 原因：原型那边标签是**带描边的胶囊**（`border:0.5px solid ...22%` + 9px 横向内边距），
     * 轮廓本身就在提示「这儿有个东西」；真机这边只画纯文字、没有胶囊
     * （用户明确要求「淡化痕迹、不突出」），同样的 `.34` 会淡到认不出来。
     * 所以把文字透明度补高一点，代偿消失的边框。
     */
    const val ALPHA = 140

    /** 点一下之后的透明度（原型 `.show` 的 `.95`）—— 「提亮看清」就是这一步 */
    const val ALPHA_BRIGHT = 242

    /** 基线相对区域上边界再往上抬多少 dp（原型用绝对定位，这里是换算值） */
    const val LIFT_DP = 6f

    /** 文字的视觉中心在基线上方多少 dp（命中区以它为中心，而不是以基线为中心） */
    const val TEXT_CENTER_DP = 4f

    /** 命中区高度 dp。文字只有 9.5dp 高，补到 24dp 才够手指点 */
    const val TOUCH_H_DP = 24f

    /** 命中区横向外扩 dp（纯文字没有内边距，左右各补一点） */
    const val PAD_X_DP = 6f

    /**
     * 文字实际占的高度，作为 em 的比例 —— 用来验证「命中区是否盖住文字」。
     *
     * 取 `0.95`（基线以上）/ `0.15`（基线以下）是**保守估计**：CJK 字面框接近满 em，
     * 但不同字体、不同厂商 ROM 的默认字体差异不小，取大一点才不会漏判。
     */
    const val GLYPH_ASCENT_EM = 0.95f
    const val GLYPH_DESCENT_EM = 0.15f

    /** 三层标签所在的区域上边界（比例），顺序 = 从上到下。文案在 `TopologyView` 里按同序配。 */
    val LAYER_TOPS = floatArrayOf(
        TopologyRegions.IN_Y0,
        TopologyRegions.RU_Y0,
        TopologyRegions.OUT_Y0,
    )

    /** 一条标签的命中区（px）。[top] >= [bottom] 表示这条标签当前点不到。 */
    class Band(val top: Float, val bottom: Float) {
        val usable: Boolean get() = bottom > top
        fun contains(y: Float): Boolean = y >= top && y <= bottom
    }

    /** 基线 y（px）。**≤ 0 表示这条标签画不出来**，那也就不该能点。 */
    fun baselineY(regionTopRatio: Float, heightPx: Float, density: Float): Float =
        regionTopRatio * heightPx - LIFT_DP * density

    /**
     * 命中区：以**文字的视觉中心**（基线往上 [TEXT_CENTER_DP]）为中心的固定 24dp 高条带。
     *
     * ⚠ 这里**刻意不做「不许伸进上一层」的夹取**。曾经加过，被验证脚本推翻了：
     *
     * 屏幕越矮，层间走廊越薄（按 dp 算走廊只有约 34dp）。矮到一定程度，24dp 的条带
     * 就会往上越过 ① 层的下边界。当时的想法是「夹住它，免得抢走 chip 的点击」。
     * 但夹取是**多余的**，而且有害：
     *
     * - 多余的：卡片本来就有优先级 —— `TopologyView` 只在**没有命中卡片**时才认标签
     *   （[pickHit]）。这是逐像素按卡片的真实位置判断的，比一条静态的区域边界准得多。
     * - 有害的：夹取会把条带往上削掉一截，而文字是钉在基线上的 —— 削掉的正好是
     *   字的上半截。实测 1280×600（300dp 高）那一档，文字顶部被削掉 2.5dp，
     *   也就是**看得见但点不到**，恰好是这套几何最想避免的毛病。
     *
     * 去掉夹取之后，条带永远完整盖住文字；真碰上 chip 压在同一处，chip 赢 —— 那本来
     * 就是对的优先级（内容是主，标签是辅）。
     */
    fun band(regionTopRatio: Float, heightPx: Float, density: Float): Band {
        val baseline = baselineY(regionTopRatio, heightPx, density)
        return Band(
            baseline - (TEXT_CENTER_DP + TOUCH_H_DP / 2f) * density,
            baseline + (TOUCH_H_DP / 2f - TEXT_CENTER_DP) * density,
        )
    }

    /** 命中区左边界（px） */
    fun hitLeftX(leftPx: Float, density: Float): Float = leftPx - PAD_X_DP * density

    /** 命中区右边界（px） */
    fun hitRightX(leftPx: Float, textWidthPx: Float, density: Float): Float =
        leftPx + textWidthPx + PAD_X_DP * density

    /**
     * 命中优先级：**卡片永远优先于标签**。
     *
     * 就一个三元表达式，之所以抽出来，是因为它是「标签的命中区可以随意压在 ① 层上」
     * 的**唯一依据**（见 [band] 的说明）。靠一句注释来保证太弱了 ——
     * 哪天有人为了「让标签好点一点」把这里反过来，卡片就会被一条 9.5dp 的标签抢走点击，
     * 而且不会有任何报错。验证脚本会直接断言这个函数。
     *
     * @param cardHit [TopologyView.hitTest] 是否命中了卡片
     * @param labelIndex 命中的标签下标，-1 = 没命中
     * @return 最终生效的标签下标；-1 表示这次点击交给卡片（或什么都不做）
     */
    fun pickHit(cardHit: Boolean, labelIndex: Int): Int = if (cardHit) -1 else labelIndex
}
