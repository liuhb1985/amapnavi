#!/usr/bin/env python3
"""
主控制器 
"""

import os
import sys
import json
import time
import threading
import socket
import traceback
from collections import deque

try:
    from config import Config
    from vehicle_tracker import VehicleTrackingCenter
    from lane_change_verification import LaneChangeVerificationSystem
    from overtake_decision import OvertakeDecisionEngine
    from return_strategy import ReturnStrategy
    from status_management import StatusManager
    from web_interface import WebInterface
    from lane_calculator import EnhancedLanePositionSystem
except ImportError:
    import sys
    import os
    current_dir = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, current_dir)
    
    from config import Config
    from vehicle_tracker import VehicleTrackingCenter
    from lane_change_verification import LaneChangeVerificationSystem
    from overtake_decision import OvertakeDecisionEngine
    from return_strategy import ReturnStrategy
    from status_management import StatusManager
    from web_interface import WebInterface
    from lane_calculator import EnhancedLanePositionSystem

# 导入声音模块
try:
    from sound_center import request_sound, request_sound_index
except ImportError:
    # 模拟声音模块，如果真实模块不存在
    def request_sound_index(index):
        pass  # 去掉打印信息
    def request_sound(sound_name):
        pass  # 去掉打印信息

# 状态常量
LaneChangeOff = 0
preLaneChange = 1
laneChangeStarting = 2
laneChangeFinishing = 3

app_lanes = None
app_lanes_count = None
python_heart = True

def process_lane_array(arr, valid_count):
    global app_lanes
    global app_lanes_count
    app_lanes_count = valid_count
    if valid_count == 0:
        app_lanes = None
        return "| 使用自有计算 |"
    else:
        app_lanes = list(arr[:valid_count])
        return "| " + " | ".join(app_lanes) + " |"

def stop_python_heart():
    global python_heart
    python_heart = False

app_navi = False
def set_app_navi(enable):
    global app_navi
    app_navi = enable

class Ratekeeper:
    """频率控制器"""
    def __init__(self, rate, print_delay_threshold=None):
        self.rate = rate
        self.last_time = time.time()
        
    def keep_time(self):
        current = time.time()
        elapsed = current - self.last_time
        sleep_time = max(0, 1.0/self.rate - elapsed)
        if sleep_time > 0:
            time.sleep(sleep_time)
        self.last_time = time.time()


