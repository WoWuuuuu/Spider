# Spider 项目结构说明文档

> Version : 2026-08-27 重构版
> Scope   : Repo 根（`d:\workspace\Spider\`）+ Android 子工程（`Spider-android/`）
> Audience: 新入职工程师 / 维护者 / Reviewer

---

## 1. 顶层目录全景（工作区根，`d:\workspace\Spider\`）

```
Spider/
├── Spider-android/          # ★ Android 端主工程（Jetpack Compose + Kotlin + sing-box libbox.aar）
│                              ★ 命名 OQ1 历史决策：永不重命名，任何 Gradle include / CI / IDE 缓存均依赖它
├── sing-box-reference/      # 仅参考的 sing-box Go 源码快照（Go 1.24.7），不参与构建
│   └── SOURCE_OF_TRUTH.txt  # 必 READ：说明"为什么保留、不参与构建、上游来源"
├── tools/                   # 工具脚本（kebab-case 命名规范 OQ3）
│   └── windows/
│       └── download-singbox.ps1  # 下载 sing-box.exe + wintun.dll，100% 项目相对路径（$PSScriptRoot）
├── docs/                    # 架构/运维文档（AC-R8 / AC-R7 的两份交付物在这里）
│   ├── project-structure.md     # ← 你正在阅读的文档
│   ├── before-after-report.md   # 重构前后对比报告
│   ├── SINGBOX_UPGRADE_GUIDE.md # 历史文档：Android libbox 升级指南
│   └── kernel_update_guide.md   # 历史文档（从根目录移入，OQ5 docs/ 单点维护）
├── backup_icons/            # 品牌图标冷备（10 个 mipmap-*/ic_launcher*.png 的副本）
│                              ★ 只在误删除时恢复使用，正常构建 0 引用
├── README.md                # 项目总入口
├── AGENT.md                 # AI 协作 prompt（随代码演进维护）
├── IDE_PROMPT.md            # IDE 侧 AI 提示
└── .trae/                   # Trae CN Spec Mode 产物（规格、任务、审查记录）
    └── specs/
        └── refactor-20260827-structure/
            ├── spec.md          # 重构规格（AC 10 条 rule / 3 条 rubric）
            └── tasks.md         # 实现计划（Task 0~7，证据链 TR-*）
