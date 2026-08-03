package com.sakura.ubox

import android.content.ComponentName
import android.content.Intent
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
import com.sakura.ubox.service.UBoxAccessibilityService
import com.sakura.ubox.ui.theme.UBoxTheme

class MainActivity : ComponentActivity() {

    private var overlayGranted = mutableStateOf(false)
    private var accessibilityGranted = mutableStateOf(false)
    private var floatingRunning = mutableStateOf(false)
    private var targetPage = mutableStateOf(AppPage.Permission)
    private var overlayRequestLaunched = false
    private var accessibilityRequestLaunched = false

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
                    onRequestOverlay = ::requestOverlayPermission,
                    onRequestAccessibility = ::requestAccessibilityPermission,
                    onToggleTask = { UBoxTaskState.toggle() }
                )
            }
        }
        refreshState()
        requestOverlayIfNeeded()
        requestAccessibilityIfNeeded()
        startFloatingServiceIfReady()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        requestOverlayIfNeeded()
        requestAccessibilityIfNeeded()
        startFloatingServiceIfReady()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetPage.value = pageFromIntent(intent)
    }

    private fun refreshState() {
        overlayGranted.value = Settings.canDrawOverlays(this)
        accessibilityGranted.value = isAccessibilityServiceEnabled()
        floatingRunning.value = FloatingWindowService.isRunning
    }

    private fun requestOverlayIfNeeded() {
        if (overlayGranted.value || overlayRequestLaunched) return
        overlayRequestLaunched = true
        requestOverlayPermission()
    }

    private fun requestAccessibilityIfNeeded() {
        if (accessibilityGranted.value || accessibilityRequestLaunched) return
        accessibilityRequestLaunched = true
        requestAccessibilityPermission()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedName = ComponentName(this, UBoxAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').any { it.equals(expectedName, ignoreCase = true) }
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
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "请找到「UBox 无障碍服务」并开启", Toast.LENGTH_LONG).show()
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
    }
}

@Composable
private fun ControlPanel(
    targetPage: AppPage,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    floatingRunning: Boolean,
    taskRunning: Boolean,
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

    fun toggle() {
        setRunning(!isRunning)
    }

    fun setRunning(running: Boolean) {
        if (isRunning == running) return
        isRunning = running
        listeners.forEach { it(running) }
    }

    fun setIntervalSeconds(seconds: Long) {
        intervalSeconds = seconds.coerceAtLeast(1L)
    }

    fun setPollCount(count: Int) {
        pollCount = count.coerceAtLeast(1)
    }

    fun setSwipeMode(mode: SwipeMode) {
        swipeMode = mode
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        listener(isRunning)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}