class AutoOvertakeController:
    """自动超车主控制器 - 重构版"""
    
    def __init__(self):
        # 初始化配置管理器
        self.config_manager = Config()
        self.config = self.config_manager.config
        
        # 初始化状态变量 - 先初始化基本属性
        self.running = True
        self.data_thread = None
        self.web_server = None
        self.udp_thread = None
        self.heartbeat_thread = None
        
        # 新增：导航车道数组相关 - 在初始化其他模块前初始化
        self.nav_lane_array = None
        self.nav_lane_array_history = deque(maxlen=10)
        self.nav_lane_array_confidence = 0.0
        self.last_nav_lane_array_time = 0
        
        # 新增：导航数据不可用标志 - 在这里初始化
        self.nav_data_unavailable = False
        
        # 1. 先初始化状态管理器（必须先于任何状态访问）
        self.status_manager = StatusManager()
        self.vehicle_data = self.status_manager.vehicle_data
        self.control_state = self.status_manager.control_state
        
        # 2. 初始化状态变量（包括基础状态变量）
        self._initialize_state_variables()
        
        # 3. 设置默认值（现在可以安全访问control_state了）
        self._setup_default_config()
        
        # 4. 初始化核心模块
        self._initialize_core_modules()
        
        # 5. 初始化网络和通信
        self._initialize_network()
        
        # 6. 初始化Web接口
        self.web_interface = WebInterface(self)
        
        # 7. 设置初始状态
        self._set_initial_state()
        
        # 8. 启动UDP监听
        self.start_udp_listener()
        
        print("AutoOvertakeController 初始化完成")
    
    def _setup_default_config(self):
        """设置配置默认值"""
        defaults = {
            'MAX_FOLLOW_TIME': 600000,
            'FOLLOW_TIME_GAP_THRESHOLD': 2.0,
            'autoOvertakeEnabled': True,
            'shouldReturnToLane': False,
            'road_type': 'normal',
            'lane_count': 3,
            'current_lane_number': 1,
            'lane_count_mode': 'auto',
            'MIN_OVERTAKE_INTERVAL': 8000,
            'HIGHWAY_MIN_SPEED': 60.0,
            'NORMAL_ROAD_MIN_SPEED': 30.0,
            'HIGHWAY_LEAD_MIN_SPEED': 40.0,
            'NORMAL_LEAD_MIN_SPEED': 30.0, 
            'LEAD_RELATIVE_SPEED_THRESHOLD': -3.0,
            'CRUISE_SPEED_RATIO_THRESHOLD': 0.85,
            'PENALTY_THRESHOLD': 60,
            'MIN_SPEED_ADVANTAGE': 5.0,
            'MIN_EFFECTIVENESS_THRESHOLD': 60,
            'HIGHWAY_EFFECTIVENESS_THRESHOLD': 65,
            'MIN_LANE_WIDTH': 2.3,
            'SAFE_LANE_WIDTH': 3.0,
            'SIDE_LEAD_DISTANCE_MIN': 20.0,
            'EFFECTIVENESS_DISTANCE_LIMIT': 50.0,
            'SIDE_VS_FRONT_SAFETY_MARGIN': 5.0,
            'LANE_COUNT_PERCENT_ADJUST': 1.0  # 新增：车道总数百分比调整
        }
        
        for key, value in defaults.items():
            if key not in self.config:
                self.config[key] = value
        
        # 设置返回策略超时
        if 'RETURN_STRATEGY' in self.config:
            self.config['RETURN_STRATEGY']['highway']['return_timeout'] = 60000
            self.config['RETURN_STRATEGY']['normal']['return_timeout'] = 60000
        
        # 同步高速超车开关和返回功能
        self._sync_return_with_highway_switch()
        
        # 保存配置
        self.config_manager.save_persistent_config()
    
    def _initialize_core_modules(self):
        """初始化核心模块"""
        # 使用统一的车辆跟踪中心
        self.tracking_center = VehicleTrackingCenter()
        
        # 车道验证系统
        self.verification_system = LaneChangeVerificationSystem()
        
        # 超车决策引擎
        self.overtake_decision = OvertakeDecisionEngine(self.config)
        
        # 返回策略
        self.return_strategy = ReturnStrategy(self.config)
        
        # 增强版车道位置系统
        self.lane_position_system = EnhancedLanePositionSystem()
        
        # 设置车道总数百分比调整
        lane_count_percent = self.config.get('LANE_COUNT_PERCENT_ADJUST', 1.0)
        self.lane_position_system.set_lane_count_percent_adjust(lane_count_percent)
        
        # 设置模块间依赖关系
        self._setup_module_dependencies()
    
    def _setup_module_dependencies(self):
        """设置模块间依赖关系"""
        # 设置跟踪中心到各个模块
        self.overtake_decision.set_tracking_center(self.tracking_center)
        self.return_strategy.set_tracking_center(self.tracking_center)
        
        # 设置车道位置系统到决策引擎
        self.overtake_decision.set_lane_position_system(self.lane_position_system)
        
        # 设置返回策略到决策引擎
        self.overtake_decision.return_strategy = self.return_strategy
    
    def _initialize_network(self):
        """初始化网络通信"""
        self.local_port = 4210
        self.remote_port = 4211
        self.remote_ip = "127.0.0.1"
        self.udp_socket = None
        self.udp_running = True
        self.connection_established = False
        
        # 数据超时管理
        self.last_op_data_time = 0
        self.op_data_timeout = 5000
        
        # 客户端管理
        self.esp32_clients = {}
        
        # 命令管理
        self.cmd_index = 1
        self.last_command_time = 0
        self.last_heartbeat_time = 0
        self.heartbeat_interval = 5000
    
    def _initialize_state_variables(self):
        """初始化状态变量"""
        
        # 决策计时
        self.last_decision_time = 0
        self.decision_interval = 2000  # 2秒
        
        # 车道管理
        self.lane_change_cnt = 0
        self.lane_change_finishing = False
        self.last_lane_count_calc = 0
        
        # 跟踪器更新间隔
        self.tracker_update_interval = 100
        self.last_tracker_update_time = 0
        
        # 道路类型模式
        self.road_type_mode = 'auto'
        self.last_manual_road_type = None
        self.last_roadcate = None
        
        # 数据稳定性管理
        self.data_stability_manager = {
            'last_vehicle_data': {},
            'data_persistence': self._create_data_persistence_config(),
            'debug_log': []
        }
        
        # 返回原车道状态管理器
        self.return_status = {
            'need_return': False,
            'tracking_target': False,
            'target_side': None,
            'target_lost': False,
            'overtaking_complete': False,
            'return_ready': False,
            'returning': False,
            'timeout_start': 0,
            'timeout_duration': 60000,
            'target_disappear_start': 0,
            'max_target_wait': 15000,
            'return_reason': '',
            'progress': 0,
            'return_start_time': 0,
            'last_safe_check_time': 0
        }
        
        # 车道变化安全监控
        self.lane_change_safety_monitor = {
            'last_safety_check': 0,
            'safety_check_interval': 500,
            'consecutive_unsafe_count': 0,
            'max_unsafe_count': 3
        }
        
        # 车辆消失管理
        self.vehicle_disappearance_manager = {
            'left_vehicle_disappeared': False,
            'right_vehicle_disappeared': False,
            'left_disappearance_start': 0,
            'right_disappearance_start': 0,
            'min_disappearance_duration': 2000
        }
        
        # 新增：禁止车道处理相关
        self.forbidden_lane_handler = {
            'current_lane_forbidden': False,
            'forbidden_lane_check_time': 0,
            'forbidden_lane_check_interval': 1000,
            'forced_change_attempts': 0,
            'max_forced_change_attempts': 3,
            'last_forced_change_time': 0
        }
        
        # 新增：推荐车道处理相关
        self.recommended_lane_handler = {
            'last_recommended_check_time': 0,
            'recommended_check_interval': 1000,
            'is_on_recommended_lane': False,
            'recommended_lanes': []
        }
        
        # ---------- 新增：后备定时器（自动变道后3秒强制即时更新） ----------
        self.pending_immediate_update = {
            'active': False,
            'direction': None,
            'start_time': 0,
            'timeout': 3000  # 3秒
        }
    
    def _create_data_persistence_config(self):
        """创建数据持久化配置"""
        return {
            'vlead': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'drel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'vrel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'l_vlead': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'l_drel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'l_vrel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'r_vlead': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'r_drel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'r_vrel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'lf_drel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'lb_drel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'rf_drel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'rb_drel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'lf_vrel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'lb_vrel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'rf_vrel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
            'rb_vrel': {'value': None, 'last_seen': 0, 'persistence_time': 500},
        }
    
    def _set_initial_state(self):
        """设置初始状态"""
        self.control_state.update({
            'overtakeState': "系统已就绪🚗随时准备超车",
            'overtakeReason': "等待操作指令...",
            'current_status': "就绪",
            'last_status_update_time': time.time() * 1000,
            'system_status_detail': "初始化完成",
            'decision_phase': "等待数据",
            'lane_analysis': "未开始",
            'safety_check': "待检查"
        })
    
    # ==================== 配置管理 ====================
    
    def _sync_return_with_highway_switch(self):
        """同步高速超车开关和返回功能"""
        # 如果高速超车开关关闭，强制关闭返回功能
        if not self.config.get('autoOvertakeEnabled', True):
            self.config['shouldReturnToLane'] = False
            if 'RETURN_STRATEGY' in self.config and 'highway' in self.config['RETURN_STRATEGY']:
                self.config['RETURN_STRATEGY']['highway']['enabled'] = False
            
            # 同步到控制状态
            if self.control_state.get('net_lane_changes', 0) != 0:
                self.reset_net_lane_changes()
        else:
            # 高速超车开关开启时，保持用户设置
            current_return = self.config.get('shouldReturnToLane', False)
            self.config['shouldReturnToLane'] = current_return
            if 'RETURN_STRATEGY' in self.config and 'highway' in self.config['RETURN_STRATEGY']:
                self.config['RETURN_STRATEGY']['highway']['enabled'] = current_return
    
    def update_config(self, config_key, value):
        """更新配置，并同步相关功能"""
        if config_key == 'autoOvertakeEnabled':
            # 保存当前返回状态（更新前）
            old_return_state = self.config.get('shouldReturnToLane', False)
            
            # 更新配置
            self.config[config_key] = value
            
            # 记录用户上一次的返回设置（只记录开启状态）
            if 'last_return_state' not in self.config:
                self.config['last_return_state'] = False
            
            if old_return_state:
                self.config['last_return_state'] = True
            
            # 同步返回功能
            self._sync_return_with_highway_switch()
            
            # 如果是开启高速超车，并且用户之前设置过开启返回，就恢复
            if value and self.config.get('last_return_state', False):
                self.config['shouldReturnToLane'] = True
                if 'RETURN_STRATEGY' in self.config and 'highway' in self.config['RETURN_STRATEGY']:
                    self.config['RETURN_STRATEGY']['highway']['enabled'] = True
                
                self.control_state.update({
                    'overtakeState': "高速超车和返回功能已开启",
                    'overtakeReason': "高速公路超车和返回功能已开启",
                    'current_status': "就绪"
                })
            
            # 如果关闭了高速超车，重置当前状态
            if not value:
                self.reset_net_lane_changes()
                if self.config['road_type'] == 'highway':
                    self.control_state.update({
                        'overtakeState': "高速超车已关闭",
                        'overtakeReason': "高速公路超车开关已关闭，返回功能自动关闭",
                        'current_status': "超车关闭"
                    })
        elif config_key == 'LANE_COUNT_PERCENT_ADJUST':
            # 更新车道总数百分比调整
            if 0.5 <= value <= 2.0:
                self.config[config_key] = value
                # 同步到车道位置系统
                self.lane_position_system.set_lane_count_percent_adjust(value)
            else:
                return False, f"无效的车道总数百分比调整值: {value}，应在0.5-2.0之间"
        else:
            self.config[config_key] = value
        
        # 保存配置
        self.save_persistent_config()
        return True
    
    def save_persistent_config(self):
        """保存持久化配置"""
        self.config_manager.save_persistent_config()
    
    # ==================== 主循环控制 ====================
    
    def start(self):
        """启动控制器"""
        print("现代汽车自动超车控制器")
        print("版本: V4.1.3 - 优化超车逻辑与稳定性")
        print("访问地址: http://<ip>:8088")
        print("----------------------------------------")
        
        # 确保配置管理器保存配置
        self.config_manager.save_persistent_config()
        
        # 启动所有线程和服务
        try:
            # 启动数据循环线程
            self.data_thread = threading.Thread(target=self.run_data_loop, daemon=True)
            self.data_thread.start()
            
            # 启动Web服务器
            self.web_interface.start_web_server()
            
            # 显示本地IP地址
            local_ip = self.get_local_ip()
            print(f"本地IP地址: {local_ip}")
            print(f"Web界面地址: http://{local_ip}:8088")
            
            # 等待UDP连接建立
            connection_wait_time = 0
            while not self.connection_established and connection_wait_time < 30 and self.running:
                time.sleep(1)
                connection_wait_time += 1
            
            if self.connection_established:
                print("✓ 数据连接已建立")
                self.control_state.update({
                    'overtakeState': "系统已就绪🚗随时准备超车",
                    'overtakeReason': "等待操作指令...",
                    'current_status': "就绪",
                    'system_status_detail': "系统正常运行中"
                })
            else:
                print("⚠ 警告: 未建立数据连接，系统将在本地模式下运行")
                self.control_state.update({
                    'overtakeState': "系统就绪（离线模式）",
                    'overtakeReason': "等待数据连接...",
                    'current_status': "离线",
                    'system_status_detail': "等待数据连接"
                })
            
            print("----------------------------------------")
            print("系统启动完成，按 Ctrl+C 退出程序")
            print("----------------------------------------")
            
            # 保持主线程运行
            while self.running:
                time.sleep(1)
                
        except KeyboardInterrupt:
            print("\n接收到中断信号，正在关闭系统...")
            self.stop()
        except Exception as e:
            print(f"系统启动异常: {e}")
            traceback.print_exc()
            self.stop()
    
    def stop(self):
        """停止控制器"""
        print("正在关闭系统...")
        
        # 设置停止标志
        self.running = False
        self.udp_running = False
        
        # 停止UDP线程
        if self.udp_thread and self.udp_thread.is_alive():
            try:
                if self.udp_socket:
                    self.udp_socket.close()
            except Exception:
                pass
            self.udp_thread.join(timeout=2)
        
        # 停止心跳线程
        if self.heartbeat_thread and self.heartbeat_thread.is_alive():
            self.heartbeat_thread.join(timeout=2)
        
        # 停止Web服务器
        if hasattr(self.web_interface, 'web_server') and self.web_interface.web_server:
            try:
                self.web_interface.web_server.shutdown()
            except Exception:
                pass
        
        # 停止数据线程
        if self.data_thread and self.data_thread.is_alive():
            self.data_thread.join(timeout=3)
        
        # 关闭UDP socket
        if self.udp_socket:
            try:
                self.udp_socket.close()
            except Exception:
                pass
        
        # 保存配置
        try:
            self.save_persistent_config()
        except Exception:
            pass
        
        # 清理资源
        self.control_state.update({
            'overtakeState': "系统已关闭",
            'overtakeReason': "系统正在关闭...",
            'current_status': "关闭",
            'system_status_detail': "系统关闭中"
        })
        
        # 短暂延时，让状态更新完成
        time.sleep(0.5)
        
        print("系统已完全关闭")
    
    def run_data_loop(self):
        """运行数据循环"""
        ratekeeper = Ratekeeper(10)
        
        # 状态管理相关变量
        last_state_change_time = 0
        state_history = []  # 状态历史记录
        max_history_size = 5  # 最大历史记录数
        
        while self.running:
            try:
                current_time = time.time() * 1000
                
                # ==================== 状态修复逻辑 ====================
                self._check_state_repair(current_time)
                
                # ==================== 数据更新 ====================
                self.update_vehicle_data()
                self.update_lane_number()
                self.update_curve_detection()
                self.update_following_status()
                
                # 车道计算（每500ms一次）
                if current_time - self.last_lane_count_calc > 500:
                    self.calculate_lane_count()
                    self.last_lane_count_calc = current_time

                # ==================== 返回原车道逻辑 ====================
                self._handle_return_logic(current_time)

                # ==================== 状态恢复逻辑优化 ====================
                self._check_state_recovery(current_time, last_state_change_time, state_history, max_history_size)

                # ==================== 定时器处理 ====================
                self._handle_timers(current_time)

                # ==================== 长时间无操作恢复 ====================
                self._check_inactivity_timeout(current_time)
                
                # ==================== 禁止车道处理 ====================
                self._handle_forbidden_lane(current_time)

                # ==================== 推荐车道检查 ====================
                self._check_and_change_to_recommended_lane(current_time)

                # ==================== 超车逻辑 ====================
                self._handle_overtake_logic(current_time)

                self.check_manual_lane_change_completion()
                self.control_state['last_status_update_time'] = current_time

                # ---------- 新增：检查后备定时器（自动变道后强制即时更新） ----------
                self.check_pending_immediate_update()

                # ---------- 新增：检查动态模式超时 ----------
                self.update_dynamic_mode()

                ratekeeper.keep_time()
                
            except Exception as e:
                time.sleep(0.1)
    
    def _check_state_repair(self, current_time):
        """检查并修复状态不一致"""
        # 基于atc_state的修复
        atc_state = self.vehicle_data.get('atc_state', 0)
        last_change_time = self.control_state.get('lastLaneChangeCommandTime', 0)
        time_since_last_command = current_time - last_change_time
        
        if (atc_state == 0 and 
            (self.control_state['isOvertaking'] or self.control_state['lane_change_in_progress']) and
            time_since_last_command > 5000):
            
            current_lane = self.config.get('current_lane_number', 0)
            original_lane = self.control_state.get('original_lane_number', 0)
            
            if current_lane == original_lane or abs(current_lane - original_lane) <= 1:
                self.control_state.update({
                    'isOvertaking': False,
                    'lane_change_in_progress': False,
                    'overtakingCompleted': False,
                    'overtakeState': "系统已就绪🚗随时准备超车",
                    'overtakeReason': "状态不一致，自动修复",
                    'current_status': "就绪",
                    'system_status_detail': "状态修复"
                })
        
        # 超时修复逻辑（15秒）
        last_change_time = self.control_state.get('lastLaneChangeCommandTime', 0)
        if last_change_time > 0 and (current_time - last_change_time > 15000):
            if self.control_state['isOvertaking'] or self.control_state['lane_change_in_progress']:
                self.control_state.update({
                    'isOvertaking': False,
                    'lane_change_in_progress': False,
                    'overtakingCompleted': False,
                    'overtakeState': "变道超时，自动恢复",
                    'overtakeReason': "15秒未完成变道，重置状态",
                    'current_status': "就绪",
                    'system_status_detail': "超时修复"
                })
    
    def _handle_return_logic(self, current_time):
        """处理返回原车道逻辑"""
        if self.control_state['net_lane_changes'] != 0:
            # 1. 更新返回状态
            self.update_return_state(self.vehicle_data, self.control_state)
            
            # 2. 检查返回完成（如果正在返回）
            self.check_return_completion(self.control_state)
            
            # 3. 检查60秒超时
            if self.check_return_timeout(self.control_state):
                # 超时已处理，继续循环
                pass
            
            # 4. 如果返回就绪且安全，执行返回
            if (self.return_status['return_ready'] and 
                not self.return_status['returning'] and
                not self.control_state['isOvertaking'] and
                not self.control_state['lane_change_in_progress'] and
                self.config['shouldReturnToLane']):
                
                # 检查超车开关
                road_type = self.config['road_type']
                if road_type == 'highway':
                    if not self.config['autoOvertakeEnabled']:
                        return
                else:
                    if not self.config['autoOvertakeEnabledL']:
                        return
                
                # 再次安全检查
                is_safe, safety_reason = self.check_return_safety(
                    self.vehicle_data, self.control_state
                )
                
                if is_safe:
                    success, reason = self.execute_return(self.vehicle_data, self.control_state)
    
    def _check_state_recovery(self, current_time, last_state_change_time, state_history, max_history_size):
        """检查状态恢复"""
        current_state = self.control_state.get('overtakeState', '')

        # 如果状态变化了，记录变化时间和重置历史
        if 'last_state' not in locals() or current_state != locals().get('last_state', ''):
            last_state_change_time = current_time
            state_history = []  # 状态变化时清空历史
            
        last_state = current_state

        # 如果不是"就绪"状态，检查是否应该重置
        if current_state != "系统已就绪🚗随时准备超车":
            # 检查是否真正处于活动状态
            is_active = (
                self.control_state.get('isOvertaking', False) or 
                self.control_state.get('lane_change_in_progress', False)
            )
            
            # 将当前活跃状态添加到历史记录
            state_history.append(is_active)
            if len(state_history) > max_history_size:
                state_history.pop(0)
            
            # 改进的重置条件：
            time_condition = current_time - last_state_change_time > 5000  # 5秒
            state_condition = len(state_history) >= 3 and all(not active for active in state_history[-3:])
            not_critical = (not self.control_state.get('isOvertaking', False) and 
                        not self.control_state.get('lane_change_in_progress', False))
            
            # 新增：检查是否需要返回原车道，如果需要则不重置净变道数
            need_return = self.return_status['need_return'] or self.control_state['net_lane_changes'] != 0
            
            if not is_active and time_condition and state_condition and not_critical and not need_return:
                # 只在不需要返回原车道时重置净变道数
                if not self.control_state.get('is_auto_overtake', False):
                    self.reset_net_lane_changes()
                    
                self.control_state.update({
                    'overtakeState': "系统已就绪🚗随时准备超车",
                    'overtakeReason': "持续5秒无活动，状态恢复",
                    'current_status': "就绪",
                    'system_status_detail': "状态恢复",
                    'isOvertaking': False,
                    'lane_change_in_progress': False
                })
                last_state_change_time = current_time
                state_history = []  # 重置后清空历史
    
    def _handle_timers(self, current_time):
        """处理定时器"""
        if (self.control_state.get('completion_timer') and 
            current_time >= self.control_state['completion_timer']):
            
            if not self.control_state['isOvertaking'] and not self.control_state['lane_change_in_progress']:
                self.control_state.update({
                    'overtakeState': "系统已就绪🚗随时准备超车",
                    'overtakeReason': "分析道路情况中...",
                    'current_status': "就绪",
                    'system_status_detail': "状态恢复"
                })
            del self.control_state['completion_timer']
    
    def _check_inactivity_timeout(self, current_time):
        """检查长时间无操作超时"""
        if (current_time - self.control_state.get('last_status_update_time', 0) > 10000 and
            not self.control_state.get('isOvertaking', False) and 
            not self.control_state.get('lane_change_in_progress', False) and
            self.control_state.get('overtakeState') != "系统已就绪🚗随时准备超车"):
            
            self.control_state.update({
                'overtakeState': "系统已就绪🚗随时准备超车",
                'overtakeReason': "长时间无操作，自动恢复",
                'current_status': "就绪",
                'system_status_detail': "超时恢复"
            })
    
    def _handle_forbidden_lane(self, current_time):
        """处理禁止车道情况 - 优先考虑推荐车道"""
        # 检查是否需要检查禁止车道
        if current_time - self.forbidden_lane_handler['forbidden_lane_check_time'] < self.forbidden_lane_handler['forbidden_lane_check_interval']:
            return
        
        self.forbidden_lane_handler['forbidden_lane_check_time'] = current_time
        
        # 获取当前车道
        current_lane = self.config.get('current_lane_number', 1)
        
        # 检查当前车道是否为禁止车道
        is_forbidden = self.lane_position_system.is_lane_forbidden(current_lane)
        
        if is_forbidden:
            self.forbidden_lane_handler['current_lane_forbidden'] = True
            
            # 如果已经在强制变道中，跳过
            if self.control_state.get('isOvertaking', False) or self.control_state.get('lane_change_in_progress', False):
                return
            
            # 检查是否超过最大尝试次数
            if (self.forbidden_lane_handler['forced_change_attempts'] >= 
                self.forbidden_lane_handler['max_forced_change_attempts']):
                
                # 重置尝试次数
                self.forbidden_lane_handler['forced_change_attempts'] = 0
                return
            
            # 检查变道冷却时间
            if current_time - self.forbidden_lane_handler['last_forced_change_time'] < 5000:
                return
            
            # 获取可用变道方向（排除禁止车道）
            available_directions = self._get_available_directions_for_forbidden_lane(current_lane)
            
            if available_directions:
                # 优先选择推荐车道方向
                best_direction = self._select_best_direction_for_forbidden_lane_with_recommended(available_directions)
                
                if best_direction:
                    # 执行变道
                    success = self._execute_forced_lane_change(best_direction, current_time, "禁止车道")
                    
                    if success:
                        self.forbidden_lane_handler['forced_change_attempts'] += 1
                        self.forbidden_lane_handler['last_forced_change_time'] = current_time
                        self.control_state['overtakeReason'] = f"当前车道{current_lane:.1f}为禁止车道，正在变道到可通行车道"
            else:
                self.control_state['overtakeReason'] = f"当前车道{current_lane:.1f}为禁止车道，但无安全变道方向"
        else:
            self.forbidden_lane_handler['current_lane_forbidden'] = False
            # 重置尝试次数
            self.forbidden_lane_handler['forced_change_attempts'] = 0
    
    def _get_available_directions_for_forbidden_lane(self, current_lane):
        """为禁止车道获取可用变道方向"""
        total_lanes = self.config.get('lane_count', 3)
        available_directions = []
        
        # 检查左方向
        if current_lane > 1:
            left_lane = current_lane - 1
            if not self.lane_position_system.is_lane_forbidden(left_lane):
                # 检查左车道是否安全
                if self._check_direction_safety_for_forbidden_lane("左", left_lane):
                    available_directions.append(("左", left_lane))
        
        # 检查右方向
        if current_lane < total_lanes:
            right_lane = current_lane + 1
            if not self.lane_position_system.is_lane_forbidden(right_lane):
                # 检查右车道是否安全
                if self._check_direction_safety_for_forbidden_lane("右", right_lane):
                    available_directions.append(("右", right_lane))
        
        return available_directions
    
    def _check_direction_safety_for_forbidden_lane(self, direction, target_lane):
        """检查禁止车道变道的安全性"""
        # 简化安全检查，主要检查盲区和激光雷达
        if direction == "左":
            # 检查左侧盲区
            if (self.vehicle_data.get('left_blindspot', False) or 
                self.vehicle_data.get('l_front_blind', False) or
                self.vehicle_data.get('lidar_lblind', False)):
                return False
            
            # 检查激光雷达距离
            lf_drel = self.vehicle_data.get('lf_drel')
            if lf_drel is not None and 0 < lf_drel < 10:
                return False
                
            lb_drel = self.vehicle_data.get('lb_drel')
            if lb_drel is not None and 0 < lb_drel < 8:
                return False
                
            # 检查雷达距离
            l_drel = self.vehicle_data.get('l_drel')
            if l_drel is not None and 0 < l_drel < 15:
                return False
        else:
            # 检查右侧盲区
            if (self.vehicle_data.get('right_blindspot', False) or 
                self.vehicle_data.get('r_front_blind', False) or
                self.vehicle_data.get('lidar_rblind', False)):
                return False
            
            # 检查激光雷达距离
            rf_drel = self.vehicle_data.get('rf_drel')
            if rf_drel is not None and 0 < rf_drel < 10:
                return False
                
            rb_drel = self.vehicle_data.get('rb_drel')
            if rb_drel is not None and 0 < rb_drel < 8:
                return False
                
            # 检查雷达距离
            r_drel = self.vehicle_data.get('r_drel')
            if r_drel is not None and 0 < r_drel < 15:
                return False
        
        return True
    
    def _select_best_direction_for_forbidden_lane_with_recommended(self, available_directions):
        """为禁止车道选择最佳变道方向 - 优先推荐车道"""
        if not available_directions:
            return None
        
        # 1. 优先选择推荐车道
        recommended_directions = []
        for direction, lane in available_directions:
            if self.lane_position_system.is_lane_recommended(lane):
                recommended_directions.append((direction, lane))
        
        if recommended_directions:
            # 从推荐车道中选择最安全的方向
            for direction, lane in recommended_directions:
                if self._check_direction_safety_for_forbidden_lane(direction, lane):
                    return direction
        
        # 2. 如果没有推荐车道或推荐车道不安全，选择安全的方向
        for direction, lane in available_directions:
            if self._check_direction_safety_for_forbidden_lane(direction, lane):
                return direction
        
        # 3. 都没有安全的，返回第一个方向（但会在执行时进行安全检查）
        return available_directions[0][0] if available_directions else None
    
    def _execute_forced_lane_change(self, direction, current_time, change_reason):
        """执行强制变道（用于禁止车道和推荐车道） - 添加完整超车条件检查"""
        # 获取道路类型
        road_type = self.config['road_type']
        
        # 1. 检查系统控制状态
        system_control = self.vehicle_data.get('system_auto_control', 0)
        if system_control >= 1:
            if system_control == 2: # and road_type == 'highway':
                self.control_state['overtakeState'] = "路段禁止变道"
                self.control_state['overtakeReason'] = "路段禁止超车，禁止变道"
                self.control_state['current_status'] = "路段禁止"
                self.control_state['system_status_detail'] = "路段禁止"
                return False
            elif system_control == 3:
                self.control_state['overtakeState'] = "缺少盲区设备"
                self.control_state['overtakeReason'] = "缺少盲区设备，禁止变道"
                self.control_state['current_status'] = "设备缺失"
                self.control_state['system_status_detail'] = "缺少盲区设备"
                return False
            elif system_control == 4:
                self.control_state['overtakeState'] = "车道领航控制中"
                self.control_state['overtakeReason'] = "车道领航控制中，禁止变道"
                self.control_state['current_status'] = "领航控制中"
                self.control_state['system_status_detail'] = "车道领航控制中"
                return False
            else:
                self.control_state['overtakeState'] = "OP控制中"
                self.control_state['overtakeReason'] = "OP自动控制中，禁止变道"
                self.control_state['current_status'] = "OP控制中"
                self.control_state['system_status_detail'] = "OP控制中"
                return False
        
        # 2. 车辆状态检查
        if not self.vehicle_data.get('IsOnroad', False):
            self.control_state['overtakeState'] = "OP不在线"
            self.control_state['overtakeReason'] = "OP不在线，禁止变道"
            self.control_state['current_status'] = "OP不在线"
            self.control_state['system_status_detail'] = "OP不在线"
            return False
        
        if not self.vehicle_data.get('engaged', False):
            self.control_state['overtakeState'] = "巡航未激活"
            self.control_state['overtakeReason'] = "巡航未激活，禁止变道"
            self.control_state['current_status'] = "巡航未激活"
            self.control_state['system_status_detail'] = "巡航未激活"
            return False
        
        # 3. 速度检查
        v_ego_kph = self.vehicle_data.get('v_ego_kph', 0)
        
        if road_type == 'highway':
            highway_min_speed = self.config.get('HIGHWAY_MIN_SPEED', 60.0)
            if v_ego_kph < highway_min_speed:
                self.control_state['overtakeState'] = "高速公路车速过低"
                self.control_state['overtakeReason'] = f"高速公路车速{v_ego_kph}km/h低于最低超车速度{highway_min_speed}km/h"
                self.control_state['current_status'] = "速度过低"
                self.control_state['system_status_detail'] = "高速公路速度不足"
                return False
        else:
            normal_min_speed = self.config.get('NORMAL_ROAD_MIN_SPEED', 30.0)
            if v_ego_kph < normal_min_speed:
                self.control_state['overtakeState'] = "普通道路车速过低"
                self.control_state['overtakeReason'] = f"普通公路车速{v_ego_kph}km/h低于最低超车速度{normal_min_speed}km/h"
                self.control_state['current_status'] = "速度过低"
                self.control_state['system_status_detail'] = "普通道路速度不足"
                return False
        
        # 4. 检查冷却时间
        min_interval = self.config.get('MIN_OVERTAKE_INTERVAL', 8000)
        if current_time - self.last_decision_time < min_interval:
            remaining = (min_interval - (current_time - self.last_decision_time)) / 1000
            self.control_state['overtakeState'] = "超车冷却中"
            self.control_state['overtakeReason'] = f"超车间隔保护，请等待{remaining:.1f}秒"
            self.control_state['current_status'] = "冷却中"
            self.control_state['system_status_detail'] = "变道冷却"
            return False
        
        # 5. 安全检查
        is_safe, safety_reason = self.check_lane_safety_before_execute(direction)
        if not is_safe:
            self.control_state['overtakeState'] = f"{change_reason}变道安全检查失败"
            self.control_state['overtakeReason'] = f"{change_reason}变道不安全: {safety_reason}"
            self.control_state['current_status'] = "安全阻止"
            self.control_state['system_status_detail'] = f"安全阻止: {safety_reason}"
            return False
        
        # 6. 语音提示
        if direction == "左":
            request_sound_index(7)  
        else:
            request_sound_index(8)  
        
        # 等待语音播报
        time.sleep(2)
        
        # 7. 再次安全检查
        is_safe, safety_reason = self.check_lane_safety_before_execute(direction)
        if not is_safe:
            request_sound_index(16)  
            self.control_state['overtakeState'] = f"变道已取消"
            self.control_state['overtakeReason'] = f"语音播报期间检测到不安全: {safety_reason}"
            self.control_state['current_status'] = "安全阻止"
            self.control_state['system_status_detail'] = f"安全阻止: {safety_reason}"
            return False
        
        # 8. 发送命令
        command_direction = "LEFT" if direction == "左" else "RIGHT"
        success = self.send_command("OVERTAKE", command_direction)
        
        if success:
            self.last_decision_time = current_time
            
            self.control_state.update({
                'is_auto_overtake': True,
                'isOvertaking': True,
                'lane_change_in_progress': True,
                'lastOvertakeDirection': direction,
                'lastLaneChangeCommandTime': current_time,
                'overtakeState': f"{change_reason}变道: {direction}侧",
                'overtakeReason': f"{change_reason}，正在变道到{direction}侧",
                'current_status': f"{change_reason}变道",
                'system_status_detail': f"{change_reason}变道到{direction}侧"
            })
            
            return True
        
        return False
    
    def _check_and_change_to_recommended_lane(self, current_time):
        """检查并变道到推荐车道 - 使用与禁止车道相同的检查"""
        # 检查是否需要检查推荐车道
        if current_time - self.recommended_lane_handler['last_recommended_check_time'] < self.recommended_lane_handler['recommended_check_interval']:
            return
        
        self.recommended_lane_handler['last_recommended_check_time'] = current_time
        
        # 检查是否在推荐车道上
        current_lane = self.config.get('current_lane_number', 1)
        
        # 检查当前车道是否为推荐车道
        is_current_recommended = self.lane_position_system.is_lane_recommended(current_lane)
        self.recommended_lane_handler['is_on_recommended_lane'] = is_current_recommended
        
        # 如果在推荐车道上，更新状态并返回
        if is_current_recommended:
            if self.control_state.get('current_status') != "推荐车道":
                self.control_state.update({
                    'overtakeState': "已在推荐车道🚗",
                    'overtakeReason': "已在推荐车道，保持当前车道",
                    'current_status': "推荐车道",
                    'system_status_detail': "已在推荐车道"
                })
            return
        
        # 检查是否在禁行车道上（优先处理禁止车道）
        is_current_forbidden = self.lane_position_system.is_lane_forbidden(current_lane)
        if is_current_forbidden:
            return  # 禁止车道处理由其他逻辑处理
        
        # 检查是否正在超车或变道
        if (self.control_state.get('isOvertaking', False) or 
            self.control_state.get('lane_change_in_progress', False)):
            return
        
        # 检查冷却时间
        min_interval = self.config.get('MIN_OVERTAKE_INTERVAL', 8000)
        if current_time - self.last_decision_time < min_interval:
            remaining = (min_interval - (current_time - self.last_decision_time)) / 1000
            return
        
        # 检查是否有推荐车道
        total_lanes = self.config.get('lane_count', 3)
        recommended_lanes = []
        
        # 找出所有推荐车道
        for lane in range(1, total_lanes + 1):
            if self.lane_position_system.is_lane_recommended(lane):
                recommended_lanes.append(lane)
        
        self.recommended_lane_handler['recommended_lanes'] = recommended_lanes
        
        # 如果没有推荐车道，返回
        if not recommended_lanes:
            return
        
        # 选择最近的推荐车道
        nearest_recommended = min(recommended_lanes, key=lambda x: abs(x - current_lane))
        
        # 确定变道方向
        if nearest_recommended < current_lane:
            direction = "左"
            target_lane = current_lane - 1
        elif nearest_recommended > current_lane:
            direction = "右"
            target_lane = current_lane + 1
        else:
            return  # 已经在推荐车道上（不应该走到这里）
        
        # 检查目标车道是否为禁止车道
        if self.lane_position_system.is_lane_forbidden(target_lane):
            return
        
        # 使用与禁止车道相同的方法执行变道
        success = self._execute_forced_lane_change(direction, current_time, "推荐车道")
        
        if success:
            self.control_state.update({
                'overtakeState': f"变道到推荐车道{direction}侧",
                'overtakeReason': f"正在变道到推荐车道{target_lane}",
                'current_status': "推荐车道变道",
                'system_status_detail': f"变道到推荐车道{target_lane}"
            })
    
    def _handle_overtake_logic(self, current_time):
        """处理超车逻辑"""
        # 检查是否在推荐车道上
        current_lane = self.config.get('current_lane_number', 1)
        is_current_recommended = self.lane_position_system.is_lane_recommended(current_lane)
        
        # 如果在推荐车道上，不执行超车逻辑
        if is_current_recommended:
            # 检查是否有多个推荐车道
            recommended_lanes = self.recommended_lane_handler.get('recommended_lanes', [])
            if len(recommended_lanes) <= 1:
                return
        
        road_type = self.config['road_type']
        
        # 如果当前车道是禁止车道，优先处理禁止车道变道
        if self.lane_position_system.is_lane_forbidden(current_lane):
            return
        
        if road_type == 'highway':
            if self.config['autoOvertakeEnabled']:
                self.perform_auto_overtake(current_time)
                self.check_overtake_completion()
        else:
            if self.config['autoOvertakeEnabledL']:
                self.perform_auto_overtake(current_time)
                self.check_overtake_completion()
    
    # ==================== 网络通信 ====================
    
    def start_udp_listener(self):
        """启动UDP监听"""
        self.udp_thread = threading.Thread(target=self.udp_listener, daemon=True)
        self.udp_thread.start()
    
    def udp_listener(self):
        """UDP监听主循环"""
        try:
            self.udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self.udp_socket.settimeout(0.1)
            self.udp_socket.bind(('0.0.0.0', self.local_port))
            self.start_heartbeat()
            
            while self.udp_running:
                try:
                    data, addr = self.udp_socket.recvfrom(4096)
                    if data:
                        ip, port = addr
                        data_str = data.decode('utf-8')
                        self.process_op_data(data_str, ip, port)
                except socket.timeout:
                    current_time = time.time() * 1000
                    if current_time - self.last_op_data_time > self.op_data_timeout:
                        self.handle_timeout_safety()
                    continue
                except Exception:
                    time.sleep(0.01)
                    
        except Exception:
            time.sleep(1)
    
    def start_heartbeat(self):
        """启动心跳线程"""
        self.heartbeat_thread = threading.Thread(target=self.heartbeat_loop, daemon=True)
        self.heartbeat_thread.start()
    
    def heartbeat_loop(self):
        """心跳循环"""
        while self.udp_running:
            try:
                current_time = time.time() * 1000
                
                if current_time - self.last_heartbeat_time >= self.heartbeat_interval:
                    self.send_esp32_heartbeat()
                    self.last_heartbeat_time = current_time
                
                time.sleep(1)
                
            except Exception:
                time.sleep(1)
    
    def send_esp32_heartbeat(self):
        if not python_heart:
            return
        """发送ESP32心跳"""
        if not self.udp_socket:
            return
            
        try:
            heartbeat_data = {
                "resp": "heartbeat",
                "device": "overtake",
                "ip": self.get_local_ip(),
                "port": self.local_port,
                "timestamp": int(time.time() * 1000)
            }
            
            message = json.dumps(heartbeat_data).encode('utf-8')
            broadcast_addr = ('255.255.255.255', self.remote_port)
            self.udp_socket.sendto(message, broadcast_addr)
            
        except Exception:
            pass
    
    def process_op_data(self, data_str, source_ip, source_port):
        global app_lanes
        global app_lanes_count
        try:
            json_data = json.loads(data_str)
            self.last_op_data_time = time.time() * 1000
            
            # 处理导航车道数据
            if 'laneValidCount' in json_data:
                valid_count = json_data['laneValidCount']
                
                if valid_count == 0:
                    # valid_count=0，导航数据无效，立即完全切换到自主计算模式
                    self.nav_lane_array = None
                    self.nav_lane_array_history.clear()
                    self.nav_lane_array_confidence = 0.0
                    
                    # 设置导航数据不可用标志
                    self.nav_data_unavailable = True  
                    
                    # 清除所有导航相关数据
                    self.control_state['nav_data_status'] = "无效"
                    self.control_state['nav_data_reason'] = "valid_count=0"
                    self.control_state['nav_lane_info'] = "导航数据无效，已切换到自主计算模式"
                    self.control_state['nav_lane_confidence'] = "0%"
                    
                    # 立即切换为自主计算模式
                    if hasattr(self, 'lane_position_system'):
                        self.lane_position_system.set_nav_lane_array(None)  # 重要：传入 None 清除数据
                    
                elif 'laneStrArray' in json_data:
                    lane_array = json_data['laneStrArray']
                    if isinstance(lane_array, list) and len(lane_array) > 0:
                        # 正常导航数据
                        self.nav_lane_array = lane_array
                        self.nav_data_unavailable = False  
                        self.control_state['nav_data_status'] = "有效"
                        self.control_state['nav_lane_info'] = f"导航车道: {len(lane_array)}车道"
                        
                        # 更新车道位置系统
                        if hasattr(self, 'lane_position_system'):
                            self.lane_position_system.set_nav_lane_array(lane_array)
            
            # 兼容旧格式，没有laneValidCount字段
            elif 'laneStrArray' in json_data:
                lane_array = json_data['laneStrArray']
                if isinstance(lane_array, list) and len(lane_array) > 0:
                    self.nav_lane_array = lane_array
                    self.nav_data_unavailable = False  
                    self.control_state['nav_data_status'] = "有效"
                    
                    if hasattr(self, 'lane_position_system'):
                        self.lane_position_system.set_nav_lane_array(lane_array)
            
            if 'ip' in json_data and 'port' in json_data:
                remote_ip = json_data['ip']
                remote_port = json_data['port']
                
                if not self.connection_established:
                    self.remote_ip = remote_ip
                    self.connection_established = True
                
                self.send_connection_response()
                
            self.update_vehicle_data_from_op(json_data)
            
            self.esp32_clients[source_ip] = time.time()
            
        except json.JSONDecodeError:
            pass
        except Exception:
            pass
    
    def process_nav_lane_array(self, lane_array):
        """处理导航车道数组"""
        if lane_array is None:
            # valid_count=0 的情况：使用自有计算
            self.nav_lane_array = None
            self.lane_position_system.set_nav_lane_array(None)
            self.nav_data_unavailable = True  
            return
        
        # 正常情况
        current_time = time.time() * 1000
        
        if isinstance(lane_array, list):
            valid_lanes = [lane for lane in lane_array if lane is not None]
            
            if valid_lanes:
                self.nav_lane_array = valid_lanes
                self.last_nav_lane_array_time = current_time
                self.nav_data_unavailable = False  
                
                # 更新车道位置系统
                self.lane_position_system.set_nav_lane_array(valid_lanes)
                
                # 更新状态显示（可选）
                lane_str = self._format_lane_array_str(valid_lanes)
                self.control_state['nav_lane_info'] = lane_str
                self.control_state['nav_lane_confidence'] = f"{self.nav_lane_array_confidence:.0%}"
    
    def _format_lane_array_str(self, lane_array):
        """格式化车道数组为字符串"""
        if not lane_array:
            return "无数据"
        
        formatted = "|" + " | ".join(lane_array) + "|"
        
        # 添加推荐车道标记
        recommended_indices = []
        for i, lane in enumerate(lane_array):
            if "*" in lane:
                recommended_indices.append(i + 1)  # 转换为1-based
        
        if recommended_indices:
            formatted += f" 推荐车道:{recommended_indices}"
        
        return formatted
    
    def send_connection_response(self):
        """发送连接响应"""
        if not self.udp_socket:
            return
            
        try:
            response = {
                "resp": "overtake",
                "device": "overtake",
                "timestamp": int(time.time() * 1000)
            }
            
            message = json.dumps(response).encode('utf-8')
            self.udp_socket.sendto(message, (self.remote_ip, self.remote_port))
            
        except Exception:
            pass
    
    def handle_timeout_safety(self):
        """处理超时安全"""
        if self.control_state.get('isOvertaking', False) or self.control_state.get('lane_change_in_progress', False):
            self.control_state.update({
                'isOvertaking': False,
                'lane_change_in_progress': False,
                'overtakingCompleted': False,
                'overtakeState': "通讯超时-安全取消",
                'overtakeReason': "5秒未收到数据，安全第一",
                'current_status': "通讯超时",
                'system_status_detail': "通讯超时"
            })
        
        self.connection_established = False
        
        self.vehicle_data.update({
            'IsOnroad': False,
            'engaged': False,
            'system_auto_control': 0
        })
        
        self.reset_side_vehicle_trackers()
    
    def send_command(self, cmd_type, arg):
        """发送命令"""
        if not self.udp_socket or not self.connection_established:
            return False
            
        current_time = time.time() * 1000
        if current_time - self.last_op_data_time > self.op_data_timeout:
            self.handle_timeout_safety()
            return False
            
        command = {
            "index": self.cmd_index,
            "cmd": cmd_type,
            "arg": arg,
            "timestamp": int(current_time)
        }

        try:
            message = json.dumps(command).encode('utf-8')
            self.udp_socket.sendto(message, (self.remote_ip, self.remote_port))
            self.cmd_index += 1
            self.last_command_time = time.time()
            self.control_state['last_command'] = f"{cmd_type}: {arg}"
            return True
            
        except Exception:
            return False
    
    def get_local_ip(self):
        """获取本地IP地址"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect("8.8.8.8", 80)
            local_ip = s.getsockname()[0]
            s.close()
            return local_ip
        except Exception:
            return "127.0.0.1"
    
    # ==================== 车辆数据管理 ====================
    
    def update_vehicle_data_from_op(self, json_data):
        """从OP数据更新车辆数据"""
        current_time = time.time() * 1000
        
        # 处理距离数据
        self._process_distance_data_first(json_data, current_time)
        
        # 处理其他字段
        self._process_other_fields(json_data, current_time)
        
        # 应用数据持久化
        self._apply_data_persistence(current_time)
        
        # 处理声音播报
        self._process_sound(json_data)
        
        # 处理系统控制状态
        self._process_system_control(json_data, current_time)
        
        # 处理道路类型
        self._process_road_type(json_data)
        
        # 处理车道变化状态
        self._process_lane_change_state(json_data)
        
        # 使用统一跟踪中心更新车辆数据
        self.update_vehicle_tracking_center()
        
        # 更新状态
        self.control_state['system_status_detail'] = "数据处理完成"
        self.control_state['decision_phase'] = "等待决策"
    
    def _process_distance_data_first(self, json_data, current_time):
        """处理距离数据"""
        distance_fields = {
            'vlead': 'drel',
            'l_vlead': 'l_drel', 
            'r_vlead': 'r_drel'
        }
        
        for vlead_key, drel_key in distance_fields.items():
            has_vehicle = False
            if vlead_key in json_data or drel_key in json_data:
                vlead = json_data.get(vlead_key)
                drel = json_data.get(drel_key)
                
                if (vlead is not None and vlead >= 0) or (drel is not None and drel >= 0):
                    has_vehicle = True
            
            # 确定对应的lead键
            if vlead_key == 'vlead':
                lead_key = 'lead1'
            elif vlead_key == 'l_vlead':
                lead_key = 'l_lead'
            else:
                lead_key = 'r_lead'
                
            if lead_key in json_data:
                has_vehicle = bool(json_data[lead_key])
            
            # 设置到vehicle_data
            if vlead_key == 'vlead':
                self.vehicle_data['lead1'] = has_vehicle
            elif vlead_key == 'l_vlead':
                self.vehicle_data['l_lead'] = has_vehicle
            else:
                self.vehicle_data['r_lead'] = has_vehicle
        
        # 更新具体数值
        distance_fields_all = ['drel', 'l_drel', 'r_drel', 'vlead', 'l_vlead', 'r_vlead', 'vrel', 'l_vrel', 'r_vrel']
        for field in distance_fields_all:
            if field in json_data:
                value = json_data[field]
                self.vehicle_data[field] = value
                
                if field in self.data_stability_manager['data_persistence']:
                    self.data_stability_manager['data_persistence'][field] = {
                        'value': value,
                        'last_seen': current_time,
                        'persistence_time': 500
                    }
    
    def _process_other_fields(self, json_data, current_time):
        """处理其他字段"""
        other_fields = [
            'v_cruise_kph', 'v_ego_kph', 'desire_speed', 'cruise_speed',
            'steer_angle', 'aego', 'gas_press', 'break_press', 'engaged',
            'left_blindspot', 'right_blindspot', 
            'l_lane_width', 'r_lane_width', 'l_edge_dist', 'r_edge_dist',
            'lat_a', 'max_curve', 'blinker', 'active', 'IsOnroad',
            'lf_drel', 'lb_drel', 'rf_drel', 'rb_drel', 'lidar_lblind', 'lidar_rblind',
            'lf_vrel', 'lb_vrel', 'rf_vrel', 'rb_vrel'
        ]
        
        for field in other_fields:
            if field in json_data:
                our_field = field
                if field == 'steer_angle':
                    our_field = 'steering_angle'
                
                value = json_data[field]

                # 转换激光雷达距离（mm到m）
                if field in ['lf_drel', 'lb_drel', 'rf_drel', 'rb_drel']:
                    try:
                        raw_value = float(value)
                        value_in_meters = raw_value / 1000.0
                        value = value_in_meters
                    except (ValueError, TypeError):
                        value = None
                
                # 更新持久化
                if field in self.data_stability_manager['data_persistence']:
                    self.data_stability_manager['data_persistence'][field] = {
                        'value': value,
                        'last_seen': current_time,
                        'persistence_time': 500
                    }
                
                self.vehicle_data[our_field] = value
    
    def _apply_data_persistence(self, current_time):
        """应用数据持久化"""
        for field, persistence_data in self.data_stability_manager['data_persistence'].items():
            if current_time - persistence_data['last_seen'] < persistence_data['persistence_time']:
                if persistence_data['value'] is not None:
                    self.vehicle_data[field] = persistence_data['value']
            else:
                self.vehicle_data[field] = None
                
                # 重置lead标志
                if field == 'drel':
                    self.vehicle_data['lead1'] = False
                elif field == 'l_drel':
                    self.vehicle_data['l_lead'] = False
                elif field == 'r_drel':
                    self.vehicle_data['r_lead'] = False
    
    def _process_sound(self, json_data):
        """处理声音播报"""
        if 'sound' in json_data:
            sound_index = int(json_data['sound'])
            request_sound_index(sound_index)
    
    def _process_system_control(self, json_data, current_time):
        global app_navi
        """处理系统控制状态"""
        # 初始化盲区状态
        l_front_blind = json_data.get('l_front_blind', False)
        r_front_blind = json_data.get('r_front_blind', False)
        lidar_lblind = json_data.get('lidar_lblind', False)
        lidar_rblind = json_data.get('lidar_rblind', False)

        self.vehicle_data['l_front_blind'] = l_front_blind
        self.vehicle_data['r_front_blind'] = r_front_blind
        self.vehicle_data['lidar_lblind'] = lidar_lblind
        self.vehicle_data['lidar_rblind'] = lidar_rblind
        
        self.vehicle_data['system_auto_control'] = 0
        
        if json_data.get('IsOnroad', False) or app_navi:
            if app_navi:
                self.vehicle_data['system_auto_control'] = 4
            else:
                blind_enable = json_data.get('blind_enable', False)
                op_blocked = json_data.get('op_blocked', False)
                road_blocked = json_data.get('road_blocked', False)

                if not blind_enable or op_blocked or road_blocked:
                    is_op_controlling = (not blind_enable or op_blocked or road_blocked)

                    if is_op_controlling:
                        if not blind_enable:
                            self.vehicle_data['system_auto_control'] = 3
                        elif road_blocked:
                            self.vehicle_data['system_auto_control'] = 2
                        else:
                            self.vehicle_data['system_auto_control'] = 1

                        self.vehicle_data['last_op_control_time'] = current_time
                    else:
                        self.vehicle_data['system_auto_control'] = 0
                else:
                    self.vehicle_data['system_auto_control'] = 0
        else:
            last_op_time = self.vehicle_data.get('last_op_control_time', 0)
            
            if self.vehicle_data.get('system_auto_control', 0) >= 1:
                if current_time - last_op_time > 30000:
                    self.vehicle_data['system_auto_control'] = 0
            else:
                self.vehicle_data['system_auto_control'] = 0
        
        # 处理道路曲率
        if 'max_curve' in json_data:
            self.vehicle_data['road_curvature'] = json_data['max_curve']
    
    def _process_road_type(self, json_data):
        """处理道路类型"""
        if 'roadcate' in json_data:
            roadcate = int(json_data['roadcate'])
            self.last_roadcate = roadcate
            self.config['roadcate'] = roadcate
            
            # 检测道路类型变化
            old_road_type = self.config.get('road_type', 'normal')
            
            if self.road_type_mode == 'auto':
                # 自动模式，根据roadcate更新道路类型
                if roadcate == 0 or roadcate == 1:
                    new_road_type = 'highway'
                else:
                    new_road_type = 'normal'
                
                if new_road_type != old_road_type:
                    self.config['road_type'] = new_road_type
                    
                    # 处理道路类型变化
                    self._handle_road_type_change(old_road_type, new_road_type)
            elif self.road_type_mode == 'manual':
                # 手动模式，检查手动设置是否与自动检测一致
                auto_road_type = 'highway' if (roadcate == 0 or roadcate == 1) else 'normal'
                if self.config['road_type'] == auto_road_type:
                    # 一致，自动切换到自动模式
                    self.road_type_mode = 'auto'
                    self.last_manual_road_type = None
                    self.config['road_type'] = auto_road_type
                    
                    # 移除配置中的手动设置记录
                    if 'last_manual_road_type' in self.config:
                        del self.config['last_manual_road_type']
                    
                    # 处理道路类型变化
                    self._handle_road_type_change(old_road_type, auto_road_type)
    
    def _handle_road_type_change(self, old_road_type, new_road_type):
        """处理道路类型变化"""
        if new_road_type == 'highway':
            if not self.config.get('autoOvertakeEnabled', True):
                # 高速超车关闭，强制关闭返回功能
                self.config['shouldReturnToLane'] = False
                if 'RETURN_STRATEGY' in self.config and 'highway' in self.config['RETURN_STRATEGY']:
                    self.config['RETURN_STRATEGY']['highway']['enabled'] = False
                
                self.control_state['system_status_detail'] = f"检测到高速公路，但高速超车已关闭"
            else:
                # 高速超车开启，恢复用户上次的返回设置
                if self.config.get('last_return_state', False):
                    self.config['shouldReturnToLane'] = True
                    if 'RETURN_STRATEGY' in self.config and 'highway' in self.config['RETURN_STRATEGY']:
                        self.config['RETURN_STRATEGY']['highway']['enabled'] = True
                    
                    self.control_state['system_status_detail'] = f"检测到高速公路，已恢复返回功能"
        elif new_road_type == 'normal' and old_road_type == 'highway':
            # 从高速公路切换到普通公路，重置净变道次数
            self.reset_net_lane_changes()
    
    def update_vehicle_tracking_center(self):
        """更新车辆跟踪中心 - 确保数据完整性"""
        current_time = time.time() * 1000
        
        if current_time - self.last_tracker_update_time < self.tracker_update_interval:
            return
            
        self.last_tracker_update_time = current_time
        
        # 确保 vehicle_data 中有必要的前车数据
        # 如果有 lead1 标志，但某些字段缺失，补充默认值
        if self.vehicle_data.get('lead1', False):
            # 确保有必要的字段
            if self.vehicle_data.get('drel') is None:
                self.vehicle_data['drel'] = 10.0  # 默认10米
            if self.vehicle_data.get('vlead') is None:
                self.vehicle_data['vlead'] = 60.0  # 默认60km/h
            if self.vehicle_data.get('vrel') is None:
                self.vehicle_data['vrel'] = -5.0   # 默认相对速度-5km/h
        
        # 使用跟踪中心的统一更新接口
        success = self.tracking_center.update_from_vehicle_data(self.vehicle_data)
        
        return success
    
    def reset_side_vehicle_trackers(self):
        """重置侧方车辆跟踪器"""
        self.tracking_center.reset_all()
        self.vehicle_disappearance_manager.update({
            'left_vehicle_disappeared': False,
            'right_vehicle_disappeared': False,
            'left_disappearance_start': 0,
            'right_disappearance_start': 0
        })

    def _process_lane_change_state(self, json_data):
        """处理车道变化状态 - 添加即时更新"""
        if 'atc_state' in json_data:
            atc_state = int(json_data['atc_state'])
            
            # 检测变道完成
            if self.lane_change_finishing and atc_state != laneChangeFinishing:
                self.lane_change_cnt += 1
                self.control_state['overtakeSuccessCount'] += 1
                self.lane_change_finishing = False
                
                # 关键修改：检测到变道完成时立即更新车道
                if self.control_state.get('lastOvertakeDirection'):
                    # 立即更新车道位置
                    new_lane, result_desc = self.lane_position_system.request_immediate_lane_update(
                        self.control_state['lastOvertakeDirection'], 
                        self.config
                    )
                    
                    # 更新配置
                    old_lane = self.config.get('current_lane_number', 1)
                    self.config['current_lane_number'] = new_lane
                    
                    # 记录超车完成
                    self.overtake_decision.record_overtake_completion(self.control_state['lastOvertakeDirection'])
                    
                    # 如果是自动超车，更新净变道数
                    if self.control_state.get('is_auto_overtake', False):
                        self.update_net_lane_changes(self.control_state['lastOvertakeDirection'], True)
                    
                    # 更新控制状态
                    direction = self.control_state['lastOvertakeDirection']
                    self.control_state.update({
                        'overtakeState': f"{direction}变道完成 (车道{old_lane}→{new_lane})",
                        'overtakeReason': f"变道完成: {result_desc}",
                        'current_status': "变道完成",
                        'system_status_detail': f"车道{new_lane}已更新",
                        'lane_position_info': f"即时更新: {old_lane}→{new_lane}"
                    })
                    
                    # ---------- 新增：取消后备定时器 ----------
                    self.pending_immediate_update['active'] = False
                    
                    # ---------- 新增：设置动态模式为 post_change ----------
                    self.lane_position_system.lane_calculator.set_dynamic_mode('post_change', time.time() * 1000)
                
            # 检测变道即将完成
            if atc_state == laneChangeFinishing:
                self.lane_change_finishing = True
                
                # 提前准备更新车道位置显示
                if self.control_state.get('lastOvertakeDirection'):
                    direction = self.control_state['lastOvertakeDirection']
                    current_lane = self.config.get('current_lane_number', 1)
                    total_lanes = self.config.get('lane_count', 3)
                    
                    # 计算预期的新车道
                    if direction == "左":
                        expected_lane = max(1, current_lane - 1)
                    else:
                        expected_lane = min(total_lanes, current_lane + 1)
                    
                    # 预更新显示
                    self.control_state['lane_position_info'] = f"变道进行中: {current_lane}→{expected_lane}"

    # ==================== 车道管理 ====================
    def calculate_lane_count(self):
        """计算车道总数 - 委托给车道位置系统"""
        # 使用增强系统计算车道位置
        result = self.lane_position_system.update_lane_position(self.vehicle_data, self.config)
                
        # 检查是否为手动模式
        lane_count_mode = self.config.get('lane_count_mode', 'auto')
        manual_lane_count = self.config.get('lane_count', 3)
                
        if lane_count_mode == 'manual':
            # 手动模式
            total_lanes = manual_lane_count
            lane_count_details = f"手动模式，车道数设置为{total_lanes}"
            overall_confidence = 1.0
                        
            # 即使有导航数据，手动模式也以用户设置为准
            if self.nav_lane_array is not None:
                lane_count_details += f" (导航数据: {len(self.nav_lane_array)}车道，但手动模式优先)"
        else:
            # 自动模式：当导航数据无效时，强制使用自主计算
            nav_unavailable = getattr(self, 'nav_data_unavailable', True)
                        
            if nav_unavailable:
                # 导航数据无效，强制使用自主计算
                total_lanes = result['lane_count']
                lane_count_details = f"导航数据无效，使用自主计算: {result['lane_count_details']}"
                overall_confidence = result['overall_confidence']
                                
                # 更新状态显示
                self.control_state['nav_data_status'] = "无效"
                self.control_state['nav_data_reason'] = "valid_count=0"
                self.control_state['nav_lane_info'] = "导航数据无效，使用自主计算"
            else:
                # 导航数据有效，使用导航数据
                if self.nav_lane_array is not None and len(self.nav_lane_array) > 0:
                    total_lanes = len(self.nav_lane_array)
                    lane_count_details = f"导航车道数组: {len(self.nav_lane_array)}车道"
                    overall_confidence = 0.95
                    
                else:
                    # 没有导航数据，使用自主计算
                    total_lanes = result['lane_count']
                    lane_count_details = result['lane_count_details']
                    overall_confidence = result['overall_confidence']
                    
        
        # 更新配置
        old_lane_count = self.config.get('lane_count', 3)
        self.config['lane_count'] = total_lanes
        self.config['current_lane_number'] = result['current_lane']
        
        # 更新控制状态显示
        self.control_state['lane_count_confidence'] = f"{overall_confidence:.0%}"
        
        # 确定车道计算方法
        if lane_count_mode == 'manual':
            method_name = "手动模式"
        elif nav_unavailable:
            method_name = "自主计算(强制)"
        elif self.nav_lane_array is not None:
            method_name = "导航数据"
        else:
            method_name = "自主计算"
        
        self.control_state['lane_count_method'] = method_name
                
        # 记录详细日志
        self.control_state['lane_calculation_details'] = lane_count_details
        if 'lane_position_details' in result:
            self.control_state['lane_position_details'] = result['lane_position_details']
        
        return total_lanes

    def update_lane_number(self):
        """更新车道编号 - 委托给车道位置系统"""
        # 使用增强系统计算车道位置
        result = self.lane_position_system.update_lane_position(self.vehicle_data, self.config)
        
        # 检查是否为手动模式
        lane_count_mode = self.config.get('lane_count_mode', 'auto')
        
        if lane_count_mode == 'manual':
            # 手动模式：直接使用配置中的车道数
            manual_lane_count = self.config.get('lane_count', 3)
            manual_current_lane = self.config.get('current_lane_number', 1)
            
            # 验证手动设置的车道数合理性
            if 1 <= manual_lane_count <= 6:
                new_lane = max(1, min(manual_current_lane, manual_lane_count))
            else:
                new_lane = result['current_lane']
        else:
            # 自动模式：使用计算出的车道位置
            new_lane = result['current_lane']
        
        old_lane = self.config['current_lane_number']
        
        if new_lane != old_lane:
            # 车道发生变化
            is_auto_overtake = self.control_state.get('is_auto_overtake', False)
            is_overtaking = self.control_state.get('isOvertaking', False)
            lane_change_in_progress = self.control_state.get('lane_change_in_progress', False)
            last_change_time = self.control_state.get('lastLaneChangeCommandTime', 0)
            
            # 判断是否为自动变道的延续
            recent_auto_change = (
                is_auto_overtake and 
                lane_change_in_progress and
                (time.time() * 1000 - last_change_time) < 10000
            )
            
            # 检查是否需要返回
            need_return = self.control_state.get('net_lane_changes', 0) != 0
            
            # 判断是否为手动变道或其他原因
            is_manual_change = (
                not recent_auto_change and  # 不是最近的自动变道
                not lane_change_in_progress and  # 不在变道进行中
                not need_return  # 不需要返回原车道
            )
            
            if is_manual_change:
                # 手动变道或其他非自动变道原因，重置净变道数
                self.reset_net_lane_changes()
                
                # 更新车道
                self.config['current_lane_number'] = new_lane
                
                # 更新控制状态中的车道信息
                self.control_state['lane_position_info'] = f"非自动变道到车道{new_lane}"
                
                # 记录详细原因
                if 'lane_position_details' in result:
                    self.control_state['lane_change_reason'] = result['lane_position_details']
                
                return
            
            # 自动超车导致的合理车道变化才更新
            if abs(new_lane - old_lane) <= 2:
                self.config['current_lane_number'] = new_lane
        
        # 更新控制状态中的车道信息
        lane_count = self.config['lane_count']
        if 'lane_position_details' in result:
            self.control_state['lane_position_info'] = result['lane_position_details']
        else:
            self.control_state['lane_position_info'] = f"车道{new_lane}/{lane_count}"
        
        # 如果是手动模式，更新显示信息
        if lane_count_mode == 'manual':
            self.control_state['lane_position_info'] += " (手动模式)"
    
    def update_vehicle_data(self):
        """更新车辆数据"""
        self.verification_system.update_lane_based_net_changes(
            self.config['current_lane_number'],
            self.verification_system.lane_change_verification['last_confirmed_lane'],
            self.vehicle_data,
            self.config,
            self.control_state
        )
    
    def update_following_status(self):
        """更新跟车状态"""
        self.overtake_decision.update_following_status(self.vehicle_data, self.control_state)
    
    def update_curve_detection(self):
        """更新弯道检测"""
        vd = self.vehicle_data

        is_curve = (vd['max_curve'] >= 1.0 or
                   abs(vd['road_curvature']) > self.config['CURVATURE_THRESHOLD'] or
                   abs(vd['steering_angle']) > self.config['STEERING_THRESHOLD'])

        if is_curve and self.control_state['isOvertaking']:
            self.cancel_overtake()
            self.control_state['current_status'] = "弯道中取消超车"
            self.control_state['overtakeReason'] = "检测到弯道，安全第一"
            self.control_state['system_status_detail'] = "弯道检测"
    
    # ==================== 超车逻辑 ====================
    
    def perform_auto_overtake(self, current_time):
        """执行自动超车"""
        # 检查是否在推荐车道上
        current_lane = self.config.get('current_lane_number', 1)
        is_current_recommended = self.lane_position_system.is_lane_recommended(current_lane)
        
        # 如果在推荐车道上，不执行超车，除非有多个推荐车道
        if is_current_recommended:
            # 检查是否有多个推荐车道
            recommended_lanes = self.recommended_lane_handler.get('recommended_lanes', [])
            if len(recommended_lanes) <= 1:
                return
        
        # 简单直接的2秒延时检查
        time_since_last_decision = current_time - self.last_decision_time
        if time_since_last_decision < self.decision_interval:
            return
        
        # 超车冷却检查（8秒）
        if current_time - self.overtake_decision.last_overtake_completion_time < 8000:
            remaining = (8000 - (current_time - self.overtake_decision.last_overtake_completion_time)) / 1000
            self.control_state['overtakeState'] = "超车冷却中"
            self.control_state['overtakeReason'] = f"距离上次超车完成还需等待{remaining:.1f}秒"
            return

        # 检查系统自动控制状态
        system_control = self.vehicle_data.get('system_auto_control', 0)
        
        if system_control >= 1:
            self._handle_system_control_restrictions(system_control)
            return
        
        # 检查道路类型和开关状态
        road_type = self.config['road_type']
        
        # 根据道路类型检查开关
        road_type_enabled = False
        if road_type == 'highway':
            road_type_enabled = self.config['autoOvertakeEnabled']
        else:
            road_type_enabled = self.config['autoOvertakeEnabledL']
        
        if not road_type_enabled:
            return
        
        # 检查是否正在超车
        if self.control_state['isOvertaking']:
            return

        # 更新返回状态
        self.update_return_state(self.vehicle_data, self.control_state)

        # 检查返回原车道时，也要检查超车开关
        should_check_return = (
            self.config['shouldReturnToLane'] and 
            self.return_status['need_return'] and
            self.return_status['return_ready'] and
            not self.control_state['isOvertaking'] and
            not self.control_state['lane_change_in_progress']
        )
        
        if not self.config['shouldReturnToLane']:
            if self.control_state['net_lane_changes'] != 0:
                self.reset_net_lane_changes()
                return

        if self.overtake_decision.check_op_control_cooldown(self.control_state):
            self.control_state['system_status_detail'] = "OP控制冷却中"
            return

        if self.control_state['lane_change_in_progress']:
            if self.control_state.get('lastOvertakeDirection'):
                if not self.check_lane_change_safety(self.control_state['lastOvertakeDirection']):
                    self.control_state['isOvertaking'] = False
                    self.control_state['lane_change_in_progress'] = False
                    self.control_state['overtakingCompleted'] = False
                    return

        self.control_state['system_status_detail'] = "检查超车条件"
        self.control_state['decision_phase'] = "条件评估"
        
        # 先检查返回条件，但要检查超车开关
        if should_check_return and road_type_enabled:
            success, reason = self.execute_return(self.vehicle_data, self.control_state)
            if success:
                return
            else:
                self.control_state['overtakeReason'] = f"返回失败: {reason}"
                return
        
        # 检查超车条件
        can_overtake = self.overtake_decision.check_overtake_conditions(self.vehicle_data, self.control_state)
        
        if can_overtake:
            self._execute_overtake_decision()
            return

        # 检查60秒超时
        if self.check_return_timeout(self.control_state):
            self.control_state['overtakeReason'] = "60秒未返回，已放弃返回原车道"
        
        self.status_manager.ensure_status_refresh(self.control_state)
    
    def _handle_system_control_restrictions(self, system_control):
        """处理系统控制限制"""
        if system_control == 2: # and self.config['road_type'] == 'highway':
            self.control_state['overtakeState'] = "路段禁止超车"
            self.control_state['overtakeReason'] = "路段禁止超车，暂停超车"
            self.control_state['current_status'] = "路段禁止"
            self.control_state['system_status_detail'] = "路段禁止超车"
        elif system_control == 3:
            # 缺少盲区设备
            self.control_state['overtakeState'] = "缺少盲区设备"
            self.control_state['overtakeReason'] = "缺少盲区设备，暂停超车"
            self.control_state['current_status'] = "设备缺失"
            self.control_state['system_status_detail'] = "缺少盲区设备"
            
            # 确保超车开关显示为关闭状态
            if self.config['road_type'] == 'highway':
                if self.config['autoOvertakeEnabled']:
                    self.config['autoOvertakeEnabled'] = False
                    self.save_persistent_config()
            else:
                if self.config['autoOvertakeEnabledL']:
                    self.config['autoOvertakeEnabledL'] = False
                    self.save_persistent_config()
        elif system_control == 4:
            # 车道领航控制中
            self.control_state['overtakeState'] = "车道领航控制中"
            self.control_state['overtakeReason'] = "车道领航控制中，暂停超车"
            self.control_state['current_status'] = "领航控制中"
            self.control_state['system_status_detail'] = "车道领航控制中"
        else:
            # system_control == 1 或普通公路上的 system_control == 2
            self.control_state['overtakeState'] = "OP控制中"
            self.control_state['overtakeReason'] = "OP自动控制中，暂停超车"
            self.control_state['current_status'] = "OP控制中"
            self.control_state['system_status_detail'] = "OP控制中"
        
        # 如果正在超车，立即取消
        if self.control_state.get('isOvertaking', False) or self.control_state.get('lane_change_in_progress', False):
            self.cancel_overtake()
        
        self.last_decision_time = time.time() * 1000
    
    def _execute_overtake_decision(self):
        """执行超车决策 - 优先选择推荐车道"""
        # 再次检查system_auto_control，确保安全
        system_control = self.vehicle_data.get('system_auto_control', 0)
        if system_control >= 1:
            self._handle_system_control_restrictions(system_control)
            return
        
        # 获取可用的超车方向（排除禁止车道）
        available_directions = self.overtake_decision.get_available_overtake_directions(
            self.vehicle_data, self.control_state
        )
        
        # 如果当前车道是禁止车道，优先处理禁止车道变道（这已经在_handle_forbidden_lane中处理）
        current_lane = self.config.get('current_lane_number', 1)
        if self.lane_position_system.is_lane_forbidden(current_lane):
            # 禁止车道处理已经在_handle_forbidden_lane中处理
            return

        # 边界检查和应急车道检查
        valid_directions = self._filter_valid_directions(available_directions)
        
        if not valid_directions:
            self.control_state['overtakeState'] = "边界检查失败"
            self.control_state['overtakeReason'] = f"车道{self.config['current_lane_number']}/{self.config['lane_count']}边界检查异常"
            self.control_state['system_status_detail'] = "边界检查失败"
            return
        
        available_directions = valid_directions

        # 优先选择推荐车道方向
        recommended_directions = []
        for direction in available_directions:
            target_lane = self._get_target_lane_for_direction(direction)
            if self.lane_position_system.is_lane_recommended(target_lane):
                recommended_directions.append(direction)
        
        # 如果有推荐车道，只从推荐车道中选择
        if recommended_directions:
            available_directions = recommended_directions
            self.control_state['overtakeReason'] += " | 优先选择推荐车道"

        self.control_state['return_timer_start'] = 0
        self.control_state['return_conditions_met'] = False
        self.control_state['original_lane_clear'] = False

        current_penalty, current_analysis = self.overtake_decision.get_current_lane_penalty(self.vehicle_data)

        direction_scores = {}
        direction_analysis = {}
        direction_effectiveness = {}

        self.control_state['system_status_detail'] = "评估可用车道"
        self.control_state['lane_analysis'] = f"可用方向: {', '.join(available_directions)}"

        for direction in available_directions:
            side = "left" if direction == "左" else "right"

            safety_score, safety_analysis = self.overtake_decision.evaluate_lane_suitability(
                self.vehicle_data, side
            )
            
            # 如果是推荐车道，给予额外分数
            target_lane = self._get_target_lane_for_direction(direction)
            if self.lane_position_system.is_lane_recommended(target_lane):
                safety_score = min(100, safety_score + 20)  # 推荐车道加20分
                safety_analysis.append(f"推荐车道: 额外+20分")
            
            is_effective, effectiveness_score, effectiveness_reasons = self.overtake_decision.is_overtake_effective(
                self.vehicle_data, direction
            )

            if not is_effective:
                direction_scores[direction] = 0
                direction_effectiveness[direction] = {
                    'score': effectiveness_score,
                    'is_effective': False,
                    'reasons': effectiveness_reasons
                }
                full_analysis = [f"超车无效: {', '.join(effectiveness_reasons)}"]
                direction_analysis[direction] = full_analysis
                continue

            effectiveness_factor = effectiveness_score / 100.0
            combined_score = safety_score * effectiveness_factor

            direction_scores[direction] = combined_score
            direction_effectiveness[direction] = {
                'score': effectiveness_score,
                'is_effective': True,
                'reasons': effectiveness_reasons
            }

            full_analysis = safety_analysis.copy()
            if effectiveness_reasons:
                full_analysis.extend([f"{reason}" for reason in effectiveness_reasons])
            
            full_analysis.append(f"综合评分: {combined_score:.0f}%")
            direction_analysis[direction] = full_analysis

        best_direction = None
        best_score = 0
        detailed_reason = ""

        self.control_state['decision_phase'] = "选择最佳车道"

        for direction in available_directions:
            score = direction_scores[direction]
            effectiveness_info = direction_effectiveness[direction]

            if not effectiveness_info['is_effective']:
                continue

            adjusted_score = score
            
            # 道路类型偏好调整
            if self.config['road_type'] == 'highway':
                current_lane = self.config['current_lane_number']

                if current_lane == self.config['lane_count'] and direction == "左":
                    adjusted_score += 8
                    direction_analysis[direction].append("最右车道优先向左")

                elif current_lane == 1 and direction == "右":
                    adjusted_score -= 4
                    direction_analysis[direction].append("快车道向右需谨慎")

            # 方向偏好调整
            if direction == "左":
                adjusted_score += 1 
                
                if abs(adjusted_score - best_score) < 5:
                    adjusted_score += 3 
                    
                direction_analysis[direction].append("分数相近，优先左车道")

            elif direction == "右":
                adjusted_score -= 1 

            if adjusted_score > self.config['PENALTY_THRESHOLD'] and adjusted_score > best_score:
                best_direction = direction
                best_score = adjusted_score

                effectiveness_text = f"有效性{effectiveness_info['score']}%"
                safety_text = f"安全性{score:.0f}%"
                analysis_text = " | ".join(direction_analysis[direction])
                detailed_reason = f"{direction}车道 {effectiveness_text} | {safety_text} | {analysis_text}"

        if best_direction and best_score > self.config['PENALTY_THRESHOLD']:
            # 再次检查system_auto_control，防止在评估过程中发生变化
            system_control = self.vehicle_data.get('system_auto_control', 0)
            if system_control >= 1:
                self._handle_system_control_restrictions(system_control)
                return
            
            # 新增：检查目标车道是否为禁止车道（X）
            target_lane = self._get_target_lane_for_direction(best_direction)
            if self.lane_position_system.is_lane_forbidden(target_lane):
                self.control_state['overtakeState'] = f"目标车道禁止通行"
                self.control_state['overtakeReason'] = f"目标车道{target_lane}为禁止通行车道(X)，取消超车"
                self.control_state['current_status'] = "禁止变道"
                self.control_state['system_status_detail'] = f"禁止变道到X车道"
                return
            
            is_distance_safe, distance_reason = self.overtake_decision.check_side_vs_front_distance_comprehensive(
                best_direction, self.vehicle_data
            )
            if not is_distance_safe:
                self.control_state['overtakeState'] = f"距离安全检查失败"
                self.control_state['overtakeReason'] = f"侧车距离不安全: {distance_reason}"
                self.control_state['current_status'] = "安全阻止"
                self.control_state['system_status_detail'] = f"距离安全阻止: {distance_reason}"
                return
            
            vd = self.vehicle_data
            
            # 使用跟踪中心获取目标车道速度
            track_direction = "left" if best_direction == "左" else "right"
            target_state = self.tracking_center.get_vehicle_state(track_direction)
            
            if target_state and target_state['has_vehicle']:
                target_speed = target_state.get('speed')
                if target_speed is None:
                    target_speed = vd['v_ego_kph'] + 10
            else:
                target_speed = vd['v_ego_kph'] + 10
            
            # 当前车道预期速度
            front_state = self.tracking_center.get_vehicle_state('front')
            if front_state and front_state['has_vehicle']:
                current_lead_speed = front_state.get('speed')
                if current_lead_speed is None:
                    current_lead_speed = vd['v_ego_kph']
            else:
                current_lead_speed = vd['v_ego_kph']
            
            actual_speed_advantage = target_speed - current_lead_speed

            is_clear = self.overtake_decision.is_target_lane_reliably_clear(best_direction, vd)
            
            if actual_speed_advantage >= 3 or is_clear:
                self.execute_overtake(best_direction)
                self.control_state['overtakeReason'] = f"{detailed_reason} | 实际速度优势: +{round(actual_speed_advantage, 1)}km/h"
                self.control_state['system_status_detail'] = f"选择{best_direction}车道超车"
            else:
                self.control_state['overtakeState'] = "目标车道无速度优势"
                self.control_state['overtakeReason'] = f"目标车道速度{target_speed:.1f}km/h vs 当前{current_lead_speed:.1f}km/h (优势{round(actual_speed_advantage, 1)}km/h)"
                self.control_state['system_status_detail'] = "速度优势不足"
        else:
            self._handle_no_suitable_lane(available_directions, direction_effectiveness, direction_scores)
    
    def _handle_no_available_directions(self):
        """处理无可用方向的情况"""
        current_lane = self.config['current_lane_number']
        total_lanes = self.config['lane_count']
        
        if current_lane == 1:
            self.control_state['overtakeState'] = "已在最左侧车道"
            self.control_state['overtakeReason'] = "已在最左侧车道，无法向左变道"
        elif current_lane == total_lanes:
            # 特殊处理：如果当前在最右侧车道，检查是否为应急车道
            roadcate = self.config.get('roadcate', 0)
            if roadcate == 1:
                self.control_state['overtakeState'] = "已在应急车道"
                self.control_state['overtakeReason'] = "应急车道禁止变道"
            else:
                self.control_state['overtakeState'] = "已在最右侧车道"
                self.control_state['overtakeReason'] = "已在最右侧车道，无法向右变道"
        else:
            self.control_state['overtakeState'] = "无可用变道方向"
            self.control_state['overtakeReason'] = "当前车道位置限制"
        self.control_state['system_status_detail'] = "无可用方向"
    
    def _filter_valid_directions(self, available_directions):
        """过滤有效方向"""
        current_lane = self.config['current_lane_number']
        total_lanes = self.config['lane_count']
        
        valid_directions = []
        for direction in available_directions:
            if direction == "左" and current_lane <= 1:
                continue
            elif direction == "右" and current_lane >= total_lanes:
                continue
            
            # 简化应急车道检查
            if direction == "右" and current_lane == total_lanes - 1:
                # 检查目标车道（最右侧车道）是否为应急车道
                target_lane = total_lanes
                roadcate = self.config.get('roadcate', 0)
                if roadcate == 1:
                    self.control_state['right_change_blocked_reason'] = f"车道{target_lane}为应急车道"
                    continue
            
            valid_directions.append(direction)
        
        return valid_directions
    
    def _handle_no_suitable_lane(self, available_directions, direction_effectiveness, direction_scores):
        """处理无合适车道的情况"""
        no_overtake_reasons = []
        for direction in available_directions:
            effectiveness_info = direction_effectiveness[direction]
            score = direction_scores[direction]
            
            if not effectiveness_info['is_effective']:
                reason = f"{direction}:{effectiveness_info['reasons'][-1] if effectiveness_info['reasons'] else '无效超车'}"
            elif score <= self.config['PENALTY_THRESHOLD']:
                reason = f"{direction}:安全性不足({score:.0f}%)"
            else:
                reason = f"{direction}:条件满足但未选择"
            
            no_overtake_reasons.append(reason)

        self.control_state['overtakeState'] = "无合适超车车道"
        self.control_state['overtakeReason'] = f"车道分析: {', '.join(no_overtake_reasons)}"
        self.control_state['system_status_detail'] = "无合适车道"
    
    def _get_target_lane_for_direction(self, direction):
        """获取目标方向的车道编号"""
        current_lane = self.config['current_lane_number']
        
        if direction == "左":
            return max(1, current_lane - 1)
        else:  # direction == "右"
            return min(self.config['lane_count'], current_lane + 1)
    
    def execute_overtake(self, direction):
        """执行超车变道"""
        target_lane = self._get_target_lane_for_direction(direction)
        
        # 新增：检查是否是推荐车道，并给出特别提示
        if self.lane_position_system.is_lane_recommended(target_lane):
            self.control_state['overtakeReason'] = f"★推荐车道★ 变道到车道{target_lane}"
        
        # 检查目标车道是否为禁止车道（X）
        if self.lane_position_system.is_lane_forbidden(target_lane):
            self.control_state['overtakeState'] = f"目标车道禁止通行"
            self.control_state['overtakeReason'] = f"目标车道{target_lane}为禁止通行车道(X)，取消超车"
            self.control_state['current_status'] = "禁止变道"
            self.control_state['system_status_detail'] = f"禁止变道到X车道"
            return
        
        # 使用跟踪中心进行安全检查
        is_safe, safety_reason = self.check_lane_safety_before_execute(direction)
        if not is_safe:
            self.control_state['overtakeState'] = f"变道前安全检查失败"
            self.control_state['overtakeReason'] = f"最后时刻检测到不安全: {safety_reason}"
            self.control_state['current_status'] = "安全阻止"
            self.control_state['system_status_detail'] = f"安全阻止: {safety_reason}"
            return
        
        # 添加变道语音提示
        # 如果是推荐车道，使用特别的语音提示
        if self.lane_position_system.is_lane_recommended(target_lane):
            request_sound_index(18)  # 可以使用一个特别的语音提示，如"推荐车道"
            time.sleep(1)
        else:
            if direction == "左":
                request_sound_index(7)  # audio_pre_lane_left
            else:  # direction == "右"
                request_sound_index(8)  # audio_pre_lane_right
            time.sleep(2)
        
        is_safe, safety_reason = self.check_lane_safety_before_execute(direction)
        if not is_safe:
            self.control_state['overtakeState'] = f"变道已取消"
            self.control_state['overtakeReason'] = f"语音播报期间检测到不安全: {safety_reason}"
            self.control_state['current_status'] = "安全阻止"
            self.control_state['system_status_detail'] = f"安全阻止: {safety_reason}"
            request_sound_index(16)  # 变道已取消
            return      

        current_success_count = self.control_state['overtakeSuccessCount']
        
        # 注意：需要将中文方向转换为英文命令
        command_direction = "LEFT" if direction == "左" else "RIGHT"
        success = self.send_command("OVERTAKE", command_direction)
        if success:
            self.last_decision_time = time.time() * 1000
            
            if self.control_state['original_lane_number'] == 0:
                self.control_state['original_lane_number'] = self.config['current_lane_number']
                self.control_state['target_return_lane'] = self.config['current_lane_number']
                self.control_state['lane_memory_start_time'] = time.time() * 1000

            # 明确标记为自动超车
            self.control_state['is_auto_overtake'] = True
            self.control_state['isOvertaking'] = True
            self.control_state['lane_change_in_progress'] = True
            self.control_state['lastOvertakeDirection'] = direction
            self.control_state['lastLaneChangeCommandTime'] = time.time() * 1000

            self.control_state['target_vehicle_tracker'] = None
            self.control_state['overtake_complete_timer'] = 0

            self.lane_change_safety_monitor['consecutive_unsafe_count'] = 0
            self.lane_change_safety_monitor['last_safety_check'] = time.time() * 1000

            road_type = self.config['road_type']
            
            if self.config['shouldReturnToLane']:
                return_enabled = self.config['RETURN_STRATEGY'][road_type]['enabled']
                if return_enabled:
                    # 只有自动变道才更新净变道数
                    self.update_net_lane_changes(direction, is_auto_overtake=True)
                else:
                    self.reset_net_lane_changes()
            else:
                pass

            self.control_state['follow_start_time'] = None
            self.control_state['is_following_slow_vehicle'] = False
            self.control_state['max_follow_time_reached'] = False

            self.control_state['overtake_start_count'] = current_success_count

            if direction == "左":
                self.control_state['overtakeState'] = "⬅️ ⬅️ ⬅️ 准备向左变道超车 "
                self.control_state['current_status'] = "自动左变道"
                self.control_state['system_status_detail'] = "左变道进行中"
            else:
                self.control_state['overtakeState'] = " 准备向右变道超车➡️ ➡️ ➡️ "
                self.control_state['current_status'] = "自动右变道"
                self.control_state['system_status_detail'] = "右变道进行中"

            # ---------- 新增：设置后备定时器 ----------
            self.pending_immediate_update['active'] = True
            self.pending_immediate_update['direction'] = direction
            self.pending_immediate_update['start_time'] = time.time() * 1000

            # ---------- 新增：设置动态模式为 changing ----------
            self.lane_position_system.lane_calculator.set_dynamic_mode('changing', time.time() * 1000)
    
    # ==================== 手动控制和用户接口 ====================
    
    def manual_overtake(self, lane):
        """手动超车 - 手动变道跳过所有检测，只检查应急车道"""
        # 修复：手动超车前也检查 system_auto_control
        system_control = self.vehicle_data.get('system_auto_control', 0)
        if system_control >= 1:
            if system_control == 2: # and self.config['road_type'] == 'highway':
                self.control_state['overtakeState'] = "路段禁止超车"
                self.control_state['overtakeReason'] = "路段禁止超车，禁止手动超车"
                self.control_state['current_status'] = "路段禁止"
            elif system_control == 3:
                self.control_state['overtakeState'] = "缺少盲区设备"
                self.control_state['overtakeReason'] = "缺少盲区设备，禁止手动超车"
                self.control_state['current_status'] = "设备缺失"
            elif system_control == 4:
                self.control_state['overtakeState'] = "车道领航控制中"
                self.control_state['overtakeReason'] = "车道领航控制中，禁止手动超车"
                self.control_state['current_status'] = "领航控制中"
            else:
                self.control_state['overtakeState'] = "OP控制中"
                self.control_state['overtakeReason'] = "OP自动控制中，禁止手动超车"
                self.control_state['current_status'] = "OP控制中"
            self.control_state['system_status_detail'] = "禁止手动超车"
            return
        
        direction = "左" if lane == "left" else "右"
        
        # 修改：手动变道只检查应急车道规则，跳过所有其他检测
        # 检查是否向应急车道变道
        if direction == "右":
            current_lane = self.config['current_lane_number']
            total_lanes = self.config['lane_count']
            if current_lane == total_lanes - 1:  # 从倒数第二车道向右变道
                target_lane = total_lanes
                # 检查目标车道是否为应急车道
                roadcate = self.config.get('roadcate', 0)
                if roadcate == 1:
                    self.control_state['overtakeState'] = "禁止向应急车道变道"
                    self.control_state['overtakeReason'] = f"车道{target_lane}为应急车道，禁止变道"
                    self.control_state['current_status'] = "禁止变道"
                    self.control_state['system_status_detail'] = "应急车道保护"
                    return
        
        # 检查是否在最左侧车道还想向左变道
        if direction == "左" and self.config['current_lane_number'] == 1:
            self.control_state['overtakeState'] = "已在最左侧车道"
            self.control_state['overtakeReason'] = "已在最左侧车道，无法向左变道"
            self.control_state['current_status'] = "禁止变道"
            self.control_state['system_status_detail'] = "左侧边界"
            return
        
        # 检查是否在最右侧车道还想向右变道
        if direction == "右" and self.config['current_lane_number'] == self.config['lane_count']:
            self.control_state['overtakeState'] = "已在最右侧车道"
            self.control_state['overtakeReason'] = "已在最右侧车道，无法向右变道"
            self.control_state['current_status'] = "禁止变道"
            self.control_state['system_status_detail'] = "右侧边界"
            return
        
        # 修改：手动变道跳过所有安全检查，直接执行
        
        # 添加变道语音提示
        if direction == "左":
            request_sound_index(7)  # audio_pre_lane_left
        else:  # direction == "右"
            request_sound_index(8)  # audio_pre_lane_right
        
        # 等待语音播报完成（约2秒）
        time.sleep(2)
        
        # 注意：需要将中文方向转换为英文命令
        command_direction = "LEFT" if direction == "左" else "RIGHT"
        success = self.send_command("LANECHANGE", command_direction)
        if success:
            self.last_decision_time = time.time() * 1000
            
            self.control_state['lastOvertakeDirection'] = direction
            self.control_state['lastLaneChangeCommandTime'] = time.time() * 1000
            self.control_state['manual_start_count'] = self.control_state['overtakeSuccessCount']

            self.control_state['isOvertaking'] = False
            self.control_state['lane_change_in_progress'] = False

            # 明确标记为手动变道，并重置净变道数
            self.control_state['is_auto_overtake'] = False
            self.reset_net_lane_changes()  # 手动变道时立即清零净变道数

            if lane == "left":
                self.control_state['current_status'] = "手动左变道中"
                self.control_state['overtakeState'] = "⬅️ ⬅️ ⬅️ 手动左变道 "
                self.control_state['system_status_detail'] = "手动左变道"
            else:
                self.control_state['current_status'] = "手动右变道中"
                self.control_state['overtakeState'] = "手动右变道➡️ ➡️ ➡️ "
                self.control_state['system_status_detail'] = "手动右变道"
            self.control_state['overtakeReason'] = "用户手动变道指令（跳过安全检查）"
    
    def cancel_overtake(self):
        """取消超车 - 取消超车清零净变道数"""
        success = self.send_command("CANCEL_OVERTAKE", "true")
        if success:
            self.control_state['current_status'] = "取消超车"
            self.control_state['overtakeState'] = "超车已取消"
            self.control_state['overtakeReason'] = "用户取消超车操作"
            self.control_state['isOvertaking'] = False
            self.control_state['lane_change_in_progress'] = False
            self.control_state['system_status_detail'] = "用户取消"
            
            # 取消超车时清零净变道数
            self.reset_net_lane_changes()
            
            self.control_state['completion_timer'] = time.time() * 1000 + 3000
    
    def change_speed(self, direction):
        """改变速度"""
        self.send_command("SPEED", direction)
    
    # ==================== 安全检查 ====================
    
    def check_lane_change_safety(self, direction):
        """检查变道安全"""
        current_time = time.time() * 1000
        
        if current_time - self.lane_change_safety_monitor['last_safety_check'] < self.lane_change_safety_monitor['safety_check_interval']:
            return True
        
        self.lane_change_safety_monitor['last_safety_check'] = current_time
        
        # 使用跟踪中心的安全检查
        side = "left" if direction == "左" else "right"
        is_safe, reason = self.tracking_center.is_safe_to_change_lane(side, self.vehicle_data, self.config)
        
        if not is_safe:
            self.lane_change_safety_monitor['consecutive_unsafe_count'] += 1
            self.control_state['overtakeReason'] = f"变道过程中检测到不安全: {reason}"
            self.control_state['safety_check'] = f"不安全: {reason}"
            
            if self.lane_change_safety_monitor['consecutive_unsafe_count'] >= self.lane_change_safety_monitor['max_unsafe_count']:
                self.control_state['overtakeState'] = "变道不安全，取消变道"
                self.control_state['overtakeReason'] = f"连续检测到不安全: {reason}，取消变道"
                self.control_state['system_status_detail'] = "安全监测失败"
                self.cancel_overtake()
                return False
        else:
            self.lane_change_safety_monitor['consecutive_unsafe_count'] = 0
            self.control_state['safety_check'] = "安全"
        
        return True
    
    def check_lane_safety_before_execute(self, direction):
        """变道前的最终安全检查"""
        side = "left" if direction == "左" else "right"
        
        # 使用跟踪中心的安全检查
        try:
            # 检查方法是否存在
            if hasattr(self.tracking_center, 'is_safe_to_change_lane'):
                is_safe, safety_reason = self.tracking_center.is_safe_to_change_lane(
                    side, self.vehicle_data, self.config
                )
            else:
                # 后备方案：使用直接的安全检查
                is_safe, safety_reason = self._direct_safety_check(side, direction)
            
            if not is_safe:
                return False, safety_reason
            
            # 检查前后激光雷达
            if direction == "左":
                # 检查左前激光雷达
                lf_drel = self.vehicle_data.get('lf_drel')
                if lf_drel is not None and 0 < lf_drel < 15:  # 前方15米内有车
                    return False, f"左前激光雷达检测到车辆过近: {lf_drel:.0f}m"
                
                # 检查左后激光雷达
                lb_drel = self.vehicle_data.get('lb_drel')
                if lb_drel is not None and 0 < lb_drel < 10:  # 后方10米内有车
                    return False, f"左后激光雷达检测到车辆过近: {lb_drel:.0f}m"
                    
                # 检查左激光雷达盲区
                if self.vehicle_data.get('lidar_lblind', False):
                    return False, "左侧激光雷达盲区有车"
            else:
                # 检查右前激光雷达
                rf_drel = self.vehicle_data.get('rf_drel')
                if rf_drel is not None and 0 < rf_drel < 15:  # 前方15米内有车
                    return False, f"右前激光雷达检测到车辆过近: {rf_drel:.0f}m"
                
                # 检查右后激光雷达
                rb_drel = self.vehicle_data.get('rb_drel')
                if rb_drel is not None and 0 < rb_drel < 10:  # 后方10米内有车
                    return False, f"右后激光雷达检测到车辆过近: {rb_drel:.0f}m"
                    
                # 检查右激光雷达盲区
                if self.vehicle_data.get('lidar_rblind', False):
                    return False, "右侧激光雷达盲区有车"
            
            return True, "安全检查通过"
            
        except Exception as e:
            # 如果安全检查失败，记录错误但允许继续
            return True, f"安全检查异常，但允许继续: {e}"

    def _direct_safety_check(self, side, direction):
        """直接安全检查（后备方案）"""
        # 检查盲区
        if side == 'left':
            if (self.vehicle_data.get('left_blindspot', False) or 
                self.vehicle_data.get('l_front_blind', False) or
                self.vehicle_data.get('lidar_lblind', False)):
                return False, "左侧盲区有车辆"
            
            # 检查侧方雷达数据
            l_drel = self.vehicle_data.get('l_drel')
            if l_drel is not None and 0 < l_drel < 20:
                return False, f"左侧有车辆过近: {l_drel:.0f}m"
                
        else:
            if (self.vehicle_data.get('right_blindspot', False) or 
                self.vehicle_data.get('r_front_blind', False) or
                self.vehicle_data.get('lidar_rblind', False)):
                return False, "右侧盲区有车辆"
            
            # 检查侧方雷达数据
            r_drel = self.vehicle_data.get('r_drel')
            if r_drel is not None and 0 < r_drel < 20:
                return False, f"右侧有车辆过近: {r_drel:.0f}m"
        
        return True, "直接安全检查通过"
    
    # ==================== 返回原车道逻辑 ====================
    
    def update_return_state(self, vehicle_data, control_state):
        """更新返回状态 - 核心逻辑"""
        current_time = time.time() * 1000
        
        # 1. 检查是否需要返回
        need_return = control_state['net_lane_changes'] != 0
        self.return_status['need_return'] = need_return
        
        if not need_return:
            # 重置返回状态
            self._reset_return_state(control_state)
            return
        
        # 2. 确定目标侧
        if control_state['net_lane_changes'] > 0:
            target_side = 'right'  # 向左变道过，目标在右侧
        else:
            target_side = 'left'   # 向右变道过，目标在左侧
        
        self.return_status['target_side'] = target_side
        
        # 3. 设置超时计时开始（第一次检测到需要返回时）
        if self.return_status['timeout_start'] == 0:
            self.return_status['timeout_start'] = current_time
        
        # 4. 检查目标跟踪状态
        target_state = self.tracking_center.get_vehicle_state(target_side)
        
        # 5. 处理4种情况
        if not self.return_status['tracking_target']:
            # 情况1：刚开始跟踪，等待15秒
            if current_time - self.return_status.get('timeout_start', 0) > 15000:  # 修改为15秒
                # 15秒内未检测到目标，认为目标不在该车道
                self.return_status['target_lost'] = True
                self.return_status['return_ready'] = True
                # 修复：将英文方向转换为中文显示
                chinese_side = "右" if target_side == 'right' else "左"
                self.return_status['return_reason'] = f"{chinese_side}侧15秒未检测到目标车辆，车道已空"
            elif target_state and target_state['has_vehicle']:
                # 检测到目标，开始跟踪
                self.return_status['tracking_target'] = True
                self.return_status['target_disappear_start'] = 0
        else:
            # 已经在跟踪
            if target_state and target_state['has_vehicle']:
                # 情况2/3：有目标车辆，检查超越状态
                distance = target_state.get('distance')
                relative_speed = target_state.get('relative_speed', 0)
                
                # 判断超越状态
                has_overtaken = self.tracking_center.has_overtaken(target_side, 0.4)
                is_reliably_clear = target_state.get('is_reliably_clear', False)
                
                if has_overtaken or is_reliably_clear:
                    # 已超越或车道畅通
                    self.return_status['overtaking_complete'] = True
                    self.return_status['return_ready'] = True
                    # 修复：将英文方向转换为中文显示
                    chinese_side = "右" if target_side == 'right' else "左"
                    self.return_status['return_reason'] = f"已超越{chinese_side}侧车辆"
                elif distance and distance < 0 and abs(distance) > 10:
                    # 目标在后方10米以外
                    self.return_status['overtaking_complete'] = True
                    self.return_status['return_ready'] = True
                    self.return_status['return_reason'] = f"目标车辆在后方{abs(distance):.0f}m"
                else:
                    # 还在超越过程中
                    self.return_status['target_disappear_start'] = 0
                    # 情况4：检查相反方向是否更畅通
                    self._check_opposite_lane_for_overtake(target_side)
            else:
                # 情况2/3：目标消失
                if self.return_status['target_disappear_start'] == 0:
                    self.return_status['target_disappear_start'] = current_time
                
                disappear_duration = current_time - self.return_status['target_disappear_start']
                
                # 检查盲区是否有车
                if target_side == 'right':
                    blind_clear = not (vehicle_data.get('right_blindspot', False) or 
                                    vehicle_data.get('r_front_blind', False) or
                                    vehicle_data.get('lidar_rblind', False))
                else:
                    blind_clear = not (vehicle_data.get('left_blindspot', False) or 
                                    vehicle_data.get('l_front_blind', False) or
                                    vehicle_data.get('lidar_lblind', False))
                
                # 消失超过2秒且盲区无车，认为已超越
                if disappear_duration > 2000 and blind_clear:
                    self.return_status['overtaking_complete'] = True
                    self.return_status['return_ready'] = True
                    self.return_status['target_lost'] = True
                    self.return_status['return_reason'] = f"目标消失{disappear_duration/1000:.0f}秒，确认已超越"
        
        # 6. 更新进度
        self._update_return_progress(current_time)
        
        # 7. 更新显示
        self._update_return_display(control_state)
    
    def _check_opposite_lane_for_overtake(self, target_side):
        """检查相反方向是否更畅通（情况4）"""
        opposite_side = 'left' if target_side == 'right' else 'right'
        opposite_state = self.tracking_center.get_vehicle_state(opposite_side)
        
        if opposite_state and not opposite_state['has_vehicle']:
            # 相反方向车道畅通，可以考虑继续超车
            # 这里可以调整净变道数，但需要谨慎
            # 暂时只记录日志
            # 修复：将英文方向转换为中文显示
            chinese_side = "左" if opposite_side == 'left' else "右"
            self.control_state['system_status_detail'] = f"{chinese_side}侧车道畅通，可考虑继续超车"
    
    def _update_return_progress(self, current_time):
        """更新返回进度 - 只显示整数"""
        if not self.return_status['need_return']:
            self.return_status['progress'] = 0
            return
        
        progress = 0
        
        # 基础进度：是否在跟踪
        if self.return_status['tracking_target']:
            progress += 20
        
        # 目标状态进度
        if self.return_status['overtaking_complete']:
            progress += 40
        elif self.return_status['target_lost']:
            progress += 30
        
        # 返回就绪进度
        if self.return_status['return_ready']:
            progress += 30
        
        # 超时影响：时间越长，进度越高（但不超过90%）
        if self.return_status['timeout_start'] > 0:
            timeout_elapsed = current_time - self.return_status['timeout_start']
            timeout_progress = min(20, timeout_elapsed / 3000)  # 60秒达到20%
            progress += timeout_progress
        
        # 只取整数
        self.return_status['progress'] = int(min(100, progress))
    
    def _update_return_display(self, control_state):
        """更新返回状态显示 - 优化显示优先级"""
        if not self.return_status['need_return']:
            return
        
        # 如果正在超车或变道中，优先显示超车状态
        if control_state['isOvertaking'] or control_state['lane_change_in_progress']:
            return
        
        side_text = "右" if control_state['net_lane_changes'] > 0 else "左"
        progress = int(self.return_status['progress'])  # 只显示整数
        
        if self.return_status['returning']:
            # 正在返回中
            control_state['overtakeState'] = f"{side_text}返回中 ({progress}%)"
            control_state['current_status'] = "返回中"
            control_state['overtakeReason'] = self.return_status['return_reason']
        elif self.return_status['return_ready']:
            # 返回准备就绪
            control_state['overtakeState'] = f"准备{side_text}返回 ({progress}%)"
            control_state['overtakeReason'] = self.return_status['return_reason']
            control_state['current_status'] = "准备返回"
        elif self.return_status['tracking_target']:
            # 跟踪目标中 - 显示目标车辆信息
            target_info = self._get_target_details()
            control_state['overtakeState'] = f"跟踪目标 ({progress}%)"
            control_state['overtakeReason'] = target_info
            control_state['current_status'] = "超越中"
        else:
            # 等待检测目标
            remaining_time = max(0, 15 - (time.time() * 1000 - self.return_status.get('timeout_start', 0)) / 1000)
            control_state['overtakeState'] = f"检测目标 ({progress}%)"
            control_state['overtakeReason'] = f"检测{side_text}侧目标车辆 ({int(remaining_time)}秒)"
            control_state['current_status'] = "检测目标"
    
    def _get_target_details(self):
        """获取目标车辆详情 - 显示包括激光雷达的具体数据"""
        if not self.return_status['target_side']:
            return "无目标车辆"
        
        target_side = self.return_status['target_side']
        state = self.tracking_center.get_vehicle_state(target_side)
        
        if not state:
            return "无法获取目标状态"
        
        details = []
        
        if state['has_vehicle']:
            # 原车雷达数据
            distance = state.get('distance', '未知')
            speed = state.get('speed', '未知')
            relative_speed = state.get('relative_speed', '未知')
            
            if distance != '未知':
                details.append(f"雷达距离:{distance:.0f}m")
            if speed != '未知':
                details.append(f"雷达速度:{speed:.0f}km/h")
            if relative_speed != '未知':
                relative_text = "更快" if relative_speed > 0 else "更慢" if relative_speed < 0 else "相同"
                details.append(f"相对速度:{abs(relative_speed):.0f}km/h({relative_text})")
        
        # 添加激光雷达数据
        if target_side == 'left':
            # 左前激光雷达
            lf_drel = self.vehicle_data.get('lf_drel')
            if lf_drel is not None and lf_drel > 0:
                details.append(f"左前激光雷达:{lf_drel:.0f}m")
            
            # 左后激光雷达
            lb_drel = self.vehicle_data.get('lb_drel')
            if lb_drel is not None and lb_drel > 0:
                details.append(f"左后激光雷达:{lb_drel:.0f}m")
                
            # 激光雷达盲区状态
            if self.vehicle_data.get('lidar_lblind', False):
                details.append("激光雷达盲区有车")
        else:  # target_side == 'right'
            # 右前激光雷达
            rf_drel = self.vehicle_data.get('rf_drel')
            if rf_drel is not None and rf_drel > 0:
                details.append(f"右前激光雷达:{rf_drel:.0f}m")
            
            # 右后激光雷达
            rb_drel = self.vehicle_data.get('rb_drel')
            if rb_drel is not None and rb_drel > 0:
                details.append(f"右后激光雷达:{rb_drel:.0f}m")
                
            # 激光雷达盲区状态
            if self.vehicle_data.get('lidar_rblind', False):
                details.append("激光雷达盲区有车")
        
        if details:
            return "目标车辆: " + " ".join(details)
        elif state['has_vehicle']:
            return "目标车辆存在（无详细数据）"
        else:
            disappear_time = self.return_status.get('target_disappear_start', 0)
            if disappear_time > 0:
                duration = (time.time() * 1000 - disappear_time) / 1000
                return f"目标消失{duration:.0f}秒"
            else:
                return "无目标车辆"
    
    def check_return_safety(self, vehicle_data, control_state):
        """返回安全检查 - 添加应急车道检查和超车开关检查"""
        # 检查超车开关
        road_type = self.config['road_type']
        if road_type == 'highway':
            if not self.config['autoOvertakeEnabled']:
                return False, "高速超车开关已关闭"
        else:
            if not self.config['autoOvertakeEnabledL']:
                return False, "普通道路超车开关已关闭"
        
        # 检查返回功能是否开启
        if not self.config['shouldReturnToLane']:
            return False, "返回功能未开启"
        
        if not self.return_status['need_return']:
            return False, "无需返回"
        
        # 获取返回方向
        return_direction = "右" if control_state['net_lane_changes'] > 0 else "左"
        check_side = "right" if return_direction == "右" else "left"
        
        # 检查应急车道规则
        emergency_lane_check, emergency_reason = self.check_emergency_lane_rule(return_direction, vehicle_data, control_state)
        if not emergency_lane_check:
            return False, f"应急车道规则: {emergency_reason}"
        
        # 使用跟踪中心的安全检查
        is_safe, safety_reason = self.tracking_center.is_safe_to_change_lane(
            check_side, vehicle_data, self.config
        )
        
        if not is_safe:
            return False, f"安全规则: {safety_reason}"
        
        return True, "安全检查通过"
    
    def check_emergency_lane_rule(self, direction, vehicle_data, control_state):
        """检查应急车道规则"""
        # 获取当前车道和目标车道
        current_lane = self.config.get('current_lane_number', 1)
        total_lanes = self.config.get('lane_count', 3)
        
        if direction == "右":
            target_lane = current_lane + 1
        else:  # 左
            target_lane = current_lane - 1
        
        # 检查是否超出车道范围（手动设置的车道数）
        if target_lane < 1 or target_lane > total_lanes:
            return False, f"目标车道{target_lane}超出范围(1-{total_lanes})"
        
        # 检查是否变道到应急车道
        # 只有roadcate=1的高速公路才有应急车道，且应急车道是最右侧车道
        roadcate = self.config.get('roadcate', 0)
        
        if roadcate == 1:  # 高速公路有应急车道
            # 检查是否向右变道到最右侧车道（应急车道）
            if direction == "右" and target_lane == total_lanes:
                return False, f"车道{target_lane}为应急车道，禁止变道"
        
        return True, "应急车道规则检查通过"
    
    def execute_return(self, vehicle_data, control_state):
        """执行返回操作 - 添加应急车道检查和超车开关检查"""
        # 检查超车开关
        road_type = self.config['road_type']
        if road_type == 'highway':
            if not self.config['autoOvertakeEnabled']:
                return False, "高速超车开关已关闭"
        else:
            if not self.config['autoOvertakeEnabledL']:
                return False, "普通道路超车开关已关闭"
        
        # 添加 current_time 定义
        current_time = time.time() * 1000
        
        # 1. 检查是否需要返回
        if not self.return_status['need_return']:
            return False, "无需返回"
        
        # 2. 安全检查（包含应急车道检查）
        is_safe, safety_reason = self.check_return_safety(vehicle_data, control_state)
        if not is_safe:
            return False, f"安全检查失败: {safety_reason}"
        
        # 3. 获取返回方向
        if control_state['net_lane_changes'] > 0:
            return_direction = "右"
            side_text = "右"
        else:
            return_direction = "左"
            side_text = "左"
        
        # 再次检查应急车道规则
        emergency_check, emergency_reason = self.check_emergency_lane_rule(return_direction, vehicle_data, control_state)
        if not emergency_check:
            return False, f"应急车道规则: {emergency_reason}"
        
        # 4. 语音提示
        sound_index = 8 if return_direction == "右" else 7
        request_sound_index(sound_index)
        
        # 等待语音播报（2秒）
        time.sleep(2)
        
        # 5. 最终安全检查（包含应急车道检查）
        is_safe, safety_reason = self.check_return_safety(vehicle_data, control_state)
        if not is_safe:
            request_sound_index(16)  # 变道已取消
            return False, f"语音播报期间检测到不安全: {safety_reason}"
        
        # 6. 发送返回命令
        # 注意：需要将中文方向转换为英文命令
        command_direction = "RIGHT" if return_direction == "右" else "LEFT"
        success = self.send_command("OVERTAKE", command_direction)
        
        if success:
            # 更新状态
            self.return_status['returning'] = True
            self.return_status['return_start_time'] = current_time
            
            # 记录返回开始时的变道计数
            control_state['return_start_count'] = control_state['overtakeSuccessCount']
            
            # 更新控制状态
            control_state.update({
                'lane_change_in_progress': True,
                'isOvertaking': True,
                'lastOvertakeDirection': return_direction,
                'lastLaneChangeCommandTime': current_time,
                'return_attempts': control_state.get('return_attempts', 0) + 1
            })
            
            return True, f"{side_text}返回开始"
        
        return False, "发送返回命令失败"
    
    def check_return_completion(self, control_state):
        """检查返回完成 - 增加手动变道检测"""
        if not self.return_status['returning']:
            return
        
        current_time = time.time() * 1000
        start_time = self.return_status.get('return_start_time', 0)
        
        # 1. 检查是否超时（15秒）
        if current_time - start_time > 15000:
            # 返回超时，重置状态
            self._reset_return_state(control_state)
            self.return_status['return_reason'] = "返回超时15秒"
            control_state['overtakeReason'] = "返回超时，已重置状态"
            return
        
        # 2. 通过车道变化检测
        if 'return_start_count' in control_state:
            current_count = control_state['overtakeSuccessCount']
            start_count = control_state['return_start_count']
            
            if current_count > start_count:
                # 车道变化完成
                self._complete_return(control_state)
    
    def _complete_return(self, control_state):
        """完成返回操作"""
        # 更新净变道数
        if control_state['net_lane_changes'] > 0:
            control_state['net_lane_changes'] -= 1
        else:
            control_state['net_lane_changes'] += 1
        
        # 检查是否已返回原车道
        if control_state['net_lane_changes'] == 0:
            # 返回完成
            self._reset_return_state(control_state)
            
            # 重置自动变道标志
            self.control_state['is_auto_overtake'] = False
            
            control_state.update({
                'overtakeState': "返回原车道完成",
                'overtakeReason': "返回完成，分析道路情况中...",
                'current_status': "返回完成",
                'system_status_detail': "返回完成",
                'completion_timer': time.time() * 1000 + 3000
            })
        else:
            # 还需要继续返回
            side_text = "右" if control_state['net_lane_changes'] > 0 else "左"
            remaining_changes = abs(control_state['net_lane_changes'])
            control_state.update({
                'overtakeState': f"继续返回原车道",
                'overtakeReason': f"还需{remaining_changes}次{side_text}变道回到原车道",
                'system_status_detail': f"还需{remaining_changes}次变道",
                'lane_change_in_progress': False,
                'isOvertaking': False
            })
            
            # 重置返回状态，准备下一次返回
            self.return_status['returning'] = False
            self.return_status['return_ready'] = False
        
        # 清理临时状态
        if 'return_start_count' in control_state:
            del control_state['return_start_count']
    
    def check_return_timeout(self, control_state):
        """检查60秒返回超时"""
        # 检查超车开关
        if self.config['road_type'] == 'highway':
            if not self.config['autoOvertakeEnabled']:
                return False
        else:
            if not self.config['autoOvertakeEnabledL']:
                return False
        
        if not self.return_status['need_return']:
            return False
        
        current_time = time.time() * 1000
        
        # 获取超时开始时间
        if self.return_status.get('timeout_start', 0) == 0:
            return False
        
        timeout_duration = current_time - self.return_status['timeout_start']
        
        if timeout_duration > self.return_status['timeout_duration']:  # 60秒
            # 超时，放弃返回
            self.reset_net_lane_changes()
            control_state['overtakeReason'] = "60秒未返回，已放弃返回原车道"
            return True
        
        return False
    
    def _reset_return_state(self, control_state=None):
        """重置返回状态"""
        self.return_status.update({
            'need_return': False,
            'tracking_target': False,
            'target_side': None,
            'target_lost': False,
            'overtaking_complete': False,
            'return_ready': False,
            'returning': False,
            'timeout_start': 0,
            'target_disappear_start': 0,
            'return_reason': '',
            'progress': 0,
            'return_start_time': 0,
            'last_safe_check_time': 0
        })
        
        if control_state:
            control_state['return_conditions_met'] = False
    
    # ==================== 净变道数管理 ====================
    
    def update_net_lane_changes(self, direction, is_auto_overtake=True):
        """更新净变道次数 - 只有自动变道才不清零"""
        if not self.config['shouldReturnToLane']:
            self.reset_net_lane_changes()
            return

        # 只有自动变道才更新净变道数，其他所有情况都清零
        if not is_auto_overtake:
            self.reset_net_lane_changes()
            self.control_state['is_auto_overtake'] = False  # 明确标记为非自动
            return

        # 自动变道
        if direction == "左":
            self.control_state['net_lane_changes'] += 1
            self.control_state['lastOvertakeDirection'] = "左"
            self.control_state['last_auto_overtake_time'] = time.time() * 1000
            self.control_state['is_auto_overtake'] = True  # 明确标记为自动
        elif direction == "右":
            self.control_state['net_lane_changes'] -= 1
            self.control_state['lastOvertakeDirection'] = "右"
            self.control_state['last_auto_overtake_time'] = time.time() * 1000
            self.control_state['is_auto_overtake'] = True  # 明确标记为自动
        
        # 重置返回超时计时
        self.return_status['timeout_start'] = time.time() * 1000
        self.return_status['tracking_target'] = False
        self.return_status['return_ready'] = False
        self.return_status['returning'] = False
    
    def reset_net_lane_changes(self):
        """重置净变道次数"""
        self.status_manager.reset_net_lane_changes(self.control_state, self.verification_system)
        self.reset_side_vehicle_trackers()
        
        # 重置返回状态
        self._reset_return_state(self.control_state)
        
        self.control_state.update({
            'overtakeState': "系统已就绪🚗随时准备超车",
            'overtakeReason': "分析道路情况中...",
            'current_status': "就绪",
            'system_status_detail': "重置完成",
            'decision_phase': "等待数据",
            'lane_analysis': "未开始",
            'safety_check': "待检查"
        })
        self.control_state['last_status_update_time'] = time.time() * 1000
    
    # ==================== 状态检查和完成 ====================
    
    def check_overtake_completion(self):
        """检查超车完成"""
        if not self.control_state['lane_change_in_progress']:
            return

        current_count = self.control_state['overtakeSuccessCount']
        start_count = self.control_state.get('overtake_start_count', current_count)

        if current_count > start_count:
            self.control_state['isOvertaking'] = False
            self.control_state['lane_change_in_progress'] = False
            self.control_state['overtakingCompleted'] = True

            direction = self.control_state['lastOvertakeDirection']
            direction_text = "左" if direction == "左" else "右"

            self.control_state['overtakeState'] = f"{direction_text}变道超车完成"
            self.control_state['overtakeReason'] = "超车完成，准备下一次操作"
            self.control_state['current_status'] = "超车完成"
            self.control_state['system_status_detail'] = "超车完成"

            self.control_state['completion_timer'] = time.time() * 1000 + 3000

            if 'overtake_start_count' in self.control_state:
                del self.control_state['overtake_start_count']

        elif time.time() * 1000 - self.control_state['lastLaneChangeCommandTime'] > 15000:
            self.control_state['lane_change_in_progress'] = False
            self.control_state['isOvertaking'] = False

            self.control_state['lastOvertakeTime'] = time.time() * 1000
            self.control_state['last_overtake_result'] = 'failed'

            self.control_state['overtakeState'] = "变道超时"
            self.control_state['overtakeReason'] = "15秒内未检测到变道完成，快速重试"
            self.control_state['system_status_detail'] = "变道超时"
            self.control_state['completion_timer'] = time.time() * 1000
    
    def check_manual_lane_change_completion(self):
        """检查手动变道完成"""
        if self.control_state.get('manual_start_count') is not None:
            current_count = self.control_state['overtakeSuccessCount']
            start_count = self.control_state['manual_start_count']

            if current_count > start_count:
                direction = self.control_state['lastOvertakeDirection']
                direction_text = "左" if direction == "左" else "右"

                self.control_state['current_status'] = "手动变道完成"
                self.control_state['overtakeState'] = f"手动{direction_text}变道完成"
                self.control_state['overtakeReason'] = "手动变道完成"
                self.control_state['isOvertaking'] = False
                self.control_state['lane_change_in_progress'] = False
                self.control_state['system_status_detail'] = "手动变道完成"

                self.control_state['completion_timer'] = time.time() * 1000 + 3000

                del self.control_state['manual_start_count']

                self.control_state['lastOvertakeTime'] = time.time() * 1000
                self.control_state['last_overtake_result'] = 'success'
    
    # ==================== 新增：后备定时器检查 ====================
    def check_pending_immediate_update(self):
        if not self.pending_immediate_update['active']:
            return
        current_time = time.time() * 1000
        if current_time - self.pending_immediate_update['start_time'] > self.pending_immediate_update['timeout']:
            # 超时，强制触发即时更新
            direction = self.pending_immediate_update['direction']
            new_lane, desc = self.lane_position_system.request_immediate_lane_update(direction, self.config)
            self.config['current_lane_number'] = new_lane
            self.control_state['lane_position_info'] = f"后备即时更新: {direction}"
            self.pending_immediate_update['active'] = False
            # 设置动态模式为 post_change
            self.lane_position_system.lane_calculator.set_dynamic_mode('post_change', time.time() * 1000)

    # ==================== 新增：检查动态模式超时 ====================
    def update_dynamic_mode(self):
        calc = self.lane_position_system.lane_calculator
        if calc.state['dynamic_mode'] == 'post_change':
            if time.time() * 1000 > calc.state['post_change_end_time']:
                calc.set_dynamic_mode('stable')
    
    # ==================== 状态数据获取 ====================

    def get_status_data(self):
        """获取状态数据"""
        status_data = self.status_manager.get_status_data(
            self.vehicle_data, 
            self.control_state, 
            self.config, 
            self.overtake_decision,
            self  # 传入controller自身
        )
        
        # 确保包含 system_auto_control 字段
        if 'system_auto_control' not in status_data:
            status_data['system_auto_control'] = self.vehicle_data.get('system_auto_control', 0)
        
        # 新增：传递导航车道信息到前端
        if self.nav_lane_array is not None:
            status_data['nav_lane_array'] = self.nav_lane_array
            status_data['nav_lane_confidence'] = self.nav_lane_array_confidence
        
        # 新增：添加推荐车道信息
        current_lane = self.config.get('current_lane_number', 1)
        total_lanes = self.config.get('lane_count', 3)
        
        # 检查左右车道是否为推荐车道
        left_recommended = False
        right_recommended = False
        
        if current_lane > 1:
            left_lane = current_lane - 1
            left_recommended = self.lane_position_system.is_lane_recommended(left_lane)
        
        if current_lane < total_lanes:
            right_lane = current_lane + 1
            right_recommended = self.lane_position_system.is_lane_recommended(right_lane)
        
        status_data['left_lane_recommended'] = left_recommended
        status_data['right_lane_recommended'] = right_recommended
        
        # 添加当前车道是否为推荐车道
        status_data['current_lane_recommended'] = self.lane_position_system.is_lane_recommended(current_lane)
        
        # 添加推荐车道列表
        recommended_lanes = self.recommended_lane_handler.get('recommended_lanes', [])
        status_data['recommended_lanes'] = recommended_lanes
        
        # 添加车道总数百分比调整
        status_data['LANE_COUNT_PERCENT_ADJUST'] = self.config.get('LANE_COUNT_PERCENT_ADJUST', 1.0)
        
        return status_data
    
    def _check_extreme_safety(self, direction):
        """无导航数据时的极端安全检查"""
        # 1. 检查盲区
        if direction == "左":
            if (self.vehicle_data.get('left_blindspot', False) or 
                self.vehicle_data.get('l_front_blind', False) or
                self.vehicle_data.get('lidar_lblind', False)):
                return False, "左侧盲区有车"
            
            lf_drel = self.vehicle_data.get('lf_drel')
            if lf_drel is not None and 0 < lf_drel < 25:  # 更严格的距离要求
                return False, f"左前车辆过近: {lf_drel:.1f}m"
                
            lb_drel = self.vehicle_data.get('lb_drel')
            if lb_drel is not None and 0 < lb_drel < 15:
                return False, f"左后车辆过近: {lb_drel:.1f}m"
                
            # 检查雷达数据
            l_drel = self.vehicle_data.get('l_drel')
            if l_drel is not None and 0 < l_drel < 20:
                return False, f"左侧雷达检测到车辆过近: {l_drel:.1f}m"
        else:
            if (self.vehicle_data.get('right_blindspot', False) or 
                self.vehicle_data.get('r_front_blind', False) or
                self.vehicle_data.get('lidar_rblind', False)):
                return False, "右侧盲区有车"
            
            rf_drel = self.vehicle_data.get('rf_drel')
            if rf_drel is not None and 0 < rf_drel < 25:
                return False, f"右前车辆过近: {rf_drel:.1f}m"
                
            rb_drel = self.vehicle_data.get('rb_drel')
            if rb_drel is not None and 0 < rb_drel < 15:
                return False, f"右后车辆过近: {rb_drel:.1f}m"
                
            # 检查雷达数据
            r_drel = self.vehicle_data.get('r_drel')
            if r_drel is not None and 0 < r_drel < 20:
                return False, f"右侧雷达检测到车辆过近: {r_drel:.1f}m"
        
        # 2. 检查车道宽度
        if direction == "左":
            lane_width = self.vehicle_data.get('l_lane_width', 3.0)
        else:
            lane_width = self.vehicle_data.get('r_lane_width', 3.0)
        
        if lane_width < 2.8:  # 更严格的车道宽度要求
            return False, f"车道过窄: {lane_width:.1f}m"
        
        # 3. 检查相对速度（前车是否够慢）
        vrel = self.vehicle_data.get('vrel', 0)
        if vrel > -5:  # 更严格的要求：前车速度不慢时，不轻易超车
            return False, f"前车速度不慢: 相对速度{vrel:.1f}km/h"
        
        # 4. 检查与前车的距离
        drel = self.vehicle_data.get('drel', 0)
        if drel < 30:  # 需要更大的距离才允许超车
            return False, f"与前车距离过近: {drel:.1f}m"
        
        # 5. 检查速度是否足够
        v_ego = self.vehicle_data.get('v_ego_kph', 0)
        road_type = self.config.get('road_type', 'normal')
        
        if road_type == 'highway':
            if v_ego < 70:  # 高速公路上需要更高的速度
                return False, f"速度过低: {v_ego:.1f}km/h"
        else:
            if v_ego < 40:  # 普通道路上最低速度
                return False, f"速度过低: {v_ego:.1f}km/h"
        
        # 6. 检查道路曲率（弯道不超车）
        max_curve = self.vehicle_data.get('max_curve', 0)
        if abs(max_curve) > 0.5:  # 更严格的弯道检查
            return False, f"弯道曲率过大: {max_curve:.2f}"
        
        # 7. 检查方向盘角度
        steer_angle = abs(self.vehicle_data.get('steering_angle', 0))
        if steer_angle > 2.0:  # 方向盘角度过大
            return False, f"方向盘角度过大: {steer_angle:.1f}°"
        
        return True, "极端安全检查通过"

    def _check_lane_safety_without_nav(self, direction):
        """无导航数据时的车道安全检查"""
        side = "left" if direction == "左" else "right"
        
        # 使用跟踪中心检查
        if self.tracking_center:
            is_safe, safety_reason = self.tracking_center.is_safe_to_change_lane(
                side, self.vehicle_data, self.config
            )
            if not is_safe:
                return False, f"跟踪中心检查: {safety_reason}"
        
        # 检查边缘距离
        if direction == "左":
            edge_dist = self.vehicle_data.get('l_edge_dist')
            if edge_dist is not None and edge_dist < 2.8:  # 更严格的距离
                return False, f"左侧边缘距离不足: {edge_dist:.1f}m"
                
            # 检查车道边界
            if self.vehicle_data.get('l_edge_dist', 100) < 1.5:
                return False, "靠近左侧道路边界"
        else:
            edge_dist = self.vehicle_data.get('r_edge_dist')
            if edge_dist is not None and edge_dist < 2.8:
                return False, f"右侧边缘距离不足: {edge_dist:.1f}m"
                
            # 检查车道边界
            if self.vehicle_data.get('r_edge_dist', 100) < 1.5:
                return False, "靠近右侧道路边界"
        
        # 检查侧前车距离
        if direction == "左":
            side_drel = self.vehicle_data.get('l_drel')
        else:
            side_drel = self.vehicle_data.get('r_drel')
        
        if side_drel is not None and 0 < side_drel < 25:
            return False, f"侧前车距离过近: {side_drel:.1f}m"
        
        # 检查侧前车相对速度
        if direction == "左":
            side_vrel = self.vehicle_data.get('l_vrel', 100)
        else:
            side_vrel = self.vehicle_data.get('r_vrel', 100)
        
        # 如果侧前车速度远低于本车，可能无法完成超车
        if side_vrel < -15:
            return False, f"侧前车速度过慢: {side_vrel:.1f}km/h"
        
        return True, "车道安全检查通过"    


# ==================== 主程序入口 ====================
if __name__ == "__main__":
    controller = AutoOvertakeController()
    controller.start()