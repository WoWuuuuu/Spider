# 拓扑主页：现状 / 实现 / 性能 / 增删可行性 / 与预期的差距

> ⚠️ **本文件的「待拍板清单」（§8）已被 `docs/spider-newui-tasks.md` 取代（2026-09-16）。**
> 任务清单以那份为准；本文保留作为调研与实测证据（卡片宽度、截断根因、性能分析）。

> 2026-09-16。对应你说的三件事：**① 界面要和原型一致（本质是可视化 + 选节点）**、
> **② 我想知道这是怎么实现的**、**③ 探讨性能优化和增删功能的可能**、
> 以及最终要的 **现状与预期的差距**。
>
> 事实来源：App 源码（`Spider-android/app/src/main/java/io/nekohasekai/sagernet/`）、
> 原型（`docs/spider-ui-prototype-v6.html`）、
> 真机截图（`docs/topology-compare/`）、
> 真机数据库（已 pull，规则/分组是实测值）。

---

## 0. 一句话结论

**骨架已经和原型对齐了**（顶栏、三层、层标签、连线算法、配色、粒度上限），
**差的是「卡片里装什么」**——原型卡片是 `图标 + 名称 + 命中数徽章 / 色点 + 出口名`，
App 卡片是 `名称 + 一行副标题` 两行纯文字。
节点选择功能**已经有了**（长按规则卡 → 选节点），但入口是隐式的，不是原型那种点开就见的弹层。

另外：**我上次那个「让卡片加宽以免标题被截断」的改动是错的**，
它既没解决问题，又让布局偏离了原型。建议回滚，理由见 §6。

---

## 1. 它到底是什么

一个**纯原生自绘**的界面，没有 WebView、没有 Compose、没有第三方图库。

| 层 | 文件 | 行数 | 职责 |
|---|---|---|---|
| 交互 / 数据编排 | `ui/TopologyFragment.kt` | 1397 | 顶栏、下拉、浮层、手势分发、写库、刷新 |
| 绘制 | `ui/topology/TopologyView.kt` | 758 | 卡片、连线、层标签、命中判定（`onDraw` 重写） |
| 数据装配 | `ui/topology/TopologyModel.kt` | 565 | 把「内核实时数据 + Room」揉成 `TopologySnapshot` |
| 规则串重建 | `ui/topology/RuleSignature.kt` | 340 | 读侧重建内核规则串，用于 ①→② 归属 |
| 几何 | `ui/topology/TopologyLayout.kt` | 290 | `placeScattered` / `placeArc`（纯 Kotlin，无 Android 依赖） |
| 连线 | `ui/topology/TopologyEdges.kt` | 213 | 贝塞尔 + 走廊变道 + 入口避障 |
| 实时数据 | `ui/topology/ClashConnections.kt` / `ClashApiClient.kt` | 152 / 137 | `/connections` 解析 + HTTP 客户端 |
| 粒子 | `ui/topology/TopologyParticleView.kt` | 135 | 叠在上面的独立 View，只重绘自己 |
| 命中区几何 | `ui/topology/TopologyLabelGeometry.kt` | 119 | 层标签的可点区域（纯 Kotlin） |
| 配色 | `ui/topology/TopologyPalette.kt` / `TopologyColors.kt` | 88 / 71 | 三层色板，底色/语义层无 `Context` 依赖 |
| | **合计** | **4265** | |

布局资源 6 个：`layout_topology.xml`（主）、`layout_topology_bar.xml`（顶栏）、
`layout_topology_drop_{row,header,divider}.xml`（分组下拉三态）、`layout_topology_proto_chip.xml`。

---

## 2. 它是怎么实现的（端到端）

