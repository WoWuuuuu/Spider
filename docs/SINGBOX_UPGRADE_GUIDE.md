# Sing-Box 内核升级操作指南

## 概述

本文档记录每次 Sing-Box 内核版本升级时需要检查和修改的配置变更。参考官方迁移指南：https://sing-box.sagernet.org/migration/

---

## 版本历史

| 内核版本 | 升级日期 | 主要变更 | 配置文件格式 |
|---------|---------|---------|-------------|
| 1.10.x | - | 基础版本 | `server`/`server_port` |
| 1.12.x | 2024-Q1 | DNS 格式变更 | `address`/`port` |
| 1.14.x | 2024-Q2 | Rule Set 格式变更 | 最新格式 |

---

## 一、升级前准备

### 1.1 获取新版本信息

```bash
# 查看当前使用的 libbox 版本
# 在 Application.kt 中检查 Libbox.VERSION

# 查看 sing-box 官方迁移指南
# https://sing-box.sagernet.org/migration/
```

### 1.2 识别需要修改的文件

主要涉及以下文件：

| 文件路径 | 作用 | 修改内容 |
|---------|------|---------|
| `assets/Spider-template.json` | 配置模板 | DNS、出站等格式 |
| `bg/ConfigOrchestrator.kt` | 配置处理 | 版本检测、迁移逻辑 |
| `bg/ProtocolParser.kt` | 协议解析 | 协议相关配置 |
| `bg/SingboxConfigBuilder.kt` | 配置构建 | 动态生成配置 |

---

## 二、版本变更详情

### 2.1 升级到 1.12.x

**变更时间**：2024 年 Q1

**主要变更**：DNS 服务器格式从旧格式迁移到新格式

#### DNS 服务器格式变化

**旧格式（1.10.x）：**
```json
{
  "dns": {
    "servers": [
      {
        "tag": "google",
        "type": "https",
        "server": "8.8.8.8",
        "server_port": 443,
        "path": "/dns-query"
      }
    ]
  }
}
```

**新格式（1.12+）：**
```json
{
  "dns": {
    "servers": [
      {
        "tag": "google",
        "type": "https",
        "address": "8.8.8.8",
        "port": 443,
        "path": "/dns-query"
      }
    ]
  }
}
```

**字段映射：**
| 旧字段 | 新字段 | 说明 |
|--------|--------|------|
| `server` | `address` | 服务器地址 |
| `server_port` | `port` | 服务器端口 |

#### 需要修改的位置

1. **Spider-template.json**：
   ```json
   // 修改前（1.10.x 格式）
   {"tag": "google", "type": "https", "server": "8.8.8.8", "server_port": 443}

   // 修改后（1.12+ 格式）
   {"tag": "google", "type": "https", "address": "8.8.8.8", "port": 443}
   ```

2. **ConfigOrchestrator.kt - migrateDnsFormat()**：
   ```kotlin
   // 确保迁移方法正确处理
   private fun migrateDnsFormat(config: JSONObject) {
       val dns = config.optJSONObject("dns") ?: return
       val servers = dns.optJSONArray("servers") ?: return

       for (i in 0 until servers.length()) {
           val server = servers.optJSONObject(i) ?: continue

           // 迁移 server -> address
           if (server.has("server") && !server.has("address")) {
               server.put("address", server.optString("server"))
               server.remove("server")
           }

           // 迁移 server_port -> port
           if (server.has("server_port") && !server.has("port")) {
               server.put("port", server.optInt("server_port"))
               server.remove("server_port")
           }
       }
   }
   ```

3. **SingboxConfigBuilder.kt - buildDns()**：
   ```kotlin
   private fun buildDns(): JSONObject {
       return JSONObject().apply {
           put("servers", JSONArray().apply {
               dnsServers.forEach { server ->
                   put(JSONObject().apply {
                       put("tag", server.tag)
                       put("type", server.type)
                       put("address", server.address)  // 使用新格式
                       server.port?.let { put("port", it) }
                   })
               }
           })
       }
   }
   ```

---

### 2.2 升级到 1.14.x

**变更时间**：2024 年 Q2

**主要变更**：
1. 部分旧格式完全移除
2. Rule Set 格式优化

#### Rule Set 格式变化

**旧格式（1.12.x）：**
```json
{
  "route": {
    "rule_set": [
      {
        "tag": "geosite-cn",
        "type": "local",
        "format": "source",
        "path": "geosite-cn.srs"
      }
    ]
  }
}
```

**新格式（1.14.x）：**
```json
{
  "route": {
    "rule_set": [
      {
        "tag": "geosite-cn",
        "type": "local",
        "format": "binary",
        "path": "geosite-cn.srs"
      }
    ]
  }
}
```

**字段映射：**
| 旧字段 | 新字段 | 说明 |
|--------|--------|------|
| `format: "source"` | `format: "binary"` | Rule Set 格式 |

#### 需要修改的位置

1. **Spider-template.json**：
   ```json
   // 修改 rule_set 格式
   "rule_set": [
     {
       "tag": "geosite-cn",
       "type": "local",
       "format": "binary",  // 从 "source" 改为 "binary"
       "path": "geosite-cn.srs"
     }
   ]
   ```

