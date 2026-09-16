package io.nekohasekai.sagernet.ui.topology

import kotlin.math.roundToInt

/** 一张卡在视图坐标系里的位置（中心点 + 尺寸，单位 px） */
class TopologyBox(
    val id: String,
    val layer: Int,
    var cx: Float = 0f,
    var cy: Float = 0f,
    var w: Float = 0f,
    var h: Float = 0f,
)

/**
 * 三层在原型里是按 366×820 的 viewBox 定的，这里换算成**比例**，
 * 这样任何屏幕尺寸都按同一套空间分配走（① 半屏，②+③ 分另一半，层间留走廊）。
 */
object TopologyRegions {
    private const val VB_W = 366f
    private const val VB_H = 820f

    /** 区域左右边界（比例） */
    const val X0 = 12f / VB_W
    const val X1 = 354f / VB_W

    /** ① 入站 · 实时连接（占上半屏） */
    const val IN_Y0 = 74f / VB_H
    const val IN_Y1 = 441f / VB_H

    /** ② 路由规则 · 仅启用 */
    const val RU_Y0 = 475f / VB_H
    const val RU_Y1 = 639f / VB_H

    /** ③ 出站 · 由规则推导 */
    const val OUT_Y0 = 673f / VB_H
    const val OUT_Y1 = 808f / VB_H

    /** 连线的控制点夹取范围（原型里是 10..356，比卡片区略宽，允许曲线稍微外扩） */
    const val CLAMP_X0 = 10f / VB_W
    const val CLAMP_X1 = 356f / VB_W
}

/**
 * 三层布局算法 —— 从原型 `docs/spider-ui-prototype-v6.html` 移植。
 *
 * **关于 [Unit] 缩放**：原型里的常数（卡宽上限 62、抖动上限 20、弧形振幅 64…）都是
 * 在「366×820 的 viewBox」里量的，那套坐标约等于 dp。真机的视图是 px，动辄一两千，
 * 所以这些常数**必须按比例放大**，否则卡片会被压成一条。
 * 每个算法收一个 [Unit] 参数，就是「1 个原型像素等于多少真机像素」。
 *
 * ⚠ 随机序列和原型**不一样**：原型跑在 JS 的 double 上，`seed*1103515245` 会超出 2^53 丢精度；
 * 这里用 Long 精确运算。位置数值因此不同，但保证的性质完全一致：
 * **构造上零重叠**（行内 x 不重叠 + 行间 y 分离），不依赖任何迭代松弛。
 */
object TopologyLayout {

    /**
     * ① chip 的景深上限缩放。
     *
     * 布局按 `chipWidth × DEPTH_MAX` 预留宽度，画的时候再按各自的 s 缩 ——
     * 这样最靠下、放大到上限的那张 chip 也刚好顶到预留框，不会压到邻居。
     */
    const val DEPTH_MAX = 1.15f

    /** 景深下限缩放 */
    const val DEPTH_MIN = 0.85f

    /** 景深下限透明度 */
    const val ALPHA_MIN = 0.55f

    /**
     * 一项待排布的内容。
     *
     * [w] ≤ 0 表示「宽度由算法均分」（仅 [placeArc] 用）。
     *
     * [minW] = 均分时**至少要有多宽**，只有 [placeArc] 用得上。存在的理由：
     * 均分的上限原本硬锁 62（原型的弧宽经验值），但卡片标题的字号有下限
     * （`TopologyView` 里 10.5dp）。规则少的时候明明有大片空白，两者却会打架 ——
     * 卡片被压在 62 单位、标题被省略号切掉。把「装下最长标题需要多宽」当上限传进来即可。
     * 0 = 不设下限（行为与加这个字段之前完全一致）。
     */
    class Item(val id: String, val w: Float, val minW: Float = 0f)

    /** 原型像素 → 真机像素的换算比例 */
    class Unit(val x: Float, val y: Float = x)

    /**
     * ① chip 的宽度估算 —— 移植自原型 `estChipW()`：
     * CJK 按 10.5 宽、其余按 6.3 宽累加，加 24 的内边距，夹在 50~120 之间。
     * 比实测字宽便宜得多，而分行与越界判定用它已经够准。
     */
    fun chipWidth(label: String): Float {
        var sum = 0f
        label.forEach { ch -> sum += if (ch.code in HAN_START..HAN_END) 10.5f else 6.3f }
        return (sum + 24f).coerceIn(50f, 120f).roundToInt().toFloat()
    }

