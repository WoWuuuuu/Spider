# 新 UI 任务清单（唯一权威版）

> 2026-09-16 建立。**取代** `docs/spider-topology-remaining-plan.md` 和
> `docs/spider-topology-status-report.md` 里的待办部分（那两份里的调研结论仍然有效，
> 但任务清单以本文件为准，不要再从旧文档里派生任务）。
>
> 判定依据：App 源码 + 真机装机实测（`moe.nb4a.newui`，1440×3200）。

---

## 一、总体状态：4 件事

| # | 事项 | 功能 | 外观 | 说明 |
|---|---|---|---|---|
| ① | **分组管理** | ✅ 完成 | ❌ 旧样式 | 增删改查全通，但用的是 Material 对话框，不是原型的卡片面板 |
| ② | **电源按钮** | ✅ 完成 | ✅ 完成 | `toggleService()` + `onServiceStateChanged()` 推状态，已实测 |
| ③ | **设置界面** | ✅ 能进 | ❌ 旧样式 | 壳子（scrim + panel）是新 UI 的，里面装的是旧 `SettingsPreferenceFragment` |
| ④ | **拓扑图** | ✅ 核心完成 | ⚠️ 有差距 | 三层布局/连线/粒子/交互全通；差「卡片内容结构」等 **5 项** |

**一句话：功能上没有死角，缺的是「照原型重做外观」+「拓扑图细节」。**

---

## 二、已经完成的（不用再动）

### 拓扑图核心
- 三层布局：① 散点（上限 22）/ ② 弧形（上限 5，**展开 8**）/ ③ 散点（上限 3，**展开 6**）
- **「+N 更多」胶囊 = 原地展开**（2026-09-16 改，用户反馈「点到 +1 直接回到旧界面了」）：
  ②③ 两个胶囊点一下把**这一层**撑开，把剩下的全画出来，不再往外跳页。
  上限的来历写在 `TopologyRepository` 的常量注释里：
  ③ 区（135 单位高）正好排得下 2 行 × 3 张 = 6；
  ② 是**单排弧形**、展开靠压窄卡片，34 单位是宽度硬下限（`(n-1)*4 + n*34 ≤ 342` → n ≤ 9），
  但 34 单位标题只剩两三个字，所以取 8。
- 连线：三次贝塞尔 + 走廊变道 + 入口避障 + 「目标节点已删除」虚线态
- 粒子层（独立 View，窗口不可见自动停）——**只跑 ②→③**（T9，2026-09-16 完成）
- ⚠ **①→② 的「连线」和「粒子」是两回事**：T9 只去掉粒子，
  ①→② 的**曲线和端点圆点照画**（`drawEdges` 遍历全部 `curves`，`stage` 只决定颜色/线宽/透明度；
  只有 `particleView.update()` 那一行按 `stage == OUTBOUND` 过滤）。
  这跟原型一致 —— 原型 `#pdots` 也只喂 outbound 那几条。
- ① 详情浮层（端点 / 应用 / 流量 / 命中规则 / 建连时间）
- 卡片点击：② 卡 → 规则编辑页；③ 卡 → 节点编辑页；① chip → 详情
- 卡片长按：① chip → 复制 `host:port`；**② 卡 → 换出口节点（唯一写操作）**
- 层标签点击提亮（每个标签各记各的，互不影响）
- 下拉刷新（手动，无轮询）+ 三种降级态文案（没开 Clash API / 内核没跑 / 没连接）
- 换分组不 `needReload`（内核按 `rule.outbound` 全库取节点，与当前分组无关）

### 分组管理（功能层）
入口 = 左上角胶囊 → 下拉。**下拉里 6 项**（2026-09-16 删掉「🏠 经典主页」后）：
更新全部订阅（置顶）/ 扫码 / 剪贴板 / 文件 / 新建分组 / 手动添加（17 种协议）。
分组行**长按** → 6 项管理：更新（订阅组）/ 编辑 / 分享（订阅组）/ 导出 / 清空节点 / 删除（带确认）。

### 设置界面（入口层）
⚙ → `toggleSettingsPanel()` → scrim + `topology_panel_host` 里挂 `SettingsPreferenceFragment`。

