# Spider 新 UI 接入老系统 · 分步实施计划 v1

> 目标：把原型 `docs/spider-ui-prototype-v6.html` 的三层拓扑主页接进 `Spider-android/`，  
> **与旧主页并存、可开关切换、可一键回退**，且**不改动现有功能的任何一行代码路径**。  
> 工程根：`D:\workspace\Spider\Spider-android`，包名 `io.nekohasekai.sagernet`。

---

## 0. 三个前置决策（我先按推荐值定，你随时可推翻）

| 编号 | 问题            | 决定                                              | 状态                   |
| -- | ------------- | ----------------------------------------------- | -------------------- |
| D1 | 实时连接数据怎么取     | **走 HTTP，且阶段 3 只做「下拉手动刷新」**，不做自动轮询              | 已定                   |
| D2 | 新 UI 是只读还是可编辑 | **主体只读，但开放两类写操作**：① 卡片编辑（点开进现成编辑页）② 节点切换（改规则出口） | ✅ 用户已确认              |
| D3 | 主题用哪套         | **新 UI 自带完整色板，只把「强调色」一根线接到项目主题**（见 §0.2）            | ✅ 用户已确认（"自带的主题可以删了"） |

### 0.1 「HTTP 轮询」和「扩 AIDL」到底在说什么

App 跑在**两个进程**里：主进程（你看到的界面）+ `:bg` 后台进程（VPN 内核跑在这里）。  
「当前有哪些连接」这份数据是内核产生的，**天生在后台进程里**。主进程要拿到它只有两条路：

|     | 路 A · HTTP 轮询                                                                   | 路 B · 扩 AIDL                                                 |
| --- | ------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| 怎么拿 | 内核自带一个小网页服务（就是 yacd 用的同一个 `127.0.0.1:9090`）。界面每隔几秒去 `GET /connections` 拿一坨 JSON | 在两个进程之间的"官方通话线路"上加一个频道，后台主动把变化推给界面                           |
| 类比  | 每隔 5 秒打电话问"到哪了"                                                                 | 他到了主动发消息给你                                                   |
| 好处  | 内核已经支持，不用改内核、不用改跨进程代码，随时能关                                                      | 实时、无轮询、更省电                                                   |
| 坏处  | 是"问一次有一次"，做不到变化立刻推；要省电就不能一直问                                                    | AIDL 是**跨进程契约**，两边都得改都得对齐；对不上是"整个后台服务连不上"级别的问题，风险外溢到 VPN 主功能 |

**所以 D1 的决定是：走 A，而且阶段 3 只做「下拉手动刷新」** —— 零风险、不碰内核、不耗电。  
真要看实时流量，用户本来就会去 yacd 页面。等新 UI 稳定且确实需要实时，再单独排期做 B。

### 0.2 主题：为什么"复用项目主题"是个伪命题，以及正确做法

**先看事实。** 我把 `res/values/themes.xml` 拆开数过：那 21 套主题**每套只改了 5 个属性** ——
`colorPrimary` / `colorPrimaryDark` / `colorAccent` / `colorMaterial100` / `colorMaterial300`，
取值全是 `material_*_accent_200` / `material_*_200` 这类 Material 现成色。
也就是说：

> **那 21 套主题不是 21 套色板，是 21 个强调色。**

背景、卡片、文字、分割线这些"底色"，全部来自父主题
`Theme.MaterialComponents.DayNight.NoActionBar` 的默认值。
再看 `values-night/colors.xml`，**整个夜间模式只覆盖了 3 个颜色**
（`fab_color_progress`、`preference_simple_menu_background`、`overlay_blur_color`）。

**结论：项目里根本没有一套"好看的完整色板"可供复用。** 想复用也无从复用。
所以"自带色板不带进产品"这个前提本身就是错的 —— 不是你的设计要迁就项目，而是项目那边只有一个 accent 可以对接。

**正确做法：把色板拆成三层，各归各的。**

| 层 | 谁决定 | 具体内容 |
|---|---|---|
| **底色层** | **新 UI 自带** | 背景、玻璃底、描边、文字、分割线。亮 = Latte，暗 = Mocha。**这就是"好看"的来源，完整保住** |
| **强调层** | **跟随项目主题**（1 个变量） | 激活态、命中高亮、选中态、连线高亮。取 `?attr/colorAccent`，用户换主题它就跟着换 |
| **语义层** | **新 UI 自带** | 入站 / 规则 / 出站 / 直连 / 拦截 / 代理 / 警示 / 错误。**21 套主题里根本没有这些语义色，必须自带** |

**为什么这样是对的**

1. 新 UI 是**自绘 View**（`onDraw`），每一个像素的颜色都是自己决定的 —— 它**不需要**走 `?attr/`。
   所以"跟着项目主题走"不等于"被项目主题绑死"，两者不冲突。
2. 拓扑图需要 **8~10 个语义色**（哪条线是代理、哪张卡是拦截、哪个是警示）。
   21 套主题能给的只有 1 个 accent，**信息量根本不够**，自带是必然的。
3. 只接 1 根线，工作量≈0：`MaterialColors.getColor(view, R.attr.colorAccent)` 一行。
   也可以退一步，写一张 21 行的映射表手工指定（比如红色主题给 `--red`、蓝色主题给 `--blue`），
   效果更可控，代价是 21 行常量。

**两个必须处理的例外**

- **BLACK 主题（AMOLED）**：`Theme.SagerNet.Black` 把 accent 换成了
  `?android:textColorSecondary`（等于"没有强调色"），而且它期望**纯黑底**。
  深色下如果还用 Mocha 的 `#1E1E2E`，在 AMOLED 屏上会显得"发灰"。
  → 给深色加一个**纯黑变体**，只改 3 个变量（背景、玻璃底、描边）。
- **玻璃模糊**：`minSdk 21`，真模糊要 Android 12+ 的 `RenderEffect`。
  → 12 以下自动降级为"半透明 + 高光边"的假玻璃（原型本来主要就是这个）。

**免费的一致性**：顶栏继续复用现有 `layout_appbar.xml`，它天然跟着主题变色。
这样"顶栏跟主题、内容区是设计过的色板"—— 视觉上既不割裂，设计也完整保留。

---

## 1. 显示细节（屏幕上到底有什么）

### 1.1 分区（viewBox 366×820，与真实 `.screen` 像素 1:1）

| 区            | 纵向范围    | 高度  | 占比 | 布局算法                     |
| ------------ | ------- | --- | -- | ------------------------ |
| 顶栏 + 电源/工具   | 0–52    | 52  | —  | 复用现有 `layout_appbar.xml` |
| ① 入站 · 实时连接  | 74–441  | 367 | 半屏 | 散点云 + 景深（近大远小）           |
| 走廊           | 441–475 | 34  | —  | 连线变道区，不放东西               |
| ② 路由规则 · 仅启用 | 475–639 | 164 | —  | 弧形排布（顺序 = 内核匹配优先级）       |
| 走廊           | 639–673 | 34  | —  | 同上                       |
| ③ 出站 · 由规则推导 | 673–808 | 135 | —  | 散点云                      |
| 底部（统计/提示）    | 808–820 | 12  | —  | 复用现有 `StatsBar`          |

### 1.2 卡片显示什么

**① 入站 chip（矮 22px）**

