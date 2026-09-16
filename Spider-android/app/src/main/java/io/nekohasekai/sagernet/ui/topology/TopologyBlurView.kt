package io.nekohasekai.sagernet.ui.topology

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View

/**
 * 拓扑图硬件级真实模糊背景层（Android 12+ RenderEffect）。
 * 位于 TopologyView 底部，负责绘制卡片和 chip 的毛玻璃外发光与背景模糊填充，
 * 与上层清晰的文字、描边、粒子完全解耦，既保证极致视觉质感，又避免文字被模糊或重复测量。
 */
class TopologyBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var boxList: List<BlurItem> = emptyList()

    data class BlurItem(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val radius: Float,
        val color: Int,
        val alpha: Int,
    )

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurRadius = 10f * density
            setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
        }
    }

    fun setBlurBoxes(items: List<BlurItem>) {
        boxList = items
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || boxList.isEmpty()) return
        for (item in boxList) {
            fillPaint.color = item.color
            fillPaint.alpha = item.alpha
            val l = item.cx - item.w / 2f
            val t = item.cy - item.h / 2f
            canvas.drawRoundRect(l, t, l + item.w, t + item.h, item.radius, item.radius, fillPaint)
        }
    }
}
