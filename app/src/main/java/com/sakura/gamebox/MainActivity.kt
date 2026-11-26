package com.sakura.gamebox

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sakura.gamebox.ocr.OcrState
import com.sakura.gamebox.ocr.ScreenCaptureService
import com.sakura.gamebox.service.FloatingWindowService
import com.sakura.gamebox.util.AccessibilityPermission
import com.sakura.gamebox.ui.theme.GameboxTheme

class MainActivity : ComponentActivity() {

    private var overlayGranted = mutableStateOf(false)
    private var accessibilityGranted = mutableStateOf(false)
    private var floatingRunning = mutableStateOf(false)
    private var targetPage = mutableStateOf(AppPage.Permission)
    private var showAccessibilityDialog = mutableStateOf(false)
    // 本次授权是否为「立刻截一帧」；App 启动时的常驻授权为 false。
    private var captureNowRequested = false
    // 本次进程内是否已发起过常驻授权，避免 onResume 重复弹窗。
    private var residentCaptureRequested = false

    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("MainActivity", "capture result: code=${result.resultCode}, data=${result.data}")
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_CAPTURE_NOW, captureNowRequested)
            }
            startForegroundService(intent)
            Toast.makeText(this, "OCR 截屏权限已就绪，点击 OCR 即可识别", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予截屏权限", Toast.LENGTH_SHORT).show()
        }
        captureNowRequested = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OcrState.init(this)
        targetPage.value = pageFromIntent(intent)
        enableEdgeToEdge()
        setContent {
            GameboxTheme {
                ControlPanel(
                    targetPage = targetPage.value,
                    overlayGranted = overlayGranted.value,
                    accessibilityGranted = accessibilityGranted.value,
                    floatingRunning = floatingRunning.value,
                    showAccessibilityDialog = showAccessibilityDialog.value,
                    onDismissAccessibilityDialog = { showAccessibilityDialog.value = false },
                    onConfirmAccessibilityDialog = {
                        showAccessibilityDialog.value = false
                        requestAccessibilityPermission()
                    },
                    onRequestOverlay = ::requestOverlayPermission,
                    onRequestAccessibility = ::requestAccessibilityPermission
                )
            }
        }
        // 冷启动时若无障碍权限曾开过却丢失（如强行停止 App），主动弹窗引导恢复；
        // debug 包会先尝试静默恢复，失败才走这里。
        AccessibilityPermission.ensureEnabledOnStart(this) {
            showAccessibilityDialog.value = true
        }
        refreshState()
        startFloatingServiceIfReady()
        guideFirstLaunch()
    }

    override fun onResume() {
        super.onResume()
        val wasGranted = accessibilityGranted.value
        refreshState()
        // 从设置页返回时发现无障碍权限被关闭，立即弹窗提示
        if (wasGranted && !accessibilityGranted.value) {
            showAccessibilityDialog.value = true
        }
        startFloatingServiceIfReady()
        // App 打开后请求一次录屏授权并常驻，之后点 OCR 不再弹窗。
        requestResidentCaptureIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetPage.value = pageFromIntent(intent)
    }

    private fun refreshState() {
        overlayGranted.value = Settings.canDrawOverlays(this)
        accessibilityGranted.value = AccessibilityPermission.isEnabled(this)
        floatingRunning.value = FloatingWindowService.isRunning
    }

    /** App 打开时请求一次录屏授权并让服务常驻；本次进程内只弹一次，之后点 OCR 直接复用。 */
    private fun requestResidentCaptureIfNeeded() {
        if (residentCaptureRequested || ScreenCaptureService.isRunning) return
        residentCaptureRequested = true
        captureNowRequested = false
        Log.d("MainActivity", "requestResidentCaptureIfNeeded: launching one-time authorization")
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermissionLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        Toast.makeText(this, "请开启「允许显示在其他应用上层」", Toast.LENGTH_LONG).show()
    }

    private fun requestAccessibilityPermission() {
        AccessibilityPermission.openSettings(this)
        Toast.makeText(this, "请找到「Gamebox 无障碍服务」并开启", Toast.LENGTH_LONG).show()
    }

    private fun guideFirstLaunch() {
        if (overlayGranted.value && accessibilityGranted.value) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PREFS_GUIDED_ONCE, false)) return
        prefs.edit().putBoolean(PREFS_GUIDED_ONCE, true).apply()
        if (!overlayGranted.value) {
            requestOverlayPermission()
        } else {
            requestAccessibilityPermission()
        }
    }

    private fun startFloatingServiceIfReady() {
        if (!overlayGranted.value || FloatingWindowService.isRunning) return
        startFloatingService()
    }

    private fun startFloatingService() {
        startForegroundService(Intent(this, FloatingWindowService::class.java))
        floatingRunning.value = true
    }

    private fun pageFromIntent(intent: Intent?): AppPage {
        return if (intent?.getStringExtra(EXTRA_TARGET_PAGE) == TARGET_PAGE_SETTINGS) {
            AppPage.Settings
        } else {
            AppPage.Permission
        }
    }

    companion object {
        const val EXTRA_TARGET_PAGE = "target_page"
        const val TARGET_PAGE_SETTINGS = "settings"
        private const val PREFS_NAME = "gamebox_first_launch"
        private const val PREFS_GUIDED_ONCE = "guided_once"
    }
}