- 主信息：`host:port`（有域名解析时显示域名）
- 副信息：↑上行 / ↓下行 实时速率 + **命中的规则名**
- 景深：越靠下越大（0.85→1.15 倍）、越清晰（透明度 0.55→1.0）
- 上限 18 张（阶段 4 视情况提到 26）

**② 规则卡（弧宽 55–62px）**

- 第一行：规则名（整行，不截断优先）
- 第二行：命中连接数 + `→` 出站名（带出站色点）
- **出站为空** → 显示「未指定」警示 tag
- **出站节点已被删** → 显示「错误」（孤儿规则态，阶段 4 单独做）
- 上限 5 张；超出 → 第 6 槽位「+N 更多」胶囊（46px，点击进完整列表）

**③ 出站卡（宽 72px）**

- 代理节点：旗帜 emoji + 名称 + 延迟（`--ms`）
- `DIRECT` / `BLOCK`：动作卡，副信息显示「动作」
- **主代理**：`outbound = 0`（新建规则的默认值）与「`outbound` 恰好等于当前选中 profile」合并成**同一张**卡，
  标题取当前节点名，取不到就退化成「走代理」。原因见 §6 决策 D4。
- **节点不存在**（规则指向已删节点）→ 半透明 ghost 卡，显示「待添加」
- 上限 3 张；超出 → 「+N」胶囊

### 1.3 五种异常态（必须有明确显示，不能白屏）

| 态            | 触发条件                        | ①层                         | ②层            | ③层         |
| ------------ | --------------------------- | -------------------------- | ------------- | ---------- |
| 服务未启动        | `serviceState == STOPPED`   | 空 + 提示「未连接」                | 正常显示          | 正常显示       |
| Clash API 关闭 | `!DataStore.enableClashAPI` | 空 + 「需开启 Clash API」+ 跳设置按钮 | 正常            | 正常         |
| 无活动连接        | 服务已起、`/connections` 为空      | 空 + 「暂无活动连接」               | 正常            | 正常         |
| 规则全禁用        | `onRules().isEmpty()`       | 正常                         | 空 + 「没有启用的规则」 | 空          |
| 孤儿规则         | `rule.outbound` 指向已删节点      | —                          | 「错误」tag       | ghost「待添加」 |



---

## 2. 操作细节（每个手势落到哪）

| 操作     | 目标        | 行为                                                   | 落地实现                                                                                       |
| ------ | --------- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| 单击     | ① 入站 chip | 底部浮层显示连接详情（host、进程、上下行、命中规则、起止时间），**不重建主屏、不丢失已显示信息** | 新 UI 内部浮层                                                                                  |
| 单击     | ② 规则卡     | 打开规则编辑页                                              | `RouteSettingsActivity` + `EXTRA_ROUTE_ID`                                                 |
| 单击     | ③ 出站卡     | 打开节点编辑页                                              | `ProfileSettingsActivity` + `EXTRA_PROFILE_ID`                                             |
| 单击     | ③「走主代理」卡 | **换节点（打开节点选择器）** ← 新 UI **唯一**的选节点入口 | `ProfileSelectActivity` → 写 `DataStore.selectedProxy`（见下方补记） |
| 长按     | ③「走主代理」卡 | 编辑当前节点                                              | `ProfileSettingsActivity` + `EXTRA_PROFILE_ID`                                             |
| 单击     | 「+N 更多」   | 打开完整规则列表                                             | `displayFragment(RouteFragment())`                                                         |
| 单击     | 层标签       | 临时提亮看清（再点回淡，纯手动，**不互斥**）                             | 新 UI 内部，View 就地处理（阶段 4 补做，见下）                                                               |
| 单击     | 电源键       | 启停 VPN。**未选节点时不开服务**，改弹提示 + 直接开选择器                | `SagerNet.startService()` / `stopService()`；`selectedProxy == 0L` 时走 `topology_need_node` |
| 单击     | 工具区       | 重组 / 搜索 / 设置                                         | 重组=重算布局；搜索=现有搜索页；设置=`SettingsFragment`                                                     |
| 单击     | 工具栏 ＋     | 新建分组（直达）                                          | `GroupSettingsActivity`（**不传 `EXTRA_GROUP_ID` 即新建**）                                       |
| 单击     | 工具栏溢出     | 经典主页（分组与订阅）                                       | `displayFragment(ConfigurationFragment())`                                                 |
| 下拉     | 主屏        | 手动拉一次 `/connections`                                 | 阶段 3 先只做这个                                                                                 |
| 长按     | ① chip    | 复制 `host:port`                                       | `ClipboardManager`                                                                         |
| **长按** | **② 规则卡** | **快速换出口节点**（在本页完成，不跳页）                               | `ProfileSelectActivity` → 写 `rule.outbound` → `ProfileManager.updateRule` → `needReload()` |
| 返回键    | 主屏        | **不退出 App**（回到抽屉或旧主页）                                | 需在 `MainActivity.kt:82-90` 分支里加判断                                                          |

> ⚠ 写操作有**两条**：长按规则卡换出口（§2.1）、点「走主代理」卡选节点（§2.2）。
> 其余全部**只读**：只打开现成编辑页，不直接写数据库。
>
> ⚠ **只读设计必须配一个「去旧主页」的出口，否则 App 直接不可用。**
> 所有「加东西」（新建分组 / 订阅 / 扫码 / 手动添加 / 更新全部订阅）原本只在旧主页
> `ConfigurationFragment`，而 `displayFragmentWithId(R.id.nav_configuration)` 现在**无条件回到新 UI**
> —— 抽屉里再没有第二个口子。2026-09-15 实测：新装用户连一个分组都建不出来。
>
> ⚠ **更正：`res/menu/topology_menu.xml` 不存在。** 那两个入口实际是**分组下拉里的两行**：
> `ACTION_NEW_GROUP = 5`（→ `GroupSettingsActivity`，不传 `EXTRA_GROUP_ID` 即新建）、
> `ACTION_CLASSIC_HOME = 7`（→ `displayFragment(ConfigurationFragment())`）。
> 注意回旧主页**只能**用 `displayFragment(ConfigurationFragment())`，
> 用 `displayFragmentWithId(R.id.nav_configuration)` 会死循环。
>
> ⚠ 层标签的「提亮」也**不改任何数据**，连事件都不往外抛 —— 它只是 View 自己的一次
> `invalidate()`。所以它没有出现在 §2.1 那条写链路里，也不需要 `needReload()`。

### 2.2 第二条写操作：选「主代理」节点（2026-09-16 补，因为发现了一个死洞）

**症状**：用户报「当没有选择节点的时候无法启用」。

**根因（两层）**：
1. `DataStore.selectedProxy == 0L` 时，内核侧 `bg/BaseService.kt:181` 会立刻
   `stopRunner(false, getString(R.string.profile_empty))` → **服务起来再自杀**，
   用户只看到一句「VPN 错误：…」，像按钮坏了。
2. **更根本的是：新 UI 里根本没有地方能选节点。**
   ③ 层**不是节点列表，是路由图** —— `TopologyModel.kt:214-218` 只画**被启用规则指向**的节点。
   新装用户一条规则都没有 → ③ 层只剩一张「走主代理」卡，
   而那张卡的单击原本是 `if (mainId == 0L) return`，**是个死键**。
   「选节点」这条路原本只存在于旧主页的节点列表里，撤掉旧主页时漏了。

