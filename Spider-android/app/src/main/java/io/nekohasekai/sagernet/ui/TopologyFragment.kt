package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.GridView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.broadcastReceiver
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.widget.QRCodeDialog
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import io.nekohasekai.sagernet.ui.topology.ClashApiClient
import io.nekohasekai.sagernet.ui.topology.ClashResult
import io.nekohasekai.sagernet.ui.topology.ClashRuleEntry
import io.nekohasekai.sagernet.ui.topology.ClashSnapshot
import io.nekohasekai.sagernet.ui.topology.ClashSubscription
import io.nekohasekai.sagernet.ui.topology.RuleMatchKind
import io.nekohasekai.sagernet.ui.topology.TopologyHit
import io.nekohasekai.sagernet.ui.topology.TopologyInbound
import io.nekohasekai.sagernet.ui.topology.TopologyInboundState
import io.nekohasekai.sagernet.ui.topology.TopologyPalette
import io.nekohasekai.sagernet.ui.topology.TopologyParticleView
import io.nekohasekai.sagernet.ui.topology.TopologyRepository
import io.nekohasekai.sagernet.ui.topology.TopologySnapshot
import io.nekohasekai.sagernet.ui.topology.TopologyView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 新主页（三层拓扑图）。
 *
 * 当前进度：**阶段 0 骨架 + 阶段 1 静态拓扑 + 阶段 2 连线与粒子 + 阶段 3 实时入站 + 阶段 4 交互
 * + 抽屉替代（顶栏 / 设置面板 / 分组下拉）**。
 *
 * ## 这个页面会写数据吗
 *
 * 会写**两处**：
 *   1. 长按 ② 规则卡换出口节点（`ProfileManager.updateRule` + `needReload()`）；
 *   2. 分组下拉里切换当前分组（`DataStore.selectedGroup = ...`）。
 * 其余操作全是只读 —— 点卡片只是打开现成的编辑页（plan §2 的约定）。
 *
 * ## 顶栏与设置面板（阶段 1）：取代旧抽屉
 *
 * 旧抽屉（`DrawerLayout` + `NavigationView`）**2026-09-16 已整个删掉** ——
 * 连 `res/menu/main_drawer_menu.xml` 和汉堡图标一起（它此前早就被
 * `setDrawerLockMode(LOCK_MODE_LOCKED_CLOSED)` 锁死，是一扇「打不开的窗」）。
 * 导航现在全部由本页承担：
 *   · ⚙ → 右侧滑入面板，里面装的还是**现成的** `SettingsPreferenceFragment`
 *     （37 个偏好项 + ~30 处联动副作用都在它里面，重写一遍必然两边不一致）；
 *   · 分组胶囊 → 分组下拉，「新建分组 / 更新全部订阅 / 手动设置」按原型放在这里。
 *
 * ## 只读设计的逃生口（必须有）
 *
 * 本页是只读的，意味着「加东西」全在旧主页；而开了新 UI 之后抽屉的「配置」会回到本页，
 * 于是**没有任何路径**能到旧主页 —— 新装用户连分组都建不出来。
 * 所以分组下拉里必须留「新建分组」和「经典主页」两个入口。
 *
 * ## 实时数据（阶段 3）
 *
 * ① 层来自 Clash API，走 HTTP 打到 `:bg` 进程里的内核。
 * **只做手动下拉刷新，没有自动轮询** —— 用户明确关注能耗（plan §0.1 决策 D1）。
 * 生命周期：`onStart` 拉一次、`onStop` 把在途请求收掉、`onDestroyView` 取消协程
 * （`ThemedActivity` 会 `recreate`，不取消的话回调会打到已 detach 的 View 上）。
 */
class TopologyFragment : ToolbarFragment(R.layout.layout_topology) {

    private var topologyView: TopologyView? = null
    private var particleView: TopologyParticleView? = null
    private var summaryView: TextView? = null
    private var refreshLayout: SwipeRefreshLayout? = null

    /* ---- 顶栏（原型 .topbar）：分组胶囊 + ⏻ + ⚙ ---- */
    private var groupPill: View? = null
    private var groupNameView: TextView? = null
    private var powerButton: ImageButton? = null

    /* ---- 右侧滑入的设置面板（取代旧抽屉） ---- */
    private var panelScrim: View? = null

    /* ---- 「添加节点」面板（原型 #addPanel，只给协议网格） ---- */
    private var addScrim: View? = null
    private var addTargetView: TextView? = null
    private var addWarnView: TextView? = null
    private var addGrid: GridView? = null

    /**
     * 面板现在是不是开着的。
     *
     * 这个字段要跨「换 Fragment」活下来 —— 用户点进「设置 ▸ 路由规则」时本页被 replace、
     * 视图销毁，但**Fragment 实例本身还在返回栈上**，所以字段值不会丢；
     * 返回时 `onViewCreated` 跑第二遍，靠它把面板自动弹回来（方案 C：
     * 子页仍然是全屏的，但回来还在设置里）。
     * 配置变更 / `recreate()` 那种真·重建走 [onSaveInstanceState]。
     */
    private var settingsPanelOpen = false

    /** 分组下拉。持在字段里是为了在 `onDestroyView` 里 dismiss —— 否则会漏一个 Window。 */
    private var groupDrop: ListPopupWindow? = null

    /**
     * ③ 层是不是「展开」态（点了「+N 更多」胶囊之后）。
     *
     * 纯粹是**画多少张卡**的开关，不碰任何数据。收起时最多 3 张、展开时最多 6 张
     * （上限的来历见 [TopologyRepository.OUT_MAX_EXPANDED]）。
     *
     * 换分组时必须复位 —— ③ 层的节点池是「当前分组」，换了组就是另一批节点，
     * 沿用上一个组的展开态会让用户莫名其妙地看到一张更长的列表。
     */
    private var outExpanded = false

    /**
     * ② 层是不是「展开」态。同上，只是 ② 是单排弧形、展开靠压窄卡片实现
     * （上限见 [TopologyRepository.RULE_MAX_EXPANDED]）。
     */
    private var ruleExpanded = false

    /** 取色板。`requireContext()` 在 `onViewCreated` 之后才安全，所以不放在 lazy 里。 */
    private var palette: TopologyPalette? = null

    /* ---- ① 详情浮层 ---- */
    private var overlayView: View? = null
    private var overlayTitle: TextView? = null
    private var overlayEndpoint: TextView? = null
    private var overlayApp: TextView? = null
    private var overlayTraffic: TextView? = null
    private var overlayRule: TextView? = null
    private var overlayStart: TextView? = null

    /** 一次刷新的完整过程（HTTP + 读库 + 上屏）。同一时刻只允许一个在跑。 */
    private var refreshJob: Job? = null

    /**
     * 内核推送流的句柄。**只在页面可见时存在** —— `onStart` 建、`onStop` 关，
     * 所以切后台之后连一条推送都不会来（没有客户端定时器，也就没有后台开销）。
     */
    private var pushConnections: ClashSubscription? = null

    private val powerSaveReceiver = broadcastReceiver { _, _ ->
        stopPush()
        startPush()
    }

    /**
     * 上一次 `/rules` 的结果。
     *
     * 推送路径拿不到它（推送只有 `/connections`），但不带上它就会丢掉
     * 「哪些规则是内核自带的」这个判断 —— ①→② 连线会把内建规则（`port=53` 之类）
     * 也认领掉。所以这里留一份，`refresh()` 每次成功都刷新它。
     */
    private var lastClashRules: List<ClashRuleEntry>? = null

    /**
     * 当前详情浮层显示的是哪个 chip（`in-<host>`），null = 浮层关着。
     *
     * 推送来了要能把它刷新 —— 否则用户盯着浮层看流量，数字永远是打开那一刻的，
     * 比不看还糟（他以为那是实时的）。
     */
    private var openInboundId: String? = null

    /** 长按那一刻记下的规则 id —— 用户选完节点回来才知道要改哪条。 */
    private var pendingEgressRuleId = 0L