### 电源按钮
⏻ → `toggleService()`：跑着 → `SagerNet.stopService()`；停着 → `connect.launch(null)`（VPN prepare）。
着色由 `MainActivity` 推过来的 `onServiceStateChanged()` 驱动。
**2026-09-16 加了一条拦截**：`DataStore.selectedProxy == 0L`（没选节点）时**不开服务** ——
内核侧 `bg/BaseService.kt:181` 会立刻 `stopRunner("请先选择节点")`，服务起来再自杀，
用户只看到一句「VPN 错误」。改成 snackbar 提示 + action 直接开节点选择器。

### 主页外壳 / 旧入口清理（2026-09-16，用户拍板「我们没有退路了」+「旧主页完全去掉采用新ui」）
- **主页只有新 UI**：`navigateTo(DEST_HOME)` 无条件 `displayFragment(TopologyFragment())`。
- **拆掉 `enableNewUI` 开关**：`global_preferences.xml` 的 SwitchPreference、`Constants.ENABLE_NEW_UI`、
  `DataStore.enableNewUI`、`onPreferenceDataStoreChanged` 的分支、20 个语种的
  `enable_new_ui` / `enable_new_ui_summary` 全删。
- **底部 `StatsBar`（`@id/stats`）整体删除**（用户要求）：`layout_main.xml` 的节点、
  `MainActivity` 的 4 处引用、`widget/StatsBar.kt` 文件全删；
  `fab` 改锚 `@id/fragment_holder` + `layout_anchorGravity="bottom|center_horizontal"`（位置不变）。
- **左侧抽屉整个删除**（2026-09-16）—— 它早就被 `setDrawerLockMode(LOCK_MODE_LOCKED_CLOSED)`
  锁死，是一扇「打不开的窗」，只剩「其它页顶栏一个点了没反应的汉堡」这一个副作用。
  删的东西：`layout_main.xml` 的 `DrawerLayout` + 两个 `NavigationView`（根布局改成
  `CoordinatorLayout`，id 仍是 `coordinator`）、`res/menu/main_drawer_menu.xml`、
  `drawable/ic_navigation_menu.xml`、`MainActivity` 的 `navigation` 字段 /
  `onNavigationItemSelected` / `refreshNavMenu` / DPAD 开合分支 / 所有 `closeDrawers()`。
  - **`displayFragmentWithId(@IdRes)` → `navigateTo(dest: Int)`**：`nav_*` id 随菜单一起没了，
    换成 `MainActivity.DEST_*` 常量。目前只有 `DEST_HOME` 有调用方，其余是**故意保留的导航表**
    （Route/Tools/Logcat/About 由设置面板直接 replace 到达；FAQ/Promotion 当前无入口，
    前者 `AboutFragment` 另有同域链接、后者在旧抽屉里本来就是隐藏的）。
  - `ToolbarFragment` 那个开抽屉的汉堡 → **「回主页」返回箭头**（语义与系统返回键一致）。
  - **删之前核对过：抽屉的 4 个目的地（Route/Tools/Logcat/About）在设置面板里都有跳转链接**
    （`SettingsPreferenceFragment.kt:58/66/74/90` 直接 replace `fragment_holder`），
    所以这次删除**没有把任何功能变成不可达**。
- **删掉分组下拉里的「🏠 经典主页」逃生口**（用户：「旧主页完全去掉」）。
  删之前逐项对过旧主页的「＋」菜单，确认新 UI 已覆盖：
  三条导入 + 手动设置 17 种协议 + 自定义配置 + 链式代理 + 新建分组 + 更新全部订阅，
  以及分组长按里的改名/删除/分享/导出/更新/清流量。
  → **还没搬过来的四项见下面「旧主页残留能力」。**
- **新 UI 唯一的选节点入口**：③「走主代理」卡单击 → `ProfileSelectActivity`；
  长按 → 编辑当前节点；未选节点时卡片标题显示「选择节点」。见 plan §2.2。
  → 这直接抬高了 **T3（自带搜索/排序的节点弹层）** 的优先级：现在它是唯一入口，
  而 `ProfileSelectActivity` 挂的还是带分组 tab 的旧主页 select 模式。
- ⚠ **`ConfigurationFragment` 不能删**：仍是 `ProfileSelectActivity`、`SwitchActivity` 的载体
  （③「+N 更多」兜底已经不需要它了，改成原地展开）。