**修法**：
1. ③「走主代理」卡**单击 → `ProfileSelectActivity`**（就是长按 ② 用的那个，
   `ConfigurationFragment` 的 select 模式，带分组 tab，**所有**分组的节点都在里面），
   **长按 → 编辑当前节点**。
2. `TopologyModel.outboundCard()`：`mainId == 0L` 时卡片标题用 `topology_pick_node`（「选择节点」）
   而不是 `route_proxy`（「走代理」）—— 否则用户看不出它能点。
3. `toggleService()`：`selectedProxy == 0L` 时**不** `connect.launch()`，
   改 snackbar `topology_need_node` + action `topology_pick_node` 直接开选择器。
   **不动内核**，只是不去触发那次注定失败的重载。

**选节点的写链路**（照抄 `ConfigurationFragment.kt:1556-1584`，那是既有且验证过的写法）：

```
ProfileSelectActivity → RESULT_OK + EXTRA_PROFILE_ID
  → runOnDefaultDispatcher
  → val lastSelected = DataStore.selectedProxy
  → DataStore.selectedProxy = profileId
  → ProfileManager.postUpdate(lastSelected)   // 0L 时内部 getProfile(0)==null 直接返回
  → ProfileManager.postUpdate(profileId)
  → onMainDispatcher { refresh(); if (canStop) needReload() }
```

⚠ 不写 `DataStore.currentProfile` —— 老主页那条选中链路也不写它，
它由 `BaseService.kt:366` 在连接时设置。

### 2.1 唯一一条写操作的完整链路（已逐行核实）
```
长按规则卡
  → ProfileSelectActivity（EXTRA_SELECTED: ProxyEntity，用来高亮当前出口）
  → 用户选完，回传 EXTRA_PROFILE_ID: Long
  → SagerDatabase.rulesDao.getById(ruleId)   // 重新读一遍，别用长按那一刻的旧对象
  → entity.outbound = profileId
  → ProfileManager.updateRule(entity)        // suspend，内部会 ruleIterator { onUpdated() }
  → needReload()                             // ktx/Utils.kt:243
```

> ⚠ 实施阶段 4 时修正：原稿这里写的是 `entity.outbound = profileId ; entity.serialize()`，
> **`RuleEntity` 没有 `serialize()` 这个方法**。它是 Room `@Entity`，字段全是 `var`，
> `@Update` 直接写整行；`packages: Set<String>` 由 `@TypeConverters(StringCollectionConverter::class)` 处理。
> 另外 `getById` 要**在选完之后重新读一次**，而不是复用长按那一刻的对象 ——
> 中间可能隔了很久，规则也许已被删除或被别处改过，拿旧对象写会把别处的改动覆盖掉。

三个必须注意的点：

1. **`ProfileManager.updateRule` 是 `suspend`**（`database/ProfileManager.kt:165`）→ 必须进协程，  
   且写完要 `ruleIterator` 通知出去，否则界面显示的还是旧值。
2. **写完必须 `needReload()`**（`ktx/Utils.kt:243-249`）。它弹一条「需要重载 / 应用」的 snackbar，  
   点「应用」才真的 `SagerNet.reloadService()`。**不要自己写静默重载** —— 老系统一贯是让用户确认，  
   静默重载会在用户不知情时打断连接。
3. **别绕去开 `RouteSettingsActivity` 来换节点。** 它的出口偏好走的是全局字段  
   `DataStore.routeOutbound` / `routeOutboundRule`（`RouteSettingsActivity.kt:69-70, 89-93, 134`），  
   **打开这个页面就会写全局状态**。卡片上换节点请走上面这条短路。  
   （点卡片进编辑页时则相反：必须传 `EXTRA_ROUTE_ID`，它会在 `:226-227` 帮你写 `DataStore.editingId`。）

---

## 3. 分阶段实施（每阶段独立验收、独立回退）

### 阶段 0 · 并存骨架（最小可验证）— ✅ **已完成**

**做了什么**

| # | 文件 | 改动 |
|---|---|---|
| 1 | `ui/TopologyFragment.kt` | **新建**。继承 `ToolbarFragment(R.layout.layout_topology)`，只读地统计规则/节点/分组并显示 |
| 2 | `res/layout/layout_topology.xml` | **新建**。结构与 `layout_route.xml` 一致（CoordinatorLayout + include `layout_appbar`） |
| 3 | `Constants.kt` | `object Key` 加 `ENABLE_NEW_UI = "enableNewUI"` |
| 4 | `database/DataStore.kt` | 加 `var enableNewUI by configurationStore.boolean(Key.ENABLE_NEW_UI)`（默认 false） |
| 5 | `res/xml/global_preferences.xml` | 「常规与外观」类目下、`nightTheme` 之后加 `SwitchPreference`，`app:defaultValue="false"` |
| 6 | `res/values/strings.xml` | 加 6 条 `translatable="false"` 文案（避开 lint 缺翻译报错） |
| 7 | `ui/MainActivity.kt` | **4 处纯新增**：① `displayFragmentWithId` 的 `nav_configuration` 分支按开关二选一 ② `displayFragment` 底部栏条件加 `|| is TopologyFragment` ③ 返回键条件加 `is TopologyFragment` ④ `onPreferenceDataStoreChanged` 加 `Key.ENABLE_NEW_UI` 分支，拨开关立即换页 |

**切换点只有一个**：`displayFragmentWithId(R.id.nav_configuration)`。
启动默认页（`onCreate:80`）、返回键兜底（`:88`）、导航栏点击（`:341`）全都复用它，
所以「新 UI 还是旧 UI」只在这一个 if 里判断，不会出现三处不一致。

**验收**：`./gradlew :app:assembleOssDebug` **BUILD SUCCESSFUL**，产出
`Spider-1.4.2-arm64-v8a-debug.apk`（23 MB）。
`ConfigurationFragment.kt` 一行未改；旧主页逻辑全部原样。
**回退**：设置里关掉「新主页（实验）」，或把 `defaultValue` 改回 `false`。

---

### 阶段 1 · 静态拓扑（只读数据）— ✅ **已完成**

**做什么**

1. `ui/topology/TopologyModel.kt`：只读 `rulesDao` / `proxyDao` / `DataStore.selectedProxy`，把数据翻译成 `TopologySnapshot`。
2. `ui/topology/TopologyLayout.kt`：移植原型两个算法 —— `placeScattered`（散点云 + 分层抖动）、`placeArc`（弧形 + 定宽槽位）。
   （原计划放阶段 2，但**卡片要画出来就得先有坐标**，所以提前。）
3. `ui/topology/TopologyPalette.kt`：§0.2 三层色板的落地。
4. `ui/topology/TopologyView.kt`：自绘 View（项目第一个 `onDraw`，无先例）。**只画卡片、不画线**。
5. `res/layout/layout_topology.xml`：`CoordinatorLayout` + `include layout_appbar.xml` + `TopologyView` + 底部一行摘要 `TextView`（原计划的 `StatsBar` 先简化成一行文字）。

**验收**：卡片数量、名称、启用状态与旧 UI **逐项一致**；旋转屏幕/切深色模式不崩。
**风险**：主线程 DB（`allowMainThreadQueries` 开着，容易顺手写错）→ 强制 dispatcher。

