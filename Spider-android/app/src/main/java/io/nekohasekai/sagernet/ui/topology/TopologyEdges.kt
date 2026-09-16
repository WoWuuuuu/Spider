package io.nekohasekai.sagernet.ui.topology

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 连线几何 —— 从原型 `docs/spider-ui-prototype-v6.html` 的
 * `bezCtrl` / `bezPath` / `bez` / `hitsBoxes` / `pickOutPort` 移植。
 *
 * 三条设计（都是原型里踩过坑才定下来的，不要随手改）：
 *
 * 1. **三次贝塞尔，控制点只有纵向偏移** → 端点切线恒为竖直：从卡片底边垂直出、垂直到卡片顶边。
 *    距离短时 dy 自动收敛，曲线自然退化成近直线，不会硬拗造型。
 * 2. **同目标的并行线加「扇形偏置」**：按来源 x 从左到右编号，bias 依次为
 *    `(k-(n-1)/2)*22`。这是「多条线指向同一出站时不重合」的真正解法 ——
 *    靠几何错开，而不是靠透明度或线宽。
 * 3. **③ 层入口要避障**：③ 是随机散点，竖直落下可能穿过别的出站框，
 *    所以先试「正上方」，撞了就改从来源那一侧水平切入（见 [pickPort]）。
 */
object TopologyEdges {

    private const val DIR_UP = 0
    private const val DIR_LEFT = 1
    private const val DIR_RIGHT = 2

    /**
     * 一条算好的曲线。粒子层直接拿它求值（每帧一次三次多项式，6 次乘法，
     * 和直线插值同量级 —— 曲线不带来任何额外运行时开销）。
     */
    class Curve(
        val id: String,
        val kind: TopologyKind,
        val dashed: Boolean,
        /** 属于哪一段。①→② 用入站色，②→③ 用出站色 —— 见 [TopologyView.drawEdges] */
        val stage: TopologyEdgeStage,
        val x1: Float, val y1: Float,
        val c1x: Float, val c1y: Float,
        val c2x: Float, val c2y: Float,
        val x2: Float, val y2: Float,
    ) {
        fun xAt(t: Float): Float {
            val m = 1f - t
            return m * m * m * x1 + 3f * m * m * t * c1x + 3f * m * t * t * c2x + t * t * t * x2
        }

        fun yAt(t: Float): Float {
            val m = 1f - t
            return m * m * m * y1 + 3f * m * m * t * c1y + 3f * m * t * t * c2y + t * t * t * y2
        }
    }

    private class Port(val x: Float, val y: Float, val dir: Int)

    private class Ctrl(val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float)

    private class Live(val edge: TopologyEdge, val a: TopologyBox, val b: TopologyBox) {
        var bias = 0f
    }

    /**
     * 把 [edges] 解析成一批可直接绘制的曲线。
     *
     * ⚠ 调用方要**分两段各调一次**（①→② 和 ②→③），不要混在一起：
     * 这个方法假设「来源在目标上方、从来源底边出、进目标顶边」，两段都满足；
     * 但 [corridorY] 和避障用的框集合是**按段**取的 ——
     * ①→② 的障碍是别的规则卡、走廊在 ② 区上边界；②→③ 的障碍是别的出站卡、走廊在 ③ 区上边界。
     *
     * @param boxes      所有卡片的落点（来自 [TopologyLayout]）
     * @param unit       原型像素 → 真机像素的换算比例
     * @param clampLo/Hi 控制点横向夹取范围（视图坐标 px）
     * @param corridorY  本段目标区的上边界；从上方进入的曲线会被提前拉到这里完成变道
     */
    fun route(
        edges: List<TopologyEdge>,
        boxes: Map<String, TopologyBox>,
        unit: TopologyLayout.Unit,
        clampLo: Float,
        clampHi: Float,
        corridorY: Float,
    ): List<Curve> {
        val live = edges.mapNotNull { e ->
            val a = boxes[e.fromId] ?: return@mapNotNull null
            val b = boxes[e.toId] ?: return@mapNotNull null
            Live(e, a, b)
        }.toMutableList()
        if (live.isEmpty()) return emptyList()

        /* 先按「目标」再按「来源 x」排序：同一目标的几条线才会从左到右单调编号，
           扇形偏置才是单调的（否则编号顺序乱掉，曲线会互相交叉）。 */
        live.sortWith(compareBy({ it.edge.toId }, { it.a.cx }))

        /* 扇形偏置只在「来源确实挤在一起」时才加。
           来源已经横向拉开时曲线本来就不重合，再加偏置只会把最外侧的曲线甩出屏幕。 */
        live.groupBy { it.edge.toId }.values.forEach { g ->
            val n = g.size
            val spread = g[n - 1].a.cx - g[0].a.cx
            val crowded = n > 1 && spread < n * 24f * unit.x
            g.forEachIndexed { k, o ->
                o.bias = if (crowded) (k - (n - 1) / 2f) * 22f * unit.x else 0f
            }
        }

        val outBoxes = live.map { it.b }.distinctBy { it.id }

        return live.map { o ->
            val a = o.a
            val b = o.b
            val ax = a.cx
            val ay = a.cy + a.h / 2f

            val port = pickPort(b, outBoxes.filter { it.id != b.id }, a, unit, clampLo, clampHi, corridorY)
            val c = ctrl(ax, ay, port.x, port.y, o.bias, port.dir, unit, clampLo, clampHi)

            /* 从上方进入 ③ 卡时，把第二个控制点提前拉到走廊里（③ 上边界之上）。
               这样曲线在走廊里就完成横向变道、然后垂直落下 —— 不会在出站带内斜着扫过别的框。
               这正是「走廊」存在的意义：变道发生在空白区，而不是在卡片堆里。 */
            val c2y = if (port.dir == DIR_UP) {
                min(c.c2y, corridorY - 14f * unit.y)
            } else {
                c.c2y
            }

            Curve(
                id = "${o.edge.fromId}>${o.edge.toId}",
                kind = o.edge.kind,
                dashed = o.edge.dashed,
                stage = o.edge.stage,
                x1 = ax, y1 = ay,
                c1x = c.c1x, c1y = c.c1y,
                c2x = c.c2x, c2y = c2y,
                x2 = port.x, y2 = port.y,
            )
        }
    }