```
 ① 数据来源
    内核 :9090  /connections  ← 实时连接（域名、上下行、命中规则、建连时间）
                /rules        ← 用来认出「哪些规则是内核自带的」（加分项，失败不影响）
    Room        rulesDao.allRules()   ORDER BY userOrder   ← 规则 + 启用状态
                proxyDao.getAll()                          ← 节点（全库一次查完）

 ② 装配（后台线程）
    ClashApiClient.fetch()      IO 线程，超时 1s/2s/3s，Proxy.NO_PROXY
      → 不抛异常，只返回 Ok / Disabled / Unavailable 三种状态
    TopologyRepository.load()   Dispatchers.Default，2 次同步 DAO
      → 按 host 聚合连接 → 最多 22 个 ① chip
      → 取 enabled 规则 → 最多 5 张 ② 卡（其余折进「+N 更多」）
      → 规则指向的节点 → 最多 3 张 ③ 卡
      → RuleSignature 反查每条连接命中哪条规则 → ①→② 连线
      → TopologySnapshot{ inbounds, rules, outbounds, edges, nodeTotal }

 ③ 上屏（主线程，一次）
    TopologyView.snapshot = snapshot   → relayout()   ← 布局**预计算**，不在 onDraw 里算
      placeScattered()  ① 散点 / ③ 散点
      placeArc()        ② 弧形（顺序 = 内核匹配优先级，绝不重排）
      TopologyEdges.route()  → curves
      particleView.update(curves, boxes, unit)   ← 把曲线推给粒子层

 ④ 绘制
    TopologyView.onDraw      画层标签 → 连线 → 卡片（每次 invalidate 全量重绘卡片层）
    TopologyParticleView     每帧只 invalidate 自己 → 卡片层的 display list 不重录
```

### 三个关键设计决定（都在注释里写明了理由）

1. **单位只有一个**：`TopologyLayout.Unit(w/366f, h/820f)`。
   因为 `regionW/342 == w/366`、`regionH/164 == h/820` 恒成立 —— 按区域各写一份常量迟早静默失配。
2. **粒子必须是独立 View**。同层画的话 `invalidate()` 会连卡片一起重绘，
   而卡片每帧要跑十几次带 `TextUtils.ellipsize` 的文本测量 —— 独立 View 是 Android 里
   「只重绘 `<g id="pdots">`」的等价物。
3. **布局预计算**（`relayout()`），不在 `onDraw` 里惰性算 —— 算完把曲线推给粒子层。

### 规则反查（阶段 3 最难的一块）

内核 `/connections` 的 `rule` 字段是 `Rule.String()` + action（如 `geosite(geolocation-!cn) => `），
**不含 RuleEntity.id**。因为红线不许改 `ConfigBuilder`，也没有可挂标记的钩子，
所以 `RuleSignature.kt` 在**读侧**把 `RuleEntity` 按内核**同样的规则**渲染成字符串去比对。

- 条件串分隔符是**空格**；`allItems` 是固定源码顺序的 slice → 完全确定性。
- 字段顺序：`network → protocol → domain/domain_suffix → domain_keyword → domain_regex
  → source_ip_cidr → ip_cidr → ip_is_private → source_port → source_port_range
  → port → port_range → user_id → rule_set（永远最后）`。
- **fail-closed**：重建串必须真的出现在内核 `/rules` 输出里，否则**不画线**。
  将来 sing-box 改格式 → 退化成「不画线」，而不是「画错线」。
- 只匹配条件、不匹配 `=> action`：`selectorName()` 有状态去重，出口 tag 读侧不可复现。

---

## 3. 现状 vs 预期：逐项对照

### 3.1 已经和原型一致的

