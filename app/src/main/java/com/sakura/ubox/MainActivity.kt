package com.sakura.ubox

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sakura.ubox.act.SwipeMode
import com.sakura.ubox.ocr.OcrAction
import com.sakura.ubox.ocr.OcrRule
import com.sakura.ubox.ocr.OcrState
import com.sakura.ubox.ocr.ScreenCaptureService
import com.sakura.ubox.service.FloatingWindowService
import com.sakura.ubox.util.AccessibilityPermission
import com.sakura.ubox.ui.theme.UBoxTheme

class MainActivity : ComponentActivity() {

    private var overlayGranted = mutableStateOf(false)
    private var accessibilityGranted = mutableStateOf(false)
    private var floatingRunning = mutableStateOf(false)
    private var targetPage = mutableStateOf(AppPage.Permission)
    private var showAccessibilityDialog = mutableStateOf(false)
    private var taskRequestedBeforePermission = mutableStateOf(false)
    private var ocrCapturing = mutableStateOf(false)
    private var ocrRules = mutableStateListOf<OcrRule>()
    private val ocrStateListener = { ocrRules.apply { clear(); addAll(OcrState.rules) }; Unit }

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
                putExtra(ScreenCaptureService.EXTRA_SINGLE_SHOT, captureNowRequested)
            }
            startForegroundService(intent)
            ocrCapturing.value = true
            Toast.makeText(
                this,
                if (captureNowRequested) "OCR 截屏已启动" else "OCR 截屏权限已就绪，之后可直接识别",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, "未授予截屏权限", Toast.LENGTH_SHORT).show()
        }
        captureNowRequested = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OcrState.init(this)
        ocrStateListener()
        OcrState.addListener(ocrStateListener)
        targetPage.value = pageFromIntent(intent)
        enableEdgeToEdge()
        setContent {
            UBoxTheme {
                var taskRunning by remember { mutableStateOf(UBoxTaskState.isRunning) }
                var ocrEnabled by remember { mutableStateOf(OcrState.isEnabled) }
                DisposableEffect(Unit) {
                    val listener: (Boolean) -> Unit = { taskRunning = it }
                    UBoxTaskState.addListener(listener)
                    onDispose { UBoxTaskState.removeListener(listener) }
                }
                ControlPanel(
                    targetPage = targetPage.value,
                    overlayGranted = overlayGranted.value,
                    accessibilityGranted = accessibilityGranted.value,
                    floatingRunning = floatingRunning.value,
                    taskRunning = taskRunning,
                    showAccessibilityDialog = showAccessibilityDialog.value,
                    onDismissAccessibilityDialog = { showAccessibilityDialog.value = false },
                    onConfirmAccessibilityDialog = {
                        showAccessibilityDialog.value = false
                        requestAccessibilityPermission()
                    },
                    onRequestOverlay = ::requestOverlayPermission,
                    onRequestAccessibility = ::requestAccessibilityPermission,
                    onToggleTask = ::onToggleTaskRequested,
                    ocrEnabled = ocrEnabled,
                    ocrCapturing = ocrCapturing.value,
                    onToggleOcr = { requested ->
                        ocrEnabled = requested
                        OcrState.setEnabled(requested)
                        if (!requested) stopCaptureService()
                    },
                    onStartCapture = ::requestCapturePermission,
                    onAddOcrRule = { rule -> OcrState.setRules(OcrState.rules + rule) },
                    onRemoveOcrRule = { id -> OcrState.setRules(OcrState.rules.filterNot { it.id == id }) },
                    ocrRules = ocrRules
                )
            }
        }
        refreshState()
        startFloatingServiceIfReady()
        guideFirstLaunch()
    }

    override fun onDestroy() {
        OcrState.removeListener(ocrStateListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val wasGranted = accessibilityGranted.value
        refreshState()
        // 从设置页返回时发现无障碍权限被关闭，立即弹窗提示
        if (wasGranted && !accessibilityGranted.value) {
            showAccessibilityDialog.value = true
        }
        // 之前在权限缺失时点过「启动」，已回来授权则自动继续启动
        if (taskRequestedBeforePermission.value && accessibilityGranted.value) {
            taskRequestedBeforePermission.value = false
            UBoxTaskState.setRunning(true)
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
        ocrCapturing.value = ScreenCaptureService.isRunning
    }

    private fun requestCapturePermission() {
        Log.d("MainActivity", "requestCapturePermission: resident=${ScreenCaptureService.isRunning}")
        if (ScreenCaptureService.isRunning) {
            // 投影已常驻，直接截一帧识别，不再弹授权框。
            startForegroundService(
                Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_CAPTURE
                }
            )
            return
        }
        captureNowRequested = true
        residentCaptureRequested = true
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermissionLauncher.launch(pm.createScreenCaptureIntent())
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

    private fun stopCaptureService() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
        ocrCapturing.value = false
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
        Toast.makeText(this, "请找到「UBox 无障碍服务」并开启", Toast.LENGTH_LONG).show()
    }

    /** 悬浮窗/主界面点「启动」时统一走这里：缺少无障碍权限则弹窗引导。 */
    private fun onToggleTaskRequested() {
        if (UBoxTaskState.isRunning) {
            UBoxTaskState.setRunning(false)
            return
        }
        refreshState()
        if (!accessibilityGranted.value) {
            taskRequestedBeforePermission.value = true
            showAccessibilityDialog.value = true
            return
        }
        UBoxTaskState.setRunning(true)
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
        private const val PREFS_NAME = "ubox_first_launch"
        private const val PREFS_GUIDED_ONCE = "guided_once"
    }
}

