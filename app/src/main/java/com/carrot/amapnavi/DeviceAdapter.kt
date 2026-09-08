package com.carrot.amapnavi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(private val onItemClick: (DeviceInfo) -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private var data: List<DeviceInfo> = emptyList()

    fun updateData(newData: List<DeviceInfo>) {
        data = newData
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvInfo: TextView = view.findViewById(R.id.tvDeviceInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val d = data[position]

        // ---- 新增：设备类型转中文 ----
        val deviceCn = when (d.device) {
            "lidar" -> "激光雷达"
            "blinker" -> "控灯模块"
            "camera" -> "摄像头"
            else -> d.device
        }
        val detectText =
            if (d.device == "lidar" || d.device == "camera") {
                when (d.detect_side) {
                    1 -> "左侧"
                    2 -> "右侧"
                    3 -> "左右两侧"
                    else -> ""
                }
            } else {
                ""
            }

        holder.tvInfo.text =
            "${d.ip}:${d.port} | ${d.device_id} | v${d.version} | board=${d.board}" +
                    (if (d.registered >= 0) " | ${if (d.registered == 1) "已注册" else "未注册"}" else "") +
                    (if (deviceCn.isNotEmpty()) " | $deviceCn" else "") +
                    (if (detectText.isNotEmpty()) " | $detectText" else "")

        holder.tvInfo.setOnClickListener {
            onItemClick(d)
        }
    }

    override fun getItemCount(): Int = data.size
}