| 项 | 证据 |
|---|---|
| 顶栏只有 pill + ⏻ + ⚙ | 原型 `.tools` 也只有这两个（「重新排布」在 `.debugbar` 里，是调试控件） |
| 三层 + 层标签文案 | 原型「入站·实时连接 / 路由规则·仅启用 / 出站·由规则推导」 |
| ② 弧形、③ 散点、① 散点 | 落点算法就是照原型 `placeArc` / `placeScattered` 搬的 |
| 连线：贝塞尔 + 走廊变道 + 入口避障 | `TopologyEdges.kt`，②→③ 真穿过率 0.7%（对照直线 4.8%） |
| 「目标节点已删除」虚线态 | 原型 `ok=available()` → `stroke-dasharray`，App 用 `c.dashed` |
| 卡片配色 proxy 蓝 / direct 桃 / block 红 | `TopologyPalette` |
| 粒度上限 5 / 3 / 22 + 「+N 更多」胶囊 | `RULE_MAX=5` / `OUT_MAX=3` / `IN_MAX=22`（22 是实测出来的） |
| ③ 卡显示延迟 | `TopologyModel.kt:504,545` `subtitle = node.delayText()` |
| 粒子速度、透明度、笔宽 | 照抄原型（`SPEED=0.30`、`.42`/`.85`、`1`/`1.2`） |

### 3.2 真正不一致的（按重要性排序）

#### ① 卡片内容结构 —— 最大的一条

| | 原型 | App |
|---|---|---|
| 第一行 | `.ico` 图标 + `.nm` 名称 + **`.hc` 命中数徽章** | 只有名称 |
| 第二行 | `.odot` 色点 + `.onm` 出口名 + `.tag` 标签 | 只有一行副标题文字 |
| 左边框 | 2.5px 彩色左边框（`.card.proxy/.direct/.block`） | 整圈描边，无左边框 |

根因在数据模型：`TopologyCard` 只有 5 个字段
（`TopologyModel.kt:35-41`）——

```kotlin
data class TopologyCard(
    val id: String, val layer: Int,
    val title: String, val subtitle: String,
    val kind: TopologyKind,
)
```

**没有** `icon` / `hitCount` / `tag` / `dotColor` 的位置，`drawCard` 自然只能画两行字。
这是「App 卡片看起来比原型空」的全部原因。

#### ② ② 卡片宽度（**我引入的偏离**）

- 原型 `placeArc` 硬锁 `Math.min(62, ...)` → 62 单位 = 屏宽的 16.9%。
- 我上次加了 `Item.minW`，把上限抬到「装得下标题」→ 3 条规则时卡片撑到**区域满宽**。

实测对比（1440×3200，density 3.5，视图 1440×2633）：

| | 卡片宽 | 可用文字宽 |
|---|---|---|
| 原型（62 单位） | **244 px** | 195 px |
| App 现状（我改后） | **433 px** | 346 px |

#### ③ ② 卡缺「命中数」

原型有（`countHits()`），App 没有。而 App 其实**已经算出了 ①→② 归属**，
按规则卡 id 计数是顺手的（`TopologyInbound.ruleCardId` 现成）。

#### ④ 面板体系：原型是「卡片式弹层」，App 是「复用旧 Activity」

| 原型的面板 | App 走的路径 | 功能 | 视觉 |
|---|---|---|---|
| `settings` | 挂 `SettingsPreferenceFragment` | ✅ | ❌ 旧设置页 |
| `groupPanel` | 分组下拉（7 个动作）+ 长按分组管理 | ✅ | ⚠️ 旧式下拉/对话框 |
| `routePanel`（规则列表） | 跳 `RouteSettingsActivity` / `RouteFragment` | ✅ | ❌ 旧界面 |
| `editor`（规则编辑） | 跳 `RouteSettingsActivity` | ✅ | ❌ 旧界面 |
| `addPanel` | ＋ 面板（17 种协议表 + 目标分组提示） | ✅ | ❌ 旧界面 |
| `scanPanel` | 跳扫码 Activity | ✅ | ❌ 旧界面 |
| 节点选择弹层（搜索 + 3 种排序 + 「协议·分组」） | 跳 `ProfileSelectActivity` | ⚠️ 无搜索、无排序、不显示分组 | ❌ 旧列表 |

**结论：功能覆盖是够的，视觉全是旧 UI 的样子。** 这是「和原型一致」这个目标里最大的一块工作量。

#### ⑤ 节点选择入口是隐式的

原型的节点选择是**点开就见的弹层**；App 是**长按 ② 规则卡**。
功能在，但用户不会知道长按。这属于「功能有、可发现性差」。

