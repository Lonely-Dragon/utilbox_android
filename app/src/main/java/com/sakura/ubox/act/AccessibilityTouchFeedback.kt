package com.sakura.ubox.act

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

class AccessibilityTouchFeedback(
    private val context: Context
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var feedbackView: FeedbackView? = null
    private var animator: ValueAnimator? = null

    fun showGesture(sx: Float, sy: Float, ex: Float, ey: Float) {
        release()

        val view = FeedbackView(context).apply {
            setPoints(sx, sy, ex, ey)
        }
        feedbackView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        runCatching { windowManager.addView(view, params) }
            .onFailure { return }

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MILLIS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                view.progress = animation.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    release()
                }

                override fun onAnimationCancel(animation: Animator) {
                    release()
                }
            })
            start()
        }
    }

    fun release() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null

        feedbackView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        feedbackView = null
    }

    private class FeedbackView(context: Context) : View(context) {
        private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6642A5F5")
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EE42A5F5")
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        private val tracePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFFFF")
            style = Paint.Style.FILL
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E64CAF50")
            style = Paint.Style.FILL
        }
        private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EFFF7043")
            style = Paint.Style.FILL
        }
        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f
        private val routePath = Path()
        private val activePath = Path()
        private val pathMeasure = PathMeasure()
        private val pointPosition = FloatArray(2)

        var progress: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        fun setPoints(startX: Float, startY: Float, endX: Float, endY: Float) {
            this.startX = startX
            this.startY = startY
            this.endX = endX
            this.endY = endY

            val controlOffset = (startY - endY) * 0.16f
            routePath.reset()
            routePath.moveTo(startX, startY)
            routePath.cubicTo(
                startX - controlOffset,
                startY - controlOffset,
                endX + controlOffset,
                endY + controlOffset,
                endX,
                endY
            )
            pathMeasure.setPath(routePath, false)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val currentDistance = pathMeasure.length * progress
            pathMeasure.getPosTan(currentDistance, pointPosition, null)
            val currentX = pointPosition[0]
            val currentY = pointPosition[1]
            val dragRadius = max(16f, 28f - progress * 8f)

            activePath.reset()
            pathMeasure.getSegment(0f, currentDistance, activePath, true)

            canvas.drawPath(routePath, routePaint)
            canvas.drawCircle(startX, startY, 22f, startPaint)
            canvas.drawPath(activePath, linePaint)
            drawTracePoints(canvas)
            drawMotionShadow(canvas, currentDistance)
            canvas.drawCircle(currentX, currentY, dragRadius, endPaint)

            if (progress > 0.88f) {
                canvas.drawCircle(endX, endY, 18f, endPaint)
            }
        }

        private fun drawTracePoints(canvas: Canvas) {
            val count = 7
            for (index in 1 until count) {
                val pointProgress = index.toFloat() / count
                if (pointProgress > progress) return
                pathMeasure.getPosTan(pathMeasure.length * pointProgress, pointPosition, null)
                canvas.drawCircle(pointPosition[0], pointPosition[1], 5f, tracePointPaint)
            }
        }

        private fun drawMotionShadow(canvas: Canvas, currentDistance: Float) {
            val shadowCount = 6
            val step = pathMeasure.length * 0.035f
            for (index in shadowCount downTo 1) {
                val distance = currentDistance - step * index
                if (distance <= 0f) continue
                pathMeasure.getPosTan(distance, pointPosition, null)
                val alpha = (34 + (shadowCount - index) * 18).coerceAtMost(130)
                shadowPaint.color = Color.argb(alpha, 255, 112, 67)
                canvas.drawCircle(pointPosition[0], pointPosition[1], 8f + index, shadowPaint)
            }
        }
    }

    companion object {
        private const val ANIMATION_DURATION_MILLIS = 1950L
    }
}