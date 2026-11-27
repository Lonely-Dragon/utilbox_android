package com.sakura.gamebox.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.sakura.gamebox.MainActivity
import com.sakura.gamebox.R
import com.sakura.gamebox.GameboxTaskState
import com.sakura.gamebox.ocr.CapturePermissionActivity
import com.sakura.gamebox.ocr.ScreenCaptureService
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var statusParams: WindowManager.LayoutParams
    private var floatingView: View? = null
    private var radialMenu: RadialMenuView? = null
    private var statusView: TextView? = null
    private var logPanelView: TextView? = null
    private var logPanelParams: WindowManager.LayoutParams? = null
    private var logPanelShown = false

    // 主绘制窗口设为不可触摸，只有下面这些小触摸窗口所在的区域才会拦截事件；
    // 其余空白处没有窗口，点击自然穿透到底下的 App。
    private var bubbleTouchView: OverlayTouchView? = null
    private var bubbleTouchParams: WindowManager.LayoutParams? = null
    private val actionTouchViews = mutableListOf<View>()
    private val actionTouchParams = mutableListOf<WindowManager.LayoutParams>()

    private var expanded = false

    /** 气泡在屏幕上的中心（浮点保存，避免整数取整在反复展开时累积漂移）。这是唯一锚点。 */
    private var bubbleCenterX = 0f
    private var bubbleCenterY = 0f

    /**
     * 主窗口在屏幕上的「实际」原点。
     * 系统（尤其 MIUI）会把悬浮窗钳制在屏幕内，因此 layoutParams.x/y 只是请求值，
     * 真实位置必须以布局后的 getLocationOnScreen 为准，否则触摸热区会错位。
     */
    private var windowOriginX = 0f
    private var windowOriginY = 0f

    /**
     * 悬浮窗「请求坐标」与「真实屏幕坐标」之间的固定平移量（inset）。
     *
     * 横屏 + 挖孔屏下 display 的 overlay parent 区域不是从 (0,0) 开始（本机 parent=[104,104]），
     * 于是 layoutParams.x=56 的主窗口实际落在屏幕 x=160，layoutParams.x=283 的触摸窗
     * 实际落在 x=387。也就是说**每个** TYPE_APPLICATION_OVERLAY 窗口都会被系统平移 inset。
     *
     * 气泡是画在主窗口内部的（跟随主窗口平移，位置正确），但触摸窗是按「屏幕坐标」
     * 直接摆的，若不减去 inset，热区就会整体偏离 (104,104)——这正是
     * 「气泡看得见却点不到」的最后一段残差。inset 由主窗口实测得出：
     * inset = getLocationOnScreen() - layoutParams.x/y。
     */
    private var overlayInsetX = 0f
    private var overlayInsetY = 0f

    /**
     * 用于「多次收敛校正」与「原点漂移看门狗」。
     * 顶层窗口 View 的 layout 在自己窗口内恒为 (0,0)，addOnLayoutChangeListener
     * 在窗口被系统钳制/移动时不会回调，因此不能依赖它感知位置变化。
     */
    private val syncHandler = Handler(Looper.getMainLooper())

    /** 一次性收敛校正的延时序列：系统钳制发生在 addView 之后的若干帧里。 */
    private val syncDelays = longArrayOf(0L, 50L, 120L, 300L, 600L, 1000L)

    /** 原点漂移看门狗：持续低频率核对系统真实原点，发现漂移立即重新对齐。 */
    private val originWatchdog = object : Runnable {
        override fun run() {
            val view = floatingView
            if (view == null) return
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            if (abs(loc[0] - windowOriginX) > 0.5f || abs(loc[1] - windowOriginY) > 0.5f) {
                syncTouchViews()
            }
            syncHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var dragThreshold = 0
    private var bubbleDragging = false

    /** 主按钮状态变化时同步状态条文案与显隐。 */
    private val statusListener: (String) -> Unit = { text ->
        statusView?.post {
            statusView?.text = text
            refreshStatusView()
        }
        logPanelView?.post { refreshLogPanelText() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        GameboxTaskState.addStatusListener(statusListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (floatingView == null) addFloatingView()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        GameboxTaskState.removeStatusListener(statusListener)
        removeFloatingView()
        isRunning = false
        super.onDestroy()
    }

    private fun addFloatingView() {
        dragThreshold = dp(8)

        val menu = RadialMenuView(this)
        radialMenu = menu
        menu.applyExpansion(expanded)

        // 窗口尺寸恒定：始终为展开尺寸，绝不在展开/收起时 resize。
        // （改变 WindowManager 窗口尺寸会让 gravity 窗口先闪到左上角再移到目标位置。）
        // 主按钮绘制在窗口正中，因此只要窗口不动，主按钮的屏幕位置就永远不变。
        val fixedSize = menu.expandedSize
        layoutParams = WindowManager.LayoutParams(
            fixedSize,
            fixedSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 绘制窗口只负责显示，绝不能拦截触摸；否则整块透明区域都会吃掉点击。
            // 实际的点击/拖动由下面独立的小触摸窗口处理，只覆盖气泡和按钮本身。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        // 气泡中心作为唯一锚点（浮点），拖动时从它推导窗口位置
        bubbleCenterX = layoutParams.x + fixedSize / 2f
        bubbleCenterY = layoutParams.y + fixedSize / 2f
        positionWindowForBubble()

        floatingView = menu
        windowManager.addView(menu, layoutParams)
        addTouchViews(menu)
        addStatusView(menu)
        addLogPanel()
        // 注意：顶层窗口 View 在自己窗口内的 layout 恒为 (0,0)，窗口被系统钳制/移动时
        // addOnLayoutChangeListener 不会回调，因此这里不能用它来感知位置变化。
        // 改用「延时多次收敛 + 原点漂移看门狗」。
        scheduleSyncTouchViews()
        startOriginWatchdog()
    }

    /** 只覆盖气泡本身的透明触摸窗口：负责展开/收起和拖动。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchViews(menu: RadialMenuView) {
        val bubble = OverlayTouchView(this)
        bubbleTouchParams = overlayTouchParams(menu.centerDiameter).apply {
            x = (bubbleCenterX - menu.centerDiameter / 2f).toInt()
            y = (bubbleCenterY - menu.centerDiameter / 2f).toInt()
        }
        bubble.setOnTouchListener { _, event ->
            handleBubbleTouch(event)
        }
        bubbleTouchView = bubble
        runCatching { windowManager.addView(bubble, bubbleTouchParams) }

        // 两个按钮各自的透明触摸窗口，仅展开时存在
        rebuildActionTouchViews(menu)
    }

    private fun overlayTouchParams(size: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    /**
     * 按气泡/按钮在窗口内的实时位置刷新各触摸窗口的位置。
     * 窗口 #1 主按钮: 中心 = 窗口真实原点 + 窗口中心 + 绘制偏移，直径 centerDiameter。
     * 窗口 #2+ 按钮: 窗口真实原点 + 弧线算出的本地中心，直径 itemDiameter。
     * 全部基于 windowOriginX/Y（系统实际摆放的位置），而不是 layoutParams 的请求值。
     */
    private fun refreshTouchViews() {
        val menu = radialMenu ?: return
        val bp = bubbleTouchParams ?: return
        val bubble = bubbleTouchView ?: return
        // 触摸窗的 layoutParams 是「请求坐标」，系统会再平移 overlayInset，所以要扣掉
        bp.x = (bubbleCenterX - menu.centerDiameter / 2f - overlayInsetX).toInt()
        bp.y = (bubbleCenterY - menu.centerDiameter / 2f - overlayInsetY).toInt()
        runCatching { windowManager.updateViewLayout(bubble, bp) }

        val centers = menu.currentItemCenters()
        for (i in actionTouchViews.indices) {
            val view = actionTouchViews[i]
            val params = actionTouchParams.getOrNull(i) ?: continue
            val center = centers.getOrNull(i)
            if (center == null) {
                view.visibility = View.GONE
                continue
            }
            view.visibility = View.VISIBLE
            params.x = (windowOriginX + center.first - menu.itemDiameter / 2f - overlayInsetX).toInt()
            params.y = (windowOriginY + center.second - menu.itemDiameter / 2f - overlayInsetY).toInt()
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    /** 依据当前是否展开，创建/销毁各按钮的触摸窗口。 */
    private fun rebuildActionTouchViews(menu: RadialMenuView) {
        for (i in actionTouchViews.indices) {
            runCatching { windowManager.removeView(actionTouchViews[i]) }
        }
        actionTouchViews.clear()
        actionTouchParams.clear()
        if (!expanded) return

        val centers = menu.currentItemCenters()
        for (i in centers.indices) {
            val view = OverlayTouchView(this)
            val params = overlayTouchParams(menu.itemDiameter).apply {
                // 按钮中心是「屏幕坐标」，layoutParams 是 inset 后的 parent 坐标，必须扣掉 inset
                x = (windowOriginX + centers[i].first - menu.itemDiameter / 2f - overlayInsetX).toInt()
                y = (windowOriginY + centers[i].second - menu.itemDiameter / 2f - overlayInsetY).toInt()
            }
            val action = menu.actionAt(i)
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    action?.let { handleAction(it) }
                }
                true
            }
            runCatching { windowManager.addView(view, params) }
            actionTouchViews.add(view)
            actionTouchParams.add(params)
        }
    }

    /** 日志条：不拦截触摸，位置始终跟随气泡中心。 */
    private fun addStatusView(menu: RadialMenuView) {
        val tv = TextView(this).apply {
            text = GameboxTaskState.statusText
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            val padH = dp(10)
            val padV = dp(4)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC263238"))
                cornerRadius = dp(10).toFloat()
            }
            visibility = if (GameboxTaskState.statusText == "空闲") View.GONE else View.VISIBLE
        }
        statusView = tv
        statusParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // layoutParams 是 inset 后的 parent 坐标，而 bubbleCenterX/Y 是屏幕坐标
            x = (bubbleCenterX - overlayInsetX).toInt()
            y = (bubbleCenterY + menu.collapsedSize / 2f + dp(6) - overlayInsetY).toInt()
        }
        runCatching { windowManager.addView(tv, statusParams) }
        tv.post { refreshStatusView() }
    }

    /** 更新日志条文案与位置（跟随气泡、靠近左右边缘时自动收拢）。 */
    private fun refreshStatusView() {
        val tv = statusView ?: return
        if (!::statusParams.isInitialized) return
        val text = GameboxTaskState.statusText
        val show = text.isNotEmpty() && text != "空闲"
        tv.text = text
        tv.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        tv.post {
            val w = if (tv.width > 0) tv.width else tv.measuredWidth
            val (screenWidth, _) = realScreenSize()
            val halfMenu = (radialMenu?.collapsedSize ?: dp(64)) / 2
            val desiredX = bubbleCenterX - w / 2f
            val maxX = (screenWidth - w - dp(8)).coerceAtLeast(dp(8))
            // 先在「屏幕坐标」里钳制，再换算成 inset 后的 parent 坐标
            statusParams.x = (desiredX.toInt().coerceIn(dp(8), maxX) - overlayInsetX).toInt()
            statusParams.y = (bubbleCenterY + halfMenu + dp(6) - overlayInsetY).toInt()
            runCatching { windowManager.updateViewLayout(tv, statusParams) }
        }
    }

    /** 日志面板：点击「日志」按钮后显示在屏幕上方，灰色透明底、黑色文字。 */
    private fun addLogPanel() {
        val tv = TextView(this).apply {
            text = GameboxTaskState.getLogText()
            textSize = 11f
            setTextColor(Color.BLACK)
            gravity = Gravity.TOP or Gravity.START
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#AA9E9E9E"))
                cornerRadius = dp(8).toFloat()
            }
            visibility = View.GONE
        }
        logPanelView = tv
        val (screenW, screenH) = realScreenSize()
        val panelW = (screenW * 0.62f).toInt()
        val params = WindowManager.LayoutParams(
            panelW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 同样是 inset 后的 parent 坐标
            x = ((screenW - panelW) / 2 - overlayInsetX).toInt()
            y = (screenH * 0.09f).toInt() - overlayInsetY.toInt()
        }
        logPanelParams = params
        runCatching { windowManager.addView(tv, params) }
    }

    /** 切换日志面板显隐。 */
    private fun toggleLogPanel() {
        val tv = logPanelView ?: return
        logPanelShown = !logPanelShown
        if (logPanelShown) refreshLogPanelText()
        tv.visibility = if (logPanelShown) View.VISIBLE else View.GONE
    }

    /** 日志面板内容更新（仅显示最近若干行）。 */
    private fun refreshLogPanelText() {
        logPanelView?.text = GameboxTaskState.getLogText()
    }

    /**
     * 窗口大小固定为 expandedSize，气泡绘制在窗口中央。
     * 要把气泡拖到屏幕边缘，就要求窗口 x/y 为负，而 MIUI 会把悬浮窗限制在屏幕内，
     * 导致气泡始终离边缘一大截。解决办法：把窗口钳制在屏幕内，
     * 再用 offsetX/offsetY 在窗口内部平移绘制内容，使气泡真正到达边缘。
     */
    private fun positionWindowForBubble() {
        val menu = radialMenu ?: return
        val size = layoutParams.width
        val (screenW, screenH) = realScreenSize()
        val margin = dp(4)

        // 先把气泡中心限制在屏幕内（保留气泡半径，保证完整可见）
        val radius = menu.centerRadiusValue
        bubbleCenterX = bubbleCenterX.coerceIn(radius + margin, screenW - radius - margin)
        bubbleCenterY = bubbleCenterY.coerceIn(radius + margin, screenH - radius - margin)

        val winX = bubbleCenterX - size / 2f
        val winY = bubbleCenterY - size / 2f
        val clampedX = winX.coerceIn(margin.toFloat(), (screenW - size - margin).toFloat())
        val clampedY = winY.coerceIn(margin.toFloat(), (screenH - size - margin).toFloat())

        // clampedX/Y 是「屏幕坐标」，而 layoutParams 是 inset 后的 parent 坐标，必须扣掉 inset。
        // 首帧 overlayInset 还是 0（尚未同步过），随后的 syncTouchViews() 会用真实原点纠正。
        layoutParams.x = (clampedX - overlayInsetX).toInt()
        layoutParams.y = (clampedY - overlayInsetY).toInt()
        // 偏移量必须基于系统「实际」摆放的原点计算，但此刻布局还没发生，
        // 先按请求值给一个初值，随后 syncTouchViews() 会用真实原点纠正。
        menu.setBubbleOffset(
            (bubbleCenterX - (clampedX + size / 2f)).toInt(),
            (bubbleCenterY - (clampedY + size / 2f)).toInt()
        )
        // 这里只做一次即时校正：拖动时本方法会被 ACTION_MOVE 高频调用，
        // 若每次都排入延时序列会积压大量回调。需要收敛的场景
        //（首次添加、旋转、展开）由调用方另外调 scheduleSyncTouchViews()。
        syncTouchViews()
    }

    /**
     * 在窗口被系统钳制「之后」的多个时间点重复校正。
     *
     * 为什么需要多次：addView / updateViewLayout 之后，系统会在随后若干帧里
     * 对悬浮窗做位置钳制，getLocationOnScreen 在第一次 post 时读到的往往还是
     * 钳制前的旧值。syncTouchViews() 本身是幂等的（原点不变时是 no-op），
     * 所以按时间序列多跑几次即可收敛到最终位置。
     */
    private fun scheduleSyncTouchViews() {
        syncDelays.forEach { delay ->
            syncHandler.postDelayed({ syncTouchViews() }, delay)
        }
    }

    private fun startOriginWatchdog() {
        syncHandler.removeCallbacks(originWatchdog)
        syncHandler.postDelayed(originWatchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun stopOriginWatchdog() {
        syncHandler.removeCallbacks(originWatchdog)
        syncHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 用主窗口「布局后的真实屏幕坐标」校正气泡与触摸热区。
     *
     * 这是本类的关键不变量：layoutParams.x/y 只是向系统「请求」的位置，
     * MIUI 等 ROM 会把悬浮窗钳制回屏幕内，请求值可能与实际位置相差数百 px。
     * 触摸热区若按请求值摆放，就会和用户看到的绘制位置分离，表现为「点了没反应、拖不动」。
     *
     * 做法：保持 bubbleCenterX/Y（用户想要的气泡位置）不变，
     * 反推出「让气泡画在这个位置」所需的窗口内绘制偏移，于是
     * 绘制位置 == 气泡中心 == 触摸热区中心，三者永远重合。
     */
    private fun syncTouchViews() {
        val menu = radialMenu ?: return
        val view = floatingView ?: return
        view.post {
            if (floatingView !== view) return@post
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            windowOriginX = loc[0].toFloat()
            windowOriginY = loc[1].toFloat()
            // 记录系统的固定平移量：真实屏幕坐标 - 请求坐标。
            // 触摸窗同样会被系统平移这么多，摆放时必须扣掉。
            overlayInsetX = windowOriginX - layoutParams.x
            overlayInsetY = windowOriginY - layoutParams.y
            val size = layoutParams.width
            // 绘制偏移 = 目标气泡中心 - 窗口真实中心。
            // 上限 = 窗口半径 - 气泡半径，超出会把气泡画到窗口外被裁掉（看不见），
            // 因此先钳制，再由实际绘制值反推气泡中心，保证「画在哪、热区就在哪」。
            val maxOffset = (size / 2f - menu.centerRadiusValue).coerceAtLeast(0f)
            val desiredDx = (bubbleCenterX - (windowOriginX + size / 2f)).coerceIn(-maxOffset, maxOffset)
            val desiredDy = (bubbleCenterY - (windowOriginY + size / 2f)).coerceIn(-maxOffset, maxOffset)
            menu.setBubbleOffset(desiredDx.toInt(), desiredDy.toInt())
            // 取整后以实际绘制值为准，保证热区与绘制严格重合
            bubbleCenterX = windowOriginX + size / 2f + menu.bubbleOffsetX
            bubbleCenterY = windowOriginY + size / 2f + menu.bubbleOffsetY
            refreshTouchViews()
            refreshStatusView()
        }
    }

    /** 真实可用屏幕尺寸：旋转后 displayMetrics 可能过期，WindowMetrics 才是权威。 */
    private fun realScreenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * 屏幕旋转/尺寸变化后，displayMetrics 与窗口实际位置都会变。
     * 必须按新尺寸重新钳制气泡位置并重建热区，否则热区会停留在旧坐标系里，
     * 表现为旋转后「气泡看得见但点不到」。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (floatingView == null) return
        positionWindowForBubble()
        runCatching { windowManager.updateViewLayout(floatingView!!, layoutParams) }
        statusView?.post { refreshStatusView() }
        // 旋转后系统会重新钳制窗口，且 displayMetrics 缓存可能滞后，
        // 需要在随后几帧里多次校正热区。
        scheduleSyncTouchViews()
    }

    /**
     * 窗口尺寸与位置恒定，展开/收起只改变 View 内部绘制，绝不触碰 WindowManager，
     * 从根上杜绝 resize 导致的「先闪到左上角再移动过来」。
     * 靠近屏幕右侧时只把弧线翻到左侧，窗口与气泡都不动。
     */
    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        val menu = radialMenu ?: return
        expanded = value

        val (screenWidth, _) = realScreenSize()
        val arcOnLeft = value && (screenWidth - bubbleCenterX) < menu.expandedSize / 2f + dp(8)

        menu.setArcOnLeft(arcOnLeft)
        menu.applyExpansion(value)
        rebuildActionTouchViews(menu)
        statusView?.post { refreshStatusView() }
        // 展开/收起会改变窗口内的绘制偏移与按钮位置，重新收敛一次。
        scheduleSyncTouchViews()
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class RadialMenuView(
        context: Context
    ) : View(context) {

        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = sp(13).toFloat()
        }
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }

        private val centerRadius = dp(26).toFloat()
        private val itemRadius = dp(22).toFloat()
        // 列表半径整体外扩 5dp
        private val orbitRadius = centerRadius + itemRadius + dp(4) + dp(5)

        val centerRadiusValue: Float get() = centerRadius

        val collapsedSize: Int = (centerRadius * 2 + dp(12)).toInt()
        val expandedSize: Int = ((orbitRadius + centerRadius + dp(8)) * 2).toInt()

        /** 右侧半圆轨道：从 -90° 起顺时针展开 180°，四个按钮间隔 45°（-67.5° / -22.5° / +22.5° / +67.5°）。 */
        private val startAngle = -90f
        private val sweepAngle = 180f

        private var isExpanded = false
        private var itemsVisible = false
        private var expansionProgress = 0f
        private var arcOnLeft = false      // 靠近屏幕右缘时弧线翻到左侧
        private var items: List<RadialItem> = emptyList()
        private val arcBounds = RectF()

        /** 气泡相对窗口中心的绘制偏移：窗口被钳制在屏幕内后靠它让气泡贴到真实边缘。 */
        private var offsetX = 0f
        private var offsetY = 0f

        fun setBubbleOffset(dx: Int, dy: Int) {
            offsetX = dx.toFloat()
            offsetY = dy.toFloat()
            invalidate()
        }

        /** 气泡在窗口内相对窗口中心的绘制偏移，触摸热区必须叠加它才能和可见气泡重合。 */
        val bubbleOffsetX: Float get() = offsetX
        val bubbleOffsetY: Float get() = offsetY

        /** 渲染/命中判定用的实际角度：右侧用原角度，左侧镜像。 */
        private fun RadialItem.effectiveAngle(): Float =
            if (arcOnLeft) 180f - angle else angle

        /** 当前展开起始角：右侧 -90°，左侧镜像后为 90°。 */
        private fun drawStartAngle(): Float = if (arcOnLeft) 90f else startAngle

        fun setArcOnLeft(value: Boolean) {
            arcOnLeft = value
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // 窗口尺寸由 setExpanded 控制，视图匹配窗口尺寸
            val width = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: expandedSize
            val height = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: expandedSize
            setMeasuredDimension(width, height)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f + offsetX
            val cy = height / 2f + offsetY

            if (expansionProgress > 0f) {
                arcBounds.set(cx - orbitRadius, cy - orbitRadius, cx + orbitRadius, cy + orbitRadius)
                canvas.drawArc(arcBounds, drawStartAngle(), sweepAngle * expansionProgress, false, guidePaint)
            }

            if (itemsVisible) {
                items.forEach { item ->
                    val effective = item.effectiveAngle()
                    val angle = drawStartAngle() + (effective - drawStartAngle()) * expansionProgress
                    val radians = Math.toRadians(angle.toDouble())
                    val distance = orbitRadius * expansionProgress
                    val ix = cx + (distance * cos(radians)).toFloat()
                    val iy = cy + (distance * sin(radians)).toFloat()

                    itemPaint.color = Color.parseColor(item.color)
                    canvas.drawCircle(ix, iy, itemRadius, itemPaint)
                    canvas.drawText(
                        item.label,
                        ix,
                        iy - (textPaint.descent() + textPaint.ascent()) / 2,
                        textPaint
                    )
                    item.bounds.set(ix - itemRadius, iy - itemRadius, ix + itemRadius, iy + itemRadius)
                }
            }

            // 中心气泡：固定显示为蓝色 Gamebox（自动滑动任务已移除）。
            val centerText = "Gamebox"
            val centerColor = COLOR_BLUE
            centerPaint.color = Color.parseColor(centerColor)
            canvas.drawCircle(cx, cy, centerRadius, centerPaint)
            val centerFontSize = if (centerText.length > 4) sp(10).toFloat() else sp(13).toFloat()
            val prevTextSize = textPaint.textSize
            textPaint.textSize = centerFontSize
            canvas.drawText(centerText, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
            textPaint.textSize = prevTextSize
        }

        fun applyExpansion(value: Boolean) {
            isExpanded = value
            if (value) itemsVisible = true
            items = if (value) buildItems() else emptyList()
            animateExpansion(if (value) 1f else 0f)
        }

        private fun buildItems(): List<RadialItem> = listOf(
            RadialItem("开始", COLOR_GREEN, -67.5f, RadialAction.TOGGLE),
            RadialItem("OCR", COLOR_BLUE, -22.5f, RadialAction.OCR),
            RadialItem("日志", COLOR_GRAY, 22.5f, RadialAction.LOG),
            RadialItem("设置", COLOR_GRAY, 67.5f, RadialAction.SETTINGS)
        )

        private fun animateExpansion(target: Float) {
            val start = expansionProgress
            val startTime = SystemClock.uptimeMillis()
            val duration = MENU_ANIM_DURATION_MS
            post(object : Runnable {
                override fun run() {
                    val t = ((SystemClock.uptimeMillis() - startTime).toFloat() / duration).coerceIn(0f, 1f)
                    expansionProgress = start + (target - start) * t
                    invalidate()
                    if (t < 1f) {
                        postDelayed(this, 16L)
                    } else if (target == 0f) {
                        itemsVisible = false
                    }
                }
            })
        }

        /** 供触摸窗口使用：气泡直径与按钮直径。 */
        val centerDiameter: Int get() = (centerRadius * 2).toInt()
        val itemDiameter: Int get() = (itemRadius * 2).toInt()

        /** 各按钮在「窗口本地坐标」中的最终中心位置（展开动画的最终态）。 */
        fun currentItemCenters(): List<Pair<Float, Float>> {
            if (!isExpanded) return emptyList()
            val cx = width / 2f + offsetX
            val cy = height / 2f + offsetY
            return items.map { item ->
                val radians = Math.toRadians(item.effectiveAngle().toDouble())
                (cx + (orbitRadius * cos(radians)).toFloat()) to
                    (cy + (orbitRadius * sin(radians)).toFloat())
            }
        }

        fun actionAt(index: Int): RadialAction? = items.getOrNull(index)?.action
    }

    /** 完全透明的触摸窗口：只负责接收触摸，自身不绘制任何东西。 */
    private class OverlayTouchView(context: Context) : View(context)

    private data class RadialItem(
        val label: String,
        val color: String,
        val angle: Float,
        val action: RadialAction,
        val bounds: RectF = RectF()
    )

    private enum class RadialAction { TOGGLE, OCR, LOG, SETTINGS }

    /** 气泡触摸窗口的处理：轻点展开/收起，拖动则移动整个悬浮窗。 */
    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                // 拖动基准必须是气泡「实际」中心，而不是上次请求的位置
                initialWindowX = bubbleCenterX.toInt()
                initialWindowY = bubbleCenterY.toInt()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (hypot(dx, dy) < dragThreshold && !bubbleDragging) return true
                if (!bubbleDragging) {
                    bubbleDragging = true
                }
                bubbleCenterX = initialWindowX + dx
                bubbleCenterY = initialWindowY + dy
                positionWindowForBubble()
                windowManager.updateViewLayout(
                    floatingView ?: return true,
                    layoutParams
                )
                statusView?.post { refreshStatusView() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val moved = hypot(event.rawX - initialTouchX, event.rawY - initialTouchY)
                if (!bubbleDragging && moved < dragThreshold) {
                    setExpanded(!expanded)
                }
                bubbleDragging = false
                // 拖动结束后系统可能把窗口再钳制一次，重新收敛热区。
                scheduleSyncTouchViews()
                return true
            }
            else -> return false
        }
    }

    private fun handleAction(action: RadialAction) {
        when (action) {
            // 「开始/停止」按钮保留展示，但自动滑动任务已删除，点击不执行任何代码。
            RadialAction.TOGGLE -> return
            RadialAction.OCR -> startOcrCapture()
            RadialAction.LOG -> toggleLogPanel()
            RadialAction.SETTINGS -> openSettings()
        }
        setExpanded(false)
    }

    /** 从悬浮窗点 OCR：服务常驻则直接复用投影截帧，否则走透明 Activity 授权。 */
    private fun startOcrCapture() {
        Log.d("FloatingWindowService", "startOcrCapture: resident=${ScreenCaptureService.isRunning}")
        if (ScreenCaptureService.isRunning) {
            // 投影已常驻，直接发 ACTION_CAPTURE，无需再次弹授权框。
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_CAPTURE
            }
            startForegroundService(intent)
        } else {
            // 服务未常驻（进程被杀/首次未授权）：请求授权，成功后立刻截一帧。
            try {
                CapturePermissionActivity.launch(this, captureNow = true)
            } catch (e: Exception) {
                Log.e("FloatingWindowService", "startOcrCapture failed: ${e.message}", e)
            }
        }
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_TARGET_PAGE, MainActivity.TARGET_PAGE_SETTINGS)
        }
        startActivity(intent)
    }

    private fun removeFloatingView() {
        stopOriginWatchdog()
        bubbleTouchView?.let { runCatching { windowManager.removeView(it) } }
        for (view in actionTouchViews) runCatching { windowManager.removeView(view) }
        actionTouchViews.clear()
        actionTouchParams.clear()
        bubbleTouchView = null
        bubbleTouchParams = null
        floatingView?.let { runCatching { windowManager.removeView(it) } }
        statusView?.let { runCatching { windowManager.removeView(it) } }
        logPanelView?.let { runCatching { windowManager.removeView(it) } }
        floatingView = null
        statusView = null
        logPanelView = null
        logPanelParams = null
        logPanelShown = false
        radialMenu = null
        expanded = false
    }

    private fun startAsForeground() {
        val channelId = ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun sp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    companion object {
        private const val CHANNEL_ID = "gamebox_floating_window"
        private const val NOTIFICATION_ID = 1001
        /** 菜单展开/收起动画时长，与 RadialMenuView.animateExpansion 保持一致。 */
        private const val MENU_ANIM_DURATION_MS = 180L
        private const val COLOR_BLUE = "#CC1E88E5"
        private const val COLOR_GREEN = "#CC43A047"
        private const val COLOR_GRAY = "#CC546E7A"
        /** 原点漂移看门狗的轮询间隔。 */
        private const val WATCHDOG_INTERVAL_MS = 800L

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
