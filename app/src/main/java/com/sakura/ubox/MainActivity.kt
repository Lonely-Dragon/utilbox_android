package com.sakura.ubox

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPage.value = pageFromIntent(intent)
        enableEdgeToEdge()
        setContent {
            UBoxTheme {
                var taskRunning by remember { mutableStateOf(UBoxTaskState.isRunning) }
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
                    onToggleTask = ::onToggleTaskRequested
                )
            }
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
        // 之前在权限缺失时点过「启动」，已回来授权则自动继续启动
        if (taskRequestedBeforePermission.value && accessibilityGranted.value) {
            taskRequestedBeforePermission.value = false
            UBoxTaskState.setRunning(true)
        }
        startFloatingServiceIfReady()
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
    onToggleTask: () -> Unit
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
                }
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
    onPollCountChange: (String) -> Unit
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
    private var statusDisplayCount = 0

    /** 悬浮窗下方展示的当前执行步骤文案。 */
    @Volatile
    var statusText: String = "空闲"
        private set

    fun setStatus(text: String) {
        if (statusText == text) return
        // 每展示 1000 次清空一次日志，避免长时间运行无限累积
        if (statusDisplayCount >= MAX_STATUS_DISPLAYS) {
            resetStatusLocked()
        }
        statusText = text
        statusDisplayCount += 1
        statusListeners.forEach { it(text) }
    }

    /** 清空展示的日志（仅内存），并重置展示计数；启动时调用。 */
    fun clearStatus() {
        statusDisplayCount = 0
        if (statusText == "空闲") return
        resetStatusLocked()
    }

    private fun resetStatusLocked() {
        statusText = "空闲"
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