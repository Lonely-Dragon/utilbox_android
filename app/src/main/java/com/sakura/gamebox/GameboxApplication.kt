package com.sakura.gamebox

import android.app.Application

class GameboxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 无障碍服务/悬浮窗服务可能在 Activity 之前被系统拉起，设置必须在那之前就可用
        GameboxTaskState.init(this)
    }
}