@Composable
private fun ControlPanel(
    targetPage: AppPage,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    floatingRunning: Boolean,
    taskRunning: Boolean,
    showAccessibilityDialog: Boolean,
    onDismissAccessibilityDialog: () -> Unit,
    onConfirmAccessibilityDialog: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onToggleTask: () -> Unit,
    ocrEnabled: Boolean,
    ocrCapturing: Boolean,
    onToggleOcr: (Boolean) -> Unit,
    onStartCapture: () -> Unit,
    onAddOcrRule: (OcrRule) -> Unit,
    onRemoveOcrRule: (String) -> Unit,
    ocrRules: List<OcrRule>
) {
    var selectedPage by rememberSaveable { mutableStateOf(targetPage) }
    var intervalSeconds by rememberSaveable { mutableStateOf(UBoxTaskState.intervalSeconds.toString()) }
    var pollCount by rememberSaveable { mutableStateOf(UBoxTaskState.pollCount.toString()) }
    var swipeMode by remember { mutableStateOf(UBoxTaskState.swipeMode) }
    var modeDropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(targetPage) {
        selectedPage = targetPage
    }

    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = onDismissAccessibilityDialog,
            title = { Text("需要无障碍权限") },
            text = { Text("UBox 需要无障碍权限才能自动滑动。请前往设置开启「UBox 无障碍服务」后返回。") },
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
                taskRunning = taskRunning,
                onRequestOverlay = onRequestOverlay,
                onRequestAccessibility = onRequestAccessibility,
                onToggleTask = onToggleTask
            )

            AppPage.Settings -> SettingsPage(
                modifier = Modifier.padding(innerPadding),
                intervalSeconds = intervalSeconds,
                pollCount = pollCount,
                swipeMode = swipeMode,
                expanded = modeDropdownExpanded,
                onExpandedChange = { modeDropdownExpanded = it },
                onSwipeModeChange = { mode ->
                    swipeMode = mode
                    UBoxTaskState.setSwipeMode(mode)
                },
                onIntervalSecondsChange = { value ->
                    if (value.isEmpty() || value.all(Char::isDigit)) {
                        intervalSeconds = value
                        value.toLongOrNull()?.let(UBoxTaskState::setIntervalSeconds)
                    }
                },
                onPollCountChange = { value ->
                    if (value.isEmpty() || value.all(Char::isDigit)) {
                        pollCount = value
                        value.toIntOrNull()?.let(UBoxTaskState::setPollCount)
                    }
                },
                ocrEnabled = ocrEnabled,
                ocrCapturing = ocrCapturing,
                onToggleOcr = onToggleOcr,
                onStartCapture = onStartCapture,
                onAddOcrRule = onAddOcrRule,
                onRemoveOcrRule = onRemoveOcrRule,
                ocrRules = ocrRules
            )
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
    taskRunning: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onToggleTask: () -> Unit
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

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onToggleTask,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (taskRunning) "暂停" else "启动")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    modifier: Modifier = Modifier,
    intervalSeconds: String,
    pollCount: String,
    swipeMode: SwipeMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSwipeModeChange: (SwipeMode) -> Unit,
    onIntervalSecondsChange: (String) -> Unit,
    onPollCountChange: (String) -> Unit,
    ocrEnabled: Boolean,
    ocrCapturing: Boolean,
    onToggleOcr: (Boolean) -> Unit,
    onStartCapture: () -> Unit,
    onAddOcrRule: (OcrRule) -> Unit,
    onRemoveOcrRule: (String) -> Unit,
    ocrRules: List<OcrRule>
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange
        ) {
            OutlinedTextField(
                value = swipeMode.label,
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                readOnly = true,
                label = { Text("滑动模式") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                SwipeMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        onClick = {
                            onSwipeModeChange(mode)
                            onExpandedChange(false)
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        OutlinedTextField(
            value = intervalSeconds,
            onValueChange = onIntervalSecondsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("时间间隔") },
            suffix = { Text("秒") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        OutlinedTextField(
            value = pollCount,
            onValueChange = onPollCountChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("轮询次数") },
            suffix = { Text("次") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        HorizontalDivider()
        Text("OCR 游戏辅助", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = ocrEnabled, onCheckedChange = onToggleOcr)
            Spacer(Modifier.width(8.dp))
            Text("启用规则自动操作")
        }
        Button(onClick = onStartCapture, modifier = Modifier.fillMaxWidth()) {
            Text(if (ocrCapturing) "重新请求截屏权限" else "授权截屏并开始识别")
        }
        OcrRulesSection(
            rules = ocrRules,
            onAddOcrRule = onAddOcrRule,
            onRemoveOcrRule = onRemoveOcrRule
        )
    }
}

@Composable
private fun OcrRulesSection(
    rules: List<OcrRule>,
    onAddOcrRule: (OcrRule) -> Unit,
    onRemoveOcrRule: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }

    Text("识别规则", style = MaterialTheme.typography.titleSmall)
    rules.forEach { rule ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "关键词：${rule.keywords.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { onRemoveOcrRule(rule.id) }) { Text("删除") }
        }
    }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("规则名称") },
        singleLine = true
    )
    OutlinedTextField(
        value = keywords,
        onValueChange = { keywords = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("关键词（逗号分隔）") },
        singleLine = true
    )
    Button(
        onClick = {
            val keywordList = keywords.split("，", ",").map { it.trim() }.filter { it.isNotEmpty() }
            if (name.isBlank() || keywordList.isEmpty()) return@Button
            onAddOcrRule(
                OcrRule(
                    id = "rule_${System.currentTimeMillis()}",
                    name = name.trim(),
                    keywords = keywordList,
                    action = OcrAction.Click(0f, 0f)
                )
            )
            name = ""
            keywords = ""
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("添加规则（命中后点击文字）")
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

object UBoxTaskState {

    private const val PREFS_NAME = "ubox_task_settings"
    private const val KEY_INTERVAL_SECONDS = "interval_seconds"
    private const val KEY_POLL_COUNT = "poll_count"
    private const val KEY_SWIPE_MODE = "swipe_mode"

    private var prefs: SharedPreferences? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var intervalSeconds: Long = com.sakura.ubox.act.read_vedio.DEFAULT_INTERVAL_SECONDS
        private set

    @Volatile
    var pollCount: Int = com.sakura.ubox.act.read_vedio.DEFAULT_POLL_COUNT
        private set

    @Volatile
    var swipeMode: SwipeMode = SwipeMode.CENTER_UP
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Boolean) -> Unit>()
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
        val stored = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = stored
        // 日志不持久化，每次启动都清空
        clearStatus()
        intervalSeconds = stored.getLong(KEY_INTERVAL_SECONDS, intervalSeconds).coerceAtLeast(1L)
        pollCount = stored.getInt(KEY_POLL_COUNT, pollCount).coerceAtLeast(1)
        val modeName = stored.getString(KEY_SWIPE_MODE, swipeMode.name)
        swipeMode = SwipeMode.entries.firstOrNull { it.name == modeName } ?: swipeMode
    }

    fun toggle() {
        setRunning(!isRunning)
    }

    fun setRunning(running: Boolean) {
        if (isRunning == running) return
        isRunning = running
        listeners.forEach { it(running) }
    }

    fun setIntervalSeconds(seconds: Long) {
        val value = seconds.coerceAtLeast(1L)
        if (value == intervalSeconds) return
        intervalSeconds = value
        prefs?.edit()?.putLong(KEY_INTERVAL_SECONDS, value)?.apply()
    }

    fun setPollCount(count: Int) {
        val value = count.coerceAtLeast(1)
        if (value == pollCount) return
        pollCount = value
        prefs?.edit()?.putInt(KEY_POLL_COUNT, value)?.apply()
    }

    fun setSwipeMode(mode: SwipeMode) {
        if (mode == swipeMode) return
        swipeMode = mode
        prefs?.edit()?.putString(KEY_SWIPE_MODE, mode.name)?.apply()
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        listener(isRunning)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}