**已完成的验证**

- `:app:compileOssDebugKotlin` → `BUILD SUCCESSFUL`，新文件零告警。
- 算法独立跑通（`.workbuddy-ai/tmp/klayout/`，不进项目）：4 种屏幕尺寸 × ② 弧形 1–15 张 × ③ 散点 1–9 张 × 16 个随机种子
  → **② 60 组用例：重叠 0、越界 0、弧形 y 重复 0；③ 576 组用例：重叠 0、越界 0**。
- 修掉一个真机必崩的坑：原型常数是 366×820 viewBox（≈dp），直接拿来当真机 px 用会把卡宽压成 62px。
  引入 `TopologyLayout.Unit(x, y)` 把所有常数按区域比例缩放。

---

### 阶段 2 · 连线 + 布局算法 — ✅ **已完成**

**做什么**

1. `ui/topology/TopologyEdges.kt`：移植原型的 `bezCtrl` / `bezPath` / `bez` / `hitsBoxes` / `pickOutPort`
   —— 三次贝塞尔（端点切线恒为竖直）、同目标扇形偏置、③ 层入口避障（上→侧）、走廊提前变道。
2. `TopologyView` 加 `drawEdges`：曲线 + 端点圆点，画在卡片**之前**（线在玻璃底下）；
   目标节点已删除 → 虚线 + 低透明度。
3. **粒子层做成独立 View** `TopologyParticleView`，叠在卡片层之上。
   原型里粒子是 SVG 内单独一个 `<g id="pdots">`，每帧只重设它的 innerHTML；
   Android 没有 DOM，「独立 View」是等价物 —— 每帧只 `invalidate()` 自己，
   卡片层的 display list 不会被重新录制。卡片层每帧要跑十几次 `drawText`（带 `ellipsize` 文本测量），
   省下来的是实打实的。
4. 能耗：`onWindowVisibilityChanged` / `onDetachedFromWindow` 立刻停掉帧循环。
   粒子速度按**时间**步长而不是每帧增量，90/120Hz 屏幕上速度才一致。

**验收**：连线端点贴在自己卡片边上、不越界；粒子可见时流动、不可见时停。  
**实测**：360 个场景 / 1080 条曲线 → 端点不贴边 **0**、越界 **0**、控制点未夹住 **0**；
真穿过其它卡片 **8 条 = 0.7%**（原型自述 6%~12%，明显更好）。
对照组（同一批边换成不做避障的直线）真穿过 **4.8%** —— 避障把 4.8% 压到 0.7%，
且**口径与上面完全一致**（都是「穿过至少一张卡的曲线占比」）。

> ⚠ 这里踩过一个坑，记下来：对照组最初数的是「采样点命中数」，
> 而主体数的是「曲线条数」，两个百分比根本不是一回事，**没法比**。
> 度量口径不一致是藏回归最舒服的地方 —— 换成同口径之后，才谈得上「没有退化」。

**关于「曲线不带来额外开销」**：粒子直接拿贝塞尔控制点求值，
每帧一次三次多项式（6 次乘法），和直线插值同量级，所以不需要预采样。

**阶段 3 回填**：①②两段的连线几何在阶段 3 一起复测（见下），②→③ 仍是 0.7%，无退化。

---

### 阶段 3 · 实时层（Clash API）— ✅ **已完成**

**做什么**

1. `ui/topology/ClashConnections.kt`：`/connections` 的 data class。
2. `ui/topology/ClashApiClient.kt`：OkHttp 5.0.0-alpha.3（现成依赖，目前只用于 URL 解析）。
3. 生命周期：`onStart` 起、`onStop` 停、**`onDestroyView` 必须取消协程**（`ThemedActivity` 会 `recreate`）。
4. 开关关闭 / 服务未启动 → ①层走 §1.3 的降级态，**不能白屏、不能报错弹窗**。
5. baseUrl 从 `DataStore` 取一份，**不要复制 `127.0.0.1:9090` 硬编码**。

**实际做法**（比原计划多了一件必须做的事）

原计划里没写「①→② 怎么知道某条连接命中了哪条规则」，实施时发现这是个真问题：
`/connections` 的 `rule` 字段是内核渲染出来的**字符串**，不含 `RuleEntity.id`。
用户红线是「不得影响现有功能」，所以**不能**去改 `ConfigBuilder` 给规则打标记。
最终走「读侧重建规则串」反查，详见 §6.1 —— 这是阶段 3 最大的技术决定。

新增文件：`ui/topology/RuleSignature.kt`（读侧重建），
验证工具：`.workbuddy-ai/validate/rule-format/`（Go 引真 sing-box 源码出 ground truth）。

**验收**：开 VPN → ①层出现连接并实时更新；关 VPN → ①层清空；切后台 → 轮询停止。  
**风险**：能耗（用户已明确关注）→ 先只做「下拉手动刷新」，观察一周再决定要不要自动轮询。

**实测**

*规则串反查*（`.workbuddy-ai/validate/rule-format/`，Go 引**真 sing-box 源码**生成基准）：

```
重建一致        : 39 / 39        ← Kotlin 重建与内核逐字节一致
checkEmpty 丢弃  : 4 / 4 条        ← 重建正确地返回 null，读侧不比内核「宽」
对照组 A 误判数  : 0 / 39         ← 把期望串改一个字符，必须全部不匹配
对照组 B 排序    : normalize 承重 ✓ ← 不排序则 user_id 用例必失败
对照组 C 空路径  : checkEmpty 分支被走到 ✓
```

> 这一步**纠正了两处我原本记错的事实**：BLOCK 渲染成 `reject()`（带括号，不是 `reject`）；
> "bypass" 不是 `bypass(...)` 动作，而是 `route(bypass)`。都是靠真源码跑出来的，不是推出来的。

*布局*（`.workbuddy-ai/validate/topology-layout/`，4 种屏幕 × 多数据量 × 多随机种子）：

```
② 弧形      用例 60    重叠 0  越界 0  y 重复 0
③ 散点云    用例 576   重叠 0  越界 0
① 入站 chip 用例 1152  重叠 0  越界 0   ← 按**绘制矩形**算，不是预留框
```

① 层这里有个隐患值得记：布局按 `chipWidth × 1.15` 预留**宽度**，
但**高度**只按 22 预留，而景深缩放会把画出来的 chip 拉到 `22 × 1.15 = 25.3`。
也就是说画出来的 chip 可能比它的框高。所以校验专门换算成绘制矩形再判，
并加了对照组：把绘制高度乘 k 倍，看几时开始重叠 ——
`k=1.20` 仍然 0 重叠，`k=1.30` 才出现 10 处。**余量约 8%，不是刀尖上跳舞。**

*连线*（同上目录，两段分别测）：

```
②→③ 规则→出站   曲线 1080  真穿过 8   = 0.7%   对照组直线 4.8%
①→② 入站→规则   曲线 7680  真穿过 6088 = 79.3%  对照组直线 83.5%
硬性质：端点不贴边 0 / 越界 0 / 控制点未夹住 0
```