### 旧主页残留能力（删掉 🏠 之后新 UI 里没有的）
逐项对过旧主页 `res/menu/add_profile_menu.xml`，只剩这四类。**都不影响主干使用**，
但要做「旧主页彻底退役」就得先补上：

| 能力 | 旧入口 | 现状 |
|---|---|---|
| **节点批量操作**：去重 / TCP 测速 / URL 测速 / 清测速结果 / 删不可用 | ＋ 菜单下半段 | ❌ 新 UI 完全没有（= G2） |
| **排序**：按原始顺序 / 名称 / 延迟 | ＋ 菜单 `group_order*` | ❌ 没有（`GroupManager.rearrange` API 已就绪） |
| **更新当前订阅**（单个，而非全部） | ＋ 菜单 `update_current_subscription` | ❌ 没有（分组长按里的「更新」能覆盖） |
| **搜索节点**（`search_go`） | ＋ 菜单第一项 | ❌ 没有（= T3 的一部分） |

> 也就是说 **G2 从「锦上添花」变成了「旧主页退役的前置条件」**。

### 顺带完成的（原本不在清单里）
① 入站详情浮层、`layout_topology_add_panel`（＋ 面板，含目标分组提示与订阅组回退警告）、
从剪贴板/文件导入、订阅链接自动转交 `MainActivity`。

---

## 三、未完成 —— 任务清单

### A. 拓扑图（5 项）

| # | 任务 | 为什么 | 完成标准 | 成本 |
|---|---|---|---|---|
| **T1** | **卡片内容结构**：加图标、命中数徽章、出口色点、2.5px 彩色左边框 | 「像不像原型」最显眼的一条。原型卡是 `图标+名称+命中数 / 色点+出口名`，App 是两行纯文字 | `TopologyCard` 加字段（或改 sealed class），`drawCard` 画 r1/r2 两行；② 卡显示命中数 | 中 |
| **T2** | **回滚 ② 卡宽度 `minW`** | 上次的改动没修好截断（标题要 480px、区域只给 346px），却把卡片从原型的 244px 拉到 433px，偏离原型 | 删 `TopologyLayout.Item.minW` + `computeLayout` 里测标题那段；验证脚本 120 个用例一并删 | 小 |
| **T3** | **节点选择弹层**：搜索（名称/协议）+ 3 种排序（延迟/名称/协议）+ 每行「协议 · 分组」 | 现在跳旧 `ProfileSelectActivity`（无搜索无排序）。原型是自带搜索的弹层 | 新 UI 内自建弹层，选中后写 `rule.outbound` 走现有那条写链路 | 中 |
| **T4** | **去掉底部统计行**（或收进调试开关） | 原型**没有**这行；它是开发期跟旧主页对账用的 | `topology_summary` 默认隐藏 | 小 |
| **T5** | **紧凑模式 / 卡片密度** | 原型 `.card.compact`（藏 r2）+ 设置项「卡片密度」；也是小屏/省电策略 | 设置项 + `TopologySnapshot` 带密度标记 + `drawCard` 分支 | 中 |
| **T7** | **无障碍**：`ExploreByTouchHelper` 把每张卡注册成虚拟节点 | 现在无障碍树是空的，读屏用户看到一片空白 —— 这是**功能缺陷** | 读屏能逐张读出卡片并能点击/长按 | 中 |

> ~~**T9 去掉 ①→② 的粒子**~~ —— **2026-09-16 完成**（用户确认「只②→③」）。
> 只改了 `TopologyView` 里 `particleView.update(...)` 那一处，把 `curves` 过滤成
> `stage == OUTBOUND`。**①→② 的连线本身照旧画**（曲线 + 两端圆点），去掉的只是流动的粒子。

> ~~**T6 位置稳定性**~~ / ~~**T8 落位动画 + 文本缓存**~~ —— **2026-09-16 用户拍板删除**：
> 「每次刷新都重新排序没关系，这个没有实际意义只是外观展示。」
> 动画的前提就是落点稳定，所以 T8 跟着一起删。**文本缓存已经做了**（`TopologyView`
> 的 `titleCache`/`subCache`/`chipCache`），留着当保险，不算成果。
>
> **新增（2026-09-16）**：`TopologyView.snapshot` setter 加了**结构指纹**
> （`structureKey()` 变了才 `relayout()`）—— 这是实时推送的前置条件，已完成。

