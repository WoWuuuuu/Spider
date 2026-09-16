package io.nekohasekai.sagernet.ui.topology

import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 一次 `/connections` 拉取的结果。**不抛异常**，失败也只是一条状态。 */
sealed interface ClashResult {

    /**
     * 拿到了数据。
     *
     * [rules] 是 `/rules` 的结果，**可能为 null**（那一路失败不影响主数据）——
     * 它的用处是「认出哪些规则是内核自带的」，见 [RuleSignature.isBuiltin]。
     */
    data class Ok(val snapshot: ClashSnapshot, val rules: List<ClashRuleEntry>?) : ClashResult

    /** 用户没开 Clash API。这是**配置状态**，不是错误 —— 界面上给一个跳设置的入口即可。 */
    data object Disabled : ClashResult

    /**
     * 连不上 / 返回了看不懂的东西。
     *
     * ⚠ 主进程**没有**任何「服务是否在跑」的信号（`SagerNet.kt` 只有
     * `startService` / `reloadService` / `stopService` 三个广播，没有状态回传，
     * 也没有 StateStore）。所以「VPN 没启动」和「其它失败」在主进程里无法区分，
     * 只能靠**回环地址上的连接被拒绝**来推断 —— 这已经足够可靠：
     * 内核没跑时 9090 端口不会有人监听。
     */
    data class Unavailable(val reason: String) : ClashResult
}

/**
 * 一个推送订阅的句柄。
 *
 * [close] 之后**不会再有任何回调** —— 主动关掉不算「流断了」，调用方不该收到 `onEnd`
 * 而去显示降级态（那会让「切后台再切回来」看起来像出错了）。
 * 靠 [ended] 这个标记来保证：`close()` 先把它置位，`onEnd` 那边用 CAS 抢。
 */
class ClashSubscription internal constructor(
    private val socket: WebSocket?,
    private val ended: AtomicBoolean,
) {

    fun close() {
        ended.set(true)
        socket?.let { runCatching { it.cancel() } }
    }

    internal companion object {

        /** Clash API 没开时的占位句柄 —— 调用方不用到处判 null。 */
        fun none(): ClashSubscription = ClashSubscription(null, AtomicBoolean(true))
    }
}

/**
 * Clash API 的最小只读客户端。
 *
 * ## 为什么走 HTTP
 *
 * Clash API 跑在 **`:bg` 进程**里（`DataStore.baseService` 的注释写着 "only in bg process"），
 * 主进程拿不到 `box` 实例；现有 AIDL 只暴露了 `cbSpeed` / `cbTraffic` / `cbSelector`，
 * 没有连接列表。所以主进程只能走 HTTP —— 见 `docs/spider-newui-plan.md` §0.1。
 *
 * ## 两种取数方式
 *
 * - [fetch]：拉一次就结束。事件驱动（进页面 / 下拉 / 换分组…）走它。
 * - [subscribeConnections]：接内核的推送流，内核自己按固定间隔推全量快照。
 *   页面可见时走它，所以「① 层是活的」这件事不需要任何客户端定时器。
 *
 * 两条路并存不是冗余：推送只带 Clash 那一半数据（① 层），②③ 层在数据库里，
 * 所以任何**结构**变化仍然得走 [fetch] 那一路把两边合起来。
 */
object ClashApiClient {

    /**
     * ⚠ 这是新 UI 里**唯一**出现 `127.0.0.1:9090` 的地方。
     *
     * 它镜像的是内核侧唯一的事实来源：`fmt/ConfigBuilder.kt:159` 把
     * `external_controller` 写死成这个值。内核不提供"查询我监听在哪"的接口，
     * 所以这个常量没法消掉 —— 只能集中在一处、并标明出处。
     *
     * 平时会优先用 [baseUrl] 从用户设置里推出来的地址；这个只做兜底。
     */
    private const val FALLBACK_CONTROLLER = "http://127.0.0.1:9090"

    /**
     * 内核推送 `/connections` 的间隔（毫秒）。
     *
     * 内核默认是 **1000**（`clashapi/connections.go:45-48`，`?interval=` 可覆盖）。
     * 这里放慢到 3000，原因是每推一次都要付两笔固定成本：
     *
     * 1. **内核侧**：`trafficManager.Snapshot()` 内部调 `runtime.ReadMemStats()`
     *    （`trafficontrol/manager.go:150-151`）—— 那是 **stop-the-world**，
     *    停的是**和代理转发同一个 Go runtime**。1 秒一次等于每秒给数据面塞一次抖动。
     * 2. **App 侧**：每帧都要把整包 JSON 重新解析一遍（`ClashJson.parseSnapshot`），
     *    再加一次读库（`TopologyRepository.load`）。这两笔跟连接数、节点数成正比。
     *
     * 而 ① 层要表达的是「哪些域名在跑」——**3 秒的粒度人眼分辨不出来**，
     * 但它把上面两笔成本都砍到 1/3。
     *
     * 想调只改这一个常量（1000 = 最实时，5000 = 最省）。
     */
    private const val PUSH_INTERVAL_MS = 3_000L

