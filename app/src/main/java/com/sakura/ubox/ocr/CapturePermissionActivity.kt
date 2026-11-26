package com.sakura.ubox.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * 透明的 MediaProjection 授权中转 Activity。
 * 悬浮窗服务无法用 ActivityResultLauncher，必须通过一个 Activity 发起
 * createScreenCaptureIntent() 并在 onActivityResult 中把结果交给截屏服务。
 * 本 Activity 无界面，只做授权请求，结束后立即 finish。
 */
class CapturePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: requesting screen capture permission")
        captureNow = intent.getBooleanExtra(EXTRA_CAPTURE_NOW, false)
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private var captureNow = false

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, data=$data")
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "permission granted, starting ScreenCaptureService")
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                    putExtra(ScreenCaptureService.EXTRA_CAPTURE_NOW, captureNow)
                }
                startForegroundService(intent)
                OcrState.setCapturing(true)
                Toast.makeText(
                    this,
                    if (captureNow) "OCR 截屏已启动" else "OCR 截屏权限已就绪",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Log.w(TAG, "permission denied or data null")
                Toast.makeText(this, "未授予截屏权限", Toast.LENGTH_SHORT).show()
            }
        }
        // 授权对话框关闭后，透明 Activity 立刻退出，让游戏画面重新可见
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }

    companion object {
        private const val TAG = "CapturePermissionActivity"
        private const val REQUEST_CAPTURE = 1001
        const val EXTRA_CAPTURE_NOW = "capture_now"

        /** 从悬浮窗发起：若服务未常驻，授权成功后立刻截一帧并弹框。 */
        fun launch(context: Context, captureNow: Boolean) {
            val intent = Intent(context, CapturePermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_CAPTURE_NOW, captureNow)
            }
            context.startActivity(intent)
        }
    }
}