①→② 的 79.3% **不是缺陷，是设计接受的结果**，理由是查过原型源码而不是猜的：
原型的 `drawLines()` 对 `kind==='in'` 的边**完全不做避障**（只有 `'out'` 边调 `pickOutPort`），
并且 ①→② 用 `opacity=".42"`、②→③ 用 `.85`；`svg.lines` 是 `z-index:3` 而卡片是 `4~7`，
**线本来就在卡片底下**，chip 又是半透明玻璃。所以原型自身就在 ~83.5%，
我们 79.3% 还略好一点。

> 由此**抓到一个真实的移植走样**：`drawEdges` 原先对所有边一律 `stroke-width 1.2 / alpha 217`，
> 等于把 ①→② 也画成实色 —— 在 79.3% 的穿透率下会糊成一层密网，玻璃质感全丢。
> 已改成按段取 `1 / 107`（≈.42）和 `1.2 / 217`（≈.85），与原型一致。

**看得见的结果**：本机没有可用模拟器/真机（`system-images/.../x86_64` 是空壳，`adb devices` 无设备），
所以加了 `bash run.sh preview` → **`docs/spider-ui-preview.html`**（明暗两套，见 §4）。
它直接调用 App 的真算法，是唯一能「看见」新 UI 的通道。忠实边界写在页面底部。

**已知取舍**：① 层的上行/下行是 `/connections` 给的**累计值**，不是速率。
单次下拉刷新只能显示累计量；要显示「实时速率」需要两次采样（或轮询），
而那正是 D1 暂时不做的能耗决定。这条留到阶段 4 的详情浮层里再定。

---

### 阶段 4 · 交互与跳转 — ✅ **已完成**

**做什么**

1. 卡片点击 → `RouteSettingsActivity` / `ProfileSettingsActivity`（见 §2）。
2. **长按规则卡 → 快速换出口节点**（见 §2.1，全计划唯一一条写操作）。
3. ①层连接详情浮层。
4. 孤儿规则态（「错误」tag + ghost「待添加」）。
5. ①层上限 18 → 26（半屏还没用满）。
6. 返回键分支（`MainActivity.kt:82-90`）加新 Fragment 判断。

**验收**：从新 UI 能完成旧 UI 的全部日常操作（看连接、改规则、换节点、启停）；换完节点重载后生效。  
**风险**：① 返回键处理不当会直接退出 App；② 写库后忘记 `needReload()` → 用户以为改了没生效。

**实际做法与偏差**

| # | 项 | 结果 |
|---|---|---|
| 1 | 卡片点击 | ✅ 新增 `TopologyHit`（View 只回答「点到了谁」，不决定跳哪） |
| 2 | 长按换出口 | ✅ 照抄 `RouteSettingsActivity.selectProfileForAdd` 这条**既有且已验证**的写法 |
| 3 | ① 详情浮层 | ✅ 同一棵视图树里的一层，**不是 Dialog / 新 Fragment** |
| 4 | 孤儿规则态 | ✅ 阶段 1 就已实现（`MISSING` → warn 色 + 虚线边），本阶段只是复核 |
| 5 | ① 上限 26 | ⚠ **做不到，实测硬上限 22**（见下） |
| 6 | 返回键分支 | ✅ 阶段 0 就已加入（`MainActivity.kt` 里已有 `it is TopologyFragment`），本阶段补了浮层的优先级 |
| 7 | 层标签点击提亮 | ⚠ 计划标着「已实现」，实际**漏做**（原型里有）—— 本阶段补上，见下 |

**偏差 5 的来龙去脉（这是本阶段最有价值的一条）**

「18 → 26」原以为只是把常量改大。实测发现 ① 区**根本放不下 26 张**，
限制**不是面积**，而是**每行放得下几张**：

- chip 宽度上限 120 原型单位（`chipWidth` 封顶）× 1.15 景深预留 = 138 单位；
- ① 区宽只有 342 单位 → **最宽的标签每行只放得下 2 张**；
- 26 张需要 13 行，而这块高度（367/820）按 `22×1.15 + 8` 的行距只排得下约 11 行。

验证（4 种屏幕 × 16 颗种子，按**绘制矩形**判）：

```
混合宽度标签   : 第 24 张起放不下
全宽标签(最坏) : 第 23 张起放不下
→ 上限取 IN_MAX = 22（比最坏情况再留一张余量）
```

于是把 `IN_MAX` 定成 **22** 而不是 26，超出的折进本来就有的「+N 更多」胶囊。
验证脚本现在会**从 App 源码里读 `IN_MAX`**（读不到就直接失败），
这样以后谁改上限都不会出现「验证通过但其实没测到真实上限」。

**顺带修掉一个一直存在的隐患**

布局按 `chipWidth × 1.15` 预留**宽度**，但**高度**只按 22 预留，
而 `drawInboundChip` 绘制时**宽高都乘**景深缩放 —— 也就是画出来的 chip 比它的预留框高。
18 张时行数少、余量足，刚好蒙过去；提到 26 张立刻暴露成 **128 处越界**。

修法：高度也按 `× DEPTH_MAX` 预留，绘制时再**除回来**（与宽度对称）。
这样「预留框 ⊇ 任何可能画出来的矩形」在**构造上**成立，而不是靠余量恰好够。
修完余量扫描从 `k=1.20` 回到 `k=1.30`（k = 把绘制高度人为放大几倍才会开始重叠）。

**补做一件「计划里写了但其实没做」的事：层标签点击提亮**

复核 §2 的手势表时发现：这一行写着「已实现」，但 `TopologyView` 里
`drawLayerLabels` 只是个静态绘制 —— **没有提亮状态、没有标签的命中判定、没有任何手势**。
计划当时是把「原型里有」当成了「App 里有」。原型那边确实是有的
（`.layer-label{opacity:.34}` + `.layer-label.show{opacity:.95}`，`toggleLabel(el)` 翻转 class），
所以这是漏做，不是设计变更。已补上：

| 项 | 做法 |
|---|---|
| 语义 | **每个标签各记各的**（照抄原型 `el.classList.toggle('show')`），不互斥、不自动复位 |
| 状态 | `TopologyView.labelBright: BooleanArray` —— 纯 View 内部，不涉及数据、不涉及跳页 |
| 事件 | **就地消费，不经过 `onCardClick`**。往外抛的话 `TopologyFragment` 那两个穷尽的 `when` 都得补空分支 |
| 命中区 | 固定 24dp 高（文字只有 9.5dp），以**文字视觉中心**为中心，横向各外扩 6dp |
| 常态透明度 | 原型 `.34` → 这里 `.55`（140）。原型标签是**带描边的胶囊**，轮廓本身在提示可点；这边只画纯文字，同样的 `.34` 会淡到认不出来 |

几何抽成了 `TopologyLabelGeometry`（纯 Kotlin、不依赖 Android），
这样 `.workbuddy-ai/validate/topology-layout` 能编译**同一份**源码来验证 ——
绘制和命中判定各算一遍是这类功能最容易烂的地方（改一处忘一处 → 看得见但点不到）。