```

### 顶层命名规范（OQ3 = B kebab-case 强制）

- 新建顶层目录 / 脚本 / Markdown **必须**使用 `kebab-case`（短横线），如：`sing-box-reference/`、`tools/windows/download-singbox.ps1`、`docs/before-after-report.md`。
- **唯一例外**：`Spider-android/`（历史决策 OQ1=A，跨平台大小写兼容 + git blame 完整保留）。

---

## 2. Android 工程（`Spider-android/`）结构与职责

```
Spider-android/
├── app/                         # ★ 主应用模块（唯一产出 APK/AAB 的模块）
│   ├── libs/
│   │   └── libbox.aar           # ★ sing-box 预编译 Android 运行时（构建链 SOURCE OF TRUTH）
│   │                              所有 flavor 均：files("libs/libbox.aar")
│   │                              本仓库不包含 Go 源码的 inline 编译链
│   ├── src/
│   │   ├── main/                # 主源码集（95% 业务代码在这里）
│   │   │   ├── java/io/nekohasekai/sfa/
│   │   │   │   ├── Application.kt            # Application 入口、DI、全局状态
│   │   │   │   ├── bg/                       # ★ 后台服务层
│   │   │   │   │   ├── VPNService.kt         # Android VpnService + tun 实现
│   │   │   │   │   ├── BoxService.kt         # libbox 生命周期总管
│   │   │   │   │   ├── ConfigOrchestrator.kt # ★ 订阅解析核心（8 格式 → sing-box JSON）
│   │   │   │   │   ├── UpdateProfileWork.kt  # WorkManager：定期更新远程 Profile
│   │   │   │   │   └── ...
│   │   │   │   ├── compose/                  # Jetpack Compose UI 层
│   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   ├── screen/configuration/  # 配置页 & Profile 导入
│   │   │   │   │   │   ├── ProfileImportHandler.kt   # ★ ZIP / 文件 / URI 导入
│   │   │   │   │   │   └── NewProfileViewModel.kt
│   │   │   │   │   └── ...
│   │   │   │   ├── database/                 # Room：Profile / TypedProfile / KV
│   │   │   │   │   ├── Profile.kt            # 数据模型（扁平 Profile，非 Bean 二级）
│   │   │   │   │   └── ProfileManager.kt
│   │   │   │   ├── ktx/                      # Kotlin 扩展函数库（简洁性 FR2）
│   │   │   │   │   └── Strings.kt            # looksLikeBase64 / decodeBase64Lenient / replaceTrafficSuffix
│   │   │   │   ├── utils/
│   │   │   │   │   └── HTTPClient.kt         # ★ 新：Subscription-Userinfo + Content-Disposition RFC 5987
│   │   │   │   ├── xposed/                   # Xposed / LSPosed Hook 能力（反检测）
│   │   │   │   └── vendor/                   # 分渠道 vendor 能力（APK 安装器 / Shizuku 等）
│   │   │   ├── res/                          # Android 资源
│   │   │   │   ├── values/strings.xml        # ★ 品牌约束：app_name="Spider" 永不改
│   │   │   │   └── mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/
│   │   │   │       └── ic_launcher.png & ic_launcher_round.png   # ★ 品牌图标（10 张）
│   │   │   └── AndroidManifest.xml           # ★ label=@string/app_name + icon=@mipmap/ic_launcher
│   │   ├── other/                            # ★ flavor=Other（日常 / F-Droid 渠道）
│   │   ├── otherLegacy/                       # flavor=OtherLegacy（旧版 Android 兼容）
│   │   ├── play/                              # flavor=Play（Google Play 合规）
│   │   ├── github/                            # GitHub 渠道更新检查器 + 安装器
│   │   ├── minApi21/ / minApi23/              # API level 条件编译（Shizuku / 安装器兼容）
│   │   └── test/                              # 单元测试（ConfigOrchestrator / Subscription）
│   ├── build.gradle.kts                       # ★ Maven 依赖清单（见 §4）
│   └── release.keystore                       # 本地调试签名（不上传公开仓库）
├── gradle/
│   └── libs.versions.toml                     # 版本目录（Version Catalog）
├── config/detekt/detekt.yml                   # Kotlin 静态检查规则
├── third_party/                               # ★ AC-R6：0 修改（Git 子模块 + 源码拷贝）
│   ├── termux-app/                            # Git submodule：终端 & 文件提供者
│   └── libxposed-api/                         # 源码拷贝：Xposed 接口
├── build.gradle.kts                           # Gradle 根脚本
├── settings.gradle.kts                        # Maven 仓库顺序（阿里云 → google → mavenCentral）
├── .gitmodules                                # 只注册 termux-app 一个 submodule
└── version.properties                         # 版本号（versionCode / versionName）
```

### 模块边界规则（架构最佳实践 §1 边界清晰）

| 层 | 职责 | 禁止 |
|----|------|------|
| `compose/` | UI 渲染 & 用户交互（纯 Kotlin + Compose） | 直接访问 libbox、直接写 Room、直接做网络 IO |
| `viewmodel / screen ViewModel` | UI ↔ 业务之间的数据状态桥 | 包含业务算法细节（下沉到 `bg/`） |
| `bg/` | 业务核心（订阅解析、VPN 生命周期、Profile 更新） | 持有 Compose / Context 引用；做 UI 决策 |
| `database/` | 持久化（Room + KV） | 暴露 LiveData / Flow 以外的实现细节到上层 |
| `ktx/` | 纯扩展函数，0 业务副作用，可单元测试 | 引入任何业务依赖 |
| `vendor/` / `xposed/` | 分渠道 + Hook 能力隔离 | 被非渠道代码直接 import（统一走 VendorInterface） |

---

## 3. Android Flavor 速查（日常命令用 Other）

| Flavor | `applicationId` | 用途 | 典型 `assemble*` / `compile*` 任务 |
|--------|-----------------|------|------------------------------------|
| **Other** ★默认 | `io.github.spidervpn` | 日常调试、F-Droid、中国大陆分发 | `:app:compileOtherDebugKotlin` `:app:assembleOtherDebug` |
| OtherLegacy | 同上 | 老 Android（API <23）兼容 | `:app:compileOtherLegacyDebugKotlin` |
| Play | 同上（+ ML Kit） | Google Play 合规（QR 走 ML Kit） | `:app:compilePlayDebugKotlin` |

> ⚠️ **常见坑**：Flavor 任务名中间大写（`OtherDebug` 不是 `debug`），老命令 `compileDebugKotlin` 在本项目不存在。

---

## 4. Maven 依赖清单（关键第三方，AC-R10 无新增依赖原则）

**入口文件**：`Spider-android/app/build.gradle.kts` + `Spider-android/gradle/libs.versions.toml`

| 依赖 | 版本 | 用途 | 引入时机 |
|------|------|------|----------|
| AndroidX / Compose / Material3 | BOM 对齐 | UI 框架（SFA 继承） | 项目基线 |
| Room | 2.6+ | Profile 数据库 | 项目基线 |
| WorkManager | 2.9+ | Profile 定时更新 | 项目基线 |
| `org.snakeyaml:snakeyaml` | 2.2 | Clash YAML 解析（ConfigOrchestrator.kt convertClashYamlToSingBox） | NekoBox 对齐阶段 |
| `org.ini4j:ini4j` | 0.5.4 | WireGuard .conf INI 解析（convertWireguardConfToSingBox） | NekoBox 对齐阶段 |
| libbox AAR | 预编译 `app/libs/libbox.aar` | sing-box 运行时（所有 flavor） | 项目基线 |
| OkHttp / Okio | 4.x / 3.x | HTTPClient 网络栈（Subscription-Userinfo / Content-Disposition） | 项目基线 |

> **C2 清理结论**：本项目**不包含**任何 Go 源码 inline Gradle 编译链（`includeBuild`、`go build` Task、`com.github.gmazzo.go` 插件均不存在）。sing-box 运行时 100% 通过 `libbox.aar` 预编译产物交付。升级流程见 `docs/SINGBOX_UPGRADE_GUIDE.md`。

---

## 5. 构建与常用命令速查

```powershell
# 进入 Android 根
cd Spider-android

