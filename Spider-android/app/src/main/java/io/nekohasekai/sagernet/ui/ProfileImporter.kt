package io.nekohasekai.sagernet.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import okhttp3.internal.closeQuietly
import java.util.zip.ZipInputStream

/**
 * 「把解析出来的节点落到导入目标分组」和「解析用户选的文件」这两件事，
 * **旧主页（`ConfigurationFragment`）和新主页（`TopologyFragment`）共用**。
 *
 * 为什么抽出来：文件那条路径要处理 WireGuard 的 `.zip`（逐 entry 试解析），三十多行；
 * 两边各抄一份必然漂移 —— 而「导入」正是最容易悄悄出错的环节（解析口径、目标分组、
 * 订阅链接的异常分流，任何一处不一致都会变成「同样的文件在 A 能导入、在 B 不能」）。
 *
 * 逻辑是从 `ConfigurationFragment` **原样搬过来**的（含 `.zip` 分支、`OpenableColumns`
 * 取文件名、异常口径），唯一改动是把 `requireContext()` 换成传入的 [Context]。
 */
object ProfileImporter {

    /**
     * 把 [proxies] 写进「导入目标分组」，返回该分组 id。
     *
     * 目标用 [DataStore.selectedGroupForImport] 而**不是** `currentGroup()`：
     * 当前分组是订阅组时，订阅组是只读的，新节点必须落到**第一个普通分组** ——
     * 这是项目既有约定，`ConfigurationFragment:397` / `MainActivity:244` /
     * `ProfileSettingsActivity:108` / `ScannerActivity:124` 四处都是同一口径。
     *
     * 调用方负责在**后台线程**调它（内部有数据库写）。
     */
    suspend fun import(proxies: List<AbstractBean>): Long {
        val targetId = DataStore.selectedGroupForImport()
        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher { DataStore.editingGroup = targetId }
        return targetId
    }

    /**
     * 解析用户选的文件，返回识别出的节点（一个都没识别出来就是空表）。
     *
     * `.zip` 按 WireGuard 配置包处理（逐 entry 试解析），其余当纯文本喂给
     * [RawUpdater.parseRaw]。
     *
     * ⚠ 解析出**订阅链接**时会抛 [io.nekohasekai.sagernet.ktx.SubscriptionFoundException]，
     * 这里**故意不吞** —— 那是「导入订阅」而不是「导入节点」，得由调用方走
     * `MainActivity.importSubscription()`。吞掉的话用户选了个订阅链接会得到
     * 「没找到节点」，完全误导。
     *
     * 调用方负责在**后台线程**调它（要读 ContentResolver 和文件流）。
     */
    suspend fun parseFile(context: Context, uri: Uri): List<AbstractBean> {
        val fileName = context.contentResolver.query(uri, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }

        val proxies = mutableListOf<AbstractBean>()
        if (fileName != null && fileName.endsWith(".zip")) {
            // try parse wireguard zip
            val zip = ZipInputStream(context.contentResolver.openInputStream(uri)!!)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val fileText = zip.bufferedReader().readText()
                RawUpdater.parseRaw(fileText, entry.name)?.let { pl -> proxies.addAll(pl) }
                zip.closeEntry()
            }
            zip.closeQuietly()
        } else {
            val fileText = context.contentResolver.openInputStream(uri)!!.use {
                it.bufferedReader().readText()
            }
            RawUpdater.parseRaw(fileText, fileName ?: "")?.let { pl -> proxies.addAll(pl) }
        }
        return proxies
    }
}