    /**
     * 超时全部压到秒级：目标是本机回环，正常时是亚毫秒级。
     * 设长了只会在内核没跑时让下拉刷新转半天。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            /* 回环地址**必须**绕开系统代理：内核自己的 HTTP 代理设置可能正指着本机，
               不排除的话会自己连自己、转圈。 */
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 推送流专用的 client。
     *
     * ⚠ **不能复用 [client]**：那个设了 `readTimeout(2s)` / `callTimeout(3s)`，
     * 是给「拉一次就结束」用的。套在长连接上，3 秒后 OkHttp 会直接把流掐掉，
     * 表现成「① 层每 3 秒闪一次降级态」。
     *
     * ⚠ **也不能开 `pingInterval`**：内核侧只写不读（`connections.go` 里没有读循环），
     * 不会回 pong，OkHttp 收不到 pong 就会判定连接失效并主动断开。
     * 半开连接靠「内核写失败后自己关掉」来收场，这条路径在内核里是有的
     * （`sendSnapshot()` 出错就 break 出循环）。
     */
    private val streamClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 拉一次 `/connections`（外加 `/rules` 作为加分项）。
     *
     * 必须在后台调用 —— 内部会 `withContext(Dispatchers.IO)`，但别在 `onDraw` 里调。
     */
    suspend fun fetch(): ClashResult = withContext(Dispatchers.IO) {
        if (!DataStore.enableClashAPI) return@withContext ClashResult.Disabled

        val base = baseUrl()

        val connectionsBody = get(base.newBuilder().addPathSegment("connections").build())
            ?: return@withContext ClashResult.Unavailable("连接不上 Clash API（内核未运行？）")
        val snapshot = ClashJson.parseSnapshot(connectionsBody)
            ?: return@withContext ClashResult.Unavailable("Clash API 返回了无法解析的连接列表")

        /* /rules 只是用来认内置规则的，失败不影响主流程 —— 拿不到就退回
           RuleSignature 里那份写死的集合。 */
        val rulesBody = get(base.newBuilder().addPathSegment("rules").build())
        val rules = rulesBody?.let { ClashJson.parseRules(it) }

        ClashResult.Ok(snapshot, rules)
    }

    /**
     * 订阅 `/connections` 的推送。
     *
     * 内核侧实现见 `experimental/clashapi/connections.go:21-77`：带 `Upgrade: websocket`
     * 时走 `ws.UpgradeHTTP`，然后按 `interval` 毫秒**主动推全量快照**。
     * 也就是说「实时」这件事内核已经做好了，客户端一行轮询都不用写。
     *
     * ⚠ 推的是**全量快照不是增量** —— 它并不比轮询省带宽（回环地址，带宽本来也不值钱），
     * 省的是往返延迟和客户端定时器。真正要防的是「收到就全量重绘」，
     * 见 [TopologyView.structureKey]。
     *
     * [onSnapshot] / [onEnd] 都在 **OkHttp 自己的线程**上回调，调用方自己切主线程。
     * [onEnd] 只在**意外断开**时来一次（主动 [ClashSubscription.close] 不会触发）。
     */
    fun subscribeConnections(
        intervalMs: Long = PUSH_INTERVAL_MS,
        onSnapshot: (ClashSnapshot) -> Unit,
        onEnd: (String) -> Unit,
    ): ClashSubscription {
        if (!DataStore.enableClashAPI) return ClashSubscription.none()

        val url = baseUrl().newBuilder()
            .addPathSegment("connections")
            .addQueryParameter("interval", intervalMs.toString())
            .build()

        return openStream(Request.Builder().url(url).build(), onEnd) { text ->
            /* 解析失败直接丢这一帧 —— 内核偶尔推个半截包不该让 ① 层变降级态，
               下一帧就回来了。 */
            ClashJson.parseSnapshot(text)?.let(onSnapshot)
        }
    }

    /**
     * 起一条 WebSocket 流，把每条文本消息交给 [onText]。
     *
     * [ended] 用 CAS 保证 [onEnd] **最多来一次**：OkHttp 在 `cancel()` 之后
     * 还会补一个 `onFailure`（"Socket closed"），不挡掉的话「切后台」会被误判成「断线」。
     */
    private fun openStream(
        request: Request,
        onEnd: (String) -> Unit,
        onText: (String) -> Unit,
    ): ClashSubscription {
        val ended = AtomicBoolean(false)
        val socket = streamClient.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!ended.get()) onText(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (ended.compareAndSet(false, true)) {
                        onEnd(reason.ifBlank { "closed($code)" })
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (ended.compareAndSet(false, true)) {
                        onEnd(t.message ?: t.javaClass.simpleName)
                    }
                }
            },
        )
        return ClashSubscription(socket, ended)
    }

    /**
     * Clash API 的 origin。
     *
     * 优先取用户设置里 `yacdURL` 的 origin —— **仅当它指向本机**。
     * `yacdURL` 是 WebView 打开的外部 UI 地址（`WebviewFragment.kt:51`），
     * 默认就是 `http://127.0.0.1:9090/ui`，但用户能改成远端地址；
     * 那种情况下它跟内核监听的地址无关，不能拿来用。
     */
    private fun baseUrl(): HttpUrl {
        val fromSetting = runCatching { DataStore.yacdURL.toHttpUrlOrNull() }.getOrNull()
        val loopback = fromSetting?.takeIf { url ->
            url.host == "127.0.0.1" || url.host == "localhost" || url.host == "::1"
        }
        val source = loopback ?: FALLBACK_CONTROLLER.toHttpUrl()
        /* 只要 origin：yacdURL 带 `/ui` 路径，直接用会把请求拼成 `/ui/connections`。 */
        return HttpUrl.Builder()
            .scheme(source.scheme)
            .host(source.host)
            .port(source.port)
            .build()
    }

    /** 失败一律返回 null —— 调用方只关心"有没有拿到"，不关心异常类型。 */
    private fun get(url: HttpUrl): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            /* OkHttp 5.x 的 body 是非空的，4.x 是可空的。用 `?.` 两种都能编过
               （项目没开 Kotlin 的 allWarningsAsErrors，最多是个 warning）。 */
            response.body?.string()
        }
    }.getOrNull()
}
