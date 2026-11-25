package com.carrot.amapnavi

// -----------------------------
// 道路类型工具
// -----------------------------
fun isHighway(roadcate: Int?): Boolean {
    if (roadcate == null) return false
    if (roadcate == 0 || roadcate == 1) return true //高速或快速
    else return false
}

fun roadcate_conv(roadcate: Int?): Int {
    if (roadcate == null) return 8 //公路
    else if (roadcate == 0) return 1 //高速
    else if (roadcate == 6) return 0 //快速
    else if (roadcate == 1) return 2 //国道
    else return 8
}

// -----------------------------
// 车道方向字符 → 图标
// -----------------------------
fun laneLetterToIcon(s: String): String {
    return when (s) {
        "L" -> "←"
        "R" -> "→"
        "S" -> "↑"
        "U" -> "↶"
        "X" -> "⛔"
        else -> s
    }
}

// -----------------------------
// 高德数据的描述文本
// -----------------------------
fun getRoadTypeDesc(type: Int?): String {
    return when (type) {
        //0 -> "高速($type)"
        //1 -> "国道($type)"
        //6 -> "快速($type)"
        //else -> "公路($type)"
        1 -> "高速($type)"
        2 -> "国道($type)"
        0 -> "快速($type)"
        else -> "公路($type)"
    }
}

fun getTurnIconDesc(icon: Int?): String {
    return when (icon) {
        1 -> "直行"
        2 -> "左转"
        3 -> "右转"
        4 -> "左变道"
        5 -> "右变道"
        6 -> "左后方转弯"
        7 -> "右后方转弯"
        8 -> "左掉头"
        9 -> "右掉头"
        10 -> "靠左行驶"
        11 -> "靠右行驶"
        12 -> "进入环岛"
        13 -> "离开环岛"
        14 -> "收费站"
        65 -> "左岔路"
        66 -> "右岔路"
        else -> "其它($icon)"
    }
}

fun getxTurnInfoDesc(turnInfo: Int): String {
    return when (turnInfo) {
        1 -> "左转"
        2 -> "右转"
        3 -> "左变道"
        4 -> "右变道"
        5 -> "直行"
        6 -> "直行"
        7 -> "直行"
        8 -> "直行"
        else -> "$turnInfo"
    }
}

fun getCpTrafficDesc(status: Int?): String {
    return when (status) {
        0 -> "OFF"
        1 -> "RED"
        2 -> "GREEN"
        else -> "其它($status)"
    }
}

fun getCpXStateDesc(status: Int?): String {
    return when (status) {
        0 -> "跟车"
        1 -> "巡航"
        2 -> "e巡航"
        3 -> "e停车"
        4 -> "e准备"
        5 -> "e停止"
        else -> "其它($status)"
    }
}

fun getNavTrafficDesc(status: Int?): String {
    return when (status) {
        2 -> "红灯"
        3 -> "绿灯"
        4 -> "黄灯"
        else -> "其它($status)"
    }
}

fun getTrafficDirDesc(dir: Int?): String {
    return when (dir) {
        1 -> "左转"
        2 -> "右转"
        3 -> "掉头"
        4 -> "直行"
        else -> "其它($dir)"
    }
}

fun getCameraTypeDesc(type: Int): String {
    return when (type) {
        0 -> "测速摄像头"
        1 -> "监控摄像头"
        2 -> "闯红灯摄像头"
        3 -> "违章拍照"
        4 -> "流动测速"
        5 -> "区间测速起点"
        6 -> "区间测速终点"
        7 -> "其他"
        13 -> "行人+闯红灯"
        else -> "其它($type)"
    }
}

// -----------------------------
// 高德 → CP 类型转换
// -----------------------------
fun convert_tbt_type(icon: Int?): Int {
    return when (icon) {
        1    -> 51  // 直行 -> notification straight直行
        2    -> 12   // 左转 -> turn left
        3    -> 13   // 右转 -> turn right
        4    -> 7    // 左前方转弯 -> fork left
        5    -> 6    // 右前方转弯 -> fork right
        6    -> 12   // 左后方转弯 -> turn left
        7    -> 13   // 右后方转弯 -> turn right
        8    -> 12   // 左转掉头 -> turn left
        9    -> 13   // 右转掉头 -> turn right
        10   -> 51  // 靠左行驶 -> notification straight直行
        11   -> 51  // 靠右行驶 -> notification straight直行
        12   -> 51  // 进入环岛 -> notification straight直行
        13   -> 51  // 离开环岛 -> notification straight直行
        // 14 收费站：CP 的收费站码是 153/154/249(xTurnInfo=6)。
        //    原先发 142，而 142 在 CP 是 "rotary straight"(环岛直行, xTurnInfo=5)，
        //    会把收费站当成进环岛——已按 CP 的 turn_type_mapping 纠正为 153。
        14   -> 153 // 收费站 -> 收费站(TG)
        // 16 隧道：CP 没有隧道转向码（它靠 szPosRoadName 含"隧道"判断），
        //    原先发 153 会让 CP 误判成收费站，改为按普通直行处理。
        16   -> 51  // 隧道 -> notification straight直行
        65   -> 7    // 走左侧岔路 -> fork left
        66   -> 6    // 走右侧岔路 -> fork left
        else -> -1   // 未知或未匹配
    }
}

