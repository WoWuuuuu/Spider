package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import androidx.activity.addCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.utils.Util

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        /* 抽屉（`DrawerLayout` + 两个 `NavigationView`）与旧底部 FAB 纸飞机开关已彻底移除。
           新主页的导航在右侧设置面板中，连接/断开总开关统一由顶栏的 ⏻ 负责。 */

        if (savedInstanceState == null) {
            navigateTo(DEST_HOME)
        }
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is TopologyFragment) {
                // 主页在返回键上直接退到后台
                moveTaskToBack(true)
            } else {
                navigateTo(DEST_HOME)
            }
        }


        setContentView(binding.root)
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /* 这里原来有个 `refreshNavMenu(clashApi)` —— 抽屉里「流量」那一项的显示开关。
       抽屉删掉（2026-09-16）之后它没有任何事可做，连同调用点一起删了：
       调用方是 `SettingsPreferenceFragment` 切换 Clash API 时的那一句，
       而新主页的 ① 层是在 `onStart` 里重新读 `DataStore.enableClashAPI` 再拉一次，
       切完设置回到主页自然会刷新，不依赖这个回调。 */

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {

            navigateTo(DEST_HOME)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            navigateTo(DEST_HOME)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
    }

    /**
     * 跳到某个页面。
     *
     * 参数**不再是 `@IdRes`**：这些目标原本是 `res/menu/main_drawer_menu.xml` 里的
     * `nav_*` id，抽屉删掉（2026-09-16）之后菜单跟着删了，id 也就不存在了。
     * 但「跳页」这件事本身跟菜单无关 —— [TopologyFragment] 也要用它跳路由页 ——
     * 所以改成一组自有的常量，语义反而更准。
     *
     * **目前只有 [DEST_HOME] 有调用方**（`ToolbarFragment` 的返回箭头）。
     * 其余分支是**故意留着**的导航表，不是漏删的死代码：
     *   · [DEST_ROUTE] / [DEST_TOOLS] / [DEST_LOGCAT] / [DEST_ABOUT] 现在由
     *     设置面板里的 `SettingsPreferenceFragment` 直接 replace `fragment_holder` 到达
     *     （`SettingsPreferenceFragment.kt:58/66/74/90`），不经过这里；
     *   · [DEST_FAQ] / [DEST_PROMOTION] 当前**确实没有入口** ——
     *     前者是上游文档站（`AboutFragment` 里另有几个同域链接，不算断），
     *     后者在旧抽屉里本来就是 `isVisible = false`。留着是为了以后加回来时不用重写。
     */
    fun navigateTo(dest: Int): Boolean {
        when (dest) {
            DEST_HOME -> {
                /* 主页**只有**新 UI 一种（2026-09-16 用户拍板：新界面转正，旧主页不再出现）。
                   原来这里是一个 `DataStore.enableNewUI` 三元分支，开关已经拆掉了。

                   ⚠ `ConfigurationFragment` 这个类**没有删、也不能删** —— 它现在以
                   「节点选择器」的身份还在服役：
                     · `ProfileSelectActivity`（长按 ② 规则卡换出口）
                     · `SwitchActivity`（快捷方式里的「切换节点」）
                   删掉它会把这两条路一起打断。这里只是不再让「主页」这个位置落到它身上。 */
                displayFragment(TopologyFragment())
            }

            DEST_ROUTE -> displayFragment(RouteFragment())
            DEST_SETTINGS -> displayFragment(SettingsFragment())
            DEST_TRAFFIC -> displayFragment(WebviewFragment())
            DEST_TOOLS -> displayFragment(ToolsFragment())
            DEST_LOGCAT -> displayFragment(LogcatFragment())
            DEST_FAQ -> {
                launchCustomTab("https://matsuridayo.github.io/")
                return false
            }

            DEST_ABOUT -> displayFragment(AboutFragment())
            DEST_PROMOTION -> {
                launchCustomTab("https://neko-box.pages.dev/喵")
                return false
            }

            else -> return false
        }
        /* 这里原来还有一句 `navigation.menu.findItem(id).isChecked = true` ——
           把抽屉里对应的那一项标成选中。抽屉删掉（2026-09-16）之后没有对象了。 */
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        DataStore.serviceState = state

        // 新主页顶栏的 ⏻ 也要跟着变。DataStore.serviceState 是个普通字段（DataStore.kt:28），
        // 不是持久化项、没有变更监听可注册，只能由这里推一下。
        // 这里不是新主页时 findFragmentById 会返回别的类型（或 null），安全跳过。
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? TopologyFragment)
            ?.onServiceStateChanged()
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    /* `cbSpeedUpdate` 不再覆写：它的唯一去处是底部那条 `StatsBar.updateSpeed()`，
       而那条已经被整个删掉了。`SagerConnection.Callback` 里它本来就有空的默认实现
       （bg/SagerConnection.kt:43），不覆写 = 收下就丢掉，不会漏掉别的逻辑。
       内核侧照旧按 connectionId 广播，这一点没动。 */

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    /* 这里原来还有 DPAD_LEFT / DPAD_RIGHT 开合抽屉的分支（遥控器/键盘导航用）。
       抽屉删掉（2026-09-16）之后它们没有对象了，一起删掉 ——
       剩下的「把按键转交给当前 Fragment」这条链保持不变。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyDown(keyCode, event)) return true

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

    companion object {
        /* 页面目标。**刻意不用 `R.id.nav_*`** —— 那些 id 来自
           `res/menu/main_drawer_menu.xml`（抽屉菜单），菜单已经跟着抽屉一起删了。
           「跳页」这件事跟菜单无关，所以自带一组常量，见 [navigateTo]。 */
        const val DEST_HOME = 0
        const val DEST_ROUTE = 1
        const val DEST_SETTINGS = 2
        const val DEST_TRAFFIC = 3
        const val DEST_TOOLS = 4
        const val DEST_LOGCAT = 5
        const val DEST_FAQ = 6
        const val DEST_ABOUT = 7
        const val DEST_PROMOTION = 8
    }

}
