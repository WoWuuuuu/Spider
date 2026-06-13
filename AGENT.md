# Spider — AI Agent 开发指令文档

## 项目定位

Spider 是一个基于 [sing-box](https://github.com/SagerNet/sing-box) 内核的跨平台代理客户端。UI 界面**完全借鉴 sing-box 官方客户端**，唯一新增的功能是：在设置界面增加 **AI Agent 订阅解析**模块。

## 架构总览

```
┌─────────────────────────────────────────────────┐
│  Spider (Flutter UI)                            │
│  ┌─────────┬──────────┬─────────┬────────────┐  │
│  │Dashboard │ 订阅管理  │ 节点列表 │   设置     │  │
│  └────┬────┴────┬─────┴────┬────┴─────┬──────┘  │
│       │         │          │          │          │
│       │    ┌────▼──────────▼──────────▼──────┐   │
│       │    │  sing_box Flutter Plugin        │   │
│       │    │  (pub.dev/packages/sing_box)    │   │
│       │    └────────────┬───────────────────┘   │
│       │                 │                       │
│  ┌────▼─────────────────▼───────────────────┐   │
│  │  sing-box Core (Go binary)               │   │
│  │  - Android: SFA (sing-box-for-android)   │   │
│  │  - iOS:     SFI (sing-box-for-ios)       │   │
│  │  - Windows: sing-box binary              │   │
│  │  - macOS:   SFM (sing-box-for-macos)     │   │
│  └──────────────────────────────────────────┘   │
│                                                 │
│  ┌──────────────────────────────────────────┐   │
│  │  AI Agent 模块 (Spider 独有)              │   │
│  │  混合订阅解析 → 兼容多版本配置 → 生成config │   │
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

## 关键仓库

| 组件 | 仓库 |
|------|------|
| sing-box 核心 | https://github.com/SagerNet/sing-box |
| sing-box Android | https://github.com/SagerNet/sing-box-for-android |
| sing-box iOS | https://github.com/SagerNet/sing-box-for-ios |
| sing-box macOS | https://github.com/SagerNet/sing-box-for-macos |
| Flutter 插件 | https://pub.dev/packages/sing_box |

## 核心功能：AI Agent 订阅解析

这是 Spider 唯一的差异化功能。位于设置页面。

### 流水线

```
用户输入原始订阅 URL/内容
         │
         ▼
┌─────────────────────┐
│ 本地正则解析器        │
│ - sing-box JSON      │
│ - V2Ray Base64       │
│ - Clash YAML         │
│ - SS/SSR URI         │
└────────┬────────────┘
         │ 解析失败
         ▼
┌─────────────────────┐
│ AI 兜底解析          │
│ 动态读取 Hive 中      │
│ 激活的 AI 模型配置    │
│ 调用 /chat/completions│
│ 输出 sing-box 1.14   │
│ outbounds JSON       │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ 配置模板注入          │
│ - 合并 Bypass CN 路由│
│ - 注入 outbounds 节点│
│ - 生成 Proxy selector│
│ - 输出 config.json   │
└─────────────────────┘
```

### 设计原则

1. **本地解析优先**：减少 AI 调用成本，常见格式直接正则匹配
2. **AI 配置动态读读取**：从 Hive `ai_models_box` 读取用户配置的 API 地址和模型名，**绝不硬编码**
3. **版本自适应**：AI 兜底时，先检测当前 sing-box 内核版本（通过 `sing-box version` 命令或 package_info），然后在 prompt 中指定输出该版本对应的配置格式（如 1.10 用旧格式、1.14+ 用新 DNS server 格式）
4. **配置版本转换**：AI 唯一作用是将不同客户端格式（Clash / sing-box 1.10）转换为当前内核版本对应的格式
5. **路由规则保持原样**：用户订阅自带的分流规则不修改，AI 只做格式转换

## 技术栈

| 层级 | 技术 |
|------|------|
| UI 框架 | Flutter (Dart) |
| 状态管理 | Riverpod |
| 本地存储 | Hive |
| HTTP | Dio |
| sing-box 调用 | sing_box Flutter Plugin |
| 平台 | Android / iOS / Windows / macOS |

## 开发步骤

### Phase 1：项目初始化

1. `flutter create --org com.spider --project-name spider --platforms=android,ios,windows,macos .`
2. 配置 pubspec.yaml 依赖
3. 创建目录结构
4. 声明 assets/binaries
5. 配置平台窗口参数

### Phase 2：AI Agent 模块

6. 创建 AiModelConfig 数据模型 (Hive typeId: 0)
7. 创建 Subscription 数据模型 (Hive typeId: 1)
8. 编写混合解析服务 (SubscriptionParserService)
9. 编写配置生成器 (ConfigGenerator)
10. 编写 AI 模型设置界面
11. 编写订阅管理界面

### Phase 3：sing-box 集成

12. 集成 sing_box Flutter Plugin
13. 实现代理启动/停止逻辑
14. 实现连接状态监控
15. 实现流量统计展示

## 目录结构

```
lib/
├── main.dart
├── app.dart
├── core/
│   ├── theme/
│   └── database/
│       └── database_service.dart          # Hive 初始化
├── features/
│   ├── dashboard/
│   │   └── dashboard_page.dart
│   ├── groups/
│   │   ├── groups_page.dart
│   │   └── models/subscription.dart
│   ├── profiles/
│   │   └── profiles_page.dart
│   ├── settings/
│   │   ├── settings_page.dart
│   │   ├── models/ai_model_config.dart
│   │   ├── providers/ai_model_provider.dart
│   │   └── widgets/ai_model_settings_card.dart
│   └── core_service/
│       ├── subscription_parser_service.dart  # 混合解析
│       └── config_generator.dart             # 配置生成
assets/
├── binaries/
│   ├── windows/
│   │   ├── sing-box.exe
│   │   └── wintun.dll
│   └── android/
│       └── libbox.so
```

## UI 规范

- **深色主题**：背景 #121212，卡片 #1E1E1E
- **iOS**：Cupertino 风格
- **Android/Windows/macOS**：Material 3
- **最小窗口**：360x600