> ⚠ **验证脚本推翻了一版设计**，值得记下来。
>
> 第一版给命中区加了「不许伸进上一层」的夹取：屏幕越矮层间走廊越薄
> （按 dp 算只有约 34dp），24dp 的条带会越过 ① 层下边界，当时想的是「夹住它，
> 免得抢走 chip 的点击」。
>
> 验证结果：1280×600（300dp 高）那一档，文字顶部被削掉 2.5dp —— 也就是**看得见但点不到**，
> 恰好是这套几何最想避免的毛病。而且夹取**本来就是多余的**：
> `TopologyView.onDown` 只在**没命中卡片**时才认标签，这是逐像素按卡片真实位置判断的，
> 比一条静态区域边界准得多。
>
> 去掉夹取后：全部 9 档机型、3 层标签，文字都被命中区完整包住。
> 那条优先级规则同时被抽成 `TopologyLabelGeometry.pickHit(cardHit, labelIndex)`
> 并被验证脚本直接断言 —— 它是「命中区可以压在 ① 层上」的**唯一依据**，
> 靠注释保证太弱（哪天有人为了「让标签好点一点」把它反过来，卡片就会被一条
> 9.5dp 的标签抢走点击，而且不报错）。
>
> 附带量到一个有意思的数：正常手机上标签的命中区**整条待在层间走廊里**，
> 与 ① 层零重叠；只有横屏 / 分屏 / 极端挤压才会侵入 0.9~9.6dp，那几档才真的靠 `pickHit` 兜底。

**两处顺带修正计划里的错**

1. §2.1 写的 `entity.serialize()` **不存在**。`RuleEntity` 是 Room `@Entity`，
   字段全是 `var`，`@Update` 直接写行；`packages: Set<String>` 由 `StringCollectionConverter` 处理。
2. `ProfileSelectActivity` **不需要**加 `EXTRA_LOCK_GROUP`。`ConfigurationFragment` 的
   `hideTab = groupList.size < 2` 确实没有 `select` 判断，但 select 模式本来就屏蔽了副作用
   （`:1197`、`:1255`、不注册 page-change 回调），而 `RuleEntity.outbound` **可以指向任意分组的节点**
   —— 限制成单组反而**减少**能力。

**实测**

```
布局（4 屏幕 × 多数据量 × 16 种子，按绘制矩形）
  ② 弧形      用例 60    重叠 0  越界 0  y 重复 0
  ③ 散点云    用例 576   重叠 0  越界 0
  ① 入站 chip 用例 2816  重叠 0  越界 0      ← 混合宽度 + 全宽标签各跑一遍
  余量扫描：k=1.30 才开始重叠（对照组 k=2.00 报 222 处，证明检测有牙）

连线（两段分开测）
  ②→③  曲线 1080   真穿过 0.7%   对照组直线 4.8%
  ①→②  曲线 10320  真穿过 82.2%  对照组直线 85.7%
  硬性质：端点不贴边 0 / 越界 0 / 控制点未夹住 0

层标签（9 档机型 × 3 层）
  看不见 0   点不到 0   最薄命中区 24.0dp
  侵入上一层内容区 6/18 层（仅横屏/分屏/极端挤压，最深 9.6dp）—— 由 pickHit 兜底
  对照组 5 项全部按预期失败（字高 3em / 命中区砍到 4dp / 横向外扩取负 /
                       优先级反转 / 零高度命中区）
```

> ①→② 从 79.3% 升到 82.2%，只是因为 chip 上限从 18 提到 22、画面更密了，
> 相对对照组的收益（85.7% → 82.2%）没有退化。这一段本来就接受穿透（见 §3 阶段 3）。

**lint**：`lintOssDebug` 报 67 个 error，其中 **66 个是既有的**
（`AssetsActivity.kt` 的 `MissingSuperCall`、`values-fa/strings.xml` 的 `TypographyEllipsis`），
**只有 1 个是本阶段引入的**：`TopologyView.onTouchEvent` 的 `ClickableViewAccessibility`。
它是**误报** —— 触摸委托给了 `GestureDetector`，`performClick()` 在 `onSingleTapUp` 里调，
要求本身满足，只是 lint 不扫委托内部。已显式抑制并写明原因，而不是为了讨好 lint 把点击判定拆成两份。

---

### 阶段 5 · 默认切换 — ✅ **已完成（2026-09-16，用户拍板「我们没有退路了」）**

原设计是「新旧两个主页都在，用一个开关决定给用户看哪个」。**这个方案已经作废** ——
用户明确要求「用 newui，旧的不要展示了，直接保留新的」，所以不是「翻默认值」，
而是**把开关本身拆掉**：

| 拆掉的东西 | 位置 |
| --- | --- |
| `SwitchPreference` | `res/xml/global_preferences.xml` |
| `Key.ENABLE_NEW_UI` | `Constants.kt` |
| `DataStore.enableNewUI` | `database/DataStore.kt` |
| 变更监听分支 | `MainActivity.onPreferenceDataStoreChanged` |
| 三元分支 | `MainActivity.displayFragmentWithId(R.id.nav_configuration)` → 无条件 `displayFragment(TopologyFragment())` |
| 20 个语种的 `enable_new_ui` / `enable_new_ui_summary` | `res/values*/strings.xml` |

⚠ **`ConfigurationFragment` 不能删**：它仍是 `ProfileSelectActivity`、`SwitchActivity`、
③「+N 更多」兜底的载体。**「回旧主页」的唯一正确写法是
`displayFragment(ConfigurationFragment())`**，用 `displayFragmentWithId` 会死循环。

⚠ **连带债（已补）**：撤掉旧主页时漏了一条路 —— **「选节点」原本只存在于旧主页的节点列表里**。
新 UI 的 ③ 层是**路由图不是节点列表**（只画被启用规则指向的节点），结构上就不可能有节点列表，
于是 `selectedProxy` 恒为 0、⏻ 必然失败。修法见 §2 手势表与阶段 4 补记。
**教训：删掉任何旧入口之前，都要重新画一遍可达性。**

---

## 4. 文件清单

**新建**

```
app/src/main/java/io/nekohasekai/sagernet/ui/TopologyFragment.kt
app/src/main/java/io/nekohasekai/sagernet/ui/topology/TopologyView.kt
app/src/main/java/io/nekohasekai/sagernet/ui/topology/TopologyLayout.kt
app/src/main/java/io/nekohasekai/sagernet/ui/topology/TopologyModel.kt        （模型 + Repository）
app/src/main/java/io/nekohasekai/sagernet/ui/topology/TopologyPalette.kt      （§0.2 色板）
app/src/main/java/io/nekohasekai/sagernet/ui/topology/ClashApiClient.kt       （阶段 3）
app/src/main/java/io/nekohasekai/sagernet/ui/topology/ClashConnections.kt     （阶段 3）
app/src/main/java/io/nekohasekai/sagernet/ui/topology/RuleSignature.kt        （阶段 3，§6.1 读侧重建）
app/src/main/java/io/nekohasekai/sagernet/ui/topology/TopologyColors.kt       （阶段 3，§0.2 的纯 Kotlin 两层色板）
app/src/main/java/io/nekohasekai/sagernet/ui/topology/TopologyLabelGeometry.kt（阶段 4，层标签几何 + 命中优先级）
app/src/main/java/io/nekohasekai/sagernet/ui/topology/TopologyEdges.kt        （阶段 2）
app/src/main/java/io/nekohasekai/sagernet/ui/topology/TopologyParticleView.kt （阶段 2）
app/src/main/res/layout/layout_topology.xml
```

