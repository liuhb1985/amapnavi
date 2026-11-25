package com.carrot.amapnavi

import android.app.Application

/* =========================================================
 * 应用入口（本次新增，唯一职责：尽早挂上崩溃捕获器）
 *
 * 为什么需要它：崩溃捕获原先只装在 MainActivity.onCreate 第一行，
 * 但静态注册的 BroadcastReceiver（开机广播 RestartReceiver / UsbBootReceiver、
 * USB 插拔等）是在【Activity 都还没创建】时就被系统拉起的，
 * 那条路径上的崩溃根本捕获不到——表现为「闪退了，但 crash 目录是空的」。
 *
 * 任何组件（Activity / Service / Receiver / Provider）启动前，
 * 系统都会先创建 Application 并回调 onCreate()，所以这里是进程内最早、
 * 也是唯一能覆盖全部入口的挂载点。
 * ========================================================= */
class CarrotApplication : Application() {

    override fun onCreate() {
        CrashLogger.install(this)
        CrashLogger.mark(this, "Application.onCreate（进程启动）")
        super.onCreate()
    }
}
