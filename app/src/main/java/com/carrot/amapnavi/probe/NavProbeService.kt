package com.carrot.amapnavi.probe

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/* =========================================================
 * 通知栏探针（接入新地图的通用手段）
 *
 * 为什么需要它：第三方地图的广播 action 是私有协议、没有公开文档，
 * 而几乎所有导航 App 在导航过程中都会往通知栏写「转向 / 距离 / 限速 / 道路名」。
 * 抓通知栏不需要知道任何 action，是拿到第一份样本最省事的办法。
 *
 * 用法：设置页 → 导航源探针 → 点「开启通知栏抓取」→ 在系统「通知使用权」
 * 里勾选 AmapNavi → 打开百度车机版开始导航 → 回到探针页导出。
 * ========================================================= */
class NavProbeService : NotificationListenerService() {

    override fun onListenerConnected() {
        NavProbeLog.add("SVC", "通知监听", "已连接（授权生效）")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName ?: return
        if (!isCandidateMap(pkg)) return

        val extras = n.notification?.extras
        val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        val bigText = extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()
        val ticker = n.notification?.tickerText?.toString()

        NavProbeLog.add(
            "NOTIFY", pkg,
            "title=$title | text=$text | big=$bigText | sub=$subText | ticker=$ticker"
        )
        // extras 全量也记一份，里面有地图 App 自己塞的隐藏字段
        NavProbeLog.add("NOTIFY-EXTRAS", pkg, NavProbeLog.dumpExtras(extras))
    }

    override fun onListenerDisconnected() {
        NavProbeLog.add("SVC", "通知监听", "已断开（授权被取消？）")
    }

    companion object {
        /** 可能是导航/地图来源的包名关键字 */
        private val MAP_KEYWORDS = listOf(
            "baidu", "amap", "autonavi", "tencent", "map", "navi", "gaode", "careland"
        )

        fun isCandidateMap(pkg: String): Boolean {
            val p = pkg.toLowerCase()
            // 排除本 App 自身与系统
            if (p.startsWith("com.carrot.")) return false
            if (p.startsWith("com.android.")) return false
            return MAP_KEYWORDS.any { p.contains(it) }
        }
    }
}
