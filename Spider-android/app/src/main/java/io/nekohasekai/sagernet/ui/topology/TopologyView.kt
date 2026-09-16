package io.nekohasekai.sagernet.ui.topology

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.nekohasekai.sagernet.R

/**
 * 触摸命中的目标。
 *
 * 刻意做得很薄：View 只回答「你点到了哪个东西」，**不决定点了之后干什么** ——
 * 跳哪个页面、要不要写库是 Fragment 的事。这样这个类不需要认识任何 Activity。
 *
 * 唯一的例外是**层标签**：点它既不跳页也不写库，只是把自己的文字临时提亮，
 * 所以 View 就地处理掉了（见 [TopologyView.labelAt]），**不往外抛事件**。
 * 抛出去的话 [io.nekohasekai.sagernet.ui.TopologyFragment] 那两个穷尽的 `when`
 * 都得为它补一个空分支，反而更难读。
 */
sealed interface TopologyHit {

    /** 命中的卡片 id（`in-<host>` / `ru-<ruleId>` / `ou-...` / 溢出胶囊）。 */
    val id: String

    /** ① 入站 chip。带上整个对象，因为详情浮层要显示它的进程/流量/命中规则。 */
    data class Inbound(val chip: TopologyInbound) : TopologyHit {
        override val id: String get() = chip.id
    }

    /** ② 规则卡 / ③ 出站卡 / 溢出胶囊 —— 都由卡 id 表达，由 Fragment 去解析含义。 */
    data class Card(override val id: String) : TopologyHit
}

/**
 * 三层拓扑图 —— 卡片 + 连线。**粒子不在这里**，在叠在上面的 [TopologyParticleView]。
 *
 * 这是全项目第一个重写 `onDraw` 的 View（之前没有任何 Canvas/Paint 用法），所以刻意做得很克制：
 *
 * - **不做每帧动画**。数据变了或尺寸变了才重算一次布局，静止时 CPU 占用为 0。
 * - **不做模糊**。`minSdk 21`，真模糊要 Android 12+ 的 `RenderEffect`，
 *   所以这里只用「半透明填充 + 细描边」模拟玻璃质感，任何版本都一致。
 * - 布局用比例算：三层区域的边界都按视图宽高的百分比取，所以任意屏幕尺寸都保持同一套空间分配。
 * - 布局是**预计算**的（[relayout]），不在 `onDraw` 里惰性算 —— 因为算完要把曲线推给粒子层。
 *
 * 交互只有两类：**卡片**（点击 / 长按，通过 [onCardClick] / [onCardLongClick] 交给 Fragment）
 * 和**层标签**（点击提亮，View 就地处理，不外抛 —— 它不改数据也不跳页）。
 */
class TopologyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * 数据快照。
     *
     * ⚠ **只有「结构」变了才重算布局。** 内核每 2 秒推一次 `/connections` 快照
     * （见 `ClashApiClient.subscribeConnections`），但那些快照里**绝大多数时候只有流量数字在动**，
     * 而流量数字在这张图上是**画不出来的** —— ① chip 只画图标 + 域名，流量只在详情浮层里。
     * 无条件 `relayout()` 的话，每 2 秒就要重算三层布局 + 两组连线 + 重建粒子曲线 + 全量重绘一次，
     * 而这些结果和上一帧一模一样。
     *
     * 判定用 [structureKey] 而不是 `equals`：`TopologySnapshot` 是 data class，
     * 流量一变 `equals` 就是 false，等于没判。
     */
    var snapshot: TopologySnapshot? = null
        set(value) {
            field = value
            val key = value?.let(::structureKey)
            if (key != lastStructureKey) {
                lastStructureKey = key
                relayout()
            } else {
                updateParticleCurves(value)
            }
        }

    /** 上一次 [relayout] 时的结构指纹。null = 还没算过，或尺寸变了要强制重算。 */
    private var lastStructureKey: String? = null

    /**
     * 「结构指纹」—— 只包含**会影响画法**的字段。
     *
     * **故意不含** ① 的 `upload` / `download` / `connectionCount` / `startAt` /
     * `endpoint` / `appPackage` / `ruleName` / `matchKind` —— 它们一个都不参与绘制
     * （详情浮层用的是 `TopologyInbound` 对象本身，不经过这里）。
     *
     * **必须含**：
     * - ① 的 `id` / `label` / `ruleCardId`（前两个决定 chip 文字，第三个决定有没有 ①→② 连线）
     * - ②③ 的 `id` / `title` / `subtitle` / `kind`（文字 + 描边色）
     * - 三个溢出计数（决定有没有「+N 更多」胶囊）
     * - `inboundState`（空态文案：未开启 / 连不上 / 真的没有连接，是三句不同的话）
     *
     * 用 String 而不是 `hashCode()`：碰撞的代价是「该重算时没重算」，
     * 画面会永远停在旧位置上，这种错必须**不可能**发生。
     * 30 张卡约 1KB，2 秒一次，成本可以忽略。
     */
    /** 上一次缓存的 ②③ 层结构 key，以及对应的 rules / outbounds / overflow 指纹 */
    private var lastDbStructureKey: String? = null
    private var lastRulesRef: List<TopologyCard>? = null
    private var lastOutboundsRef: List<TopologyCard>? = null
    private var lastRuleOverflow: Int = -1
    private var lastOutboundOverflow: Int = -1

    private fun dbStructureKey(snap: TopologySnapshot): String {
        if (lastDbStructureKey != null &&
            lastRulesRef === snap.rules &&
            lastOutboundsRef === snap.outbounds &&
            lastRuleOverflow == snap.ruleOverflow &&
            lastOutboundOverflow == snap.outboundOverflow
        ) {
            return lastDbStructureKey!!
        }
        val key = buildString(256) {
            append(snap.ruleOverflow).append('|').append(snap.outboundOverflow).append('|')
            snap.rules.forEach {
                append(it.id).append('\u001f').append(it.title).append('\u001f')
                    .append(it.subtitle).append('\u001f').append(it.kind.ordinal).append('\u001e')
            }
            append('|')
            snap.outbounds.forEach {
                append(it.id).append('\u001f').append(it.title).append('\u001f')
                    .append(it.subtitle).append('\u001f').append(it.kind.ordinal).append('\u001e')
            }
        }
        lastRulesRef = snap.rules
        lastOutboundsRef = snap.outbounds
        lastRuleOverflow = snap.ruleOverflow
        lastOutboundOverflow = snap.outboundOverflow
        lastDbStructureKey = key
        return key
    }

    /**
     * 「结构指纹」—— 只包含**会影响画法**的字段。
     *
     * 拆分缓存：②③ 层来自数据库，在推送过程中不会变，复用 [dbStructureKey] 避免反复拼接字符串。
     * ① 层在尾部追加，包含 [TopologyInbound.sizeHint] 变动检测。
     */
    private fun structureKey(snap: TopologySnapshot): String = buildString(512) {
        append(snap.inboundState.ordinal).append('|')
        append(dbStructureKey(snap)).append('|')
        snap.inbounds.forEach {
            append(it.id).append('\u001f').append(it.displayLabel).append('\u001f')
                .append(it.ruleCardId ?: "").append('\u001f')
                .append(it.sizeHint).append('\u001e')
        }
    }

    /** 硬件模糊背景层 */
    var blurView: TopologyBlurView? = null

    /** 粒子层。卡片层是它唯一的数据源 —— 粒子层自己不碰数据库、也不算布局。 */
    var particleView: TopologyParticleView? = null

    private val palette = TopologyPalette(context)
    private val density = resources.displayMetrics.density

    private val boxes = HashMap<String, TopologyBox>()
    private var curves: List<TopologyEdges.Curve> = emptyList()

    /**
     * 「省略号之后的文字」缓存，按卡片 id 存。
     *
     * `TextUtils.ellipsize` 内部要 `measureText`，是 `onDraw` 里最贵的一步：
     * 每张卡 2 次（标题 + 副标题）× 30 张 = **60 次 `measureText` 每帧**。
     * 静止时只画一次，无所谓；但推送一来就是每 2 秒一次全量重绘 —— 那时候它是唯一的瓶颈。
     *
     * 缓存的合法性来自一个事实：**省略结果只取决于 (文字, 字号, 可用宽度)**，
     * 而这三样在 [computeLayout] 之后就定死了。所以只要在 [computeLayout] 里清空就够了，
     * key 里不必再带宽度 —— 带了也只是把同一个字符串重复算一遍。
     */
    private val titleCache = HashMap<String, String>()
    private val subCache = HashMap<String, String>()
    private val chipCache = HashMap<String, String>()

    /** 原型像素 → 真机像素。见 [computeLayout] 里的推导：就是 `w/366, h/820`。 */
    private var unit = TopologyLayout.Unit(1f, 1f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    /** 单击卡片。null 表示这一层不关心（阶段 4 之前就是这样）。 */
    var onCardClick: ((TopologyHit) -> Unit)? = null

    /** 长按卡片。目前只有 ② 规则卡和 ① chip 用。 */
    var onCardLongClick: ((TopologyHit) -> Unit)? = null

    /** ACTION_DOWN 那一刻命中的目标。UP 时要求仍命中同一个，避免「按下在 A、抬起在 B」误触发。 */
    private var downHit: TopologyHit? = null

    /**
     * ACTION_DOWN 那一刻命中的层标签下标，-1 = 没命中。
     *
     * 和 [downHit] 分开存而不是塞进 [TopologyHit]：标签不参与「卡片」那一套语义，
     * 混进去就得让 [TopologyHit] 多一个永远到不了 Fragment 的分支。
     */
    private var downLabel = -1

    /**
     * 三层标签的文案，顺序 = 从上到下，**必须**跟 [TopologyLabelGeometry.LAYER_TOPS] 一一对应。
     *
     * 拆成两处（几何在纯对象里、文案在这里）是因为 `R.string` 属于 Android，
     * 而几何要能被验证脚本单独编译。顺序对不上会在构造时直接抛异常（见 [init]）。
     */
    private val labelRes = intArrayOf(
        R.string.topology_layer_inbound,
        R.string.topology_layer_rule,
        R.string.topology_layer_out,
    )

    /**
     * 每层标签是否被点亮。
     *
     * **纯手动**：不自动复位、也不互斥 —— 照抄原型的 `el.classList.toggle('show')`，
     * 每个标签各记各的，三个全亮也是合法状态（用户就是想一次都看清楚）。
     * 自动复位会跟「中文常驻但刻意压淡」的意图打架：用户刚点亮想看清，
     * 界面自己又暗回去，等于让他再点一次。
     */
    private val labelBright = BooleanArray(TopologyLabelGeometry.LAYER_TOPS.size)

    init {
        require(labelRes.size == TopologyLabelGeometry.LAYER_TOPS.size) {
            "层标签的文案和几何表对不上：文案 ${labelRes.size} 条、" +
                "几何 ${TopologyLabelGeometry.LAYER_TOPS.size} 条 —— 加层时两边都要改"
        }
    }

    /**
     * 用 [GestureDetector] 而不是自己算 slop 和长按计时：
     * 长按阈值、触摸抖动、双击窗口这些都是系统行为，自己实现一定会跟系统不一致。
     */
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            /**
             * 返回 false 就不会消费这次 DOWN —— 按在空白处时，下拉刷新照常工作。
             *
             * 层标签命中也返回 true。这是必须的：父层是 SwipeRefreshLayout，
             * 没人消费 DOWN 时它会把整串手势拿去做下拉刷新，标签就再也收不到 UP。
             * 代价是标签那一条（约 24dp 高、文字宽）里拉不动刷新 —— 换来标签真能点。
             * 标签本身很窄，而且只要有卡片压在上面就优先给卡片，所以实际影响很小。
             */
            override fun onDown(e: MotionEvent): Boolean {
                downHit = hitTest(e.x, e.y)
                /* 卡片优先 —— 见 TopologyLabelGeometry.pickHit。标签的命中区会压在 ① 层上
                   （屏幕矮的时候尤其明显），必须靠这条规则保证 chip 的点击不被抢走。 */
                downLabel = TopologyLabelGeometry.pickHit(downHit != null, labelAt(e.x, e.y))
                return downHit != null || downLabel >= 0
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val layer = downLabel
                if (layer >= 0) {
                    performClick()
                    /* 就地翻转，不往外抛 —— 这是纯视觉状态，不涉及数据也不涉及跳页 */
                    labelBright[layer] = !labelBright[layer]
                    invalidate()
                    return true
                }
                val hit = downHit ?: return false
                performClick()
                onCardClick?.invoke(hit)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val hit = downHit ?: return
                /* 长按要有触觉反馈，否则用户不知道是自己按久了还是界面卡了 */
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onCardLongClick?.invoke(hit)
            }

            /* 空实现：默认的 onShowPress 会让按下时有高亮态，这里没有按下态设计 */
            override fun onShowPress(e: MotionEvent) = Unit
        }
    )

    /**
     * 触摸事件全部转给 [gestureDetector]。
     *
     * `ClickableViewAccessibility` 要求「可点击的 View 必须在 onTouchEvent 里调 performClick」。
     * 这里是**委托**给 GestureDetector 的，`performClick()` 在 `onSingleTapUp` 里调 ——
     * 要求本身是满足的，只是 lint 看不穿这层委托（它只扫 onTouchEvent 的方法体）。
     * 所以这里显式抑制，而不是为了让 lint 满意去把点击判定拆成两份。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(event)) return true
        return super.onTouchEvent(event)
    }

    /** 无障碍要求：可点击的 View 必须实现它，否则 TalkBack 无法触发点击 */
    override fun performClick(): Boolean = super.performClick()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        /* 尺寸变了 → 同一个结构指纹对应的落点也全变了，必须强制重算。
           不能只把指纹置空就完事：置空之后如果不重新写回，后面每一次推送
           都会「看起来结构变了」而全量重算。所以 [relayout] 结尾会把指纹写回去。 */
        lastStructureKey = null
        relayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snap = snapshot ?: return
        if (width <= 0 || height <= 0) return
        drawLayerLabels(canvas)
        drawEdges(canvas)
        drawCards(canvas, snap)
    }

    // ---------------------------------------------------------------- 布局

    /**
     * 一次性把「卡片落点 + 连线曲线」都算出来，然后把曲线推给粒子层。
     * 只在数据变化 / 尺寸变化时调用，不在每帧调用。
     */
    private fun relayout() {
        val snap = snapshot ?: return
        if (width <= 0 || height <= 0) return

        computeLayout(snap)

        val w = width.toFloat()
        val h = height.toFloat()
        val clampLo = TopologyRegions.CLAMP_X0 * w
        val clampHi = TopologyRegions.CLAMP_X1 * w

        /* 分两段各算一次：两段的走廊位置和避障框集合都不一样 ——
           ①→② 的障碍是别的规则卡、走廊在 ② 区上边界；
           ②→③ 的障碍是别的出站卡、走廊在 ③ 区上边界。
           混在一起算会让两段的避障互相干扰。 */
        curves = TopologyEdges.route(
            edges = snap.edges.filter { it.stage == TopologyEdgeStage.INBOUND },
            boxes = boxes,
            unit = unit,
            clampLo = clampLo,
            clampHi = clampHi,
            corridorY = TopologyRegions.RU_Y0 * h,
        ) + TopologyEdges.route(
            edges = snap.edges.filter { it.stage == TopologyEdgeStage.OUTBOUND },
            boxes = boxes,
            unit = unit,
            clampLo = clampLo,
            clampHi = clampHi,
            corridorY = TopologyRegions.OUT_Y0 * h,
        )

        updateParticleCurves(snap)

        /* 算完了就把指纹写回去 —— 这样 [onSizeChanged] 那边「置空 → 重算」
           只会让这一次重算发生，不会让后面每一次推送都跟着重算。 */
        lastStructureKey = structureKey(snap)
        invalidate()
    }

    /**
     * 粒子**只在命中规则时才在 ②→③ 连线上流动**：
     * 从 ①→② 提取当前有流量流入的规则 ID（activeRuleIds），
     * ②→③ 连线中只有来源是这些活跃规则的连线，才注入粒子流动。
     * 没有流量时粒子完全静止，省电且真实反映数据流动。
     */
    private fun updateParticleCurves(snap: TopologySnapshot?) {
        if (snap == null) {
            particleView?.update(emptyList(), boxes.values.toList(), unit)
            return
        }
        val activeRuleIds = snap.edges
            .filter { it.stage == TopologyEdgeStage.INBOUND }
            .mapTo(HashSet()) { it.toId }

        val activeOutboundCurves = curves.filter {
            it.stage == TopologyEdgeStage.OUTBOUND && it.fromId in activeRuleIds
        }

        particleView?.update(
            activeOutboundCurves,
            boxes.values.toList(),
            unit,
        )
    }

    private fun computeLayout(snap: TopologySnapshot) {
        boxes.clear()
        /* 落点变了 → 卡宽高可能全变 → 省略结果全部作废。见 [titleCache]。 */
        titleCache.clear()
        subCache.clear()
        chipCache.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val x0 = TopologyRegions.X0 * w
        val x1 = TopologyRegions.X1 * w
        val regionW = x1 - x0

        /* 原型像素 → 真机像素，只有一个换算比例：
             横向 = 区域宽 / 342 = (342/366·w) / 342 = w / 366
             纵向 = ② 区域高 / 164 = (164/820·h) / 164 = h / 820  （③ 同理，135/820·h/135）
           所以整张图的单位就是 (w/366, h/820) —— 卡宽上限 62、卡高 46、抖动 20、振幅 64
           这些原型常数全部乘它。以前每个区域各写一份等价表达式，改区域常数时会静默失配。 */
        unit = TopologyLayout.Unit(w / 366f, h / 820f)

        /* ① 入站 chip：散点云。矮 22、gap 8、每行 3~5 张（原型同款参数）。
           宽度按「放大后的最大宽度」入算（× 1.15 = 景深上限），这样即使最靠下、
           放大到 1.15 倍的那张 chip 也不会顶到邻居 —— 打包时就留够了位。

           ⚠ 高度也必须同样处理（这是阶段 4 把上限提到 26 时才暴露出来的）：
           以前宽按 ×1.15 预留、高却只按 22 预留，绘制时宽高**都**乘景深缩放，
           于是画出来的 chip 最高 22×1.15 = 25.3，比预留的 22 高。
           18 张时行数少、余量足，刚好没露馅；26 张时行变多、行距被压紧，
           底部那几行直接越出 ① 区（实测 128 处越界、3 处重叠）。
           现在高也按 ×1.15 预留，绘制时再除回来（见 drawInboundChip），
           这样「预留框 ⊇ 任何可能画出来的矩形」在构造上成立，而不是靠余量恰好够。 */
        if (snap.inbounds.isNotEmpty()) {
            val items = ArrayList<TopologyLayout.Item>(snap.inbounds.size)
            val baseH = 22f * TopologyLayout.DEPTH_MAX * unit.y
            snap.inbounds.forEach {
                val itemW = TopologyLayout.chipWidth(it.displayLabel) * it.sizeHint * TopologyLayout.DEPTH_MAX * unit.x
                val itemH = baseH * it.sizeHint
                items.add(TopologyLayout.Item(it.id, itemW, h = itemH))
            }
            TopologyLayout.placeScattered(
                items, x0, x1,
                TopologyRegions.IN_Y0 * h, TopologyRegions.IN_Y1 * h,
                baseH, gap = 8f * unit.y,
                capLo = 3, capHi = 5,
                seed = 20260914L, layer = 1, unit = unit, out = boxes,
            )
        }

        /* ② 规则：弧形。顺序 = 内核匹配优先级，绝不重排；胶囊按定宽占位。 */
        if (snap.rules.isNotEmpty() || snap.ruleOverflow > 0) {
            val cardH = 46f * unit.y
            /* 「装得下最长标题」需要多宽 —— 传给 placeArc 当均分的**上限**。
               ⚠ 字号必须用 drawCard 里那套**完全相同**的算法算出来（含 10.5dp 下限），
               否则量出来的宽度是假的：下限在小屏/低密度下会把实际字号顶到远大于
               `cardH * 0.30`，按后者量就会低估一半。
               drawCard 的内边距是 box.w 的 10%（两侧共 20%），
               即可用文字宽 = 0.8 × box.w → 反解 box.w = 文字宽 / 0.8。 */
            val titleSize = (cardH * 0.30f).coerceIn(10.5f * density, 16f * density)
            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textSize = titleSize
            val titleNeed = snap.rules.maxOfOrNull { textPaint.measureText(it.title) } ?: 0f
            /* +2dp 是浮点/字距的安全余量：贴着 0.8×w 算出来的宽度，
               真到 ellipsize 那一步会因为零点几像素的差而切掉最后一个字母。 */
            val flexMinW = titleNeed / 0.8f + 2f * density

            val items = ArrayList<TopologyLayout.Item>(snap.rules.size + 1)
            snap.rules.forEach { items.add(TopologyLayout.Item(it.id, 0f, flexMinW)) }
            if (snap.ruleOverflow > 0) {
                items.add(TopologyLayout.Item(ID_RULE_MORE, 46f * unit.x))
            }
            TopologyLayout.placeArc(
                items, x0, x1,
                TopologyRegions.RU_Y0 * h, TopologyRegions.RU_Y1 * h,
                cardH, 2, unit, boxes,
            )
        }

        /* ③ 出站：散点云。 */
        if (snap.outbounds.isNotEmpty() || snap.outboundOverflow > 0) {
            val cardW = 72f * unit.x
            val items = ArrayList<TopologyLayout.Item>(snap.outbounds.size + 1)
            snap.outbounds.forEach { items.add(TopologyLayout.Item(it.id, cardW)) }
            if (snap.outboundOverflow > 0) {
                items.add(TopologyLayout.Item(ID_OUT_MORE, cardW))
            }
            TopologyLayout.placeScattered(
                items, x0, x1,
                TopologyRegions.OUT_Y0 * h, TopologyRegions.OUT_Y1 * h,
                46f * unit.y, gap = 8f * unit.y, capLo = 1, capHi = 3,
                seed = 20260914L, layer = 3, unit = unit, out = boxes,
            )
        }

        /* 收集真实模糊层几何数据（Android 12+） */
        blurView?.let { bv ->
            val blurItems = ArrayList<TopologyBlurView.BlurItem>(boxes.size)
            snap.inbounds.forEach { chip ->
                boxes[chip.id]?.let { box ->
                    val depth = TopologyLayout.depthAt(box.cy, TopologyRegions.IN_Y0 * h, TopologyRegions.IN_Y1 * h)
                    val s = TopologyLayout.depthScale(depth)
                    val alpha = (TopologyLayout.depthAlpha(depth) * 255f).toInt().coerceIn(0, 255)
                    val bw = box.w / TopologyLayout.DEPTH_MAX * s
                    val bh = box.h / TopologyLayout.DEPTH_MAX * s
                    val radius = minOf(bh / 2f, 9f * unit.y * s)
                    blurItems.add(
                        TopologyBlurView.BlurItem(
                            box.cx, box.cy, bw, bh, radius,
                            palette.cardFill, (alpha * 0.85f).toInt()
                        )
                    )
                }
            }
            snap.rules.forEach { card ->
                boxes[card.id]?.let { box ->
                    val radius = minOf(box.h * 0.24f, 11f * density)
                    blurItems.add(
                        TopologyBlurView.BlurItem(
                            box.cx, box.cy, box.w, box.h, radius,
                            palette.cardFill, 220
                        )
                    )
                }
            }
            snap.outbounds.forEach { card ->
                boxes[card.id]?.let { box ->
                    val radius = minOf(box.h * 0.24f, 11f * density)
                    blurItems.add(
                        TopologyBlurView.BlurItem(
                            box.cx, box.cy, box.w, box.h, radius,
                            palette.cardFill, 220
                        )
                    )
                }
            }
            bv.setBlurBoxes(blurItems)
        }
    }

    // ---------------------------------------------------------------- 触摸

    /**
     * 命中判定。
     *
     * **按绘制矩形算，不是按预留框** —— chip 画出来时宽高都乘了景深缩放（0.85~1.15），
     * 拿预留框去判会明显偏大，手指点在两张 chip 之间的空白也会被算成命中。
     *
     * **触摸目标补偿**：chip 画出来只有约 17dp 高（1080×1894 的机器上 `22 × h/820`），
     * 远低于 48dp 的推荐触摸尺寸，所以纵向补到至少 24dp、横向补 4dp。
     * 补完相邻 chip 的判定区**一定会重叠**（它们只隔 8 个原型单位）——
     * 这时取**离触点最近**的那个中心，而不是先遍历到的那个，手感才符合直觉。
     *
     * 遍历顺序是绘制顺序的**逆序**（后画的在上）：③ → ② → ①。
     * 三层区域本来不重叠，但胶囊和卡片在同一层，逆序能保证「看得见的在上」。
     */
    private fun hitTest(x: Float, y: Float): TopologyHit? {
        val snap = snapshot ?: return null
        if (boxes.isEmpty()) return null

        var best: TopologyHit? = null
        var bestDist = Float.MAX_VALUE

        fun consider(hit: TopologyHit, b: TopologyBox, w: Float, h: Float, expandY: Boolean) {
            val padX = 4f * density
            val padY = if (expandY) ((24f * density - h) / 2f).coerceAtLeast(0f) else 0f
            if (x < b.cx - w / 2f - padX || x > b.cx + w / 2f + padX) return
            if (y < b.cy - h / 2f - padY || y > b.cy + h / 2f + padY) return
            val dx = x - b.cx
            val dy = y - b.cy
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = hit
            }
        }

        snap.outbounds.forEach { card ->
            boxes[card.id]?.let { consider(TopologyHit.Card(card.id), it, it.w, it.h, expandY = false) }
        }
        boxes[ID_OUT_MORE]?.let { consider(TopologyHit.Card(ID_OUT_MORE), it, it.w, it.h, expandY = false) }

        snap.rules.forEach { card ->
            boxes[card.id]?.let { consider(TopologyHit.Card(card.id), it, it.w, it.h, expandY = false) }
        }
        boxes[ID_RULE_MORE]?.let { consider(TopologyHit.Card(ID_RULE_MORE), it, it.w, it.h, expandY = false) }

        val inTop = TopologyRegions.IN_Y0 * height
        val inBot = TopologyRegions.IN_Y1 * height
        snap.inbounds.forEach { chip ->
            boxes[chip.id]?.let { b ->
                val s = TopologyLayout.depthScale(TopologyLayout.depthAt(b.cy, inTop, inBot))
                consider(
                    TopologyHit.Inbound(chip), b,
                    b.w / TopologyLayout.DEPTH_MAX * s, b.h * s, expandY = true,
                )
            }
        }

        return best
    }

    /**
     * 层标签的命中判定 —— 返回层下标，-1 = 没命中。
     *
     * 几何全部交给 [TopologyLabelGeometry]，这里只做「取文字宽度 + 比大小」。
     * 基线算出来 ≤ 0 就跳过：跟 [drawLayerLabels] 一样，**画不出来的就不该能点**，
     * 不然会出现「点了有反应但屏幕上什么也没有」。
     */
    private fun labelAt(x: Float, y: Float): Int {
        if (width <= 0 || height <= 0) return -1
        val h = height.toFloat()
        val left = TopologyRegions.X0 * width.toFloat()

        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = TopologyLabelGeometry.SIZE_DP * density
        val hl = TopologyLabelGeometry.hitLeftX(left, density)

        TopologyLabelGeometry.LAYER_TOPS.forEachIndexed { i, top ->
            val baseline = TopologyLabelGeometry.baselineY(top, h, density)
            if (baseline <= 0f) return@forEachIndexed
            if (x < hl) return@forEachIndexed
            val band = TopologyLabelGeometry.band(top, h, density)
            if (!band.usable || !band.contains(y)) return@forEachIndexed
            val tw = textPaint.measureText(context.getString(labelRes[i]))
            if (x > TopologyLabelGeometry.hitRightX(left, tw, density)) return@forEachIndexed
            return i
        }
        return -1
    }

    // ---------------------------------------------------------------- 绘制

    /**
     * 三层小标签：中文常驻，但刻意压得很淡 —— 需要时看得见、用的时候几乎感觉不到。
     *
     * 点一下临时提亮看清、再点一下回淡，**纯手动**（照抄原型的 `toggleLabel`）。
     * 常态压到 [TopologyLabelGeometry.ALPHA] 是有意为之，理由见那个常量的注释。
     * 这里没有透明度动画 —— 真机上这就是一次 `invalidate()`；
     * 加动画得引入每帧重绘，为一条 9.5dp 的标签不值。
     */
    private fun drawLayerLabels(canvas: Canvas) {
        val h = height.toFloat()
        val x = TopologyRegions.X0 * width.toFloat()
        textPaint.typeface = Typeface.DEFAULT
        textPaint.color = palette.layerLabel
        textPaint.textSize = TopologyLabelGeometry.SIZE_DP * density

        TopologyLabelGeometry.LAYER_TOPS.forEachIndexed { i, top ->
            val baseline = TopologyLabelGeometry.baselineY(top, h, density)
            if (baseline > 0f) {
                textPaint.alpha =
                    if (labelBright[i]) TopologyLabelGeometry.ALPHA_BRIGHT else TopologyLabelGeometry.ALPHA
                canvas.drawText(context.getString(labelRes[i]), x, baseline, textPaint)
            }
        }

        textPaint.alpha = 255
    }

    /**
     * 连线画在卡片**之前** —— 视觉上线在玻璃卡片底下。
     *
     * 配色按**段**分：②→③ 跟着**出站目标**走（同一个节点永远是同一个颜色），
     * ①→② 用固定的入站色 —— 因为入站→规则这一段表达的是「这条流量归哪条规则管」，
     * 还没到"走哪个出口"，用出站色会提前泄露结论。
     */
    private fun drawEdges(canvas: Canvas) {
        if (curves.isEmpty()) return
        val dotR = 2f * unit.x
        val dash = DashPathEffect(floatArrayOf(4f * unit.x, 4f * unit.x), 0f)

        curves.forEach { c ->
            val inbound = c.stage == TopologyEdgeStage.INBOUND
            val color = when (c.stage) {
                TopologyEdgeStage.INBOUND -> palette.inbound
                TopologyEdgeStage.OUTBOUND -> palette.outStrokeOf(c.kind)
            }
            linePaint.color = color
            /* ①→② 比 ②→③ 更细、更淡 —— 照抄原型：`in` 段 stroke-width=1 / opacity=.42，
               `out` 段 stroke-width=1.2 / opacity=.85。这不是随手调的手感：
               ①→② 最多 18 条，而 ① 层本身是散点，实测约八成曲线会从别的 chip 后面穿过去
               （②→③ 只有 0.7%）。画成实色就成了一层密网，玻璃质感全没了。
               好在连线画在卡片之前，穿过 chip 的部分本来就被半透明的卡片压住。 */
            linePaint.strokeWidth = (if (inbound) 1f else 1.2f) * unit.x
            /* 目标节点已删除 → 虚线 + 明显降低存在感，一眼能看出「这条线指向的东西没了」 */
            linePaint.alpha = when {
                c.dashed -> 115
                inbound -> 107
                else -> 217
            }
            linePaint.pathEffect = if (c.dashed) dash else null

            path.reset()
            path.moveTo(c.x1, c.y1)
            path.cubicTo(c.c1x, c.c1y, c.c2x, c.c2y, c.x2, c.y2)
            canvas.drawPath(path, linePaint)

            dotPaint.color = color
            dotPaint.alpha = if (c.dashed) 115 else 230
            canvas.drawCircle(c.x1, c.y1, dotR, dotPaint)
            canvas.drawCircle(c.x2, c.y2, dotR, dotPaint)
        }
        linePaint.pathEffect = null
        linePaint.alpha = 255
        dotPaint.alpha = 255
    }

    private fun drawCards(canvas: Canvas, snap: TopologySnapshot) {
        snap.inbounds.forEach { chip ->
            boxes[chip.id]?.let { drawInboundChip(canvas, it, chip) }
        }
        snap.rules.forEach { card ->
            boxes[card.id]?.let {
                drawCard(canvas, it, card.title, card.subtitle, palette.strokeOf(card.kind))
            }
        }
        snap.outbounds.forEach { card ->
            boxes[card.id]?.let {
                drawCard(canvas, it, card.title, card.subtitle, palette.outStrokeOf(card.kind), isOutbound = true)
            }
        }
        boxes[ID_RULE_MORE]?.let { drawCapsule(canvas, it, snap.ruleOverflow) }
        boxes[ID_OUT_MORE]?.let { drawCapsule(canvas, it, snap.outboundOverflow) }
        drawEmptyStates(canvas, snap)
    }

    /**
     * ① 入站 chip —— 单行，只有【图标 + 名称】。
     *
     * 不显示 IP、不显示流量：原型里这两样都在详情浮层里（阶段 4 做），
     * 22px 高的 chip 塞两行字在 16:9 老机上会糊成一团。
     *
     * **景深**（原型同款）：`d` = 在 ① 区内的归一化 y，越靠下（越"近"）越大越清晰。
     * `s = 0.85 + 0.30d`、`alpha = 0.55 + 0.45d`。
     * ⚠ 字号的下限卡在**缩放之后**，而不是缩放之前 —— 否则在 720×1280 这类老机上
     * 最远的那张 chip 会掉到 6.6dp，读不出来。原型自己的注释也是这个意思：
     * 「景深是氛围，不能牺牲信息」。
     */
    private fun drawInboundChip(canvas: Canvas, box: TopologyBox, chip: TopologyInbound) {
        val h = height.toFloat()
        val depth = TopologyLayout.depthAt(
            box.cy,
            TopologyRegions.IN_Y0 * h,
            TopologyRegions.IN_Y1 * h,
        )
        val s = TopologyLayout.depthScale(depth)
        val alpha = (TopologyLayout.depthAlpha(depth) * 255f).toInt().coerceIn(0, 255)

        /* 宽和高都要先**除回** DEPTH_MAX：布局为了「预留框 ⊇ 任何绘制结果」，
           两个方向都是按 ×DEPTH_MAX 入算的（见 computeLayout）。这里除回来，
           再乘本张自己的景深缩放 s，才是真正的绘制尺寸。
           漏掉高这一项的话，画出来的 chip 会变成 22×1.15×1.15 ≈ 29，比预留的还高。 */
        val w = box.w / TopologyLayout.DEPTH_MAX * s
        val hh = box.h / TopologyLayout.DEPTH_MAX * s
        val l = box.cx - w / 2f
        val t = box.cy - hh / 2f
        val radius = minOf(hh / 2f, 9f * unit.y * s)

        /* 玻璃拟态：半透明填充 + 极细描边（不做真模糊，见类注释） */
        fillPaint.color = palette.cardFill
        fillPaint.alpha = (alpha * 0.82f).toInt()
        canvas.drawRoundRect(l, t, l + w, t + hh, radius, radius, fillPaint)

        strokePaint.color = palette.inbound
        strokePaint.strokeWidth = 0.5f * unit.x
        strokePaint.alpha = (alpha * 0.55f).toInt()
        canvas.drawRoundRect(l, t, l + w, t + hh, radius, radius, strokePaint)
        strokePaint.strokeWidth = density

        /* 字号按缩放**之后**卡下限，保证最远的那张也读得出来 */
        val size = (hh * 0.4545f).coerceIn(9f * density, 15f * density)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = size
        textPaint.color = palette.textSecondary

        val pad = 7f * unit.x * s
        val icon = if (isLanHost(chip.label)) ICON_LAN else ICON_WEB
        val iconW = textPaint.measureText(icon)
        val gap = 4f * unit.x * s
        val labelText = chip.displayLabel
        val labelMax = (w - pad * 2f - iconW - gap).coerceAtLeast(1f)
        val label = chipCache.getOrPut("${box.id}_${w.toInt()}") {
            TextUtils.ellipsize(labelText, textPaint, labelMax, TextUtils.TruncateAt.MIDDLE).toString()
        }

        val fm = textPaint.fontMetrics
        val baseline = box.cy - (fm.ascent + fm.descent) / 2f
        textPaint.alpha = alpha
        canvas.drawText(icon, l + pad, baseline, textPaint)
        canvas.drawText(label, l + pad + iconW + gap, baseline, textPaint)
        textPaint.alpha = 255

        /* 恢复成卡片层用的笔宽/透明度，别把状态漏给下一个绘制者 */
        strokePaint.alpha = 255
        fillPaint.alpha = 255
    }

    /** 内网 / 回环地址给个「家」的图标，跟原型的 `lan` 判断一致 */
    private fun isLanHost(host: String): Boolean =
        host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("127.") ||
            LAN_172.containsMatchIn(host)

    private fun drawCard(
        canvas: Canvas,
        box: TopologyBox,
        title: String,
        subtitle: String,
        strokeColor: Int,
        isOutbound: Boolean = false,
    ) {
        val l = box.cx - box.w / 2f
        val t = box.cy - box.h / 2f
        val r = l + box.w
        val b = t + box.h
        val radius = minOf(box.h * 0.24f, 11f * density)

        if (isOutbound) {
            val auraExpand = 3.5f * density
            val auraRadius = radius + auraExpand
            fillPaint.color = strokeColor
            fillPaint.alpha = 48
            canvas.drawRoundRect(l - auraExpand, t - auraExpand, r + auraExpand, b + auraExpand, auraRadius, auraRadius, fillPaint)
        }

        fillPaint.color = palette.cardFill
        fillPaint.alpha = 255
        canvas.drawRoundRect(l, t, r, b, radius, radius, fillPaint)
        strokePaint.color = strokeColor
        strokePaint.alpha = 255
        canvas.drawRoundRect(l, t, r, b, radius, radius, strokePaint)

        if (isOutbound) {
            val badgeW = 18f * density
            val badgeH = 11f * density
            val badgeR = badgeH / 2f
            val badgeRight = r - 5f * density
            val badgeTop = t + 4f * density
            val badgeLeft = badgeRight - badgeW
            val badgeBottom = badgeTop + badgeH

            fillPaint.color = strokeColor
            fillPaint.alpha = 50
            canvas.drawRoundRect(badgeLeft, badgeTop, badgeRight, badgeBottom, badgeR, badgeR, fillPaint)
            fillPaint.alpha = 255

            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textSize = 8.5f * density
            textPaint.color = strokeColor
            val badgeText = "⇄"
            val bw = textPaint.measureText(badgeText)
            val bFm = textPaint.fontMetrics
            val bBaseline = (badgeTop + badgeBottom) / 2f - (bFm.ascent + bFm.descent) / 2f
            canvas.drawText(badgeText, badgeLeft + (badgeW - bw) / 2f, bBaseline, textPaint)
        }

        val pad = box.w * 0.10f
        val maxW = box.w - pad * 2f
        /* 字号下限刻意卡在 10.5dp / 9.5dp，而不是原型换算出来的 9dp / 8dp。
           原因：三层区域是按**视图高度**取比例的，而原型 viewBox 是 366×820（1:2.24）。
           在 16:9 的老机（如 720×1280）上整个图会被压到 58%，字号跟着掉到 9dp —— 已经低于可读下限。
           抬高下限后，两行文字仍放得下（49px vs 卡高 54px），而且现代高屏本来就没触发这个 clamp。 */
        val titleSize = (box.h * 0.30f).coerceIn(10.5f * density, 16f * density)
        val subSize = (box.h * 0.26f).coerceIn(9.5f * density, 14f * density)

        /* 先量标题（定字号 → 量文本 → 取 fontMetrics），再量副标题，最后整体垂直居中。
           顺序不能反：ellipsize 和 fontMetrics 都依赖当前 textSize。
           省略结果走缓存（见 [titleCache]）—— 每帧重新量 60 次是纯浪费。 */
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = titleSize
        val titleText = titleCache.getOrPut(box.id) {
            TextUtils.ellipsize(title, textPaint, maxW, TextUtils.TruncateAt.END).toString()
        }
        val titleFm = textPaint.fontMetrics
        val titleH = titleFm.descent - titleFm.ascent

        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = subSize
        val subText = subCache.getOrPut(box.id) {
            TextUtils.ellipsize(subtitle, textPaint, maxW, TextUtils.TruncateAt.END).toString()
        }
        val subFm = textPaint.fontMetrics
        val subH = subFm.descent - subFm.ascent

        val hasSub = subText.isNotEmpty()
        val lineGap = box.h * 0.04f
        val blockH = titleH + if (hasSub) lineGap + subH else 0f
        var top = box.cy - blockH / 2f

        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.color = palette.textPrimary
        textPaint.textSize = titleSize
        canvas.drawText(titleText, l + pad, top - titleFm.ascent, textPaint)
        top += titleH + lineGap

        if (hasSub) {
            textPaint.typeface = Typeface.DEFAULT
            textPaint.color = palette.textSecondary
            textPaint.textSize = subSize
            canvas.drawText(subText, l + pad, top - subFm.ascent, textPaint)
        }
    }

    /** 溢出胶囊：「+N 更多」。虚线描边、无填充，明确表示「这里还有东西」 */
    private fun drawCapsule(canvas: Canvas, box: TopologyBox, overflow: Int) {
        if (overflow <= 0) return
        val l = box.cx - box.w / 2f
        val t = box.cy - box.h / 2f

        strokePaint.color = palette.textMuted
        strokePaint.pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
        rect.set(l, t, l + box.w, t + box.h)
        canvas.drawRoundRect(rect, box.h / 2f, box.h / 2f, strokePaint)
        strokePaint.pathEffect = null

        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.color = palette.textMuted
        textPaint.textSize = (box.h * 0.26f).coerceIn(9.5f * density, 13f * density)
        val label = context.getString(R.string.topology_more, overflow)
        val tw = textPaint.measureText(label)
        val fm = textPaint.fontMetrics
        canvas.drawText(label, box.cx - tw / 2f, box.cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun drawEmptyStates(canvas: Canvas, snap: TopologySnapshot) {
        val h = height.toFloat()
        textPaint.typeface = Typeface.DEFAULT
        textPaint.color = palette.textMuted
        textPaint.textSize = 11f * density

        /* ① 层的数据来自 Clash API，所以空的时候必须说清楚是**哪一种**空 ——
           「没开 Clash API」和「内核没跑」和「真的没有活动连接」是三件不同的事，
           一律显示「暂无连接」等于把用户往错的方向引。见 plan §1.3。 */
        if (snap.inbounds.isEmpty()) {
            centered(
                canvas,
                context.getString(
                    when (snap.inboundState) {
                        TopologyInboundState.OK -> R.string.topology_inbound_empty
                        TopologyInboundState.DISABLED -> R.string.topology_clash_disabled
                        TopologyInboundState.UNAVAILABLE -> R.string.topology_clash_unavailable
                        TopologyInboundState.IDLE -> R.string.topology_inbound_idle
                    }
                ),
                TopologyRegions.IN_Y0 * h,
                TopologyRegions.IN_Y1 * h,
            )
        }
        if (snap.rules.isEmpty()) {
            centered(
                canvas,
                context.getString(R.string.topology_rule_empty),
                TopologyRegions.RU_Y0 * h,
                TopologyRegions.RU_Y1 * h,
            )
        }
        if (snap.outbounds.isEmpty()) {
            centered(
                canvas,
                context.getString(R.string.topology_out_empty),
                TopologyRegions.OUT_Y0 * h,
                TopologyRegions.OUT_Y1 * h,
            )
        }
    }

    private fun centered(canvas: Canvas, text: String, regionTop: Float, regionBottom: Float) {
        val tw = textPaint.measureText(text)
        val fm = textPaint.fontMetrics
        val y = (regionTop + regionBottom) / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, width / 2f - tw / 2f, y, textPaint)
    }

    companion object {
        const val ID_RULE_MORE = "ru-more"
        const val ID_OUT_MORE = "ou-more"

        private const val ICON_LAN = "🏠"
        private const val ICON_WEB = "🌐"

        /** 原型的 `172.(1[6-9]|2\d|3[01]).` */
        private val LAN_172 = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.")
    }
}