2. **ConfigOrchestrator.kt - migrateRuleSet()**：
   ```kotlin
   private fun migrateRuleSet(config: JSONObject) {
       val route = config.optJSONObject("route") ?: return
       val ruleSets = route.optJSONArray("rule_set") ?: return

       for (i in 0 until ruleSets.length()) {
           val ruleSet = ruleSets.optJSONObject(i) ?: continue

           // 迁移 format: source -> binary
           if (ruleSet.optString("format") == "source") {
               ruleSet.put("format", "binary")
           }
       }
   }
   ```

---

## 三、版本检测机制

### 3.1 当前实现

**文件**：Application.kt

```kotlin
private fun detectLibboxVersion(): String {
    // 新格式（1.12+）使用 address/port
    val newFormatTest = """{
        "dns": {
            "servers": [{"tag": "test", "type": "udp", "address": "8.8.8.8"}],
            "rules": [],
            "final": "test"
        },
        "inbounds": [],
        "outbounds": [{"type": "direct", "tag": "direct"}],
        "route": {"rules": [], "final": "direct"}
    }"""

    return try {
        Libbox.checkConfig(newFormatTest)
        "1.12.0"
    } catch (e: Exception) {
        "1.10.0"
    }
}
```

### 3.2 版本比较工具

```kotlin
object VersionComparator {
    private data class Version(val major: Int, val minor: Int, val patch: Int) :
        Comparable<Version> {
        override fun compareTo(other: Version): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            return patch.compareTo(other.patch)
        }
    }

    private fun parseVersion(versionStr: String?): Version {
        if (versionStr.isNullOrEmpty()) return Version(0, 0, 0)
        val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-.+)?$""").find(versionStr)
        return if (match != null) {
            Version(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            )
        } else {
            Version(0, 0, 0)
        }
    }

    fun isVersionAtLeast(singBoxVersion: String?, minVersion: String): Boolean {
        return parseVersion(singBoxVersion) >= parseVersion(minVersion)
    }
}
```

### 3.3 版本检测调用

**文件**：ConfigOrchestrator.kt

```kotlin
// 使用版本检测
if (VersionComparator.isVersionAtLeast(singBoxVersion, "1.12.0")) {
    // 使用新格式（address/port）
    server.put("address", serverAddress)
    server.put("port", serverPort)
} else {
    // 使用旧格式（server/server_port）
    server.put("server", serverAddress)
    server.put("server_port", serverPort)
}
```

---

## 四、升级检查清单

每次内核升级时，按以下清单检查：

### 4.1 必检项

- [ ] **DNS 格式**：`server` -> `address`, `server_port` -> `port`
- [ ] **Rule Set 格式**：`format: "source"` -> `format: "binary"`
- [ ] **版本检测逻辑**：确保能正确识别新版本
- [ ] **迁移逻辑**：确保旧配置能正确迁移到新格式

### 4.2 可选检项

- [ ] **新协议支持**：检查是否需要添加新协议解析
- [ ] **配置简化**：检查是否有冗余配置可移除
- [ ] **性能优化**：检查配置生成性能

### 4.3 测试验证

- [ ] **单元测试**：运行 `gradlew test`
- [ ] **配置验证**：使用 `Libbox.checkConfig()` 验证配置
- [ ] **手动测试**：在设备上测试代理功能

---

## 五、配置模板版本化

为不同版本维护独立模板：

```
assets/
├── templates/
│   ├── template_1.10.json    # 1.10.x 格式
│   ├── template_1.12.json    # 1.12.x 格式
│   └── template_1.14.json    # 1.14.x 格式
└── Spider-template.json      # 默认模板（当前最新版本）
```

**选择模板逻辑**：

```kotlin
fun getConfigTemplate(version: String): String {
    val templateName = when {
        VersionComparator.isVersionAtLeast(version, "1.14.0") -> "template_1.14.json"
        VersionComparator.isVersionAtLeast(version, "1.12.0") -> "template_1.12.json"
        else -> "template_1.10.json"
    }
    return assets.open("templates/$templateName").bufferedReader().use { it.readText() }
}
```

---

## 六、常见问题

### Q1: 如何知道当前使用的是哪个版本？

在 `Application.kt` 中检查：
```kotlin
Log.d("Application", "Libbox version: ${ConfigOrchestrator.singBoxVersion}")
```

### Q2: 旧配置会报错吗？

不会。`migrateToVersion()` 会自动将旧格式迁移到新格式。

### Q3: 如何禁用自动迁移？

```kotlin
// 在 ConfigOrchestrator.kt 中设置
var skipMigration = false
```

### Q4: 新版本有破坏性变更怎么办？

查看官方迁移指南：https://sing-box.sagernet.org/migration/

---

## 七、升级操作流程

1. **获取新版本信息**
   - 查看 sing-box 官方 Release Notes
   - 阅读官方迁移指南

2. **修改配置文件**
   - 按本文档第二章的说明修改模板
   - 更新迁移逻辑

3. **运行测试**
   ```bash
   # 运行单元测试
   gradlew test

   # 构建 APK
   gradlew assembleDebug
   ```

4. **验证配置**
   - 使用 Logcat 检查版本检测日志
   - 手动测试代理功能

5. **更新文档**
   - 在本文档添加新的版本记录
   - 更新版本历史表

---

## 八、参考资源

- **Sing-Box 官方文档**：https://sing-box.sagernet.org/
- **迁移指南**：https://sing-box.sagernet.org/migration/
- **配置生成器**：https://sing-box.sagernet.org/configuration/
- **Sing-Box GitHub**：https://github.com/SagerNet/sing-box

---

**最后更新**：2024-06-11
**维护者**：Spider Team
**版本**：v1.0