fun convert_x_tbt_type(nTBTTurnType: Int): Int {

    // xTurnInfo : 1: left turn, 2: right turn, 3: left lane change,
    //             4: right lane change, 5: rotary, 6: tg, 7: arrive or uturn
    val turnTypeMapping: Map<Int, Triple<String, String, Int>> = mapOf(
        12 to Triple("turn", "left", 1),
        16 to Triple("turn", "sharp left", 1),

        // new
        1000 to Triple("turn", "slight left", 1),
        1001 to Triple("turn", "slight right", 2),
        1002 to Triple("fork", "slight left", 3),
        1003 to Triple("fork", "slight right", 4),
        1006 to Triple("off ramp", "left", 3),
        1007 to Triple("off ramp", "right", 4),

        13 to Triple("turn", "right", 2),
        19 to Triple("turn", "sharp right", 2),

        102 to Triple("off ramp", "slight left", 3),
        105 to Triple("off ramp", "slight left", 3),
        112 to Triple("off ramp", "slight left", 3),
        115 to Triple("off ramp", "slight left", 3),

        101 to Triple("off ramp", "slight right", 4),
        104 to Triple("off ramp", "slight right", 4),
        111 to Triple("off ramp", "slight right", 4),
        114 to Triple("off ramp", "slight right", 4),

        7 to Triple("fork", "left", 3),
        44 to Triple("fork", "left", 3),
        17 to Triple("fork", "left", 3),
        75 to Triple("fork", "left", 3),
        76 to Triple("fork", "left", 3),
        118 to Triple("fork", "left", 3),

        6 to Triple("fork", "right", 4),
        43 to Triple("fork", "right", 4),
        73 to Triple("fork", "right", 4),
        74 to Triple("fork", "right", 4),
        123 to Triple("fork", "right", 4),
        124 to Triple("fork", "right", 4),
        117 to Triple("fork", "right", 4),

        131 to Triple("rotary", "slight right", 5),
        132 to Triple("rotary", "slight right", 5),
        140 to Triple("rotary", "slight left", 5),
        141 to Triple("rotary", "slight left", 5),
        133 to Triple("rotary", "right", 5),
        134 to Triple("rotary", "sharp right", 5),
        135 to Triple("rotary", "sharp right", 5),
        136 to Triple("rotary", "sharp left", 5),
        137 to Triple("rotary", "sharp left", 5),
        138 to Triple("rotary", "sharp left", 5),
        139 to Triple("rotary", "left", 5),
        142 to Triple("rotary", "straight", 5),

        14 to Triple("turn", "uturn", 7),
        201 to Triple("arrive", "straight", 8),

        51 to Triple("notification", "straight", 0),
        52 to Triple("notification", "straight", 0),
        53 to Triple("notification", "straight", 0),
        54 to Triple("notification", "straight", 0),
        55 to Triple("notification", "straight", 0),

        153 to Triple("", "", 6), // TG
        154 to Triple("", "", 6),
        249 to Triple("", "", 6)
    )

    val result = turnTypeMapping[nTBTTurnType]

    if (result != null) {
        val navType = result.first
        val navModifier = result.second
        val xTurnInfo = result.third

        return xTurnInfo
    } else {
        val navType = "invalid"
        val navModifier = ""
        val xTurnInfo = -1

        return xTurnInfo
    }
}

fun convert_sdi_type(gaodeType: Int?): Int? {
    if (gaodeType == null) return null
    return when (gaodeType) {
        0 -> 1   // 测速摄像头 -> 超速 (固定式)
        1 -> 14   // 监控摄像头 -> 治安监控 CCTV
        2 -> 6   // 闯红灯摄像头 -> 闯红灯抓拍
        3 -> 5   // 违章拍照 -> 跟车抓拍摄像头
        4 -> 7   // 流动测速 -> 超速 (移动式)
        5 -> 2   // 区间测速起点 -> 区间测速开始
        6 -> 3   // 区间测速终点 -> 区间测速结束
        7 -> 4   // 其他 -> 区间测速中（或可按需求调整）
        13 -> 6  // 行人+闯红灯 -> 闯红灯抓拍
        else -> -1
    }
}