> `TopologyColors.kt` 是从 `TopologyPalette` 里抽出来的：色板的**底色层 + 语义层**不需要
> `Context`，抽成纯 Kotlin 对象之后，预览生成器可以**编译同一份文件**，
> 而不是抄一份常量 —— 抄一份迟早漂移，漂移过的预览就不再「忠实」，也就失去了意义。
> 只有**强调层**（`?attr/colorAccent`）还需要 `Context`，留在 `TopologyPalette` 里。
>
> `TopologyLabelGeometry.kt` 是同一个理由：标签的基线 / 命中区 / 命中优先级被
> **绘制**和**触摸判定**同时用到，各算一遍就会出现「看得见但点不到」这种不报错的毛病。
> 抽出来之后验证脚本能编译同一份源码直接断言。文案（`R.string`）留在 `TopologyView`，
> 两边条数不一致会在 View 构造时 `require` 抛异常。

**验证工具（不在 App 里，不参与打包）**

```
.workbuddy-ai/validate/rule-format/        Go 引真 sing-box 源码出基准 + Kotlin 一致性校验
.workbuddy-ai/validate/topology-layout/    三层布局零重叠/零越界 + 两段连线几何体检 + 层标签几何 + 忠实预览图
```

这两个目录是**独立可跑的**，用 Gradle 缓存里的 `kotlin-compiler-embeddable` 编译，
刻意不往项目里加 JUnit 或测试源集 —— 这些算法都是纯 Kotlin，不需要 Android 环境。
改 `TopologyLayout.kt` / `TopologyEdges.kt` / `TopologyColors.kt` / `TopologyLabelGeometry.kt` /
`RuleSignature.kt` 之后应该重跑一次。

```
bash run.sh           布局：零重叠 / 零越界 / 弧形 y 互不相同
bash run.sh geom      真机上的绝对像素尺寸
bash run.sh edge      连线端点 / 越界 / 碰撞率（含同口径对照组）
bash run.sh label     层标签可见性 / 命中区 / 命中优先级
bash run.sh preview   生成 docs/spider-ui-preview.html
```

每个脚本都**从 App 源码同步要验证的文件**再编译，所以不存在「验证的是旧副本」这种假绿；
`edge` 和 `label` 还会把关键常量（`IN_MAX`、层标签几何）**从源码里读出来**，
读不到就直接失败 —— 常量改了而验证没跟上，会立刻暴露。

`bash run.sh preview` 会生成 **`docs/spider-ui-preview.html`** —— 三层拓扑的忠实预览（明暗两套）。
它直接调用 App 的真算法，所以能当「没有模拟器时的眼睛」用。
忠实边界写在页面底部：**落点 / 景深 / 连线控制点 / 笔宽 / 透明度 / 颜色全部忠实**，
**文字截断与字体不忠实**（真机是 `TextUtils.ellipsize` 的真实测量）——
所以别拿它判断标题会不会被截断。层标签也**不在预览里**（它画在 `TopologyView` 的 Canvas 上，
需要 Android 环境），要单独看标签只能靠 `bash run.sh label` 的数字。

**修改（每处都只加不改）**

```
app/src/main/java/io/nekohasekai/sagernet/Constants.kt          + 1 个 Key
app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt + 1 个 var
app/src/main/res/xml/global_preferences.xml                     + 1 个 SwitchPreference
app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt    + 4 处（都在 displayFragmentWithId 这一个切换点上）
app/src/main/res/values/strings.xml                             + 若干 translatable="false"
```

**为了「调试包不覆盖在用的 App」而加的两处（只影响 debug 变体）**

```
app/build.gradle.kts                    debug 的 applicationIdSuffix 改成 ".newui"
app/src/debug/AndroidManifest.xml       （新建）debug 专用 application 标签
```

`buildSrc` 里 debug 的默认后缀是 `"debug"` → `moe.nb4a.debug`，
而 `moe.nb4a.debug` 很可能就是开发者手机上正在用的那个包，装上去会**直接覆盖**它。
改成 `".newui"` → `moe.nb4a.newui`，两者可以并存。
（清单里所有 `authorities` / `permission` 都写成 `${applicationId}`，所以换后缀是安全的；
唯一一处硬编码的 `"io.nekohasekai.sagernet"` 在 `Plugins.kt` 里，那是**插件协议常量**，与自身身份无关。）

应用名来自 `@string/app_name`，光靠包名区分不了 —— 桌面上会出现两个一模一样的「Spider」。
所以加一个 debug 专用清单把 `android:label` 换成「Spider 新界面」。
用清单属性而不是 `src/debug/res/values/strings.xml` 覆盖 `app_name`，是因为
`app_name` 在 `values` / `values-fa` / `values-ru` / `values-uk` 四处都有定义，
资源覆盖只在默认语言下生效，其他语言会被再盖回去；清单属性与语言无关。
`tools:replace="android:label"` 是必需的，否则清单合并器会因属性冲突报错。

> 这两处**只改 debug 变体**，release 完全不受影响。要还原就把 `applicationIdSuffix` 删掉、
> 并删掉 `app/src/debug/AndroidManifest.xml`。

**绝对不碰**

```
ui/ConfigurationFragment.kt   （1973 行，旧主页，一行都不改）
res/layout/layout_main.xml    （fragment_holder 语义不变）
ui/ToolbarFragment.kt         （drawerLayout 硬依赖不动）
```

---

## 5. 工程红线（违反会直接出问题）

1. **新增字符串必须 `translatable="false"`** —— `warningsAsErrors = true`（`buildSrc/.../Helpers.kt:56-58`），漏翻译直接编译失败。
2. **新 UI 必须挂在 `R.id.fragment_holder`** —— 否则 `(requireActivity() as MainActivity)` 那 13 处强转全崩。
3. **不能自己 `setTheme`** —— 主题在 `ThemedActivity.onCreate` 的 `super` 之前注入（`:26-32`）。
4. **轮询协程必须在 `onDestroyView` 取消** —— `ThemedActivity:59-66` 会因 uiMode 变化 `recreate`。
5. **数据读取必须 `runOnDefaultDispatcher`** —— DAO 全同步且 `allowMainThreadQueries()` 开着。
6. **不新增 `onDraw` 以外的重度自绘** —— 全项目零先例，第一个自绘控件要克制。
7. **`minSdk 21`** —— Android 12+ 才有的模糊效果（`RenderEffect`）必须做版本分支，默认降级成半透明。

---

## 6. 决策记录

| 编号   | 决定                            | 谁定的       |
| ---- | ----------------------------- | --------- |
| D1   | 走 HTTP，阶段 3 只做「下拉手动刷新」，不做自动轮询 | 我建议，用户未反对 |
| D2   | 主体只读 + 开放「卡片编辑」和「节点切换」两类写操作   | 用户        |
| D3   | **三层拆开**：底色层新 UI 自带（Latte/Mocha）、强调层接 `?attr/colorAccent`、语义层自带；深色额外加纯黑变体 | 用户提出"我就是为了好看才自己写界面的"，据此修正（见 §0.2） |
| D4   | ③ 层为「走主代理」补一张卡，并把它与「`outbound` == 当前选中 profile」合并 | 实施阶段 1 时发现：`RuleEntity.outbound` 默认值就是 `0`（RuleEntity.kt:28），内核把 `0` 和"当前 profile id"都归一成 `TAG_PROXY`（ConfigBuilder.kt:577-582）。若丢掉，规则全用默认值的用户会看到 **③ 层整层空白** |
| D5   | ①→② 的归属**不改 `ConfigBuilder`**，改用「读侧重建规则串」反查：把 `RuleEntity` 按内核同样的规则渲染成字符串，去和 `/connections` 的 `rule` 字段比对 | 见 §6.1。用户红线是"不得影响现有功能"，改 `ConfigBuilder` 会动到内核入参，否决 |
| 阶段 5 | 暂不做，等阶段 0–4 跑稳再议              | 待定        |