### A2. 实时推送（2026-09-16 已完成）

内核自带 WebSocket 推送（`/connections?interval=N`），App 侧已接上，零新依赖。
细节见 `docs/spider-newui-plan.md` 与 `ClashApiClient.kt` 的注释。
- ✅ `subscribeConnections()` + `ClashSubscription`；独立 `streamClient`；**不开 `pingInterval`**
- ✅ `onStart`/`onStop` 生命周期门控；`PUSH_INTERVAL_MS = 3000`
- ✅ 结构指纹（实测 2.95 µs，省掉 2–8 ms 的 `relayout`）
- ✅ 详情浮层跟着推送刷新（`openInboundId` + `refreshOpenDetail()`）
- ✅ 断线降级 `UNAVAILABLE`，**不自动重连**

### B. 分组管理（2 项）

| # | 任务 | 为什么 | 完成标准 | 成本 |
|---|---|---|---|---|
| **G1** | 分组管理改成原型的**卡片式面板** | 现在是 `MaterialAlertDialog` 列表，原型是 `groupPanel` | 外观对齐原型 | 中 |
| **G2** | **节点批量操作**：多选 / 排序 / 测速 / 去重 / 清流量 | 这些**目前只有旧主页有**，新 UI 完全没有。代码注释自己也标注了 | 新 UI 内可达（或在③卡长按菜单里） | 大 |

### C. 设置界面（2 项）

| # | 任务 | 为什么 | 完成标准 | 成本 |
|---|---|---|---|---|
| **S1** | 设置面板改成原型的 5 段卡片式 | 现在装的是旧 `SettingsPreferenceFragment` | 外观对齐原型（路由/DNS/连接/界面/工具） | 大 |
| **S2** | 补 3 个原型有、App 没有的设置项：**卡片密度**、**省电模式（关闭动画）**、**卡片显示服务器地址** | 原型标注 `note:"new"` / `"adapted"` | 三项真实生效（不是 toast 占位） | 中 |

### D. 增删功能（1 项）

| # | 任务 | 为什么 | 完成标准 | 成本 |
|---|---|---|---|---|
| **E1** | 新增节点 / 删除节点 / 删除规则 | 你上次问的「增删功能的可能」。API 层已齐（见下） | ③ 卡长按 → 删除（带撤销）；＋ 面板 → 新增 | 中 |

API 层已就绪，**不用碰内核、不用碰旧 UI**：
`ProfileManager.createProfile/updateProfile/deleteProfile`、
`GroupManager.createGroup/updateGroup/deleteGroup/clearGroup/rearrange`、
`createRule/updateRule/deleteRule/deleteRules`（全 suspend）。
⚠ 删除都不弹确认 → **必须复用 `UndoSnackbarManager`**（`ConfigurationFragment:1228` 那套）。
⚠ `DataStore.selectedGroupForImport()` 用 `!!`，**没有 BASIC 组会 NPE**；
你这台机器只有 1 个订阅组「灯塔」，所以「新增节点」没有合法目标分组 —— **做 E1 前必须先定策**。

---

## 四、建议的开工顺序

**第一批（低风险、见效快）**：T2 → T4
> T9 已于 2026-09-16 完成；T6 已按用户要求删除。剩下 T2（回滚 ② 卡宽度 `minW`）
> 和 T4（底部统计行收进调试开关）。

**第二批（「像原型」的核心）**：T1 → T3 → T5
> 卡片内容结构是观感差距的大头；节点选择弹层让「选节点」变得可见。

**第三批（能力补齐）**：T7
> 无障碍是**功能缺陷**（读屏用户看到一片空白），严格说优先级应该高于任何外观项。
> T8 已删除；文本缓存已完成，留着当保险。

**第四批（大件）**：E1 → G1 → S1 → G2 → S2
> 增删功能优先，因为它是功能不是外观；S1 最重，可以最后做。

---

## 五、需要你拍板

1. **② 卡标题截断怎么办？**
   - 回滚接受省略号（最贴原型，T2 就是这个）
   - ② 层改两行排布（宽度翻倍，能救 432px 那条，480px 那条仍差一点）
   - 改文案把 `xxx rule for 中国` 缩成 `中国 · xxx`
