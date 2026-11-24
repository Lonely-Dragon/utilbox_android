package com.sakura.ubox.ocr

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * OCR 结果弹框 Activity：单次截屏识别完成后由 [ScreenCaptureService] 拉起，
 * 以对话框形式展示识别到的文字与命中的规则，用户点「关闭」后退出。
 * 使用透明主题，仅承载一个 AlertDialog。
 */
class OcrResultActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val texts = intent.getStringArrayExtra(EXTRA_TEXTS) ?: emptyArray()
        val matchName = intent.getStringExtra(EXTRA_MATCH)

        val lines = mutableListOf<String>()
        if (!matchName.isNullOrBlank()) {
            lines += "命中规则：$matchName"
        }
        if (texts.isEmpty()) {
            lines += "未识别到文字"
        } else {
            texts.forEachIndexed { index, text -> lines += "${index + 1}. $text" }
        }

        AlertDialog.Builder(this)
            .setTitle("OCR 识别结果")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("关闭") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    companion object {
        const val EXTRA_TEXTS = "ocr_result_texts"
        const val EXTRA_MATCH = "ocr_result_match"

        fun launch(context: Context, texts: List<String>, matchName: String?) {
            val intent = Intent(context, OcrResultActivity::class.java).apply {
                putExtra(EXTRA_TEXTS, texts.toTypedArray())
                putExtra(EXTRA_MATCH, matchName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
