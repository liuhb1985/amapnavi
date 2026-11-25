package com.carrot.amapnavi.probe

import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* =========================================================
 * 导航源探针日志
 *
 * 用途：接入新地图（如百度车机版）前，先把目标 App 真实发出的
 * 广播 / 通知栏内容原样抓下来，再据此写字段映射。
 *
 * 说明：第三方地图的广播 action 与字段名没有公开文档，
 * 靠猜是写不出可用代码的——必须先在真机上抓一份样本。
 * 本对象负责把样本暂存在内存环形缓冲里，并落盘到
 * /sdcard/AmapNavi/probe/ 便于导出。
 * ========================================================= */
object NavProbeLog {

    private const val MAX_LINES = 600

    private val lines = ArrayList<String>(MAX_LINES)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(kind: String, source: String, detail: String) {
        val line = "[${timeFmt.format(Date())}][$kind] $source :: $detail"
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeAt(0)
            lines.add(line)
        }
        android.util.Log.i("PROBE", line)
    }

    /** 把 Intent 的 extras 全量展开成一行（嵌套 Bundle 递归一层） */
    fun dumpExtras(bundle: Bundle?): String {
        if (bundle == null) return "(无 extras)"
        val sb = StringBuilder()
        for (key in bundle.keySet()) {
            val v = bundle.get(key)
            sb.append(key).append('=')
            when (v) {
                null -> sb.append("null")
                is Bundle -> sb.append('{').append(dumpExtras(v)).append('}')
                is ByteArray -> sb.append("byte[").append(v.size).append(']')
                is Array<*> -> sb.append(v.contentDeepToString())
                else -> sb.append(v.toString())
            }
            sb.append(" | ")
        }
        return sb.toString()
    }

    fun snapshot(): List<String> = synchronized(lines) { ArrayList(lines) }

    fun clear() = synchronized(lines) { lines.clear() }
}
