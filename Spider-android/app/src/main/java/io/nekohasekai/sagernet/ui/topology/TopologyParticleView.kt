package io.nekohasekai.sagernet.ui.topology

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * 粒子层 —— 叠在 [TopologyView] 之上的一个**独立 View**。
 *
 * **为什么必须单独做一个 View**：原型里粒子是 SVG 内单独一个 `<g id="pdots">`，
 * 每帧只重设它的 innerHTML，不重新解析那些 path。Android 没有 DOM，但「独立 View」是等价物：
 * 每帧只 `invalidate()` 自己，**卡片层的 display list 不会被重新录制**。
 * 卡片层每帧要做十几次 `drawText`（还带 `TextUtils.ellipsize` 的文本测量），省下来的是实打实的。
 *
 * **能耗**：窗口不可见 / View 被移出窗口时立刻停掉帧循环（`onWindowVisibilityChanged`、
 * `onDetachedFromWindow`），回到可见再续上。静止时 CPU 占用为 0 —— 这是唯一一条真正值得做的优化。
 *
 * 粒子直接拿贝塞尔的控制点求值：每帧一次三次多项式（6 次乘法），
 * 和直线插值同量级 —— **曲线不带来任何额外运行时开销**，所以不需要预采样。
 */
class TopologyParticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val palette = TopologyPalette(context)

    private var curves: List<TopologyEdges.Curve> = emptyList()
    private var occluders: List<TopologyBox> = emptyList()
    private var unit = TopologyLayout.Unit(1f, 1f)

    /** 每条曲线的进度。换数据时按索引尽量保留，避免整屏粒子「跳一下」。 */
    private var progress = FloatArray(0)

    private var running = false
    private var lastFrameNs = 0L

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 由 [TopologyView] 在布局算完之后调用。粒子层自己不碰数据库、也不算布局。
     */
    fun update(
        curves: List<TopologyEdges.Curve>,
        occluders: List<TopologyBox>,
        unit: TopologyLayout.Unit,
    ) {
        this.curves = curves
        this.occluders = occluders
        this.unit = unit

        val old = progress
        progress = FloatArray(curves.size) { i ->
            if (i < old.size) old[i] else (i * 0.37f) % 1f
        }

        if (running) postInvalidateOnAnimation() else invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running || curves.isEmpty()) return

        val now = System.nanoTime()
        /* 时间步长而不是固定每帧增量：90/120Hz 屏幕上粒子速度才和 60Hz 一致。
           上限 50ms —— 卡顿或后台回前台时不要「瞬移」一大段。 */
        val dt = if (lastFrameNs == 0L) 0f
        else ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNs = now

        val r = 1.9f * unit.x
        curves.forEachIndexed { i, c ->
            var t = progress[i] + dt * SPEED
            if (t > 1f) t -= 1f
            progress[i] = t

            val x = c.xAt(t)
            val y = c.yAt(t)
            /* 粒子跑到卡片背后时本来就被玻璃挡住看不见 —— 那就干脆不画。
               视觉上完全一样，但省掉这一帧的圆形绘制，也避免「粒子钻进卡片又钻出来」的观感。 */
            if (occluded(x, y)) return@forEachIndexed

            dotPaint.color = palette.outStrokeOf(c.kind)
            canvas.drawCircle(x, y, r, dotPaint)
        }

        postInvalidateOnAnimation()
    }

    private fun occluded(x: Float, y: Float): Boolean {
        for (o in occluders) {
            if (abs(x - o.cx) < o.w / 2f && abs(y - o.cy) < o.h / 2f) return true
        }
        return false
    }

    // ------------------------------------------------------------ 生命周期

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setRunning(visibility == VISIBLE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setRunning(windowVisibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setRunning(false)
    }

    private fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        if (value) {
            lastFrameNs = 0L
            postInvalidateOnAnimation()
        } else {
            /* 停下来后 invalidate 一次：onDraw 直接 return，本层被录制成空 →
               叠在下面的卡片层原样露出来，不留残影。 */
            invalidate()
        }
    }

    private companion object {
        /** 每秒走完一条曲线的比例。原型是每帧 +0.005（60fps ≈ 0.3/s），保持一致。 */
        const val SPEED = 0.30f
    }
}
