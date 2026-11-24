package com.sakura.ubox.ocr

import com.sakura.ubox.UBoxTaskState

/** 避免 OCR 包与顶层包互相依赖的桥接：转发状态栏文案更新。 */
internal object UBoxTaskStateBridge {
    fun setStatus(text: String) = UBoxTaskState.setStatus(text)
}