2. **开工顺序**认可吗？要不要先做「第一批」？
3. **S1（设置面板重做）**工作量最大，且现有旧偏好页是**功能完整**的 —— 要不要做？还是保留现状（能进、能用，只是不好看）？
4. ~~**分组下拉里的 🏠「经典主页（分组与订阅）」这一行要不要留？**~~
   → **已拍板：删掉**（2026-09-16，用户：「旧主页完全去掉采用新ui」）。
   **代价**：四类能力变成不可达（见「旧主页残留能力」），
   其中「节点批量操作」= **G2 因此升级为必做**，否则等于功能真的没了。
5. **`ConfigurationFragment`（2105 行）怎么瘦身？**（2026-09-16 新问题）
   它现在的唯一合法身份是「节点选择器」（`ProfileSelectActivity` / `SwitchActivity`，
   两者都以 `ConfigurationFragment(true, …)` select 模式构造）。
   非 select 模式那半（工具栏菜单、FAB、订阅管理、分组管理、导入导出……）已经全部搬到新 UI。
   - 选项：**① 先补 G2 再瘦身**（推荐 —— 否则会先把还没搬的能力删掉）/
     **② 只删「确认已死」的部分**（`false` 构造分支 + 工具栏菜单 inflate，
     保守、可分次做）/ **③ 暂不动**。
   - ⚠ 动手前必须确认没有任何地方还在 `ConfigurationFragment(false, …)` 构造它。
6. **pill（左上角分组胶囊）到底表达什么？** —— 见 `docs/spider-newui-plan.md` 与 MEMORY §5。
   候选 A 来源管理入口（推荐）/ B 视图筛选 / C 去掉分组语义。
   连带：③ 层要不要从「按当前分组过滤」改成「全量节点池」（原型本来就是全量）。

---

## 六、仓库垃圾盘点（2026-09-16，用户提问后查的）

全部在仓库根 `D:\workspace\Spider\`，**都是 2026-08-27 那次「NekoBox → Spider 迁移 +
libbox 重建」留下的中间产物**，与现在的构建无关
（`grep -rn libbox --include=*.gradle*` **零命中**；App 实际用的是 `app/libs/libcore.aar`）。

| 路径 | 大小 | 是什么 | git 跟踪 |
|---|---|---|---|
| `_libbox_rebuild_tmp/` | 32M | 反解 APK → D8 转 jar → 按 `io/nekohasekai/libbox/**` 过滤 → 重组 AAR 的**全部中间件**（`apk_unzip/`、`classes-all.jar`、`jar1.jar`、`jar2.jar`、`aar_root/`） | ❌ |
| `_migration_preserve/` | 26M | 迁移时从旧工程保留的（`app_executableSo/`、`buildScript/`、`buildSrc/`、`gradle/`、`libcore/`） | ❌ |
| `_save_before_delete/` | 25M | 删除前的备份（`app/`、`res-icons/`、`strings.xml`） | ❌ |
| `_tools/` | 23M | 只有一个 `apktool_2.9.3.jar` | ❌ |
| `libbox.so` | 66M | 单独构建的 Go 内核产物，**构建流程没引用** | ❌ |
| `Spider-1.4.2-arm64-v8a-signed.apk` + `.idsig` | 13.7M | 08-27 的 release 签名包 | ❌ |
| `_phase0_*.ps1` / `_debug_packages.ps1` | 13K | 一次性迁移脚本 | ❌ |
| `migrate.ps1` | 3.6K | ⚠ **危险**：会 `Remove-Item -Recurse -Force` 删掉整个 `Spider-android/`，再从 NekoBox 恢复 | ❌ |
| `_build_assembleOssDebug.log` | 30K | 构建日志（`*.log` 已在 `.gitignore`） | ❌ |
| `.trae/` | 104K | Trae IDE 的 specs | ❌ |
| `backup_icons/` | 148K | 改品牌前的启动图标备份 | ✅ 10 个文件 |

**合计约 106 MB**（不含 `backup_icons`）。除 `backup_icons/` 外全是 untracked，
删掉不产生任何提交变更。
⚠ 删 `libbox.so` / `_migration_preserve/` 之前先确认 `sing-box-src/` + `gomobile-src/`
能从源码重建 —— 否则那可能是唯一一份。