#### ⑥ App 多出来的东西：底部那行统计

真机截图底部那行 `3 rules enabled · 3 outbounds · 3 links · 42 nodes total`
—— **原型里没有这行**。它是开发期用来跟旧主页逐项对账的
（源码注释：「一行小字，用来核对与旧主页是否逐项一致」）。
既然目标是「和原型一致」，它应该去掉或收进调试开关。

#### ⑦ 缺原型有的：紧凑模式 / 密度切换

原型 `.card.compact .r2{display:none}`（藏副信息，只留图标 + 名称）+ 调试栏「切换密度」。
App 没有。注意：**调试栏本身不该抄**，但「紧凑模式」作为省电/小屏策略是有价值的。

### 3.3 原型里是**假数据**、不该抄的

- **① chip 的图标和友好名**：原型 `docs/spider-ui-prototype-v6.html:561` 是写死的查表
  （`"8.8.8.8": ["🔍","Google DNS"]`），不是真实能力。App 用真实 host，**这是对的**。
- **`.debugbar`**：浅色 / 深色 / 切换密度 / 重新排布 / 重置 —— 调试控件，不是产品设计。

---

## 4. 「标题被截断」的真实原因（顺带回答这个悬着的问题）

真机上的 3 条启用规则（**从设备数据库 pull 出来的实测值**）：

| id | 名称 | 启用 | outbound | 卡片上是否被截断 |
|---|---|---|---|---|
| 2 | `Block ADs` | ✅ | -2 拦截 | ❌ 没截断 |
| 3 | `Play store rule for 中国` | ✅ | 0 走主代理 | ✅ `Play store rule…` |
| 4 | `Domain rule for 中国` | ✅ | -1 绕过 | ✅ `Domain rule fo…` |

标题字号 = 44.4px（12.7dp）。按 Roboto Bold 字宽估算：

| 标题 | 需要宽度 | 可用（App 现状 346px） | 可用（原型 195px） |
|---|---|---|---|
| `Block ADs` | ≈ 214 px | 放得下 ✅ | 放得下 ✅ |
| `Domain rule for 中国` | ≈ 432 px | 放不下 ❌ | 放不下 ❌ |
| `Play store rule for 中国` | ≈ 480 px | 放不下 ❌ | 放不下 ❌ |

（em 宽度是按 Roboto Bold 逐字累加的估算，±5%。真机字体可能不是 Roboto，量级不变。）

**所以：**
1. 我的 `minW` **确实生效了** —— 卡片从原型的 244px 撑到了 433px（区域满宽）。
2. **但没用**：标题要 480px，区域最多给 346px 文字宽。加宽到顶也装不下。
3. **而且它让布局偏离了原型**（244 → 433）。
4. 原型对这个是有设计的：`.card.arc` 字号更小（10px vs 11px）+ **紧凑模式**，
   而且注释写明「62px 放不下『命中数 + 4 字规则名』同一行，所以命中数移到第二行」
   —— **原型的设计预算就是 4 个汉字**。

**这不是 bug，是规则名长度超过了卡片的设计预算。**
要让 `Play store rule for 中国` 在 433px 卡里不截断，字号得降到 32px（9.1dp）；
要在原型的 244px 卡里不截断，得降到 18px（5.1dp）—— 已经不可读了。
**任何字号微调都救不了，这不是调参问题。**

---

## 5. 性能

### 5.1 现在的实际开销

| 场景 | 开销 |
|---|---|
| **空闲（不操作）** | 数据层 **0**。没有任何定时器 / 轮询 —— 只在**下拉刷新**时拉一次 |
| **每次下拉刷新** | 2 次同步 DAO（`allRules()` + `getAll()`）+ 2 次 HTTP（`/connections`、`/rules`），全在后台线程；主线程只做一次 `snapshot=` + `relayout()`。`refreshJob?.cancel()` 保证连点不堆积 |
| **持续开销 = 粒子层** | 屏幕可见时**一直 60fps 重绘**（`postInvalidateOnAnimation()`）。每帧 = N 条曲线 × (1 次三次多项式 + 1 次 `drawCircle` + `occluded()` 扫 M 个遮挡体) |

