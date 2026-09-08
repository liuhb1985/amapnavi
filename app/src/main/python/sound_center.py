# sound_center.py

_last_sound = None

# 定义 index 对应的声音文件名（不带扩展名）
SOUND_TABLE = {
    0: "",
    1: "audio_pre_lane_change",          #audioPreLaneChange
    2: "audio_lane_change",              #audioLaneChange
    3: "audio_turn",                     #audioTurn
    4: "audio_atc_cancel",               #audioAtcCancel
    5: "audio_atc_resume",               #audioAtcResume
    6: "audio_car_watchout",             #laneChangeBlocked
    7: "audio_pre_lane_left",            #audioPreLaneChangeLeft
    8: "audio_pre_lane_right",           #audioPreLaneChangeRight
    9: "audio_lane_change_ok",           #audioLaneChangeOk
    10: "audio_last_lane",               #audioLastLane
    11: "audio_new_lane",                #audioNewLane
    12: "audio_lane_change_end",         #audioLaneChangeEnd
    13: "traffic_sign_green",
    14: "traffic_sign_changed",
    15: "audio_traffic_error",
    16: "audio_lane_change_cancel",
    17: "",
    18: "",
    19: "",
    20: "engage",
    21: "disengage",
    22: "prompt",
    23: "prompt_distracted",
    24: "warning_soft",
    25: "warning_immediate",
    26: "tici_engaged",
    27: "tici_disengaged",
    28: "audio_stopstop",
    29: "audio_stopping",
    30: "audio_auto_hold",
    31: "audio_1",
    32: "audio_2",
    33: "audio_3",
    34: "audio_4",
    35: "audio_5",
    36: "audio_6",
    37: "audio_7",
    38: "audio_8",
    39: "audio_9",
    40: "audio_10",
}

def request_sound(name: str):
    global _last_sound
    _last_sound = name
    print(f"sound:{name}")
    return name

def request_sound_index(index: int):
    global _last_sound

    if index not in SOUND_TABLE:
        print(f"Invalid sound index: {index}")
        return ""

    name = SOUND_TABLE[index]
    _last_sound = name
    print(f"sound:{name}")
    return name

def get_requested_sound():
    global _last_sound

    # 如果没有声音请求，必须返回字符串，否则会导致 Java 端 null crash
    if _last_sound is None:
        return ""

    s = _last_sound
    _last_sound = None
    return s
