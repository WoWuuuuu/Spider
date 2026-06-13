# IDE 开发提示词

> 将以下内容复制粘贴到 Trae / Cursor / Copilot 等 IDE 的 AI 对话框中，一次性交付开发任务。

---

## 提示词

```
请阅读当前目录下的 AGENT.md，然后按以下顺序逐步执行开发任务。每完成一个 Phase，运行 `flutter analyze` 确认零错误后再进入下一个。

## Phase 1：项目初始化

1. 在当前目录初始化 Flutter 项目：
   flutter create --org com.spider --project-name spider --platforms=android,ios,windows,macos .

2. 编辑 pubspec.yaml，添加以下依赖：
   - flutter_riverpod: ^2.6.1
   - go_router: ^14.8.1
   - hive: ^2.2.3
   - hive_flutter: ^1.1.0
   - dio: ^5.9.2
   - path_provider: ^2.1.5
   - package_info_plus: ^8.3.1
   - window_manager: ^0.3.9
   - lucide_icons: ^0.257.0
   - build_runner: ^2.4.13 (dev)
   - riverpod_generator: ^2.4.0 (dev)
   - hive_generator: ^2.0.1 (dev)

3. 创建目录结构：
   lib/core/theme/
   lib/core/database/
   lib/features/dashboard/
   lib/features/groups/
   lib/features/profiles/
   lib/features/settings/
   lib/features/core_service/
   assets/binaries/windows/

4. 在 pubspec.yaml 中声明 assets：
   assets/binaries/windows/sing-box.exe
   assets/binaries/windows/wintun.dll

5. 修改 lib/main.dart：
   - 引入 window_manager
   - Windows 下初始化窗口：宽 360，高 600，最小 320x500，居中
   - 引入 ProviderScope（Riverpod）

## Phase 2：AI Agent 模块

6. 创建 lib/features/settings/models/ai_model_config.dart：
   - @HiveType(typeId: 0)
   - 字段：id(String), name(String), baseUrl(String), apiKey(String), modelName(String), isActive(bool)
   - 继承 HiveObject

7. 创建 lib/features/groups/models/subscription.dart：
   - @HiveType(typeId: 1)
   - 字段：id(String), name(String), url(String), rawContent(String), nodesJson(String), lastUpdated(DateTime)
   - 继承 HiveObject

8. 运行代码生成：
   dart run build_runner build --delete-conflicting-outputs

9. 创建 lib/core/database/database_service.dart：
   - Hive.initFlutter()
   - 注册 AiModelConfigAdapter 和 SubscriptionAdapter
   - 打开 ai_models_box 和 subscriptions_box

10. 创建 lib/features/core_service/subscription_parser_service.dart：
    混合订阅解析器，实现方法 parseSubscription(String rawContent) -> String：
    - 本地正则解析优先：识别 sing-box JSON、V2Ray vmess:// 链接、trojan:// 链接、Base64 编码、Clash YAML
    - 解析失败时调用 AI 兜底：
      a. 先检测当前 sing-box 内核版本（通过 sing-box version 命令输出，或从 assets 中二进制文件名提取版本号）
      b. 从 Hive ai_models_box 读取 isActive=true 的配置
      c. 构造 prompt 时明确指定："请将以下订阅内容转换为 sing-box [当前版本号] 格式的 outbounds JSON 数组。注意：1.14+ 使用新的 dns server 格式（domain_resolver），1.10 及更早版本使用旧的 outbound dns rule 格式。"
      d. 调用 baseUrl/chat/completions
    - 绝不硬编码 API 地址和模型名
    - 版本检测逻辑封装为 getSingBoxVersion() 方法，供其他模块复用

11. 创建 lib/features/core_service/config_generator.dart：
    - 定义 sing-box 1.14 基础配置模板（含 dns、inbounds、route、outbounds）
    - 基础 outbounds 包含：Proxy selector、DIRECT、BLOCK
    - 路由规则：dns-out、ip_is_private -> DIRECT、geoip-cn/geosite-cn -> DIRECT、final -> Proxy
    - inbounds：mixed 127.0.0.1:7890
    - 将解析出的节点注入 outbounds，自动创建 Proxy selector 和 urltest AUTO
    - 输出 config.json 到应用文档目录

12. 创建 lib/features/settings/providers/ai_model_provider.dart：
    - aiModelListProvider：StateNotifier 管理 AI 模型列表（增删改查）
    - activeAiModelProvider：获取 isActive=true 的配置
    - testConnection 方法：用 Dio 向 baseUrl/chat/completions 发最简请求测试连通性

13. 创建 lib/features/settings/widgets/ai_model_settings_card.dart：
    - 表单：配置名、Base URL、API Key、模型名称输入框
    - 按钮：测试连接、保存/更新
    - 已保存列表：显示每个配置，支持激活、测试、编辑、删除

14. 创建各页面：
    - dashboard_page.dart：代理状态卡片、AI 模型状态、节点统计、快捷操作（启动/停止/更新订阅/生成配置）
    - groups_page.dart：添加订阅表单（名称+URL）、订阅列表（删除）
    - profiles_page.dart：从订阅解析出的节点列表，按类型着色
    - settings_page.dart：AI 模型配置卡片

15. 配置路由和导航：
    - 底部导航栏 4 个标签：仪表盘、订阅、节点、设置
    - 使用 go_router 或 IndexedStack

## Phase 3：sing-box 集成

16. 添加依赖：sing_box: ^0.0.1（来自 pub.dev/packages/sing_box）

17. 在 lib/core_service/singbox_service.dart 封装 sing_box 调用：
    - connect(config) / disconnect()
    - watchConnectionStatus() -> Stream<SingBoxConnectionStatus>
    - watchConnectionStats() -> Stream<SingBoxConnectionStats>

18. 在 dashboard_page.dart 接入真实代理控制：
    - 启动按钮：调用 configGenerator 生成 config.json -> singBox.connect(config)
    - 停止按钮：调用 singBox.disconnect()
    - 显示实时连接状态和流量统计

## 关键约束

- 深色主题：背景 #121212，卡片 #1E1E1E
- AI 配置从 Hive 动态读取，绝不硬编码
- 路由规则保持用户订阅原样，AI 只做格式转换
- 每个 Phase 完成后运行 flutter analyze 确认零错误
- 先创建所有文件，最后运行代码生成和编译验证
```