- HTTP 超时压到 1s/2s/3s，且 `Proxy.NO_PROXY`（回环必须绕开系统代理，否则自己连自己转圈）。
- 卡片层**不重绘**：`invalidate()` 只打在粒子层上。
- 窗口不可见 / 被移出窗口时粒子立刻停（`onWindowVisibilityChanged` / `onDetachedFromWindow`）。

### 5.2 可优化点（按性价比排序）

1. **①→② 不该有粒子 —— 原型就不画。**
   原型 `drawLines()` 只在 `else`（②→③）分支 push 粒子，①→② 只画端点圆点。
   App 把 `curves` **全**给了粒子层（`TopologyView.kt:263`）。
   改一行（只传 `stage == OUTBOUND`）：曲线数从 ~20 降到 ~3，
   60fps 下的工作量降一个数量级，**而且更贴原型**。← 最推荐
2. **内核没连上时停粒子**：现在 ① 层空着、②→③ 的粒子还在跑。
   加一条「没有活动连接就不跑」→ 待机时 CPU 归 0。（原型也一直跑，这条是**超出原型**的优化。）
3. **`occluded()` 是 O(M) 线性扫描**（M ≈ 卡片数，当前 ~27）。
   可换「按 y 排序 + 二分」或预先剔除不可能命中的框。当前 M 小，收益有限，顺手做。
4. **省电模式开关**：真需要时在设置里加一项停掉粒子动画。
5. **分组下拉在主线程读 `allGroups()`**（同步 DAO）。行数是个位数，可接受；
   严格说该挪后台 —— 但换异步会让下拉晚一拍弹出，体感更差，**倾向不改**。

### 5.3 不建议做

- 持续飘动的力导向（每帧 O(n²)，+3~8% CPU、常亮多耗 5~15mA）
- 实时碰撞检测 + 拖拽磁吸（每帧 O(n²) + 事件，CPU 持续 10%+，明显发热掉电）

---

## 6. 增删功能的可行性 —— **API 层已经齐了**

**不用碰内核、不用碰旧 UI**，现有 Manager 层就是完整的一套：

| 想做的事 | 现成 API | 备注 |
|---|---|---|
| 新增节点 | `ProfileManager.createProfile(groupId, bean)` | 17 种协议的入口**已经全部接好**（`manualProtos` 表），目标分组**已经算好**（`updateAddTarget()`） |
| 改节点 | `ProfileManager.updateProfile(profile)` | ③ 卡**单击已经**跳 `ProfileSettingsActivity` |
| 删节点 | `ProfileManager.deleteProfile(groupId, profileId)` | ⚠ 会顺带清 `DataStore.selectedProxy` + `GroupManager.rearrange(groupId)` |
| 增/改/删规则 | `createRule` / `updateRule` / `deleteRule` / `deleteRules` | 长按换出口**已经在用** `updateRule` |
| 增/改/删/清空/重排分组 | `GroupManager.createGroup` / `updateGroup` / `deleteGroup` / `clearGroup` / `rearrange` | 分组长按**已经**接上 |

### 三个必须处理的点

1. **删除不可逆 → 必须配撤销。**
   `deleteProfile` / `deleteRule` / `deleteGroup` **都不弹确认**。
   项目里有现成的 `UndoSnackbarManager`（`ConfigurationFragment:1228`、`RouteFragment`、`AssetsActivity` 都在用），
   新 UI 直接复用这一套，别自己写。
2. **订阅组只读 → 新增节点必须先过 `selectedGroupForImport()`。**
   已经在 `updateAddTarget()` 里做了（含「订阅组 → 第一个普通分组」的回退提示）。
3. **改完要触发重载**：`needReload()` / `postUpdate()`，否则用户以为没生效。

### ⚠ 一个已存在的坑（不是新 UI 引入的，但会挡住「新增节点」）

