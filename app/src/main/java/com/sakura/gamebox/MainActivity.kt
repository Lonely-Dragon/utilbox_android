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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.sakura.gamebox.net.ScriptConfigApi
import com.sakura.gamebox.net.ScriptBrief
import com.sakura.gamebox.net.ScriptConfigStore
import com.sakura.gamebox.net.ScriptRuleParser
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
        ScriptConfigStore.init(this)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(modifier: Modifier = Modifier) {
    var scripts by remember { mutableStateOf<List<ScriptBrief>>(ScriptConfigStore.scripts) }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember {
        val savedId = ScriptConfigStore.selectedScriptId
        mutableStateOf(ScriptConfigStore.scripts.firstOrNull { it.id == savedId })
    }
    var fetchResult by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf(false) }
    // 「确定/解锁」按钮状态：true=已确定脚本（红色解锁），false=未确定（蓝色确定）
    var scriptApplied by remember { mutableStateOf(ScriptConfigStore.scriptApplied) }
    var appliedName by remember { mutableStateOf(ScriptConfigStore.appliedScriptName) }
    var activationCode by remember { mutableStateOf(ScriptConfigStore.activationCode) }
    var tokenResult by remember { mutableStateOf("") }
    var tokenLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        fetchResult = "请输入激活码后点击「获取」加载脚本"
    }

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

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("激活码", style = MaterialTheme.typography.titleMedium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = activationCode,
                        onValueChange = {
                            activationCode = it
                            ScriptConfigStore.activationCode = it
                        },
                        singleLine = true,
                        label = { Text("激活码") },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val code = activationCode.trim()
                            if (code.isEmpty()) {
                                tokenResult = "请输入激活码"
                                return@Button
                            }
                            tokenLoading = true
                            tokenResult = "查询中..."
                            scope.launch {
                                runCatching { ScriptConfigApi.getTokenRemaining(code) }
                                    .onSuccess { token ->
                                        tokenResult = if (token.expired) {
                                            "已过期（剩余 ${token.remaining}）"
                                        } else {
                                            "剩余时间：${token.remaining}"
                                        }
                                    }
                                    .onFailure { tokenResult = "查询失败：${it.message}" }
                                tokenLoading = false
                            }
                        },
                        enabled = !tokenLoading
                    ) {
                        Text(if (tokenLoading) "查询中" else "测试")
                    }
                }

                if (tokenResult.isNotBlank()) {
                    Text(
                        tokenResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("脚本", style = MaterialTheme.typography.titleMedium)

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selected?.name ?: "未选择脚本",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("脚本") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        scripts.forEach { script ->
                            DropdownMenuItem(
                                text = { Text(script.name) },
                                onClick = {
                                    selected = script
                                    ScriptConfigStore.selectedScriptId = script.id
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    Button(
                        onClick = {
                            val code = activationCode.trim()
                            if (code.isEmpty()) {
                                fetchResult = "请先在上方输入激活码"
                                return@Button
                            }
                            loading = true
                            fetchResult = "获取中..."
                            scope.launch {
                                runCatching { ScriptConfigApi.listScripts(code, "天下归心") }
                                    .onSuccess { list ->
                                        scripts = list
                                        ScriptConfigStore.scripts = list
                                        if (list.isEmpty()) {
                                            fetchResult = "暂无可用脚本"
                                        } else {
                                            val target = selected?.takeIf { s -> list.any { it.id == s.id } }
                                                ?: list.first()
                                            selected = target
                                            ScriptConfigStore.selectedScriptId = target.id
                                            fetchResult = "已获取 ${list.size} 个脚本，选中「${target.name}」"
                                        }
                                    }
                                    .onFailure { fetchResult = "获取失败：${it.message}" }
                                loading = false
                            }
                        },
                        enabled = activationCode.isNotBlank() && !loading && !unlocking
                    ) {
                        Text(if (loading) "获取中..." else "获取")
                    }

                    Button(
                        onClick = {
                            if (scriptApplied) {
                                // 解锁：清空已落实的脚本规则（不恢复内置默认规则）。
                                // 仅当脚本正在运行时才下发停止命令，避免启动一个没有投影的空前台服务。
                                if (ScreenCaptureService.isScriptRunning) {
                                    ScreenCaptureService.stopScript(context)
                                }
                                OcrState.setRules(emptyList())
                                ScriptConfigStore.scriptApplied = false
                                ScriptConfigStore.appliedScriptName = ""
                                scriptApplied = false
                                appliedName = ""
                                fetchResult = "已解锁：脚本规则已删除"
                                return@Button
                            }
                            val code = activationCode.trim()
                            if (code.isEmpty()) {
                                fetchResult = "请先输入激活码"
                                return@Button
                            }
                            val script = selected
                            if (script == null) {
                                fetchResult = "请先获取并选择脚本"
                                return@Button
                            }
                            unlocking = true
                            fetchResult = "确定中..."
                            scope.launch {
                                runCatching { ScriptConfigApi.getConfig(script.id, code) }
                                    .onSuccess { config ->
                                        val rules = ScriptRuleParser.parse(config.rules)
                                        // 落实脚本规则并持久化，悬浮窗「开始」按钮将驱动这套规则。
                                        OcrState.setRules(rules)
                                        ScriptConfigStore.scriptApplied = true
                                        ScriptConfigStore.appliedScriptName = config.name
                                        ScriptConfigStore.pollIntervalMs = config.pollInterval.toLong() * 1000L
                                        ScriptConfigStore.loopDelayMs = config.loopDelay.toLong()
                                        scriptApplied = true
                                        appliedName = config.name
                                        fetchResult = "已确定「${config.name}」：${rules.size} 条规则"
                                    }
                                    .onFailure { fetchResult = "确定失败：${it.message}" }
                                unlocking = false
                            }
                        },
                        enabled = (if (scriptApplied) true
                                   else activationCode.isNotBlank() && selected != null) && !unlocking && !loading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (scriptApplied) Color(0xFFE53935) else Color(0xFF1E88E5)
                        )
                    ) {
                        Text(if (unlocking) "处理中..." else if (scriptApplied) "解锁" else "确定")
                    }
                }

                if (scriptApplied && appliedName.isNotBlank()) {
                    Text(
                        "已确定脚本：$appliedName（悬浮窗点「开始」运行）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (fetchResult.isNotBlank()) {
                    Text(
                        fetchResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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