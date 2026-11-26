package com.sakura.ubox.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.sakura.ubox.MainActivity
import com.sakura.ubox.R
import com.sakura.ubox.UBoxTaskState
import com.sakura.ubox.ocr.CapturePermissionActivity
import com.sakura.ubox.ocr.ScreenCaptureService
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

    /** 气泡在屏幕上的中心（浮点保存，避免整数取整在反复展开时累积漂移）。 */
    private var bubbleCenterX = 0f
    private var bubbleCenterY = 0f

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
        UBoxTaskState.addStatusListener(statusListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (floatingView == null) addFloatingView()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        UBoxTaskState.removeStatusListener(statusListener)
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
     * 窗口 #1 主按钮: 中心 (bubbleCenter + offset)，直径 centerDiameter。
     * 窗口 #2+ 按钮: 沿弧线计算出的中心，直径 itemDiameter。
     */
    private fun refreshTouchViews() {
        val menu = radialMenu ?: return
        val bp = bubbleTouchParams ?: return
        val bubble = bubbleTouchView ?: return
        bp.x = (bubbleCenterX - menu.centerDiameter / 2f).toInt()
        bp.y = (bubbleCenterY - menu.centerDiameter / 2f).toInt()
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
            params.x = (layoutParams.x + center.first - menu.itemDiameter / 2f).toInt()
            params.y = (layoutParams.y + center.second - menu.itemDiameter / 2f).toInt()
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
                x = (layoutParams.x + centers[i].first - menu.itemDiameter / 2f).toInt()
                y = (layoutParams.y + centers[i].second - menu.itemDiameter / 2f).toInt()
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
            text = UBoxTaskState.statusText
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
            visibility = if (UBoxTaskState.statusText == "空闲") View.GONE else View.VISIBLE
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
            x = bubbleCenterX.toInt()
            y = (bubbleCenterY + menu.collapsedSize / 2f + dp(6)).toInt()
        }
        runCatching { windowManager.addView(tv, statusParams) }
        tv.post { refreshStatusView() }
    }

    /** 更新日志条文案与位置（跟随气泡、靠近左右边缘时自动收拢）。 */
    private fun refreshStatusView() {
        val tv = statusView ?: return
        if (!::statusParams.isInitialized) return
        val text = UBoxTaskState.statusText
        val show = text.isNotEmpty() && text != "空闲"
        tv.text = text
        tv.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        tv.post {
            val w = if (tv.width > 0) tv.width else tv.measuredWidth
            val screenWidth = resources.displayMetrics.widthPixels
            val halfMenu = (radialMenu?.collapsedSize ?: dp(64)) / 2
            val desiredX = bubbleCenterX - w / 2f
            val maxX = (screenWidth - w - dp(8)).coerceAtLeast(dp(8))
            statusParams.x = desiredX.toInt().coerceIn(dp(8), maxX)
            statusParams.y = (bubbleCenterY + halfMenu + dp(6)).toInt()
            runCatching { windowManager.updateViewLayout(tv, statusParams) }
        }
    }

    /** 日志面板：点击「日志」按钮后显示在屏幕上方，灰色透明底、黑色文字。 */
    private fun addLogPanel() {
        val tv = TextView(this).apply {
            text = UBoxTaskState.getLogText()
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
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
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
            x = (screenW - panelW) / 2
            y = (screenH * 0.09f).toInt()
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
        logPanelView?.text = UBoxTaskState.getLogText()
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
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val margin = dp(4)

        // 先把气泡中心限制在屏幕内（保留气泡半径，保证完整可见）
        val radius = menu.centerRadiusValue
        bubbleCenterX = bubbleCenterX.coerceIn(radius + margin, screenW - radius - margin)
        bubbleCenterY = bubbleCenterY.coerceIn(radius + margin, screenH - radius - margin)

        val winX = bubbleCenterX - size / 2f
        val winY = bubbleCenterY - size / 2f
        val clampedX = winX.coerceIn(margin.toFloat(), (screenW - size - margin).toFloat())
        val clampedY = winY.coerceIn(margin.toFloat(), (screenH - size - margin).toFloat())

        layoutParams.x = clampedX.toInt()
        layoutParams.y = clampedY.toInt()
        menu.setBubbleOffset(
            (bubbleCenterX - (clampedX + size / 2f)).toInt(),
            (bubbleCenterY - (clampedY + size / 2f)).toInt()
        )
        refreshTouchViews()
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

        val screenWidth = resources.displayMetrics.widthPixels
        val arcOnLeft = value && (screenWidth - bubbleCenterX) < menu.expandedSize / 2f + dp(8)

        menu.setArcOnLeft(arcOnLeft)
        menu.applyExpansion(value)
        rebuildActionTouchViews(menu)
        statusView?.post { refreshStatusView() }
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

            // 中心气泡：固定显示为蓝色 UBox（自动滑动任务已移除）。
            val centerText = "UBox"
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
        private const val CHANNEL_ID = "ubox_floating_window"
        private const val NOTIFICATION_ID = 1001
        /** 菜单展开/收起动画时长，与 RadialMenuView.animateExpansion 保持一致。 */
        private const val MENU_ANIM_DURATION_MS = 180L
        private const val COLOR_BLUE = "#CC1E88E5"
        private const val COLOR_GREEN = "#CC43A047"
        private const val COLOR_GRAY = "#CC546E7A"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