    /** chip 在 ① 区内的归一化纵向位置：0 = 最远（最上）、1 = 最近（最下） */
    fun depthAt(cy: Float, regionTop: Float, regionBottom: Float): Float {
        if (regionBottom <= regionTop) return 1f
        return ((cy - regionTop) / (regionBottom - regionTop)).coerceIn(0f, 1f)
    }

    /** 景深缩放：越靠下（近）越大 */
    fun depthScale(depth: Float): Float = DEPTH_MIN + (DEPTH_MAX - DEPTH_MIN) * depth

    /** 景深透明度：越靠下（近）越清晰 */
    fun depthAlpha(depth: Float): Float = ALPHA_MIN + (1f - ALPHA_MIN) * depth

    /** 原型的 `/[一-龥]/` */
    private const val HAN_START = 0x4E00
    private const val HAN_END = 0x9FA5

    /** 原型用的同一套 LCG，只是换成 Long 精确运算 */
    private class Rng(seed: Long) {
        private var s: Long = if (seed == 0L) 20260914L else seed
        fun next(): Double {
            s = (s * 1103515245L + 12345L) and 0x7FFFFFFFL
            return s.toDouble() / 0x7FFFFFFFL.toDouble()
        }
    }

    /**
     * 通用散点云：洗牌 → 贪心分行 → **行间距随机** → 行内随机边距+间距 → **每张卡纵向错开**。
     *
     * 三个「不是网格」的关键：
     *  1. 行与行间距随机（只保证 ≥ 卡高+gap），所以行不等距；
     *  2. 同一行每张卡 y 各自偏移，不再是水平一条线；
     *  3. 纵向偏移用**分层抽样**（先算 n 个等距档位再洗牌），保证铺满整个可抖区间，
     *     不会碰巧挤在中间 —— 这是用户当初要求「错开」的直接落实。
     */
    fun placeScattered(
        items: List<Item>,
        x0: Float, x1: Float, y0: Float, y1: Float,
        h: Float, gap: Float, capLo: Int, capHi: Int,
        seed: Long, layer: Int, unit: Unit,
        out: MutableMap<String, TopologyBox>,
    ) {
        if (items.isEmpty()) return
        val rnd = Rng(seed)

        /* 1. 种子洗牌 */
        val list = items.toMutableList()
        for (i in list.size - 1 downTo 1) {
            val j = (rnd.next() * (i + 1)).toInt()
            val t = list[i]; list[i] = list[j]; list[j] = t
        }

        /* 2. 贪心分行：每行容量 capLo~capHi 随机 */
        val w = x1 - x0
        val hh = y1 - y0
        val maxRows = ((hh - h) / (h + gap)).toInt().coerceAtLeast(0) + 1

        class Row(val items: MutableList<Item> = mutableListOf(), var used: Float = 0f)

        fun buildRows(lo: Int, hi: Int): List<Row> {
            val rows = mutableListOf<Row>()
            var cur = Row()
            var cap = 0
            list.forEach { n ->
                if (cur.items.isEmpty()) cap = lo + (rnd.next() * (hi - lo + 1)).toInt()
                val add = (if (cur.items.isNotEmpty()) gap else 0f) + n.w
                if (cur.items.isNotEmpty() && (cur.used + add > w || cur.items.size >= cap)) {
                    rows.add(cur); cur = Row()
                    cap = lo + (rnd.next() * (hi - lo + 1)).toInt()
                }
                cur.used += (if (cur.items.isNotEmpty()) gap else 0f) + n.w
                cur.items.add(n)
            }
            if (cur.items.isNotEmpty()) rows.add(cur)
            return rows
        }

        var rows = buildRows(capLo, capHi)
        if (rows.size > maxRows) {
            /* 行数放不下 → 强制少分行 */
            val forced = ((list.size + maxRows - 1) / maxRows).coerceAtLeast(1)
            rows = buildRows(forced, forced)
        }

        /* 3. 行中心：随机间距 */
        val r = rows.size
        val slack = ((hh - h) - (r - 1) * (h + gap)).coerceAtLeast(0f)
        val wts = FloatArray(r + 1)
        var sum = 0f
        for (i in 0..r) {
            val v = (rnd.next() + 0.2).toFloat()
            wts[i] = v; sum += v
        }
        val pads = FloatArray(r + 1) { wts[it] / sum * slack } // [0]顶余量 [1..r-1]行间余量 [r]底余量
        val cy = FloatArray(r)
        var yy = y0 + h / 2f + pads[0]
        for (i in 0 until r) {
            cy[i] = yy
            if (i < r - 1) yy += h + gap + pads[i + 1]
        }

        /* 4. 摆放 */
        val jitCap = 20f * unit.y
        val rjCap = 3f * unit.y
        for (i in 0 until r) {
            val row = rows[i]
            val n = row.items.size
            val upLim = if (i > 0) gap + pads[i] else pads[0]
            val dnLim = if (i < r - 1) gap + pads[i + 1] else pads[r]
            /* 0.45 而不是 0.5：相邻两行各让一半刚好贴边，留 10% 安全距离 */
            val jit = minOf(jitCap, minOf(upLim, dnLim) * 0.45f).coerceAtLeast(0f)
            /* 随机微调必须**从 jit 预算里扣**，不能叠在 jit 之上。
               大数据量扫描时就是在这一步抓到过重叠：行多、行间余量小时总偏移会超出预算。 */
            val rj = minOf(rjCap, jit / n.coerceAtLeast(1))
            val base = (jit - rj).coerceAtLeast(0f)
            val off = FloatArray(n) { k -> if (n > 1) -base + 2f * base * k / (n - 1) else 0f }
            for (k in n - 1 downTo 1) {
                val j = (rnd.next() * (k + 1)).toInt()
                val t = off[k]; off[k] = off[j]; off[j] = t
            }

            val gaps = n - 1
            val slots = gaps + 2
            val w2 = FloatArray(slots)
            var s2 = 0f
            for (k in 0 until slots) {
                val v = (rnd.next() + 0.2).toFloat()
                w2[k] = v; s2 += v
            }
            val extra = (w - row.used).coerceAtLeast(0f)
            val pd = FloatArray(slots) { w2[it] / s2 * extra }

            var x = x0 + pd[0]
            row.items.forEachIndexed { k, item ->
                val y = cy[i] + off[k] + ((rnd.next() * 2 - 1).toFloat()) * rj
                out[item.id] = TopologyBox(item.id, layer, x + item.w / 2f, y, item.w, h)
                if (k < gaps) x += item.w + gap + pd[k + 1]
            }
        }
    }