# ★ 基线编译（本项目 10 条 AC 验收必跑，AC-R4 exit=0）
.\gradlew.bat :app:compileOtherDebugKotlin

# 打包 APK（Other Debug = 默认日常调试包）
.\gradlew.bat :app:assembleOtherDebug

# 单元测试（ConfigOrchestrator / Subscription 解析）
.\gradlew.bat :app:testOtherDebugUnitTest

# detekt 静态检查（代码质量 FR2）
.\gradlew.bat detekt

# 运行工具脚本（下载 Windows sing-box.exe + wintun.dll）
#   新路径（相对路径，无硬编码绝对路径 AC-R9）
powershell -ExecutionPolicy Bypass -File ..\tools\windows\download-singbox.ps1
#   ★ 旧路径（download_cores.ps1）已在 2026-08-27 重构中删除
```

---

## 6. 品牌约束红线（★ 字节级不变，FR4.2 / AC-R5）

任何时候（包括 Release 打包、UI 改版、白标定制、紧急 hotfix、未来重构）**均不得修改**以下 3 项：

1. **`app_name` 字符串** — `Spider-android/app/src/main/res/values/strings.xml` 第 4 行
   ```xml
   <string name="app_name">Spider</string>
   ```
2. **10 张启动器图标** — 物理文件必须存在且内容不变
   - 5 个密度 × 2（方形 + 圆角）= **10 张**：
   ```
   res/mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
   res/mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_round.png
   ```
   - 冷备副本在 `backup_icons/`（误删恢复用）
3. **`AndroidManifest.xml` 两处引用** — `Spider-android/app/src/main/AndroidManifest.xml`
   ```xml
   <application
       android:label="@string/app_name"
       android:icon="@mipmap/ic_launcher"
       ... >
   ```
4. **`applicationId`** — `Spider-android/app/build.gradle.kts` 中 `defaultConfig.applicationId = "io.github.spidervpn"`

> 验证方法（10s 快速检查 AC-R5）：
> ```powershell
> Select-String -Path .\Spider-android\app\src\main\res\values\strings.xml -Pattern 'app_name.*Spider'
> (Get-ChildItem -Path .\Spider-android\app\src\main\res -Recurse -Filter 'ic_launcher*.png').Count  # 必须 =10
> ```

---

## 7. 历史决策记录（防止 3 个月后重蹈覆辙）

| 编号 | 决策 | 背景/原因 | 生效日期 |
|------|------|-----------|----------|
| H1 | **不做 NekoBox 源码整体替换** | Spider 基于 SFA（Compose + 扁平 Profile + libbox.aar），NekoBox 基于 SagerNet（View XML + ProxyEntity Bean + Kryo）；整体替换 = 重写 80% UI + 数据库；性价比远低于「算法等价移植」 | 2026-08-27 前 |
| H2 | NekoBox 只移植**解析算法**（ConfigOrchestrator / HTTPClient / ProfileImportHandler / Strings ktx） | 满足用户「多客户端订阅兼容」需求，同时保留 Compose 架构 + 品牌 + Profile 模型的一致性 | 2026-08-27 前 |
| H3 | **算法迁移后先修 18 处编译错误**，然后才允许任何文件移动/删除 | 基线编译不过 → 任何结构清理之后均无法验证是否引入回归 | 2026-08-27 |
| H4 | 工作区根 **0 裸文件**（AC-R1） | 工程师第一眼看到顶层 10+ 份 dump/dns.go/screen.png 会对仓库质量和项目范围产生错误判断 | 2026-08-27 |
| H5 | `Spider-android/` 根 **0 非构建文件**（AC-R2） | Gradle 目录污染会减慢 IDE 索引 + 让 includeBuild/子模块边界模糊 | 2026-08-27 |
| H6 | **sing-box 源码保留一份且仅一份**放在 `sing-box-reference/`，附 SOURCE_OF_TRUTH.txt | 消除「两份 sing-box/ + sing-box-src/ 谁是生效的」永久困惑（AC-R3） | 2026-08-27 |
| H7 | 工具脚本统一 kebab-case + `tools/<os>/` 分类 + `$PSScriptRoot` 相对路径 | AC-R9 0 硬编码绝对路径；跨工程师 / 跨盘位 / CI Runner 都可以直接跑 | 2026-08-27 |
| H8 | **third_party/ 绝不重命名 / 移动 / 删除** | termux-app 是 git submodule，libxposed-api 是源码拷贝；边界改动连锁失效（AC-R6） | 2026-08-27 |

---

*End of project-structure.md. Next read: `docs/before-after-report.md` for the cleanup evidence & change list.*
