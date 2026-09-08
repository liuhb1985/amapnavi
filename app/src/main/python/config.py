#!/usr/bin/env python3
"""
配置管理模块 - 独立运行版
负责系统配置参数的初始化和持久化存储
"""

import json
import os

# ------------------------
# 新增：用于从 Android 传入持久化目录
# ------------------------
CONFIG_DIR = None

def set_config_dir(path):
    """由 Android 传入持久化目录"""
    global CONFIG_DIR
    CONFIG_DIR = path

class Config:
    """配置管理类"""
    
    def __init__(self):
        """初始化配置参数"""
        # 优先使用 Android 传入的路径
        if CONFIG_DIR:
            base_dir = CONFIG_DIR
        else:
            # fallback：使用 Python 当前工作目录
            base_dir = os.getcwd()
            print("[Config] CONFIG_DIR not set, using fallback directory:", base_dir)

        self.config_file = os.path.join(base_dir, "auto_overtake_config.json")

        self.config = self._init_default_config()
        self.load_persistent_config()
    
    def _init_default_config(self):
        """初始化默认配置"""
        return {
            # 道路和车道配置
            'road_type': 'normal',
            'lane_count': 3,
            'current_lane_number': 2,
            'lane_count_mode': 'auto',  # 'auto' 或 'manual'
            'manual_lane_count': 3,

            # 超车功能开关 - 默认都关闭
            'autoOvertakeEnabled': False,    # 高速超车默认关闭
            'autoOvertakeEnabledL': False,   # 公路超车默认关闭
            'shouldReturnToLane': False,     # 返回功能默认关闭
        
            # 前车最低速度限制
            'HIGHWAY_LEAD_MIN_SPEED': 35,
            'NORMAL_LEAD_MIN_SPEED': 20,

            # 超车触发条件参数
            'HIGHWAY_MIN_SPEED': 60,
            'NORMAL_ROAD_MIN_SPEED': 30,
            'CRUISE_SPEED_RATIO_THRESHOLD': 0.9,
            'FOLLOW_TIME_GAP_THRESHOLD': 4,
            'MAX_FOLLOW_TIME': 600000,
            'LEAD_RELATIVE_SPEED_THRESHOLD': -3,

            # 远距离超车参数
            'EARLY_OVERTAKE_SPEED_RATIO': 0.6,
            'EARLY_OVERTAKE_MIN_LEAD_SPEED': 50,
            'EARLY_OVERTAKE_MIN_DISTANCE': 30,
            'EARLY_OVERTAKE_MAX_DISTANCE': 150,
            'EARLY_OVERTAKE_MIN_SPEED_DIFF': 20,

            # 安全变道条件参数
            'MIN_LANE_WIDTH': 2.3,
            'SAFE_LANE_WIDTH': 2.8,
            'SIDE_LEAD_DISTANCE_MIN': 20,
            'SIDE_RELATIVE_SPEED_THRESHOLD': 15,

            # 弯道检测参数
            'CURVATURE_THRESHOLD': 0.02,
            'STEERING_THRESHOLD': 8.0,

            # 冷却时间参数(毫秒)
            'OVERTAKE_COOLDOWN_BASE': 8000,
            'OVERTAKE_COOLDOWN_FAILED': 3000,
            'OVERTAKE_COOLDOWN_SUCCESS': 15000,
            'OVERTAKE_COOLDOWN_CONDITION': 5000,

            # 惩罚权重系统
            'PENALTY_WEIGHTS': {
                'vrel': 2.0,
                'side_lead_distance': 1.5,
                'side_relative_speed': 1.8,
                'lane_width': 1.2,
                'blindspot': 3.0,
                'curvature': 1.5,
                'min_speed_advantage': 5.0
            },

            # 决策阈值
            'PENALTY_THRESHOLD': 60.0,
            'MIN_SPEED_ADVANTAGE': 5.0,

            # 🆕 返回策略配置 - 修改返回超时为60秒
            'RETURN_STRATEGY': {
                'highway': {
                    'enabled': True,
                    'return_timeout': 60000,  # 60秒
                    'max_return_attempts': 3,
                },
                'normal': {
                    'enabled': False,
                    'return_timeout': 60000,  # 60秒
                    'max_return_attempts': 0,
                }
            },

            # 高速公路专用策略
            'HIGHWAY_STRATEGY': {
                'prefer_left_overtake': True,
                'avoid_rightmost_lane': True,
                'emergency_lane_penalty': 100,
                'fast_lane_bonus': 15,
                'min_advantage_threshold': 3
            }
        }


    def load_persistent_config(self):
        """从持久化存储加载配置"""
        try:
            if os.path.exists(self.config_file):
                with open(self.config_file, 'r') as f:
                    saved_config = json.load(f)
                    # 只更新已存在的配置项
                    for key, value in saved_config.items():
                        if key in self.config:
                            self.config[key] = value
        except Exception:
            pass
        self.config['autoOvertakeEnabled'] = False   # 强制关闭高速超车
        self.config['autoOvertakeEnabledL'] = False # 强制关闭公路超车
        self.config['shouldReturnToLane'] = False  # 强制关闭返回原车道
        self.config['road_type'] = 'normal'       # 强制设置为普通道路
        


    def save_persistent_config(self):
        """保存配置到持久化存储"""
        try:
            with open(self.config_file, 'w') as f:
                json.dump(self.config, f, indent=2)
        except Exception:
            pass

    def get(self, key, default=None):
        """获取配置值"""
        return self.config.get(key, default)

    def set(self, key, value):
        """设置配置值"""
        self.config[key] = value

    def update(self, updates):
        """批量更新配置"""
        for key, value in updates.items():
            if key in self.config:
                self.config[key] = value