    /**
     * 返回键：浮层开着时先关浮层。
     *
     * `isEnabled` 跟着浮层可见性走 —— 关着的时候直接放行，让 `MainActivity` 那个回调
     * 去执行「退到后台」的原有行为。`OnBackPressedDispatcher` 是**后注册的先问**，
     * 本回调在 `onViewCreated` 注册，晚于 `MainActivity.onCreate`，所以优先级更高。
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            /* 设置面板比 ① 详情浮层更「上层」：两个都开着时先关面板。 */
            if (settingsPanelOpen) hideSettingsPanel() else hideInboundDetail()
        }
    }

    /**
     * 返回键只在「确实有东西可以关」的时候才拦截。
     *
     * 没有东西可关时必须 `isEnabled = false` —— 只有这样 `MainActivity` 那个回调才会接手，
     * 执行它原有的「退到后台，而不是回退到自己」。少这一步主页按返回键就没反应了。
     */
    private fun updateBackCallback() {
        backCallback.isEnabled = settingsPanelOpen ||
            overlayView?.visibility == View.VISIBLE ||
            addScrim?.visibility == View.VISIBLE
    }

    /**
     * ⏻ 的 VPN 授权流程。
     *
     * 和 `MainActivity` 的 FAB **同一个 contract**：首次启动会拉起系统的 VPN 授权弹窗。
     * 自己直接 `startService` 会绕过授权，表现是「点一下没反应」。
     */
    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    /**
     * 长按规则卡 → 选出口节点。
     *
     * 这是全计划唯一一条写操作，链路照抄 `RouteSettingsActivity.selectProfileForAdd`
     * （项目里既有的、已经验证过的写法），而不是自己另发明一套。
     */
    private val pickEgress = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ruleId = pendingEgressRuleId
        pendingEgressRuleId = 0L
        if (result.resultCode != Activity.RESULT_OK || ruleId == 0L) {
            return@registerForActivityResult
        }
        val profileId = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
        if (profileId == 0L) return@registerForActivityResult

        lifecycleScope.launch(Dispatchers.Default) {
            /* 按 id **重新读一遍**：从长按到选完之间可能过了很久，规则也许已被删除，
               或者被别的页面改过。拿长按那一刻的旧对象去写，会把别处的改动覆盖掉。 */
            val rule = SagerDatabase.rulesDao.getById(ruleId)
            if (rule == null) {
                onMainDispatcher { snackbar(R.string.topology_egress_failed).show() }
                return@launch
            }
            rule.outbound = profileId
            /* suspend：内部先 rulesDao.updateRule，再 ruleIterator { onUpdated() } 通知出去。
               少了后半步，界面显示的还是旧值。 */
            ProfileManager.updateRule(rule)
            val currentGroup = DataStore.currentGroupId()
            DataStore.setGroupRuleOutbound(currentGroup, rule.id, profileId)
            onMainDispatcher {
                /* 必须让用户**确认后**再重载 —— 静默 reload 会在用户不知情时打断连接。
                   老系统一贯如此（plan §2.1 第 2 点）。 */
                needReload()
                refresh()
            }
        }
    }

    /**
     * 选「主代理」节点 —— 新主页**唯一**的节点选择入口。
     *
     * 为什么非要有这条：③ 层不是节点列表，而是**路由图** —— 它只画「被启用规则指向」的
     * 节点（`TopologyModel.kt:214-218`）。新装用户一条规则都没有，③ 层就只剩一张
     * 「走主代理」卡，于是整个新 UI 里**没有任何地方能选节点**，`DataStore.selectedProxy`
     * 永远是 0，⏻ 按下去必然失败（`BaseService.kt:181` 会立刻 `stopRunner`）。
     * 老主页撤掉之后这个洞才露出来。
     *
     * 复用现成的 [ProfileSelectActivity]（就是长按 ② 用的那个：`ConfigurationFragment`
     * 的 select 模式，带分组 Tab，**所有**分组的节点都在里面），不另写一个选择器。
     */
    private val pickMainNode = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val profileId = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
        if (profileId == 0L) return@registerForActivityResult
        selectMainNode(profileId)
    }

    /**
     * 把某个节点设为「主代理」。
     *
     * 照抄老主页 `ConfigurationFragment.kt:1556-1584` 那条被验证过的链路 ——
     * 只写 `selectedProxy`、只让 `ProfileManager` 通知受影响的两行，
     * **不自己 set 服务状态、不自己 startService**。
     *
     * 服务在跑的时候要重载，但同样让用户确认（`needReload()`），
     * 不静默重载 —— 静默重载会在用户不知情时打断连接。
     */
    private fun selectMainNode(profileId: Long) {
        runOnDefaultDispatcher {
            val currentGroup = DataStore.currentGroupId()
            DataStore.setGroupSelectedProxy(currentGroup, profileId)
            val lastSelected = DataStore.selectedProxy
            if (lastSelected == profileId) return@runOnDefaultDispatcher
            DataStore.selectedProxy = profileId
            /* 旧的也要通知：列表里那一行的「已选中」标记要撤掉。
               lastSelected 是 0 时 `postUpdate` 内部 `getProfile(0) == null` 直接返回，安全。 */
            ProfileManager.postUpdate(lastSelected)
            ProfileManager.postUpdate(profileId)
            onMainDispatcher {
                refresh()
                if (DataStore.serviceState.canStop) needReload()
            }
        }
    }

    /** 打开节点选择器。长按 ② 用的也是同一个 Activity，只是返回值去处不同。 */
    private fun openNodePicker() {
        val current = DataStore.selectedProxy
        if (current <= 0L) {
            pickMainNode.launch(Intent(requireContext(), ProfileSelectActivity::class.java))
            return
        }
        /* 传当前节点过去让选择页把它高亮出来 —— 用户一眼能看到「现在走的是谁」。
           `getProfile` 要查库（DAO 全同步、`allowMainThreadQueries()`），放后台线程，
           否则点一下会掉帧。写法与长按 ② 那条一致。 */
        lifecycleScope.launch(Dispatchers.Default) {
            val entity = ProfileManager.getProfile(current)
            onMainDispatcher {
                val intent = Intent(requireContext(), ProfileSelectActivity::class.java)
                if (entity != null) intent.putExtra(ProfileSelectActivity.EXTRA_SELECTED, entity)
                pickMainNode.launch(intent)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        palette = TopologyPalette(requireContext())

        setupTopBar(view)

        topologyView = view.findViewById(R.id.topology_view)
        particleView = view.findViewById(R.id.topology_particles)
        topologyView?.blurView = view.findViewById(R.id.topology_blur)
        summaryView = view.findViewById(R.id.topology_summary)
        refreshLayout = view.findViewById(R.id.topology_refresh)

        overlayView = view.findViewById(R.id.topology_overlay)
        overlayTitle = view.findViewById(R.id.overlay_title)
        overlayEndpoint = view.findViewById(R.id.overlay_endpoint)
        overlayApp = view.findViewById(R.id.overlay_app)
        overlayTraffic = view.findViewById(R.id.overlay_traffic)
        overlayRule = view.findViewById(R.id.overlay_rule)
        overlayStart = view.findViewById(R.id.overlay_start)

        /* 卡片层是粒子层唯一的数据源：布局算完（含曲线控制点）会主动推给粒子层。
           这样粒子层不需要自己知道布局算法，也不需要再读一遍数据库。 */
        topologyView?.particleView = particleView

        topologyView?.onCardClick = ::onCardClick
        topologyView?.onCardLongClick = ::onCardLongClick

        /* 点遮罩关浮层。sheet 自己吃掉点击，所以点内容不会误关。 */
        overlayView?.setOnClickListener { hideInboundDetail() }
        view.findViewById<View>(R.id.topology_overlay_sheet)
            ?.setOnClickListener { /* 吃掉，别冒泡到遮罩 */ }
        view.findViewById<View>(R.id.overlay_close)?.setOnClickListener { hideInboundDetail() }

        /* 设置面板：和 ① 详情浮层一样是「同一棵视图树里的一层」。
           点遮罩关、点面板自己吃掉事件（否则点面板也会关）。 */
        panelScrim = view.findViewById(R.id.topology_panel_scrim)
        panelScrim?.setOnClickListener { hideSettingsPanel() }
        view.findViewById<View>(R.id.topology_panel)
            ?.setOnClickListener { /* 吃掉，别冒泡到遮罩 */ }
        view.findViewById<View>(R.id.topology_panel_back)
            ?.setOnClickListener { hideSettingsPanel() }

        /* 「添加节点」面板：几何、关法都和设置面板一样。 */
        addScrim = view.findViewById(R.id.topology_add_scrim)
        addTargetView = view.findViewById(R.id.topology_add_target)
        addWarnView = view.findViewById(R.id.topology_add_warn)
        addGrid = view.findViewById(R.id.topology_add_grid)
        addScrim?.setOnClickListener { hideAddPanel() }
        view.findViewById<View>(R.id.topology_add_panel)
            ?.setOnClickListener { /* 吃掉，别冒泡到遮罩 */ }
        view.findViewById<View>(R.id.topology_add_back)
            ?.setOnClickListener { hideAddPanel() }
        /* 「17 种」这个数字从 manualProtos 来，不写死在 XML 里 ——
           以后加协议只改一处。 */
        view.findViewById<TextView>(R.id.topology_add_section)
            ?.text = getString(R.string.topology_add_section, manualProtos.size)
        addGrid?.adapter = ProtoAdapter()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        /* 下拉 = 手动拉一次 /connections。没有别的触发源。 */
        refreshLayout?.setOnRefreshListener { refresh() }
        /* 转圈颜色接项目强调色 —— 复用 TopologyPalette 里那一根"接主题"的线（plan §0.2 D3），
           不另开一条。 */
        refreshLayout?.setColorSchemeColors(palette?.accent ?: 0)

        /* 面板原来是开着的就弹回来（方案 C：子页仍是全屏的，但返回还在设置里）。
           两个来源都要看：
             · `savedInstanceState` —— 配置变更 / recreate() 那种真·重建；
             · `settingsPanelOpen` —— 「设置 ▸ 路由规则」replace 掉本页又返回，
               Fragment 实例还在返回栈上，字段值没丢，但新视图默认是 GONE。 */
        val restorePanel = savedInstanceState?.getBoolean(KEY_SETTINGS_OPEN) ?: settingsPanelOpen
        if (restorePanel) showSettingsPanel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SETTINGS_OPEN, settingsPanelOpen)
    }

    override fun onResume() {
        super.onResume()
        /* 回到前台时对一次 ⏻ 的着色。VPN 状态由 MainActivity 推过来（见 onServiceStateChanged），
           这里是兜底：从别的页面回来、或者服务被别处停掉的情况。 */
        updatePowerButton()
    }

    // ------------------------------------------------------------ 顶栏

    /**
     * 把顶栏塞进 Toolbar。
     *
     * **为什么不是另起一行**：`ThemedActivity` 在 API 35+ 把状态栏内边距打在
     * `findViewById(R.id.appbar)` 上，顶栏留在 `AppBarLayout` 里才吃得到那层 padding，
     * 自己放外面会被状态栏压住。`ConfigurationFragment` 也是这么干的（`toolbar.addView`）。
     */
    private fun setupTopBar(view: View) {
        toolbar.setTitle("")
        /* 抽屉 2026-09-16 整个删掉了，`ToolbarFragment` 现在装的是「回主页」的返回箭头
           （原来装的是开抽屉的汉堡）。新主页自己画分组胶囊，不需要任何左侧图标。 */
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
        /* contentInsetStart 默认 16dp、带导航图标时是 72dp。清零后由 bar 自己的 padding 决定，
           否则胶囊会被推到屏幕中间。 */
        toolbar.setContentInsetsAbsolute(0, 0)

        val bar = layoutInflater.inflate(R.layout.layout_topology_bar, toolbar, false)
        toolbar.addView(
            bar,
            Toolbar.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        groupPill = bar.findViewById(R.id.topology_group_pill)
        groupNameView = bar.findViewById(R.id.topology_group_name)
        powerButton = bar.findViewById(R.id.topology_power)

        groupPill?.setOnClickListener { showGroupDrop() }
        powerButton?.setOnClickListener { toggleService() }
        bar.findViewById<View>(R.id.topology_settings)
            ?.setOnClickListener { toggleSettingsPanel() }

        updatePowerButton()
    }

    /**
     * ⏻ —— 与 `MainActivity` 的 FAB 完全同一个流程（`canStop` 决定是停还是起）。
     * 不自己判断服务状态、也不自己 startService。
     *
     * **唯一的例外是「还没选节点」**：`DataStore.selectedProxy == 0L` 时内核侧
     * `BaseService.reload()`（`bg/BaseService.kt:181`）会立刻 `stopRunner(false, "请先选择节点")`。
     * 也就是说服务会**起来、再自杀**，用户看到的只是底部飘一句「VPN 错误：…」，
     * 然后什么都没发生 —— 看着就像按钮坏了。
     * 与其让它白起一次，不如在这里拦住，直接把选节点的入口推给用户。
     * 这一步**不动内核**，只是不去触发那次注定失败的重载。
     */
    private fun toggleService() {
        if (DataStore.serviceState.canStop) {
            SagerNet.stopService()
        } else if (DataStore.selectedProxy == 0L) {
            snackbar(R.string.topology_need_node).setAction(R.string.topology_pick_node) {
                openNodePicker()
            }.show()
            return
        } else {
            connect.launch(null)
        }
        /* 状态是异步变的，这里只是让按钮**立刻**有个反馈；真正的终态由
           [onServiceStateChanged] 推过来。 */
        powerButton?.postDelayed({ updatePowerButton() }, 300)
    }

    /**
     * ⏻ 的着色：跑着的时候用强调色，停着的时候交回图标自己的
     * `?attr/colorControlNormal`（把 tint 置 null 即可）。
     * 只改 tint 不换图标 —— 两套图标要维护两份资源，没必要。
     */
    private fun updatePowerButton() {
        val button = powerButton ?: return
        val running = DataStore.serviceState.canStop
        button.imageTintList = if (running) {
            palette?.accent?.let { ColorStateList.valueOf(it) }
        } else {
            null
        }
    }

    /**
     * `MainActivity.changeState` 推过来的服务状态。
     *
     * 之所以要这条线：`DataStore.serviceState` 是个**普通字段**（`DataStore.kt:28`），
     * 不是持久化项，没有变更监听可注册。不推的话 ⏻ 的着色会一直停在旧状态。
     */
    fun onServiceStateChanged() = updatePowerButton()

    // ------------------------------------------------------------ 分组下拉

    /**
     * 分组下拉（原型 #subDrop）。
     *
     * **它是新 UI 唯一的「导入 / 添加 / 分组」入口。** 原型刻意把「＋ 导入」那个二级折叠
     * 按钮去掉、把导入方式平铺到一级（原注释：「不再多一次点击」）：
     * 更新全部订阅（高频，置顶）→ 扫码 / 剪贴板 / 文件 → 新增分组 → 手动添加…
     *
     * 用 `ListPopupWindow` 而不是 `PopupMenu`：分组名是动态的，`PopupMenu` 得先 add 一遍，
     * 而且没法给「当前分组」打勾、也没法插标题行和分隔线。
     * 定位与「点外面关闭」由它负责，不用自己算坐标。
     */
    private fun showGroupDrop() {
        val anchor = groupPill ?: return
        val rows = groupRows()
        if (rows.isEmpty()) return

        val pop = ListPopupWindow(requireContext())
        pop.anchorView = anchor
        pop.isModal = true
        /* 宽度按屏幕算，**不要写死**。
           写死 250dp 在中文下够用，换成英文/德文就切了（英文比中文长约一倍），
           实测会出现 `Update all subscript…` / `Classic home (grou…`。
           上限 320dp，左右各留 16dp。 */
        pop.width = minOf(dp(320), resources.displayMetrics.widthPixels - dp(32))
        pop.setAdapter(DropAdapter(rows))
        pop.setOnItemClickListener { _, _, position, _ ->
            /* ⚠ 必须先 dismiss()。
               `ListPopupWindow` **不会**在点中一行之后自己关掉 —— 自己关的是 `PopupMenu`，
               不是它。漏掉这一句的表现是「选了分组，下拉还挂在屏幕上不消失」，
               而且它会盖住底下的界面，看起来像一直在弹。
               先关再执行：关掉之后 popup 的 Window 就没了，
               后面要起的 Activity / 弹的对话框不会再被它挡住。 */
            pop.dismiss()
            onDropRow(rows[position])
        }
        /* 分组行**长按** → 分组管理（改名 / 更新 / 清空 / 删除），见 showGroupActions()。
           ⚠ 用 `listView.onItemLongClickListener`，**不要**挂到行视图上。
           `AbsListView.performLongPress()` 的判断顺序是：先看 `mOnItemLongClickListener`，
           没有才走 `if (mLongClickable) child.performLongClick()` ——
           而 `ListPopupWindow` 内部那个 `DropDownListView` **从来没有被设成 longClickable**
           （它只在构造里读 `android:longClickable`，默认 false），
           所以「给每一行 setOnLongClickListener」这条路能不能生效是看运气的。
           挂在 ListView 上则一定生效，还顺带免掉了 convertView 串用那个坑。
           ⚠ `listView` 只有在 `show()` 之后才非空。 */
        groupDrop = pop
        pop.show()
        pop.listView?.onItemLongClickListener =
            AdapterView.OnItemLongClickListener { _, _, position, _ ->
                val row = rows.getOrNull(position)
                if (row !is DropRow.Group) {
                    false
                } else {
                    /* 先关下拉再弹对话框，否则对话框会被 popup 的 Window 盖住 */
                    pop.dismiss()
                    showGroupActions(row.group)
                    true
                }
            }
    }

    /**
     * 分组行长按 → 分组管理。
     *
     * **为什么不直接复用 `ConfigurationFragment.showGroupManagementDialog`**（:1846）：
     * 那是个 200+ 行的成员函数，自绘 `NestedScrollView` + 6 个动作，还牵着
     * `showShareOptions` / `showExportOptions` 两个兄弟函数（:2054 / :2074）。
     * 要共用就得把它抽成独立件并改老主页的调用点 —— 碰「不得影响现有功能」这条红线，
     * 收益（多一个 Share/Export）远小于风险。
     *
     * 所以这里用标准 items 对话框，动作集与旧的一致，且**走同一批 public API**：
     * `GroupSettingsActivity` / `GroupUpdater` / `GroupManager`，
     * 文案也全部复用既有字符串（`group_edit` / `group_update` / `clear_profiles` / `delete`），
     * 不新增任何资源。
     */
    private fun showGroupActions(group: ProxyGroup) {
        /* Pair<文案资源, 动作>。用 List 而不是数组，方便按条件 add。 */
        val actions = ArrayList<Pair<Int, () -> Unit>>(6)

        /* 「更新」只对订阅组有意义 —— 普通分组没有远端可拉。 */
        if (group.type == GroupType.SUBSCRIPTION) {
            actions += R.string.group_update to {
                runOnDefaultDispatcher { GroupUpdater.startUpdate(group, true) }
            }
        }

        /* 与旧主页的「Edit Group」是同一个 Intent、同一个 extra。 */
        actions += R.string.group_edit to {
            startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
            })
        }

        /* 「分享」也只给订阅组 —— 普通分组没有 universal link 可分享。
           旧 UI 同样把 Share 放在 `group.type == SUBSCRIPTION` 分支里（:1996-2007）。 */
        if (group.type == GroupType.SUBSCRIPTION) {
            actions += R.string.share_subscription to { showShareOptions(group) }
        }

        actions += R.string.action_export to { showExportOptions(group) }

        actions += R.string.clear_profiles to {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.clear_profiles_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        GroupManager.clearGroup(group.id)
                        /* 节点没了，③ 出站层和「节点共 N 个」都要跟着变 */
                        onMainDispatcher { refresh() }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /* 「未分组」是 `DataStore.currentGroup()` 现场造出来的兜底组，删了下次读还会再造一个，
           所以跟旧 UI 一样（`ConfigurationFragment.kt:2036`）藏掉这一项。 */
        if (!group.ungrouped) {
            actions += R.string.delete to {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.delete_group_prompt)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            GroupManager.deleteGroup(group.id)
                            /* 删的**可能正好是当前分组**。不用自己修 `DataStore.selectedGroup`：
                               `currentGroup()` 在 `getById` 落空时会自动回退到第一个分组
                               （DataStore.kt:61-78），下次读就正常了。 */
                            onMainDispatcher { refresh() }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(group.displayName())
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 分享订阅 —— 复制 universal link，或弹二维码。
     *
     * 与旧主页 `ConfigurationFragment.showShareOptions`（:2054-2073）同一套：
     * 同样的两个选项、同样的 `SagerNet.trySetPrimaryClip` 与 `QRCodeDialog`。
     * 只是反馈用本页的 `snackbar` 而不是 `(activity as MainActivity).snackbar` —— 两者等价，
     * 本页是 MainActivity 的直接子 Fragment。
     */
    private fun showShareOptions(group: ProxyGroup) {
        val options = arrayOf(
            getString(R.string.action_export_clipboard),
            getString(R.string.share_qr_nfc),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.share_subscription)
            .setItems(options) { _, which ->
                val link = group.toUniversalLink()
                if (which == 0) {
                    val success = SagerNet.trySetPrimaryClip(link)
                    snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                        .show()
                } else {
                    /* TopologyFragment 挂在 MainActivity 的 FragmentManager 上，
                       所以 parentFragmentManager 就是 Activity 的 FM —— 与旧主页一致。 */
                    QRCodeDialog(link, group.displayName())
                        .showAllowingStateLoss(parentFragmentManager)
                }
            }
            .show()
    }

    /**
     * 导出分组里的节点 —— 复制到剪贴板（每行一个标准分享链接），或写成 txt 文件。
     *
     * 与旧主页 `ConfigurationFragment.showExportOptions`（:2075-2098）同一套，
     * 写文件的活交给 [exportProfiles]。
     */
    private fun showExportOptions(group: ProxyGroup) {
        val options = arrayOf(
            getString(R.string.action_export_clipboard),
            getString(R.string.action_export_file),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_export)
            .setItems(options) { _, which ->
                if (which == 0) {
                    runOnDefaultDispatcher {
                        val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
                        val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            snackbar(getString(R.string.copy_toast_msg)).show()
                        }
                    }
                } else {
                    exportGroup = group
                    startFilesForResult(exportProfiles, "profiles_${group.displayName()}.txt")
                }
            }
            .show()
    }

    /* ============================ 分组维护 ============================
       下面五个函数是 2026-09-16 从旧主页搬过来的，来源与等价关系：
       - 排序      ← `ConfigurationFragment.checkOrderMenu()`（:1167-1212）
       - 清流量统计 ← `action_clear_traffic_statistics`（:517-532）
       - 清测速结果 ← `action_connection_test_clear_results`（:534-550）
       - 移除重复   ← `action_remove_duplicate`（:591-641）
       - 删不可用   ← `action_connection_test_delete_unavailable`（:552-589）

       **刻意不搬的两个**：`action_connection_tcp_ping` / `action_connection_url_test`。
       它们的实现（`pingTest()` :751 / `urlTest()` :892）整个挂在
       `ConfigurationFragment.GroupPagerAdapter` 里，还牵着 `TestDialog`（:654，200+ 行
       进度对话框 + 最小化通知 + 取消），要复用就得把老主页那个大内部类拆开 ——
       碰「不得影响现有功能」这条红线，收益不值这个风险。要做就单独做一版。 */

    /**
     * 下拉的行序：标题 → 分组（当前那个打勾）→ 分隔线 → 七个动作。
     *
     * ⚠ `allGroups()` 是同步 DAO，这里在主线程读。项目全局开了 `allowMainThreadQueries()`，
     * 不会崩；分组表只有个位数行，代价是一次很小的查询。
     * 换成异步的话下拉要等一个来回才弹出来 —— 点一下要等一下，体感更差。
     */
    private fun groupRows(): List<DropRow> {
        val groups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())
        val selected = DataStore.selectedGroup
        return buildList(groups.size + 8) {
            add(DropRow.Header(R.string.topology_group_header))
            groups.forEach { add(DropRow.Group(it, it.id == selected)) }
            add(DropRow.Divider)
            /* 顺序照抄原型：更新全部订阅（高频）置顶，然后三条导入，再新增分组，手动添加收尾。 */
            add(DropRow.Action("⟳", R.string.update_all_subscription, ACTION_UPDATE_ALL))
            add(DropRow.Action("📷", R.string.add_profile_methods_scan_qr_code, ACTION_SCAN))
            add(DropRow.Action("📋", R.string.action_import, ACTION_CLIPBOARD))
            add(DropRow.Action("📁", R.string.action_import_file, ACTION_FILE))
            add(DropRow.Action("＋", R.string.group_create, ACTION_NEW_GROUP))
            add(DropRow.Action("⚙️", R.string.add_profile_methods_manual_settings, ACTION_MANUAL))
            /* 这里原来还有一条「🏠 经典主页」逃生口，2026-09-16 用户拍板「旧主页完全去掉」后删掉。
               删之前盘过一遍旧主页的「＋」菜单，确认**新 UI 已经覆盖**了：
               三条导入 + 手动设置 17 种协议 + 自定义配置 + 链式代理 + 新建分组 + 更新全部订阅，
               以及分组长按里的改名/删除/分享/导出/更新/清除流量（见 [showGroupManagementDialog]）。
               还没搬过来的只有四项，见 docs/spider-newui-tasks.md 的「旧主页残留能力」。 */
        }
    }

    /* 刻意写成语句体（带花括号）而不是 `= when (...) { ... }`：
       表达式体下 `when (row.id)` 会因为「Int 不穷尽」直接编译不过，
       而这里本来就不需要返回值。 */
    private fun onDropRow(row: DropRow) {
        when (row) {
            /* 标题行和分隔线不可点（Adapter 的 isEnabled 已经挡掉了），这里只是穷尽 when */
            is DropRow.Header, DropRow.Divider -> Unit

            is DropRow.Group -> {
                if (!row.current) {
                    DataStore.selectedGroup = row.group.id
                    updateGroupLabel(row.group.displayName())
                    ruleExpanded = false
                    outExpanded = false
                    switchGroupState(row.group.id)
                }
            }

            is DropRow.Action -> when (row.id) {
                ACTION_UPDATE_ALL -> confirmUpdateAll()

                /* 与旧主页的「＋ → 扫码」是同一个 Intent */
                ACTION_SCAN -> startActivity(Intent(requireContext(), ScannerActivity::class.java))

                ACTION_CLIPBOARD -> importFromClipboard()

                ACTION_FILE -> startFilesForResult(importFile, "*/*")

                ACTION_NEW_GROUP ->
                    startActivity(Intent(requireContext(), GroupSettingsActivity::class.java))

                ACTION_MANUAL -> showAddPanel()
            }
        }
    }

    /**
     * 切换分组并恢复该组专属的选定主节点与规则-节点配对关系。
     */
    private fun switchGroupState(groupId: Long) {
        lifecycleScope.launch(Dispatchers.Default) {
            val remembered = DataStore.getGroupSelectedProxy(groupId)
            val activeId = if (remembered > 0L && SagerDatabase.proxyDao.getById(remembered)?.groupId == groupId) {
                remembered
            } else {
                val groupNodes = SagerDatabase.proxyDao.getByGroup(groupId)
                val firstId = groupNodes.firstOrNull()?.id ?: 0L
                if (firstId > 0L) DataStore.setGroupSelectedProxy(groupId, firstId)
                firstId
            }
            if (activeId > 0L && activeId != DataStore.selectedProxy) {
                val last = DataStore.selectedProxy
                DataStore.selectedProxy = activeId
                ProfileManager.postUpdate(last)
                ProfileManager.postUpdate(activeId)
            }

            // 恢复该组专属的规则配对
            val rules = SagerDatabase.rulesDao.allRules()
            for (rule in rules) {
                val savedOutbound = DataStore.getGroupRuleOutbound(groupId, rule.id)
                if (savedOutbound != null) {
                    if (savedOutbound <= 0L || SagerDatabase.proxyDao.getById(savedOutbound) != null) {
                        if (rule.outbound != savedOutbound) {
                            rule.outbound = savedOutbound
                            ProfileManager.updateRule(rule)
                        }
                    }
                }
            }

            onMainDispatcher {
                refresh()
                if (DataStore.serviceState.canStop) needReload()
            }
        }
    }

    /**
     * 从剪贴板导入。
     *
     * 口径**照抄**旧主页的 `action_import_clipboard`（`ConfigurationFragment:419-439`）：
     * 「剪贴板为空 / 解析不出节点 / 解析出的是订阅链接」三种情况分别给不同提示，
     * 一种都不能合并 —— 合并了用户就分不清「我复制的东西不对」还是「App 坏了」。
     */
    private fun importFromClipboard() {
        val text = SagerNet.getClipboardText()
        if (text.isBlank()) {
            snackbar(getString(R.string.clipboard_empty)).show()
            return
        }
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val proxies = RawUpdater.parseRaw(text)
                if (proxies.isNullOrEmpty()) {
                    onMainDispatcher {
                        snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                    }
                } else {
                    finishImport(proxies)
                }
            } catch (e: SubscriptionFoundException) {
                /* 剪贴板里是订阅链接 —— 那是「导入订阅」，交给 MainActivity 走它原有的确认流程。
                   ⚠ 这一句**不能**包在 onMainDispatcher 里：importSubscription 自己就是 suspend，
                   而且它内部会自己切回主线程。 */
                (activity as? MainActivity)?.importSubscription(e.link.toUri())
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher { snackbar(e.readableMessage).show() }
            }
        }
    }

    /**
     * 从文件导入。契约和解析口径都跟旧主页一致（`ConfigurationFragment:350-395`），
     * 解析那步共用 [ProfileImporter.parseFile]（含 WireGuard 的 `.zip` 分支）。
     */
    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file == null) return@registerForActivityResult
            /* 同步取 context：协程里再 requireContext() 有在 detach 之后被调用的风险，
               而 applicationContext 的 contentResolver 足够用来解析文件。 */
            val resolverContext = requireContext().applicationContext
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    val proxies = ProfileImporter.parseFile(resolverContext, file)
                    if (proxies.isEmpty()) {
                        onMainDispatcher {
                            snackbar(getString(R.string.no_proxies_found_in_file)).show()
                        }
                    } else {
                        finishImport(proxies)
                    }
                } catch (e: SubscriptionFoundException) {
                    (activity as? MainActivity)?.importSubscription(e.link.toUri())
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher { snackbar(e.readableMessage).show() }
                }
            }
        }

    /**
     * 导出到文件时，用户选完保存位置才回调 —— 回调里拿不到当初那个分组，
     * 所以得自己记一下。用完立刻置空，避免下次误用上一次的值。
     */
    private var exportGroup: ProxyGroup? = null

    /**
     * 导出分组里的节点为 txt。与旧主页
     * `ConfigurationFragment.exportProfilesFromHome`（:139-162）同一份实现。
     */
    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            val group = exportGroup
            exportGroup = null
            if (data == null || group == null) return@registerForActivityResult
            /* 与 importFile 同理：先把 context 取出来，别在协程里 requireContext() */
            val resolverContext = requireContext().applicationContext
            runOnDefaultDispatcher {
                val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
                val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                try {
                    resolverContext.contentResolver.openOutputStream(data)!!.bufferedWriter()
                        .use { it.write(links) }
                    onMainDispatcher { snackbar(R.string.action_export_msg).show() }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher { snackbar(e.readableMessage).show() }
                }
            }
        }

    /** 落库 + 提示 + 刷新，剪贴板与文件两条路径共用。 */
    private suspend fun finishImport(proxies: List<AbstractBean>) {
        ProfileImporter.import(proxies)
        onMainDispatcher {
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
            /* 新节点会让 ③ 出站层和「节点共 N 个」都变，刷一次。 */
            refresh()
        }
    }

    /** 与旧主页的「＋ → 更新全部订阅」是同一段实现（ConfigurationFragment:519-533）。 */
    private fun confirmUpdateAll() {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
            .setMessage(R.string.update_all_subscription)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    SagerDatabase.groupDao.allGroups()
                        .filter { it.type == GroupType.SUBSCRIPTION }
                        .forEach { GroupUpdater.startUpdate(it, true) }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun updateGroupLabel(name: String?) {
        groupNameView?.text = name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.topology_group_fallback)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 下拉里的一行。四种类型，分组行/动作行共用同一个行布局，靠 [DropAdapter] 显隐。 */
    private sealed interface DropRow {

        /** 分组标题（「已导入 · 分组」），不可点 */
        data class Header(val labelRes: Int) : DropRow

        /** 水平分隔线：上方 = 已导入的分组，下方 = 导入方式，不可点 */
        object Divider : DropRow

        data class Group(val group: ProxyGroup, val current: Boolean) : DropRow

        /** [emoji] 用 emoji 字形当图标，理由见 `layout_topology_drop_row.xml` 的注释 */
        data class Action(val emoji: String, val labelRes: Int, val id: Int) : DropRow
    }

    private inner class DropAdapter(private val rows: List<DropRow>) : BaseAdapter() {

        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        /* 标题行和分隔线不可点。ListPopupWindow 只对 isEnabled 为 true 的行回调点击，
           所以这一条同时是「视觉上不像是能点的」和「真的点不动」。 */
        override fun isEnabled(position: Int) = when (rows[position]) {
            is DropRow.Header, DropRow.Divider -> false
            else -> true
        }

        override fun getViewTypeCount() = 3

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is DropRow.Header -> TYPE_HEADER
            DropRow.Divider -> TYPE_DIVIDER
            else -> TYPE_ROW
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is DropRow.Header -> (
                    convertView
                        ?: layoutInflater.inflate(R.layout.layout_topology_drop_header, parent, false)
                    ).apply { findViewById<TextView>(R.id.drop_header).setText(row.labelRes) }

                DropRow.Divider -> convertView
                    ?: layoutInflater.inflate(R.layout.layout_topology_drop_divider, parent, false)

                else -> bindRow(row, convertView, parent)
            }

        /** 分组行 / 动作行共用的那一支 */
        private fun bindRow(row: DropRow, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.layout_topology_drop_row, parent, false)
            val label = view.findViewById<TextView>(R.id.drop_label)
            val sub = view.findViewById<TextView>(R.id.drop_sub)
            val icon = view.findViewById<TextView>(R.id.drop_icon)
            val check = view.findViewById<ImageView>(R.id.drop_check)

            when (row) {
                is DropRow.Group -> {
                    label.text = row.group.displayName()
                    sub.visibility = View.VISIBLE
                    sub.setText(
                        if (row.group.type == GroupType.SUBSCRIPTION) {
                            R.string.subscription
                        } else {
                            R.string.topology_group_local
                        }
                    )
                    icon.visibility = View.GONE
                    check.visibility = if (row.current) View.VISIBLE else View.GONE
                    check.imageTintList = palette?.accent?.let { ColorStateList.valueOf(it) }
                }

                is DropRow.Action -> {
                    label.setText(row.labelRes)
                    sub.visibility = View.GONE
                    icon.text = row.emoji
                    icon.visibility = View.VISIBLE
                    check.visibility = View.GONE
                }

                /* Header / Divider 走各自的分支，到不了这里 */
                else -> Unit
            }

            /* 长按不在这里接 —— 挂在 ListPopupWindow 内部的 ListView 上，
               见 showGroupDrop() 里的注释（挂行视图上是不可靠的）。 */
            return view
        }
    }

    // ------------------------------------------------------------ 设置面板

    /**
     * 开设置面板。
     *
     * 内容是**现成的** [SettingsPreferenceFragment] —— 37 个偏好项 + ~30 处联动副作用
     * 都在它里面，重写一遍等于把 `needReload` / `needRestart` / `setTheme + recreate`
     * 抄第二遍，迟早两边不一致。
     *
     * 它挂在本 Fragment 的 `childFragmentManager` 上，所以它内部那 5 个跳转链接
     * 必须用 **Activity 的** FragmentManager（已改成 `requireActivity().supportFragmentManager`）——
     * 用 `parentFragmentManager` 会指到 child manager，而 `R.id.fragment_holder` 不在
     * child 的视图树里，直接崩「No view found for id」。
     *
     * 幂等：已经加过就不再加（配置变更回来时容器会自己恢复子 Fragment）。
     */
    private fun showSettingsPanel() {
        val scrim = panelScrim ?: return
        if (childFragmentManager.findFragmentById(R.id.topology_panel_host) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.topology_panel_host, SettingsPreferenceFragment())
                .commit()
        }
        scrim.visibility = View.VISIBLE
        settingsPanelOpen = true
        updateBackCallback()
    }

    private fun hideSettingsPanel() {
        panelScrim?.visibility = View.GONE
        settingsPanelOpen = false
        updateBackCallback()
    }

    private fun toggleSettingsPanel() {
        if (settingsPanelOpen) hideSettingsPanel() else showSettingsPanel()
    }

    // ------------------------------------------------------------ 添加节点面板

    /**
     * 「添加节点」面板（原型 `#addPanel` 的 `protoOnly` 那一档）。
     *
     * 这里**只给协议网格** —— 扫码 / 剪贴板 / 文件三条已经平铺在分组下拉的一级了，
     * 再放一遍就是原型明确反对的「多一次点击」（原注释：`protoOnly=1：只给协议网格，
     * 避免再套一层扫码入口`）。
     */
    private fun showAddPanel() {
        val scrim = addScrim ?: return
        updateAddTarget()
        scrim.visibility = View.VISIBLE
        updateBackCallback()
    }

    private fun hideAddPanel() {
        addScrim?.visibility = View.GONE
        updateBackCallback()
    }

    /**
     * 「目标分组」那一行。
     *
     * 必须显示 [DataStore.selectedGroupForImport] 的**真实结果**，而不是「当前分组」：
     * 当前是订阅组时新节点会落到**第一个普通分组**（订阅组只读），
     * 不说明的话用户会以为节点加丢了 —— 这正是原型 `.tgt.warn` 那段提示的用意。
     */
    private fun updateAddTarget() {
        val current = runCatching { DataStore.currentGroup() }.getOrNull()
        val target = runCatching {
            DataStore.selectedGroupForImport().let { SagerDatabase.groupDao.getById(it) }
        }.getOrNull()

        addTargetView?.text = getString(
            R.string.topology_add_target,
            target?.displayName() ?: getString(R.string.topology_add_no_group),
        )

        /* 两者不一致 = 走了「订阅组 → 第一个普通分组」这条回退，必须提示 */
        val fallback = current != null && target != null && current.id != target.id
        addWarnView?.visibility = if (fallback) View.VISIBLE else View.GONE
        if (fallback) {
            addWarnView?.text = getString(R.string.topology_add_warn, current.displayName())
        }
    }

    /**
     * 手动添加的 17 种，顺序**照抄** `add_profile_menu.xml`（和原型 `PROTOS` 一致）。
     *
     * 每一项都只是「打开对应的设置页」，和旧主页菜单分支是**同一个 Intent**，
     * 不自己另发明一套。VLESS 复用 [VMessSettingsActivity] 并多带一个 `vless=true`
     * —— 旧主页 `ConfigurationFragment:461-465` 就是这么写的。
     *
     * 这是个纯静态表（只有 Class 引用，没有 Context），所以可以放字段里常驻，不会泄漏。
     */
    private val manualProtos = listOf(
        ManualProto(R.string.action_socks, SocksSettingsActivity::class.java),
        ManualProto(R.string.action_http, HttpSettingsActivity::class.java),
        ManualProto(R.string.action_shadowsocks, ShadowsocksSettingsActivity::class.java),
        ManualProto(R.string.action_vmess, VMessSettingsActivity::class.java),
        ManualProto(R.string.topology_proto_vless, VMessSettingsActivity::class.java, vless = true),
        ManualProto(R.string.action_trojan, TrojanSettingsActivity::class.java),
        ManualProto(R.string.action_trojan_go, TrojanGoSettingsActivity::class.java),
        ManualProto(R.string.action_mieru, MieruSettingsActivity::class.java),
        ManualProto(R.string.action_naive, NaiveSettingsActivity::class.java),
        ManualProto(R.string.action_hysteria, HysteriaSettingsActivity::class.java),
        ManualProto(R.string.action_tuic, TuicSettingsActivity::class.java),
        ManualProto(R.string.action_shadowtls, ShadowTLSSettingsActivity::class.java),
        ManualProto(R.string.action_anytls, AnyTLSSettingsActivity::class.java),
        ManualProto(R.string.action_ssh, SSHSettingsActivity::class.java),
        ManualProto(R.string.action_wireguard, WireGuardSettingsActivity::class.java),
        ManualProto(R.string.custom_config, ConfigSettingActivity::class.java),
        ManualProto(R.string.proxy_chain, ChainSettingsActivity::class.java),
    )

    private class ManualProto(
        val labelRes: Int,
        val target: Class<out Activity>,
        val vless: Boolean = false,
    )

    private inner class ProtoAdapter : BaseAdapter() {
        override fun getCount() = manualProtos.size
        override fun getItem(position: Int): Any = manualProtos[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView
                ?: layoutInflater.inflate(R.layout.layout_topology_proto_chip, parent, false))
                    as TextView
            val proto = manualProtos[position]
            view.setText(proto.labelRes)
            view.setOnClickListener { openManual(proto) }
            return view
        }
    }

    private fun openManual(proto: ManualProto) {
        val intent = Intent(requireContext(), proto.target)
        if (proto.vless) intent.putExtra("vless", true)
        startActivity(intent)
    }

    override fun onStart() {
        super.onStart()
        /* 进前台就拉一次：可能是从设置页改完 Clash API 开关回来的，
           也可能是切出去很久了。反正一次 HTTP，代价可以忽略。
           这一趟同时也把 ②③ 层（数据库那半边）带上来 —— 推送只有 Clash 那一半。 */
        refresh()
        startPush()
        runCatching {
            requireContext().registerReceiver(
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            )
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { requireContext().unregisterReceiver(powerSaveReceiver) }
        /* 切后台把在途请求和推送流一起收掉 —— 本页没有任何客户端定时器，
           所以这里收完就是「零后台开销」。 */
        refreshJob?.cancel()
        refreshJob = null
        stopPush()
    }

    // ------------------------------------------------------------ 内核推送

    /**
     * 接上内核的 `/connections` 推送流。
     *
     * 为什么不用客户端轮询：内核**本来就支持**服务端推送
     * （`clashapi/connections.go:21-77`，带 `Upgrade: websocket` 时按 interval 主动推快照），
     * 所以「① 层是活的」这件事零成本 —— 不需要定时器、不需要判断间隔。
     */
    private fun startPush() {
        if (pushConnections != null) return
        pushConnections = ClashApiClient.subscribeConnections(
            onSnapshot = ::onPushSnapshot,
            onEnd = ::onPushEnded,
        )
    }

    private fun stopPush() {
        pushConnections?.close()
        pushConnections = null
    }

    /**
     * 收到一帧推送（**OkHttp 的线程**）。
     *
     * 这里只做「Clash 那一半」：把新的连接列表和数据库里的 ②③ 合起来。
     * 读库是同步 DAO（`allowMainThreadQueries()`），所以整段丢到 Default 上。
     */
    private fun onPushSnapshot(clash: ClashSnapshot) {
        runOnDefaultDispatcher {
            val snapshot = runCatching {
                TopologyRepository.load(
                    ClashResult.Ok(clash, lastClashRules), ruleExpanded, outExpanded,
                )
            }.getOrNull() ?: return@runOnDefaultDispatcher
            onMainDispatcher { applySnapshot(snapshot) }
        }
    }

    /**
     * 推送流断了（**OkHttp 的线程**，且只在**意外**断开时来一次）。
     *
     * 不能装作没发生：① 层会停在最后一帧上，看起来像「一切正常但流量不动了」，
     * 那比直接说「连不上」更误导。所以把 ① 层打成降级态。
     *
     * **不自动重连** —— 那等于又引入了一个定时器。恢复路径是现成的：
     * 下拉刷新、或切出去再回来（`onStart` 会重新 `refresh()` + `startPush()`）。
     */
    private fun onPushEnded(reason: String) {
        Logs.d("Topology push ended: $reason")
        runOnMainDispatcher {
            pushConnections = null
            val current = topologyView?.snapshot ?: return@runOnMainDispatcher
            /* 已经是降级态就别再打一次 —— 免得把「Clash API 没开」覆盖成「连不上」。 */
            if (current.inboundState != TopologyInboundState.OK &&
                current.inboundState != TopologyInboundState.IDLE
            ) {
                return@runOnMainDispatcher
            }
            applySnapshot(current.copy(inboundState = TopologyInboundState.UNAVAILABLE))
        }
    }

    /**
     * 把一份快照上屏。**推送和手动刷新共用这一条**，免得两条路各写一遍、
     * 其中一条漏掉某个视图。
     *
     * 注意 [TopologyView.snapshot] 自己会判断「结构有没有变」——
     * 只有流量变了的话它内部直接跳过重算布局，所以这里不用再判一次。
     */
    private fun applySnapshot(snapshot: TopologySnapshot) {
        topologyView?.snapshot = snapshot
        summaryView?.text = buildSummary(snapshot)
        refreshOpenDetail(snapshot)
    }

    /**
     * 详情浮层开着的话，用新数据重画一遍。
     *
     * 找不到这个 host 就**保持原样**：连接关掉之后它本来就会从 ① 层消失，
     * 但用户可能正看着那个数字，突然把浮层抽掉比留着更难受。他按返回键就能关。
     */
    private fun refreshOpenDetail(snapshot: TopologySnapshot) {
        val id = openInboundId ?: return
        if (overlayView?.visibility != View.VISIBLE) return
        val chip = snapshot.inbounds.firstOrNull { it.id == id } ?: return
        showInboundDetail(chip)
    }

    override fun onDestroyView() {
        /* 取消协程：`ThemedActivity` 会 `recreate`，回调回来时 View 已经 detach。
           （下面取值也一律用 `view?.`，双保险。） */
        refreshJob?.cancel()
        refreshJob = null
        stopPush()
        backCallback.isEnabled = false
        /* 下拉是一个独立 Window，视图没了必须主动 dismiss，否则会漏一个 Window
           （而且它 anchor 在已经被销毁的胶囊上）。 */
        groupDrop?.dismiss()
        groupDrop = null
        topologyView?.particleView = null
        topologyView?.onCardClick = null
        topologyView?.onCardLongClick = null
        topologyView = null
        particleView = null
        summaryView = null
        refreshLayout = null
        groupPill = null
        groupNameView = null
        powerButton = null
        panelScrim = null
        addScrim = null
        addTargetView = null
        addWarnView = null
        addGrid?.adapter = null
        addGrid = null
        palette = null
        overlayView = null
        overlayTitle = null
        overlayEndpoint = null
        overlayApp = null
        overlayTraffic = null
        overlayRule = null
        overlayStart = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ 交互

    private fun onCardClick(hit: TopologyHit) {
        when (hit) {
            is TopologyHit.Inbound -> showInboundDetail(hit.chip)

            is TopologyHit.Card -> when {
                /* 溢出胶囊 → **原地展开**，不跳页。
                   两个胶囊原来都往外跳：
                     · ② 规则胶囊 → `navigateTo(DEST_ROUTE)`，也就是旧样式的「Route」整页列表；
                     · ③ 出站胶囊 → `ConfigurationFragment`，也就是**旧主页**。
                   用户的反馈就是「点到 +1 直接回到旧界面了」—— 对，而且那个胶囊画在
                   ② 区最右端、因为弧形排布压得很低，看上去更像是属于 ③ 层，
                   所以两条路都得堵上，不能只堵一条。

                   现在两个都是「把这一层撑开、把剩下的全画出来」：
                   ② 从 5 张撑到 8 张（压窄卡片），③ 从 3 张撑到 6 张（2 行 × 3）。
                   全部画得下时胶囊自己消失。 */
                hit.id == TopologyView.ID_RULE_MORE -> {
                    ruleExpanded = !ruleExpanded
                    refresh()
                }

                hit.id == TopologyView.ID_OUT_MORE -> {
                    outExpanded = !outExpanded
                    refresh()
                }

                /* ② 默认分流卡 → 打开节点选择器选主代理 */
                hit.id == TopologyRepository.ID_DEFAULT_RULE -> openNodePicker()

                /* ② 规则卡 → 规则编辑页。必须传 EXTRA_ROUTE_ID：
                   它会在 RouteSettingsActivity 里写 DataStore.editingId，
                   不传的话会当成「新建规则」。 */
                hit.id.startsWith("ru-") -> {
                    val ruleId = hit.id.removePrefix("ru-").toLongOrNull() ?: return
                    startActivity(
                        Intent(requireContext(), RouteSettingsActivity::class.java)
                            .putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, ruleId)
                    )
                }

                /* ③ 出站卡 → 节点编辑页。
                   ⚠ 例外：「走主代理」卡不是「某个节点的卡」，它是**当前节点这个位置本身**。
                   单击 = 换人（打开节点选择器）—— 原来这里在未选中时直接 return，等于一个死键，
                   而新 UI 里又再没有第二个能选节点的地方。
                   编辑当前节点挪到长按（见 [onCardLongClick]）。 */
                hit.id == TopologyRepository.ID_MAIN || hit.id.startsWith("ou-") -> openNodePicker()

                /* 直连 / 拦截不是可编辑实体，点了不做任何事 */
                else -> Unit
            }
        }
    }

    private fun openProfile(profileId: Long) {
        startActivity(
            Intent(requireContext(), ProfileSettingsActivity::class.java)
                .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, profileId)
        )
    }

    private fun onCardLongClick(hit: TopologyHit) {
        when (hit) {
            /* ① chip 长按 → 复制 host:port（plan §2 的手势表） */
            is TopologyHit.Inbound -> {
                val text = hit.chip.endpoint
                if (text.isBlank()) return
                val clipboard = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Spider", text))
                snackbar(getString(R.string.topology_detail_copied, text))
            }

            /* ② 出站卡 / 默认分流卡长按 → 打开节点选择器 */
            is TopologyHit.Card -> {
                if (hit.id == TopologyRepository.ID_DEFAULT_RULE ||
                    hit.id == TopologyRepository.ID_MAIN ||
                    hit.id.startsWith("ou-")
                ) {
                    openNodePicker()
                    return
                }
                if (!hit.id.startsWith("ru-")) return
                val ruleId = hit.id.removePrefix("ru-").toLongOrNull() ?: return
                pendingEgressRuleId = ruleId
                /* 传当前出口节点过去，让选择页把它高亮出来 —— 用户一眼能看到「现在走的是谁」。
                   outbound 是 0/-1/-2 时没有对应实体，那就什么都不高亮（传 null）。
                   getProfile 会查库，放后台线程。 */
                lifecycleScope.launch(Dispatchers.Default) {
                    val current = SagerDatabase.rulesDao.getById(ruleId)?.outbound
                    val entity = if (current != null && current > 0) {
                        ProfileManager.getProfile(current)
                    } else {
                        null
                    }
                    onMainDispatcher {
                        val intent = Intent(requireContext(), ProfileSelectActivity::class.java)
                        if (entity != null) {
                            intent.putExtra(ProfileSelectActivity.EXTRA_SELECTED, entity)
                        }
                        pickEgress.launch(intent)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ ① 详情浮层

    /**
     * 打开 ① 入站详情。
     *
     * 刻意**不弹 Dialog、不换 Fragment** —— 需求是「不重建主屏、不丢失已显示信息」，
     * 走生命周期的话下拉刷新拿到的实时连接就没了。这里是同一棵视图树里的一层。
     */
    private fun showInboundDetail(chip: TopologyInbound) {
        val overlay = overlayView ?: return

        /* 记下在看哪一朵云 —— 推送来了要照着这个 id 找新的数据重画（见 [refreshOpenDetail]）。 */
        openInboundId = chip.id

        overlayTitle?.text = chip.label
        overlayEndpoint?.text = labelled(R.string.topology_detail_endpoint, chip.endpoint)
        overlayApp?.text = labelled(
            R.string.topology_detail_app,
            chip.appPackage.ifBlank { getString(R.string.topology_detail_no_app) },
        )

        /* 流量一定要带上「几条连接合计」和「累计值」两个限定词 ——
           ① 层是按 host 聚合的，不写清楚就是在误导。 */
        val traffic = buildString {
            append("↓").append(formatBytes(chip.download))
            append("  ↑").append(formatBytes(chip.upload))
            if (chip.connectionCount > 1) {
                append("（").append(getString(R.string.topology_detail_count, chip.connectionCount))
                append("）")
            }
        }
        overlayTraffic?.text = labelled(R.string.topology_detail_traffic, traffic)

        overlayRule?.text = labelled(
            R.string.topology_detail_rule,
            chip.ruleName?.let { "$it · ${matchKindText(chip.matchKind)}" }
                ?: getString(R.string.topology_detail_no_rule),
        )

        overlayStart?.text = labelled(R.string.topology_detail_start, formatStart(chip.startAt))

        overlay.visibility = View.VISIBLE
        updateBackCallback()
    }

    private fun hideInboundDetail() {
        overlayView?.visibility = View.GONE
        openInboundId = null
        updateBackCallback()
    }

    private fun labelled(labelRes: Int, value: String): String =
        getString(labelRes) + "：" + value.ifBlank { "—" }

    private fun matchKindText(kind: RuleMatchKind): String = getString(
        when (kind) {
            RuleMatchKind.GEOSITE -> R.string.topology_match_geosite
            RuleMatchKind.GEOIP -> R.string.topology_match_geoip
            RuleMatchKind.DOMAIN -> R.string.topology_match_domain
            RuleMatchKind.APP -> R.string.topology_match_app
            RuleMatchKind.IP -> R.string.topology_match_ip
            RuleMatchKind.PORT -> R.string.topology_match_port
            RuleMatchKind.NETWORK -> R.string.topology_match_network
            RuleMatchKind.PROTOCOL -> R.string.topology_match_protocol
            RuleMatchKind.BUILTIN -> R.string.topology_match_builtin
            RuleMatchKind.UNKNOWN -> R.string.topology_match_unknown
        }
    )

    /**
     * 内核给的是 RFC3339（`2026-09-15T09:31:02.123456789+08:00`）。
     *
     * 内核和 App 跑在同一台机器上，所以它的时区偏移**就是**设备的偏移，
     * 直接把「日期 + 时刻」按本地时间解析即可，不需要真的处理时区。
     * 解析失败就原样显示 —— 宁可不加工，也不要显示一个错的时间。
     */
    private fun formatStart(raw: String): String {
        if (raw.isBlank()) return "—"
        val head = raw.substringBefore('.').substringBefore('+').removeSuffix("Z")
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(head)
        }.getOrNull() ?: return raw

        val elapsed = System.currentTimeMillis() - parsed.time
        val ago = when {
            elapsed < 0 -> null
            elapsed < 60_000L -> getString(R.string.topology_detail_ago_seconds, elapsed / 1000)
            elapsed < 3_600_000L -> getString(R.string.topology_detail_ago_minutes, elapsed / 60_000)
            elapsed < 86_400_000L -> getString(R.string.topology_detail_ago_hours, elapsed / 3_600_000)
            else -> getString(R.string.topology_detail_ago_days, elapsed / 86_400_000)
        } ?: return SimpleDateFormat("HH:mm:ss", Locale.US).format(parsed)

        return getString(
            R.string.topology_detail_clock,
            SimpleDateFormat("HH:mm:ss", Locale.US).format(parsed),
            ago,
        )
    }

    // ------------------------------------------------------------ 刷新

    /**
     * 拉一次实时数据 + 读一遍数据库，然后上屏。
     *
     * 注意：项目里所有 DAO 都是**同步**方法，而且 `SagerDatabase` 开了
     * `allowMainThreadQueries()` —— 在主线程调用不会崩，但会掉帧。
     * 所以整段都放在 [Dispatchers.Default] 上。
     *
     * 失败一律**不弹错、不白屏**：`ClashApiClient` 不抛异常，只返回状态，
     * 由 ① 层自己显示对应的降级文案（见 plan §1.3）。
     */
    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.Default) {
            val clash = ClashApiClient.fetch()
            /* `/rules` 顺手存一份给推送路径用 —— 推送只带 `/connections`，
               没有它就认不出内核自带的规则（见 [lastClashRules]）。 */
            if (clash is ClashResult.Ok) lastClashRules = clash.rules
            val snapshot =
                runCatching { TopologyRepository.load(clash, ruleExpanded, outExpanded) }
                    .getOrNull()
            /* 分组名一起读掉 —— 反正已经在后台线程了。`currentGroup()` 在没有分组时
               会现场建一个「未分组」，那是写库，绝不能放主线程。 */
            val groupName = runCatching { DataStore.currentGroup().displayName() }.getOrNull()

            onMainDispatcher {
                /* 无论结果如何都要把转圈收掉，否则下拉的圈会一直转 */
                refreshLayout?.isRefreshing = false
                updateGroupLabel(groupName)
                if (snapshot == null) return@onMainDispatcher
                applySnapshot(snapshot)
            }
        }
    }

    /** 一行小字，用来核对与旧主页是否逐项一致。
     *
     *  文案全在资源里（`topology_summary_*` / `topology_inbound_*`）—— 这段以前是写死在
     *  Kotlin 里的中文，非中文环境下会直接露出中文，所以这次一并提出来。
     *  中间的「 · 」分隔符留在代码里：它不属于任何一种语言，也没必要翻译。 */
    private fun buildSummary(snapshot: TopologySnapshot): String = buildString {
        append(getString(R.string.topology_summary_rules, snapshot.enabledRuleTotal))
        if (snapshot.ruleOverflow > 0) {
            append(
                getString(
                    R.string.topology_summary_rules_more,
                    snapshot.rules.size,
                    snapshot.ruleOverflow
                )
            )
        }
        append(" · ").append(getString(R.string.topology_summary_outbounds, snapshot.outbounds.size))
        if (snapshot.outboundOverflow > 0) {
            append(" ").append(getString(R.string.topology_more, snapshot.outboundOverflow))
        }
        append(" · ").append(getString(R.string.topology_summary_edges, snapshot.edges.size))
        append(" · ").append(getString(R.string.topology_summary_nodes, snapshot.nodeTotal))
        append("\n")
        append(inboundSummary(snapshot))
    }

    /**
     * ① 层那一行的文案。
     *
     * 三种"空"必须说成三件不同的事 —— 否则用户看到「暂无连接」会去查网络，
     * 而真正的原因是 Clash API 没开。
     */
    private fun inboundSummary(snapshot: TopologySnapshot): String = when (snapshot.inboundState) {
        TopologyInboundState.OK -> buildString {
            append(getString(R.string.topology_inbound_live, snapshot.inbounds.size))
            if (snapshot.inboundOverflow > 0) {
                append(" ").append(getString(R.string.topology_more, snapshot.inboundOverflow))
            }
            /* ↓↑ 加数字，符号本身不需要翻译 */
            append(" · ↓${formatBytes(snapshot.downloadTotal)} ↑${formatBytes(snapshot.uploadTotal)}")
            append(" · ").append(getString(R.string.topology_pull_refresh))
        }

        TopologyInboundState.IDLE ->
            getString(R.string.topology_inbound_live, 0) +
                " · " + getString(R.string.topology_pull_refresh)

        TopologyInboundState.DISABLED -> getString(R.string.topology_inbound_disabled_hint)

        TopologyInboundState.UNAVAILABLE -> getString(R.string.topology_inbound_unavailable_hint)
    }

    /** 与原型 `fmtB()` 同一口径：≥1M 保留一位小数、≥1K 取整、否则原样 */
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1fM", bytes / 1048576.0)
        bytes >= 1024L -> "${Math.round(bytes / 1024.0)}K"
        else -> "$bytes"
    }

    private companion object {
        /** 设置面板开着没 —— 见 [settingsPanelOpen]。 */
        const val KEY_SETTINGS_OPEN = "topology_settings_open"

        /* 分组下拉里动作行的 id。用普通 Int 常量而不是 menu id：
           这些行是 ListPopupWindow 里自绘的，跟 Menu 资源没关系。 */
        const val ACTION_UPDATE_ALL = 1
        const val ACTION_SCAN = 2
        const val ACTION_CLIPBOARD = 3
        const val ACTION_FILE = 4
        const val ACTION_NEW_GROUP = 5
        const val ACTION_MANUAL = 6

        /* 下拉的行类型。三种视图（标题 / 分隔线 / 普通行）必须分开，
           否则 convertView 会串 —— BaseAdapter 会拿标题行的 TextView 去当普通行用。 */
        const val TYPE_ROW = 0
        const val TYPE_HEADER = 1
        const val TYPE_DIVIDER = 2
    }
}