    /**
     * ② 层专用：弧形排布（中间高两边低，像漏斗）。
     *
     * 1. 从左到右按规则顺序排 —— 顺序就是内核的匹配优先级，**不能打乱**；
     * 2. 支持**定宽槽位**：带 w 的项（如「+N 更多」胶囊）按定宽占位，其余均分剩余空间；
     * 3. 槽位多时先收窄间隙，尽量保住卡片宽度；
     * 4. 均分项可以用 [Item.minW] 声明「至少要有这么宽」—— 均分的上限会抬到
     *    `max(62, minW)`，但绝不会超过实际可用宽度。
     */
    fun placeArc(
        items: List<Item>,
        x0: Float, x1: Float, y0: Float, y1: Float,
        h: Float, layer: Int, unit: Unit,
        out: MutableMap<String, TopologyBox>,
    ) {
        val n = items.size
        if (n == 0) return
        val w = x1 - x0
        val gap = (if (n > 5) 4f else 6f) * unit.x

        val fixed = items.filter { it.w > 0f }.sumOf { it.w.toDouble() }.toFloat()
        val flex = items.count { it.w <= 0f }
        /* 下限 34 按 unit 放大 —— 原型里 342px 宽区域的经验值。
           上限取 max(62, 最长标题的需求)：规则少时让卡片长到装得下标题，
           规则多时（可用宽度本来就不够）自然回落到均分值。
           不写 max() 的话就是原来那个 bug —— 2 张规则卡各占 62 单位、
           中间空一大片，而 "Block" 这种 5 个字母的标题照样被切成 "Block…"。 */
        val flexW = if (flex > 0) {
            val avail = (w - (n - 1) * gap - fixed) / flex
            val need = items.filter { it.w <= 0f }.maxOf { it.minW }
            avail.coerceAtLeast(34f * unit.x).coerceAtMost(maxOf(62f * unit.x, need))
        } else 0f

        val widths = FloatArray(n) { if (items[it].w > 0f) items[it].w else flexW }
        val span = widths.sum() + (n - 1) * gap
        val sx = x0 + (w - span) / 2f

        val cx = (n - 1) / 2f
        val amp = minOf(64f * unit.y, maxOf(24f * unit.y, y1 - y0 - h - 16f * unit.y))
        val baseY = y0 + h / 2f + 8f * unit.y /* 最高点（正中间那张）的中心 */
        val yBot = y1 - h / 2f

        var cur = sx
        for (i in 0 until n) {
            val iw = widths[i]
            val t = if (cx > 0f) (i - cx) / cx else 0f
            val y = minOf(yBot, baseY + t * t * amp + (i - cx) * 2.2f * unit.y)
            out[items[i].id] = TopologyBox(items[i].id, layer, cur + iw / 2f, y, iw, h)
            cur += iw + gap
        }
    }
}