@Composable
private fun ControlPanel(
    targetPage: AppPage,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    floatingRunning: Boolean,
    showAccessibilityDialog: Boolean,
    onDismissAccessibilityDialog: () -> Unit,
    onConfirmAccessibilityDialog: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit
) {
    var selectedPage by rememberSaveable { mutableStateOf(targetPage) }

    LaunchedEffect(targetPage) {
        selectedPage = targetPage
    }

    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = onDismissAccessibilityDialog,
            title = { Text("需要无障碍权限") },
            text = { Text("请前往系统设置开启「Gamebox 无障碍服务」后返回。") },
            confirmButton = { TextButton(onClick = onConfirmAccessibilityDialog) { Text("去开启") } },
            dismissButton = { TextButton(onClick = onDismissAccessibilityDialog) { Text("取消") } }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                AppPage.entries.forEach { page ->
                    NavigationBarItem(
                        selected = selectedPage == page,
                        onClick = { selectedPage = page },
                        icon = { Text(page.title) },
                        label = { }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedPage) {
            AppPage.Permission -> PermissionPage(
                modifier = Modifier.padding(innerPadding),
                overlayGranted = overlayGranted,
                accessibilityGranted = accessibilityGranted,
                floatingRunning = floatingRunning,
                onRequestOverlay = onRequestOverlay,
                onRequestAccessibility = onRequestAccessibility
            )

            AppPage.Settings -> SettingsPage(modifier = Modifier.padding(innerPadding))
        }
    }
}

enum class AppPage(val title: String) {
    Permission("权限"),
    Settings("设置")
}

@Composable
private fun PermissionPage(
    modifier: Modifier = Modifier,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    floatingRunning: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("权限", style = MaterialTheme.typography.headlineMedium)

        PermissionCard(
            title = "悬浮窗权限",
            granted = overlayGranted,
            buttonText = if (overlayGranted) "已授权" else "去授权",
            onClick = onRequestOverlay,
            enabled = !overlayGranted
        )

        PermissionCard(
            title = "无障碍权限",
            granted = accessibilityGranted,
            buttonText = if (accessibilityGranted) "已开启" else "去开启",
            onClick = onRequestAccessibility,
            enabled = !accessibilityGranted
        )

        Text(
            text = if (floatingRunning) "悬浮窗已运行" else "授权后会自动启动悬浮窗",
            style = MaterialTheme.typography.bodyMedium,
            color = if (floatingRunning)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!floatingRunning) {
            Text(
                text = "授权后会自动启动悬浮窗",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsPage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("当前游戏", style = MaterialTheme.typography.titleMedium)
                Text(
                    "天下归心",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    granted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (granted) "已就绪" else "未授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
            FilledTonalButton(onClick = onClick, enabled = enabled) {
                Text(buttonText)
            }
        }
    }
}

object GameboxTaskState {

    private val statusListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()

    /** 日志只存在于内存，不做持久化。展示达到该次数后自动清空一次。 */
    private const val MAX_STATUS_DISPLAYS = 1000
    private const val MAX_LOG_LINES = 200
    private var statusDisplayCount = 0
    private val statusHistory = ArrayDeque<String>()

    /** 悬浮窗下方展示的当前执行步骤文案。 */
    @Volatile
    var statusText: String = "空闲"
        private set

    /** 供日志面板展示的最近若干行（内存，不持久化）。 */
    fun getLogText(limit: Int = 12): String = synchronized(statusHistory) {
        if (statusHistory.isEmpty()) "暂无日志" else statusHistory.takeLast(limit).joinToString("\n")
    }

    fun setStatus(text: String) {
        if (statusText == text) return
        // 每展示 1000 次清空一次日志，避免长时间运行无限累积
        if (statusDisplayCount >= MAX_STATUS_DISPLAYS) {
            resetStatusLocked()
        }
        statusText = text
        statusDisplayCount += 1
        synchronized(statusHistory) {
            statusHistory.addLast(text)
            while (statusHistory.size > MAX_LOG_LINES) statusHistory.removeFirst()
        }
        statusListeners.forEach { it(text) }
    }

    /** 清空展示的日志（仅内存），并重置展示计数；启动时调用。 */
    fun clearStatus() {
        statusDisplayCount = 0
        synchronized(statusHistory) { statusHistory.clear() }
        if (statusText == "空闲") return
        resetStatusLocked()
    }

    private fun resetStatusLocked() {
        statusText = "空闲"
        synchronized(statusHistory) { statusHistory.clear() }
        statusListeners.forEach { it(statusText) }
    }

    fun addStatusListener(listener: (String) -> Unit) {
        statusListeners.add(listener)
        listener(statusText)
    }

    fun removeStatusListener(listener: (String) -> Unit) {
        statusListeners.remove(listener)
    }

    fun init(context: Context) {
        // 日志不持久化，每次启动都清空
        clearStatus()
    }
}