`DataStore.kt:80-85`：

```kotlin
fun selectedGroupForImport(): Long {
    val current = currentGroup()
    if (current.type == GroupType.BASIC) return current.id
    val groups = SagerDatabase.groupDao.allGroups()
    return groups.find { it.type == GroupType.BASIC }!!.id   // ← 没有 BASIC 组就 NPE
}
```

你设备上实测的分组表：**只有 1 个组 `灯塔`，`type=1`（SUBSCRIPTION）**，没有 BASIC 组。
所以「新增节点」在这台机器上**没有合法目标分组**。

- 新 UI 的 `updateAddTarget()` 用 `runCatching` 兜住了 → 显示「无分组」，不会崩。
- 旧 UI 的四处调用点（`MainActivity:246`、`ProfileSettingsActivity:108`、
  `ProfileImporter:38`、`ScannerActivity:124`）**没有兜**。

这是**既有行为**，按红线我不该动它。但如果你要在新 UI 里做「新增节点」，
**得先决定**：是让新 UI 在没 BASIC 组时自动建一个「我的节点」，还是照旧报错。

---

## 7. 关于我上次那个改动：建议回滚

`TopologyLayout.Item.minW` + `TopologyView.computeLayout` 里那段测标题宽度的代码。

**回滚的理由（三条，任一条都够）：**

1. **它没解决问题** —— 卡片已经撑到区域满宽 433px，标题还是被切（要 480px）。
2. **它让布局偏离原型** —— 原型硬锁 62 单位（244px），我改成了 433px。
3. **原型是有意这么设计的** —— 注释写明设计预算是「命中数 + 4 字规则名」，
   长名字就该省略号；看全名的方式是**点开卡片**（App 单击 ② 卡就是跳规则编辑页）。

**留着的代价**：布局比原型宽 78%，`Validate` 里多 120 个用例，以及一个「看起来像在修 bug
其实修不动」的假象。

---

## 8. 待你拍板

**A. 标题截断怎么办**（三选一或组合）

- **A1. 回滚 `minW`**，接受省略号 —— 最贴原型，成本 0
- **A2. ② 层改两行排布**（规则 ≤6 条时），卡片宽度翻倍 → 能装下 `Domain rule for 中国`(432px)，
  `Play store rule for 中国`(480px) 仍差一点。代价：偏离原型的单弧线设计
- **A3. 副标题位置改放「缩短名」**，比如把 `xxx rule for 中国` 显示成 `中国 · xxx`
  —— 不动布局，靠文案解决。但这要改规则名生成逻辑，影响旧 UI
- **A4. 什么都不做** —— 原型本来就是这个观感

**B. 先补哪一块？**（我的建议顺序）

- **B1. 卡片内容结构**（图标 + 命中数徽章 + 出口色点 + 左边框）
  —— 这是「和原型一致」里最显眼、成本最低的一条
- **B2. ①→② 粒子去掉**（一行代码，性能 + 保真双收益）
- **B3. 节点选择弹层**（原型式：搜索 + 3 种排序 + 「协议·分组」）
  —— 让「选节点」变成点开就见的
- **B4. 底部统计行去掉或收进调试开关**
- **B5. 增删功能**（API 齐了，但先要定 §6 那个 BASIC 组的坑）

**C. 要不要保留「去旧主页」的出口？**
现在顶栏下拉里有「经典主页」一项。功能上必要（新 UI 只读，新建分组/订阅都在旧主页），
但它是原型的额外项。建议**保留**，只是文案/位置可以调。

---

## 附：证据文件

- `docs/topology-compare/prototype-v6.png` —— 原型渲染（headless Chrome，411×1500 CSS px）
- `docs/topology-compare/app-2026-09-16.png` —— 真机 1440×3200 截图
- `docs/spider-topology-remaining-plan.md` —— 之前的未完成项计划（本报告取代其 §一/§二）
- `docs/spider-newui-plan.md` —— 阶段契约
