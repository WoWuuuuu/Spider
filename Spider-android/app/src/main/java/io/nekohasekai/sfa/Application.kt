package io.nekohasekai.sfa

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.sfa.bg.AppChangeReceiver
import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.constant.Bugs
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.HookModuleUpdateNotifier
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.utils.PrivilegeSettingsClient
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import io.nekohasekai.sfa.Application as BoxApplication

class Application : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()
        AppLifecycleObserver.register(this)

        // Inject libbox checkConfig to ConfigOrchestrator for validation
        io.nekohasekai.sfa.bg.ConfigOrchestrator.configValidator = { configStr ->
            io.nekohasekai.libbox.Libbox.checkConfig(configStr)
        }

        // singBoxVersion is resolved after Libbox.setup() in initialize().
        // ConfigOrchestrator lazy-detects via Libbox.version() until then.

//        Seq.setContext(this)
        runCatching {
            Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))
        }.onFailure {
            Log.d("Application", "set locale: ${it.message}")
        }
        HookStatusClient.register(this)
        PrivilegeSettingsClient.register(this)

        val baseDir = filesDir
        baseDir.mkdirs()
        val workingDir = getExternalFilesDir(null)
        val tempDir = cacheDir
        tempDir.mkdirs()
        if (workingDir != null) {
            workingDir.mkdirs()
            CrashReportManager.install(workingDir, baseDir)
            OOMReportManager.install(workingDir)
        }

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            initialize(baseDir, workingDir, tempDir)
            UpdateProfileWork.reconfigureUpdater()
            HookModuleUpdateNotifier.sync(this@Application)
        }

        if (Vendor.isPerAppProxyAvailable()) {
            registerReceiver(
                AppChangeReceiver(),
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                },
            )
        }
    }

    private fun resolveSingBoxVersion(): String {
        runCatching { Libbox.version() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { version ->
                Log.d("Application", "Libbox.version(): $version")
                return version
            }

        // Hiddify 风格：setup 后通过 checkConfig 试探新 DNS 格式
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
            Log.w("Application", "Version probe failed, defaulting to 1.12.0", e)
            "1.12.0"
        }
    }

    private fun initialize(baseDir: File, workingDir: File?, tempDir: File) {
        val actualWorkingDir = workingDir ?: return
        setupLibbox(baseDir, actualWorkingDir, tempDir)
        io.nekohasekai.sfa.bg.ConfigOrchestrator.singBoxVersion = resolveSingBoxVersion()
        Log.d("Application", "Libbox version detected: ${io.nekohasekai.sfa.bg.ConfigOrchestrator.singBoxVersion}")
    }

    fun reloadSetupOptions() {
        val baseDir = filesDir
        val workingDir = getExternalFilesDir(null) ?: return
        val tempDir = cacheDir
        Libbox.reloadSetupOptions(createSetupOptions(baseDir, workingDir, tempDir))
    }

    private fun setupLibbox(baseDir: File, workingDir: File, tempDir: File) {
        Libbox.setup(createSetupOptions(baseDir, workingDir, tempDir))
    }

    private fun createSetupOptions(baseDir: File, workingDir: File, tempDir: File): SetupOptions = SetupOptions().also {
        it.basePath = baseDir.path
        it.workingPath = workingDir.path
        it.tempPath = tempDir.path
        it.fixAndroidStack = Bugs.fixAndroidStack
        it.logMaxLines = 3000
        it.debug = BuildConfig.DEBUG
        it.crashReportSource = "Application"
        it.oomKillerEnabled = Settings.oomKillerEnabled
        it.oomKillerDisabled = Settings.oomKillerDisabled
        it.oomMemoryLimit = Settings.oomMemoryLimitMB.toLong() * 1024L * 1024L
    }

    companion object {
        lateinit var application: BoxApplication
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
        val wifi by lazy { application.getSystemService<WifiManager>()!! }
        val power by lazy { application.getSystemService<PowerManager>()!! }
        val packageManager by lazy { application.packageManager }
        
        // Aliases for compatibility
        val notificationManager get() = notification
        val wifiManager get() = wifi
        val powerManager get() = power
    }
}