    /**
     * 为一条指向 ③ 卡的线挑入口：先试「正上方竖直落下」，会撞到别的出站框就改走侧面。
     * 优先级：上 → 来源所在的一侧 → 另一侧。三个都撞（极少见）就退回上方。
     */
    private fun pickPort(
        b: TopologyBox,
        others: List<TopologyBox>,
        a: TopologyBox,
        unit: TopologyLayout.Unit,
        clampLo: Float,
        clampHi: Float,
        corridorY: Float,
    ): Port {
        val hw = b.w / 2f
        val hh = b.h / 2f
        val up = Port(b.cx, b.cy - hh, DIR_UP)
        val left = Port(b.cx - hw, b.cy, DIR_LEFT)
        val right = Port(b.cx + hw, b.cy, DIR_RIGHT)

        val order = if (a.cx > b.cx) listOf(up, right, left) else listOf(up, left, right)
        val ax = a.cx
        val ay = a.cy + a.h / 2f

        if (others.isEmpty()) return up
        for (p in order) {
            val c = ctrl(ax, ay, p.x, p.y, 0f, p.dir, unit, clampLo, clampHi)
            val c2y = if (p.dir == DIR_UP) min(c.c2y, corridorY - 14f * unit.y) else c.c2y
            if (!hitsBoxes(ax, ay, c.c1x, c.c1y, c.c2x, c2y, p.x, p.y, others, unit)) return p
        }
        return up
    }

    /** 控制点：`dy` 随距离收敛，横向偏置叠在起点上，终点按进入方向外推 */
    private fun ctrl(
        ax: Float, ay: Float, bx: Float, by: Float,
        bias: Float, dir: Int,
        unit: TopologyLayout.Unit,
        clampLo: Float, clampHi: Float,
    ): Ctrl {
        val dist = abs(by - ay) + abs(bx - ax)
        val dy = min(70f * unit.y, max(22f * unit.y, dist * 0.42f))
        val ox = when (dir) {
            DIR_LEFT -> -dy
            DIR_RIGHT -> dy
            else -> 0f
        }
        val oy = if (dir == DIR_UP) -dy else 0f
        return Ctrl(
            (ax + bias).coerceIn(clampLo, clampHi), ay + dy,
            (bx + ox).coerceIn(clampLo, clampHi), by + oy,
        )
    }

    /**
     * 采样这条贝塞尔的中段，看有没有穿过别的框（③ 出站层的框是障碍）。
     * 20 个采样点是试出来的：12 个太稀，会漏检「擦着框角过去」的情况。
     */
    private fun hitsBoxes(
        ax: Float, ay: Float,
        c1x: Float, c1y: Float, c2x: Float, c2y: Float,
        bx: Float, by: Float,
        boxes: List<TopologyBox>,
        unit: TopologyLayout.Unit,
    ): Boolean {
        val pad = 2f * unit.x
        for (i in 1..20) {
            val t = i / 21f
            val m = 1f - t
            val px = m * m * m * ax + 3f * m * m * t * c1x + 3f * m * t * t * c2x + t * t * t * bx
            val py = m * m * m * ay + 3f * m * m * t * c1y + 3f * m * t * t * c2y + t * t * t * by
            for (o in boxes) {
                if (abs(px - o.cx) < o.w / 2f + pad && abs(py - o.cy) < o.h / 2f + pad) return true
            }
        }
        return false
    }
}