### 6.1 D5 的来龙去脉（阶段 3 实测，非推测）

**问题**：`/connections` 里每条连接的 `rule` 字段，是 sing-box 自己渲染的**路由层文本**，
不是用户给规则起的名字，也不带 `RuleEntity.id`。原文在
`sing-box-src/experimental/clashapi/trafficontrol/tracker.go:66-70`：

```go
if t.Rule != nil { rule = F.ToString(t.Rule, " => ", t.Rule.Action()) } else { rule = "final" }
```

**有没有"给规则打标记"的现成口子？没有。** 逐处查过：

- `Rule_DefaultOptions` 的全部字段（`option/rule.go:68-115`）里没有任何 name/description；
- `RuleActionRouteOptions`（`option/rule_action.go:171-187`）只有 override_address / override_port /
  network_strategy / fallback_delay / udp_* / tls_*，也没有；
- `RuleActionRoute.String()` 是 `route(<outbound>,<descriptions>)`（`route/rule/rule_action.go:192-197`），
  括号里塞不进去自定义文本。

所以想精确反查只有两条路：**(a)** 改 `ConfigBuilder` 往规则里塞标记（动内核入参，被 D5 否决）；
**(b)** 在 App 侧把规则串**重建**出来再比对。

**重建可行，且已用真机外的 ground truth 验过。** 关键三点：

1. `abstractDefaultRule.String()` = `strings.Join(F.MapToString(r.allItems), " ")`
   （`route/rule/rule_abstract.go:178-183`），而 `allItems` 是**按 `rule_default.go` 里 if 分支的源码顺序**
   追加的切片（不是 map）→ **顺序完全确定**，不存在 Go map 随机序问题。
2. 分隔符是**空格**，不是逗号；列表 >3 项会截断成 `[a b c...]`。
3. 因此只要在 Kotlin 侧复刻同一套渲染，就能把 live 的 `rule` 串映射回 `RuleEntity`。
   **复刻正确性由 Go 侧 ground truth 兜底**（见 §4 的 `validate/rule-format/`）。

**实测修正了两个我原先记错的点**（都写在 §6.1.1，避免以后再踩）：

#### 6.1.1 规则串的真实格式（Go harness 实测，30 例）

| 输入 | 真实输出 |
| --- | --- |
| `geosite:geolocation-!cn` → HK-01 | `rule_set=geosite:geolocation-!cn => route(HK-01)` |
| `geosite:category-ads-all` → BLOCK | `rule_set=geosite:category-ads-all => reject()` |
| `domain:eastmoney.com` | `domain_suffix=eastmoney.com => route(HK-01)` |
| `full:api.example.com` | `domain=api.example.com => route(US-LAX)` |
| `keyword:google` | `domain_keyword=google => route(US-LAX)` |
| `a.com,b.com` | `domain_suffix=[a.com b.com] => route(HK-01)` |
| `a.com,b.com,c.com,d.com` | `domain_suffix=[a.com b.com c.com...] => route(HK-01)` |
| `geoip:private` | `ip_is_private=true => route(direct)` |
| `geoip:cn` | `rule_set=geoip:cn => route(direct)` |
| `port=80,8000:9000` | `port=80 port_range=8000:9000 => route(HK-01)` |
| packages → uid | `user_id=[10086 10123] => route(HK-01)` |
| 无规则命中 | `final`（`t.Rule == nil` 时 tracker 直接写死） |

⚠ 两个**反直觉**的点：

1. **BLOCK 是 `reject()`，带括号**（`RuleActionReject.String()` 在 method 为默认值时返回 `reject`，
   但经 `F.ToString(t.Rule, " => ", action)` 后写成 `reject()`）。我原先记的是 `reject`，错的。
2. **"绕过"不是 `bypass(...)` 动作**：App 是把 `outbound` 设成 `bypass` 这个 tag、动作仍是 route
   （ConfigBuilder.kt:577-582），所以渲染成 `route(bypass)`。

**字段顺序**（= `rule_default.go` 的 allItems 追加序，App 会用到的部分）：
`network` → `protocol` → `domain`/`domain_suffix` → `domain_keyword` → `domain_regex` →
`source_ip_cidr` → `ip_cidr` → `ip_is_private` → `source_port` → `source_port_range` →
`port` → `port_range` → `user_id` → `rule_set`（**`rule_set` 永远在最后**）。

**一条既有怪癖，重建时必须一起复刻**：`SingBoxOptionsUtil.makeSingBoxRule` 在两条分支里都执行
`rule_set = mutableListOf<String>()`（重新赋空表），而 `ConfigBuilder` 先调 domains 分支、后调 ip 分支。
→ 一条同时写了 `geosite:x`（domains）和 `geoip:y`（ip）的规则，**geosite 标记会被 ip 分支清掉**，
最终只剩 `rule_set=geoip:cn`。这不是我要修的 bug（修了就改内核行为），重建侧照抄。

**重名规则怎么办**：`enabledRules()` 是 `ORDER BY userOrder`（`database/RuleEntity.kt:74-75`），
内核也是按同一顺序取第一条命中。所以重建时**按 userOrder 取第一个匹配**即可 —— 这与内核语义一致，
不是"猜"。

**降级**：重建失败（格式漂移 / 内核自带规则如 `port=53 => hijack-dns()`）时**不画 ①→② 连线**，
只显示从规则串里文本解析出来的「匹配类型」（geoip/geosite/domain/port/应用/兜底）。
不白屏、不弹错。

---

## 7. 澄清：**不是"删旧的"**

这条流程里，**旧主页一行都不会被删**。正确的顺序是：

| 步骤               | 旧主页状态          | 用户看到什么        |
| ---------------- | -------------- | ------------- |
| 1. 搭新 UI（阶段 0–4） | 原封不动，还是默认      | 完全感知不到有新东西    |
| 2. 测新 UI         | 原封不动           | 只有自己开开关才看得到   |
| 3. 翻默认值（阶段 5）    | **仍然在**，降级成设置项 | 默认新主页，随时能切回旧的 |
| 4. 删旧主页          | ——             | **建议永远不做**    |

第 4 步完全可以永远不做：旧主页留着就是兜底，删掉它换不来任何好处，只会让"出问题了退回旧版"  
这条路消失。而且从工程上说，`ConfigurationFragment` 的 1973 行里有一堆别人依赖的入口  
（导入导出、分享、订阅更新、分组管理），删它等于要先把这些入口全部搬到新 UI 上 —— 那是另一个大工程。

---

## 8. 优先级建议

如果只先做一件事，我建议 **阶段 0 + 阶段 1**（并存骨架 + 静态拓扑）。  
这两步跑通就等于证明了「新 UI 能读对数据、且不影响旧 UI」，后面全是加法。
