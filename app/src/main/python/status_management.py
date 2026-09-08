#!/usr/bin/env python3
"""
状态管理模块
"""

import time
import socket

class StatusManager:
    """状态管理器 - 简化返回状态显示"""
    
    def __init__(self):
        self.vehicle_data = self._init_vehicle_data()
        self.control_state = self._init_control_state()

    def _safe_float_round(self, value, default=0.0):
        """安全地将值转换为浮点数并四舍五入"""
        if value is None:
            return default
        try:
            return round(float(value), 1)
        except (ValueError, TypeError):
            return default
        
    def _get_enhanced_system_detail(self, control_state, config, controller=None):
        """获取增强的系统状态详情 - 重点显示返回状态"""
        base_detail = control_state.get('system_status_detail', '')
        
        # 🆕 安全检查：检查controller是否存在且有return_status属性
        if not controller or not hasattr(controller, 'return_status'):
            return base_detail if base_detail else "系统运行中"
        
        try:
            # 获取返回状态信息
            return_status_info = self.get_return_status_info(controller)
            
            # 如果有返回状态，优先显示返回状态
            if return_status_info.get('need_return', False):
                if return_status_info.get('returning', False):
                    return f"返回中: {return_status_info.get('return_reason', '')}"
                elif return_status_info.get('return_ready', False):
                    return f"准备返回: {return_status_info.get('return_reason', '')}"
                elif return_status_info.get('tracking_target', False):
                    return f"跟踪目标: {return_status_info.get('return_reason', '')}"
                else:
                    return f"等待检测目标: {return_status_info.get('return_reason', '')}"
            
            # 没有返回状态时，显示基础详情
            return base_detail if base_detail else "系统运行中"
        except Exception as e:
            # 🆕 错误处理：防止崩溃
            print(f"获取增强系统详情失败: {e}")
            return base_detail if base_detail else "系统运行中"

    def _init_vehicle_data(self):
        """初始化车辆数据字典"""
        return {
            # 速度相关 - 全部使用整数
            'v_cruise_kph': 0,      # 巡航速度 (km/h)
            'v_ego_kph': 0,         # 本车速度 (km/h)
            'desire_speed': 0,      # 期望速度
            'vlead': 0,             # 前车速度
            'drel': 0,              # 前车距离
            'vrel': 0,              # 前车相对速度

            # 车道信息
            'lane_count': 3,        # 车道总数
            'l_lane_width': 3.0,    # 左侧车道宽度
            'r_lane_width': 3.0,    # 右侧车道宽度
            'l_edge_dist': 2.0,     # 左侧边缘距离
            'r_edge_dist': 2.0,     # 右侧边缘距离

            # 控制状态
            'IsOnroad': False,      # 是否在道路上
            'active': False,        # 系统是否激活
            'engaged': False,       # 巡航是否激活
            'steering_angle': 0,    # 方向盘角度
            'lat_a': 0,             # 横向加速度
            'road_curvature': 0,    # 道路曲率
            'max_curve': 0,         # 最大曲率

            # 盲区检测
            'left_blindspot': False,    # 左侧盲区有车
            'right_blindspot': False,   # 右侧盲区有车
            'l_front_blind': False,     # 左侧前盲区
            'r_front_blind': False,     # 右侧前盲区

            # 侧方车辆信息 - 全部使用整数
            'left_vlead': 0,           # 左侧前车速度
            'l_drel': 0,               # 左侧前车距离
            'l_vrel': 0,               # 左侧前车相对速度
            'right_vlead': 0,          # 右侧前车速度
            'r_drel': 0,               # 右侧前车距离
            'r_vrel': 0,               # 右侧前车相对速度

            # 🆕 车辆检测状态（将由跟踪中心更新）
            'lead1': False,           # 前车存在标志
            'l_lead': False,          # 左侧车辆存在标志
            'r_lead': False,          # 右侧车辆存在标志
            'left_has_vehicle': False, # 左侧是否有车
            'right_has_vehicle': False, # 右侧是否有车
            'left_reliable_no_vehicle': False, # 左侧可靠无车
            'right_reliable_no_vehicle': False, # 右侧可靠无车
            'left_track_quality': 0,   # 左侧跟踪质量
            'right_track_quality': 0,  # 右侧跟踪质量

            # 车辆信号
            'blinker': 'none',      # 转向灯状态
            'gas_press': False,     # 油门踏板
            'break_press': False,   # 刹车踏板

            # 新增激光雷达字段
            'lf_drel': 0.0,        # 左前激光雷达距离
            'lb_drel': 0.0,        # 左后激光雷达距离  
            'rf_drel': 0.0,        # 右前激光雷达距离
            'rb_drel': 0.0,        # 右后激光雷达距离
            'lidar_lblind': False, # 左侧激光雷达盲区
            'lidar_rblind': False, # 右侧激光雷达盲区

            # 新增激光雷达相对速度字段
            'lf_vrel': 0.0,        # 左前激光雷达相对速度 (km/h)
            'lb_vrel': 0.0,        # 左后激光雷达相对速度 (km/h)
            'rf_vrel': 0.0,        # 右前激光雷达相对速度 (km/h)
            'rb_vrel': 0.0,        # 右后激光雷达相对速度 (km/h)

            # 系统控制
            'system_auto_control': 0,   # OP自动控制状态
            'last_op_control_time': 0,  # 最后OP控制时间
            'atc_type': 'none'          # 自动控制类型
        }

    def _init_control_state(self):
        """初始化控制状态字典"""
        control_state = {
            # 基本状态
            'current_status': '就绪',          # 当前状态描述
            'last_command': '',               # 最后执行的命令
            'blinker_state': 'none',          # 转向灯状态
            'cruise_active': False,           # 巡航激活状态

            # 🆕 详细状态信息
            'system_status_detail': '初始化', # 系统状态详情
            'decision_phase': '等待数据',     # 决策阶段
            'lane_analysis': '未开始',        # 车道分析
            'safety_check': '待检查',         # 安全检查

            # 超车状态
            'isOvertaking': False,            # 是否正在超车
            'overtakeState': '系统已就绪🚗随时准备超车',
            'overtakeReason': '分析道路情况中...', # 超车原因
            'overtakingCompleted': False,     # 超车是否完成
            'overtakeSuccessCount': 0,        # 超车成功次数
            'lastOvertakeDirection': '',      # 最后超车方向
            'lastOvertakeTime': 0,            # 最后超车时间

            # 变道控制
            'lane_change_in_progress': False, # 变道进行中
            'lastLaneChangeCommandTime': 0,   # 最后变道命令时间

            # 🆕 简化智能返回系统
            'net_lane_changes': 0,            # 净变道次数（左+1, 右-1）
            'return_attempts': 0,             # 当前返回尝试次数
            'is_auto_overtake': False,        # 是否为自动超车

            # 跟车计时
            'follow_start_time': None,        # 跟车开始时间
            'is_following_slow_vehicle': False, # 是否跟随慢车
            'max_follow_time_reached': False, # 是否达到最大跟车时间

            # 冷却系统
            'last_overtake_result': 'none',   # 最后超车结果
            'dynamic_cooldown': 8000,         # 动态冷却时间(ms)
            'consecutive_failures': 0,        # 连续失败次数

            # 自动超车专用
            'last_auto_overtake_time': 0,     # 最后自动超车时间
            'return_timeout': 60000,          # 🆕 返回超时时间60秒(ms)

            # OP控制冷却
            'op_control_cooldown': 0,         # OP控制冷却时间
            'last_op_control_end_time': 0,    # OP控制结束时间

            # 🆕 简化目标车辆跟踪
            'target_vehicle_side': None,     # 目标车辆所在侧 ('left'/'right')
            'consecutive_overtake_count': 0,  # 连续超车次数
            'last_lane_number': 0,           # 上次车道编号
            'lane_change_detected': False,    # 是否检测到变道
            'last_status_update_time': 0,     # 最后状态更新时间
            'completion_timer': 0,           # 完成状态计时器,
            
            # 🆕 简化原车道记忆系统
            'original_lane_number': 0,
            'target_return_lane': 0,
            
            # 🆕 跟踪中心状态信息
            'tracking_center_initialized': False,
            'tracking_data_version': 0,
            'last_tracking_update_time': 0,
            'vehicle_states': {
                'front': {},
                'left': {},
                'right': {}
            },

            # 🆕 条件稳定性检查（必需字段，用于overtake_decision.py）
            'condition_stability_timer': 0,
            'condition_stable_duration': 500,  # 500ms
            'condition_met_count': 0,
            'condition_met_threshold': 2,      # 需要2次稳定
            'last_condition_check_time': 0,
            'stable_condition_flags': {},      # 🆕 这个字段是必需的，否则会报KeyError
            'quick_trigger_enabled': False
        }
        
        return control_state

    def ensure_status_refresh(self, control_state):
        """确保状态及时刷新 - 优化版本"""
        current_time = time.time() * 1000
        
        # 状态超时检查：5秒无活动自动恢复等待状态
        if (current_time - control_state.get('last_status_update_time', 0) > 5000 and
            not control_state.get('isOvertaking', False) and 
            not control_state.get('lane_change_in_progress', False)):
            
            # 🆕 如果有净变道，不重置为等待超车条件，显示返回相关状态
            if control_state.get('net_lane_changes', 0) == 0:
                if control_state.get('overtakeState') != "等待超车条件":
                    control_state.update({
                        'overtakeState': "等待超车条件",
                        'overtakeReason': "分析道路情况中...",
                        'current_status': "就绪",
                        'system_status_detail': "状态刷新"
                    })
            
            control_state['last_status_update_time'] = current_time
        
        # 状态机检查
        state_timeout = 10000  # 10秒状态超时
        
        # 情况1：超车完成且没有其他活动，恢复就绪状态
        if (control_state.get('overtakingCompleted') and 
            not control_state['isOvertaking'] and 
            not control_state['lane_change_in_progress']):
            
            control_state.update({
                'overtakeState': "等待超车条件",
                'overtakeReason': "分析道路情况中...",
                'current_status': "就绪",
                'system_status_detail': "超车完成",
                'overtakingCompleted': False
            })

        # 情况2：长时间处于同一状态，强制刷新
        elif (control_state.get('last_status_update_time') and 
              current_time - control_state['last_status_update_time'] > state_timeout and
              not control_state['isOvertaking'] and 
              not control_state['lane_change_in_progress']):
            
            # 🆕 如果有净变道，不重置状态
            if control_state.get('net_lane_changes', 0) == 0:
                current_state = control_state.get('overtakeState', '')
                if current_state not in ['等待超车条件', '就绪']:
                    control_state.update({
                        'overtakeState': "等待超车条件",
                        'overtakeReason': "状态超时，自动恢复",
                        'current_status': "就绪",
                        'system_status_detail': "状态超时"
                    })
        
        # 更新状态时间戳
        control_state['last_status_update_time'] = current_time

    def get_local_ip(self):
        """获取本地IP地址"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except:
            return "127.0.0.1"

    def get_no_overtake_reasons(self, vehicle_data, config, control_state, overtake_decision):
        """获取未超车的具体原因"""
        reasons = []

        if overtake_decision.tracking_center:
            # 检查前方车辆数据可靠性
            front_state = overtake_decision.tracking_center.get_vehicle_state('front')
            if front_state:
                if not front_state['data_valid'] and front_state['has_vehicle']:
                    reasons.append("前车数据不可靠，等待稳定")
                    return reasons
        else:
            # 旧版检查
            if not overtake_decision.is_vehicle_data_reliable(vehicle_data, ""):
                reasons.append("前车数据不可靠，等待稳定")
                return reasons

        if vehicle_data.get('system_auto_control', 0) == 1:
            reasons.append("OP自动控制中")
            return reasons
        if vehicle_data.get('system_auto_control', 0) == 2:
            reasons.append("路段禁止超车")
            return reasons
        if vehicle_data.get('system_auto_control', 0) == 3:
            reasons.append("缺少盲区设备")
            return reasons
        if vehicle_data.get('system_auto_control', 0) == 4:
            reasons.append("车道领航控制中")
            return reasons

        if not vehicle_data.get('IsOnroad', False):
            reasons.append("OP不在线")
            return reasons

        if not vehicle_data.get('engaged', False):
            reasons.append("巡航未激活")
            return reasons

        if not vehicle_data.get('lead1', False):
            reasons.append("前方无车辆")
            return reasons

        # 🆕 修复：确保速度是整数
        v_ego_kph = vehicle_data.get('v_ego_kph', 0)
        v_cruise_kph = vehicle_data.get('v_cruise_kph', 1)  # 避免除0
        if v_cruise_kph <= 0:
            v_cruise_kph = 1
            
        speed_ratio = v_ego_kph / v_cruise_kph if v_cruise_kph > 0 else 1.0
        time_gap = overtake_decision.calculate_time_gap(vehicle_data)

        trigger_conditions_met = []

        vrel_value = vehicle_data.get('vrel')
        if vrel_value is not None and vrel_value < config['LEAD_RELATIVE_SPEED_THRESHOLD']:
            trigger_conditions_met.append("前车相对速度")

        if 0 < time_gap <= config['FOLLOW_TIME_GAP_THRESHOLD']:
            trigger_conditions_met.append("跟车时间距离")

        if speed_ratio < config['CRUISE_SPEED_RATIO_THRESHOLD']:
            trigger_conditions_met.append("速度比例")

        if not trigger_conditions_met:
            reasons.append("未满足任何超车触发条件")
            # 🆕 格式化显示为整数
            vrel_display = int(round(vrel_value)) if vrel_value is not None else "无数据"
            reasons.append(f"相对速度:{vrel_display}km/h(阈值:{config['LEAD_RELATIVE_SPEED_THRESHOLD']}km/h)")
            reasons.append(f"时间距离:{time_gap:.1f}秒(阈值:{config['FOLLOW_TIME_GAP_THRESHOLD']}秒)")
            speed_ratio_percent = int(round(speed_ratio * 100))
            reasons.append(f"速度比例:{speed_ratio_percent}%(阈值:{config['CRUISE_SPEED_RATIO_THRESHOLD']*100:.0f}%)")
            return reasons

        # 🆕 修复：车速显示为整数
        v_ego_int = int(round(v_ego_kph))
        
        if config['road_type'] == 'highway' and v_ego_kph < config['HIGHWAY_MIN_SPEED']:
            highway_min_int = int(round(config['HIGHWAY_MIN_SPEED']))
            reasons.append(f"高速车速{v_ego_int}km/h过低(阈值:{highway_min_int}km/h)")

        if config['road_type'] == 'normal' and v_ego_kph < config['NORMAL_ROAD_MIN_SPEED']:
            normal_min_int = int(round(config['NORMAL_ROAD_MIN_SPEED']))
            reasons.append(f"普通路车速{v_ego_int}km/h过低(阈值:{normal_min_int}km/h)")

        now = time.time() * 1000
        if control_state['lastOvertakeTime'] > 0 and now - control_state['lastOvertakeTime'] < control_state['dynamic_cooldown']:
            remaining = (control_state['dynamic_cooldown'] - (now - control_state['lastOvertakeTime'])) / 1000
            reasons.append(f"冷却时间剩余{remaining:.0f}秒")

        if trigger_conditions_met and reasons:
            reasons.insert(0, f"触发条件: {', '.join(trigger_conditions_met)}")

        # 🆕 手动模式左变道验证结果
        if config.get('lane_count_mode', 'auto') == 'manual':
            manual_check = control_state.get('manual_left_check', {})
            if not manual_check.get('allowed', True):
                reason = manual_check.get('reason', '手动验证失败')
                # 截断过长的原因，只显示关键部分
                if len(reason) > 40:
                    reason = reason[:40] + "..."
                reasons.append(f"手动验证: {reason}")            

        return reasons

    def reset_net_lane_changes(self, control_state, verification_system):
        """重置净变道次数 - 简化版"""
        # 原有重置逻辑
        control_state['net_lane_changes'] = 0
        control_state['return_attempts'] = 0
        control_state['is_auto_overtake'] = False

        # 多源验证系统重置
        if verification_system:
            verification_system.reset_verification_system()

        # 清理原车道记忆
        control_state['original_lane_number'] = 0
        control_state['target_return_lane'] = 0

        # 状态重置
        control_state['isOvertaking'] = False
        control_state['lane_change_in_progress'] = False
        control_state['overtakingCompleted'] = False
        control_state['overtakeState'] = "等待超车条件"
        control_state['overtakeReason'] = "分析道路情况中..."
        control_state['current_status'] = "就绪"
        control_state['system_status_detail'] = "重置完成"

    def update_vehicle_data_from_tracking_center(self, vehicle_data, tracking_center):
        """从跟踪中心更新车辆数据"""
        if not tracking_center:
            return
        
        # 获取所有车辆状态
        all_states = tracking_center.get_all_states()
        
        # 更新车辆存在标志
        front_state = all_states.get('front', {})
        left_state = all_states.get('left', {})
        right_state = all_states.get('right', {})
        
        # 更新车辆存在标志
        vehicle_data['lead1'] = front_state.get('has_vehicle', False)
        vehicle_data['l_lead'] = left_state.get('has_vehicle', False)
        vehicle_data['r_lead'] = right_state.get('has_vehicle', False)
        
        # 更新是否有车辆标志
        vehicle_data['left_has_vehicle'] = left_state.get('has_vehicle', False)
        vehicle_data['right_has_vehicle'] = right_state.get('has_vehicle', False)
        
        # 更新可靠无车标志
        vehicle_data['left_reliable_no_vehicle'] = left_state.get('is_reliably_clear', False)
        vehicle_data['right_reliable_no_vehicle'] = right_state.get('is_reliably_clear', False)
        
        # 更新跟踪质量
        vehicle_data['left_track_quality'] = left_state.get('track_quality', 0)
        vehicle_data['right_track_quality'] = right_state.get('track_quality', 0)
        
        # 更新距离和速度数据（如果需要）
        if front_state.get('has_vehicle', False):
            vehicle_data['drel'] = front_state.get('distance')
            vehicle_data['vlead'] = front_state.get('speed')
            vehicle_data['vrel'] = front_state.get('relative_speed')
        
        if left_state.get('has_vehicle', False):
            vehicle_data['l_drel'] = left_state.get('distance')
            vehicle_data['l_vlead'] = left_state.get('speed')
            vehicle_data['l_vrel'] = left_state.get('relative_speed')
        
        if right_state.get('has_vehicle', False):
            vehicle_data['r_drel'] = right_state.get('distance')
            vehicle_data['r_vlead'] = right_state.get('speed')
            vehicle_data['r_vrel'] = right_state.get('relative_speed')
        

    def get_status_data(self, vehicle_data, control_state, config, overtake_decision, controller=None):
        """获取状态数据 - 重点整合返回状态显示"""
        vd = vehicle_data
        cs = control_state
        cfg = config

        # 🆕 修复：确保所有速度都是整数，用于显示
        # 本车速度
        v_ego_kph = vd.get('v_ego_kph', 0)
        vd['v_ego_kph'] = int(round(v_ego_kph))
        
        # 巡航速度
        v_cruise_kph = vd.get('v_cruise_kph', 0)
        vd['v_cruise_kph'] = int(round(v_cruise_kph))
        
        # 期望速度
        desire_speed = vd.get('desire_speed', 0)
        vd['desire_speed'] = int(round(desire_speed))
        
        # 前车速度
        vlead = vd.get('vlead')
        if vlead is not None:
            vd['vlead'] = int(round(vlead))
        
        # 前车相对速度
        vrel = vd.get('vrel')
        if vrel is not None:
            vd['vrel'] = int(round(vrel))
        
        # 左侧前车速度
        l_vlead = vd.get('l_vlead')
        if l_vlead is not None:
            vd['l_vlead'] = int(round(l_vlead))
        
        # 左侧前车相对速度
        l_vrel = vd.get('l_vrel')
        if l_vrel is not None:
            vd['l_vrel'] = int(round(l_vrel))
        
        # 右侧前车速度
        r_vlead = vd.get('r_vlead')
        if r_vlead is not None:
            vd['r_vlead'] = int(round(r_vlead))
        
        # 右侧前车相对速度
        r_vrel = vd.get('r_vrel')
        if r_vrel is not None:
            vd['r_vrel'] = int(round(r_vrel))

        time_gap = overtake_decision.calculate_time_gap(vd)
        speed_ratio = vd['v_ego_kph'] / vd['v_cruise_kph'] if vd['v_cruise_kph'] > 0 else 1.0

        # 🆕 计算道路类型模式信息 - 添加安全检查
        road_type_mode = 'auto'
        manual_road_type = None
        last_manual_road_type = None
        
        if controller:
            try:
                if hasattr(controller, 'road_type_mode'):
                    road_type_mode = controller.road_type_mode
                if hasattr(controller, 'last_manual_road_type'):
                    last_manual_road_type = controller.last_manual_road_type
            except Exception as e:
                print(f"获取控制器属性失败: {e}")
        
        road_name = '高速公路' if cfg['road_type'] == 'highway' else '普通道路'
        mode_text = '自动' if road_type_mode == 'auto' else '手动'
        road_type_display = f"{road_name}({mode_text}模式)"        

        remaining_cooldown = 0
        now = time.time() * 1000
        if cs['lastOvertakeTime'] > 0:
            elapsed = now - cs['lastOvertakeTime']
            remaining_cooldown = max(0, cs['dynamic_cooldown'] - elapsed) / 1000

        remaining_return_timeout = 0
        if cs['net_lane_changes'] != 0 and cs['last_auto_overtake_time'] > 0:
            elapsed_auto = now - cs['last_auto_overtake_time']
            remaining_return_timeout = max(0, cs['return_timeout'] - elapsed_auto) / 1000

        remaining_op_cooldown = 0
        if cs['op_control_cooldown'] > 0:
            elapsed_op = now - cs['last_op_control_end_time']
            remaining_op_cooldown = max(0, cs['op_control_cooldown'] - elapsed_op) / 1000

        trigger_conditions = overtake_decision.get_trigger_conditions(vd, cs)
        no_overtake_reasons = self.get_no_overtake_reasons(vd, cfg, cs, overtake_decision)

        # 🆕 修复：检查车道宽度是否为None
        l_lane_width = vd.get('l_lane_width', 3.0)
        r_lane_width = vd.get('r_lane_width', 3.0)
        min_lane_width = cfg.get('MIN_LANE_WIDTH', 2)
        
        left_lane_narrow = l_lane_width < min_lane_width
        right_lane_narrow = r_lane_width < min_lane_width

        left_warnings = []
        right_warnings = []

        if left_lane_narrow:
            left_warnings.append("车道过窄")
        
        # 🆕 修复：统一盲区检测逻辑，但警告显示保持"盲区有车"
        left_has_blindspot = (
            vd.get('left_blindspot', False) or 
            vd.get('l_front_blind', False) or
            vd.get('lidar_lblind', False)
        )
        if left_has_blindspot:
            left_warnings.append("盲区有车")
        
        # 修改：只有当有左侧前车数据时才检查距离 - 修复None值问题
        l_drel = vd.get('l_drel')
        side_lead_distance_min = cfg.get('SIDE_LEAD_DISTANCE_MIN', 15)
        if l_drel is not None and l_drel > 0 and l_drel < side_lead_distance_min:
            left_warnings.append("侧车过近")
        
        # 修改：只有当有左侧前车数据时才检查相对速度 - 修复None值问题
        l_vrel = vd.get('l_vrel')
        side_relative_speed_threshold = cfg.get('SIDE_RELATIVE_SPEED_THRESHOLD', 20)
        if l_vrel is not None and l_vrel != 0 and l_vrel < -side_relative_speed_threshold:
            left_warnings.append("侧车过慢")

        if right_lane_narrow:
            right_warnings.append("车道过窄")
        
        # 🆕 修复：统一盲区检测逻辑，但警告显示保持"盲区有车"
        right_has_blindspot = (
            vd.get('right_blindspot', False) or 
            vd.get('r_front_blind', False) or
            vd.get('lidar_rblind', False)
        )
        if right_has_blindspot:
            right_warnings.append("盲区有车")
        
        # 修改：只有当有右侧前车数据时才检查距离 - 修复None值问题
        r_drel = vd.get('r_drel')
        if r_drel is not None and r_drel > 0 and r_drel < side_lead_distance_min:
            right_warnings.append("侧车过近")
        
        # 修改：只有当有右侧前车数据时才检查相对速度 - 修复None值问题
        r_vrel = vd.get('r_vrel')
        if r_vrel is not None and r_vrel != 0 and r_vrel < -side_relative_speed_threshold:
            right_warnings.append("侧车过慢")

        max_follow_time_ms = cfg.get('MAX_FOLLOW_TIME', 600000)  # 默认10分钟
        max_follow_time_minutes = max_follow_time_ms / 60000

        # 🆕 获取返回状态信息
        return_status_info = self.get_return_status_info(controller)
        
        # 🆕 获取目标跟踪状态
        target_tracking_state = {}
        return_strategy_state = {}
        
        try:
            return_strategy = getattr(overtake_decision, 'return_strategy', None)
            if return_strategy:
                return_strategy_state = return_strategy.get_return_state()
                target_tracking_state = return_strategy.get_target_tracking_state(cs)
        except Exception:
            return_strategy_state = {'current_stage': 'unknown', 'reason': '获取失败'}
            target_tracking_state = {'tracking': False, 'details': '获取失败'}

        # 🆕 跟踪中心状态信息
        tracking_center_status = {}
        if overtake_decision and hasattr(overtake_decision, 'tracking_center') and overtake_decision.tracking_center:
            tracking_center = overtake_decision.tracking_center
            try:
                all_states = tracking_center.get_all_states()
                tracking_center_status = {
                    'initialized': True,
                    'data_version': all_states.get('data_version', 0),
                    'last_update_time': all_states.get('last_update_time', 0),
                    'vehicle_states': {
                        'front': all_states.get('front', {}),
                        'left': all_states.get('left', {}),
                        'right': all_states.get('right', {})
                    }
                }
                
                # 更新控制状态中的车辆状态
                cs['tracking_center_initialized'] = True
                cs['tracking_data_version'] = all_states.get('data_version', 0)
                cs['last_tracking_update_time'] = all_states.get('last_update_time', 0)
                cs['vehicle_states'] = tracking_center_status['vehicle_states']
                
            except Exception as e:
                tracking_center_status = {
                    'initialized': False,
                    'error': f'获取跟踪中心状态失败: {str(e)}'
                }

        # 系统状态
        status_data = {
            # 系统状态
            'w': True,
            'ip': self.get_local_ip(),
            
            # 🆕 详细状态信息 - 包含返回状态
            'system_status_detail': self._get_enhanced_system_detail(cs, cfg, controller),

            # 🆕 道路类型模式信息
            'road_type_mode': road_type_mode,
            'road_type_display': '高速公路' if cfg['road_type'] == 'highway' else '普通公路',
            'manual_road_type': last_manual_road_type,            

            # 🆕 详细状态信息
            'system_status_detail': cs.get('system_status_detail', ''),
            'decision_phase': cs.get('decision_phase', ''),
            'lane_analysis': cs.get('lane_analysis', ''),
            'safety_check': cs.get('safety_check', ''),

            # 速度信息 - 全部整数
            's': vd['v_ego_kph'],
            'c': vd['v_cruise_kph'],
            'd': vd['desire_speed'],

            # 前车信息 - 安全处理None值
            'ls': int(round(vd.get('vlead', 0))) if vd.get('vlead') is not None else None,
            'ld': int(round(vd.get('drel', 0))) if vd.get('drel') is not None else None,
            'lrs': int(round(vd.get('vrel', 0))) if vd.get('vrel') is not None else None,

            # 盲区状态
            'lb': bool(vd.get('left_blindspot', False)),      # 左侧后盲区
            'rb': bool(vd.get('right_blindspot', False)),     # 右侧后盲区
            'l_front_blind': bool(vd.get('l_front_blind', False)),  # 左侧前盲区
            'r_front_blind': bool(vd.get('r_front_blind', False)),  # 右侧前盲区
            'lidar_lblind': vd.get('lidar_lblind', False),    # 左侧激光雷达盲区
            'lidar_rblind': vd.get('lidar_rblind', False),    # 右侧激光雷达盲区

            # 🆕 车辆检测状态
            'lead1': vd.get('lead1', False),
            'l_lead': vd.get('l_lead', False),
            'r_lead': vd.get('r_lead', False),
            'left_has_vehicle': vd.get('left_has_vehicle', False),
            'right_has_vehicle': vd.get('right_has_vehicle', False),
            'left_reliable_no_vehicle': vd.get('left_reliable_no_vehicle', False),
            'right_reliable_no_vehicle': vd.get('right_reliable_no_vehicle', False),
            'left_track_quality': vd.get('left_track_quality', 0),
            'right_track_quality': vd.get('right_track_quality', 0),

            # 车道几何信息 - 保留1位小数
            'llw': round(float(l_lane_width), 1),
            'rlw': round(float(r_lane_width), 1),
            'led': round(float(vd.get('l_edge_dist', 2.0)), 1),
            'red': round(float(vd.get('r_edge_dist', 2.0)), 1),
            
            # 激光雷达数据 - 允许负值，保留1位小数
            'lf_drel': round(vd.get('lf_drel'), 1) if vd.get('lf_drel') is not None else None,
            'lb_drel': round(vd.get('lb_drel'), 1) if vd.get('lb_drel') is not None else None,
            'rf_drel': round(vd.get('rf_drel'), 1) if vd.get('rf_drel') is not None else None,
            'rb_drel': round(vd.get('rb_drel'), 1) if vd.get('rb_drel') is not None else None,

            # 新增激光雷达相对速度字段 - 保留1位小数
            'lf_vrel': round(vd.get('lf_vrel'), 1) if vd.get('lf_vrel') is not None else None,
            'lb_vrel': round(vd.get('lb_vrel'), 1) if vd.get('lb_vrel') is not None else None,
            'rf_vrel': round(vd.get('rf_vrel'), 1) if vd.get('rf_vrel') is not None else None,
            'rb_vrel': round(vd.get('rb_vrel'), 1) if vd.get('rb_vrel') is not None else None,

            # 配置信息
            'rt': cfg.get('road_type', 'normal'),
            'lc': cfg.get('lane_count', 3),
            'cl': cfg.get('current_lane_number', 2),
            'lane_count_mode': cfg.get('lane_count_mode', 'auto'),

            # 超车状态
            'os': cs.get('overtakeState', '等待超车条件'),
            'or': cs.get('overtakeReason', '分析道路情况中...'),
            'oc': cs.get('overtakeSuccessCount', 0),

            # 超车参数 - 保留原始精度
            'hms': int(cfg.get('HIGHWAY_MIN_SPEED', 75)),
            'nms': int(cfg.get('NORMAL_ROAD_MIN_SPEED', 40)),
            'sr': cfg.get('CRUISE_SPEED_RATIO_THRESHOLD', 0.8),
            'ftg': round(float(cfg.get('FOLLOW_TIME_GAP_THRESHOLD', 3.0)), 1),  # 保留1位小数
            'mft': int(max_follow_time_minutes),
            'mft_ms': cfg.get('MAX_FOLLOW_TIME', 120000),
            'mlw': round(float(min_lane_width), 1),  # 保留1位小数
            'slw': round(float(cfg.get('SAFE_LANE_WIDTH', 3)), 1),  # 保留1位小数
            'sld': int(side_lead_distance_min),
            'srs': int(side_relative_speed_threshold),
            'lrs_threshold': int(cfg.get('LEAD_RELATIVE_SPEED_THRESHOLD', -5)),

            # 🆕 手动模式左变道验证信息
            'manual_left_check': cs.get('manual_left_check', {
                'allowed': True,
                'reason': '未验证'
            }),            

            # 功能开关
            'aoe': cfg.get('autoOvertakeEnabled', True),
            'aoel': cfg.get('autoOvertakeEnabledL', True),
            'srtl': cfg.get('shouldReturnToLane', True),

            # 警告状态
            'left_lane_narrow': left_lane_narrow,
            'right_lane_narrow': right_lane_narrow,

            # 系统控制状态
            'system_auto_control': vd.get('system_auto_control', 0),

            # 🆕 智能返回系统
            'net_lane_changes': cs.get('net_lane_changes', 0),
            'return_attempts': cs.get('return_attempts', 0),

            # 冷却系统
            'remaining_cooldown': int(remaining_cooldown),
            'dynamic_cooldown': cs.get('dynamic_cooldown', 8000),
            'last_overtake_result': cs.get('last_overtake_result', 'none'),
            'consecutive_failures': cs.get('consecutive_failures', 0),

            # 实时指标 - 时间距离保留1位小数
            'time_gap': round(float(time_gap), 1),  # 保留1位小数
            'speed_ratio': speed_ratio,
            'sr_threshold': cfg.get('CRUISE_SPEED_RATIO_THRESHOLD', 0.8),

            # 条件分析
            'trigger_conditions': trigger_conditions,
            'no_overtake_reasons': no_overtake_reasons,

            # 超时信息
            'remaining_return_timeout': int(remaining_return_timeout),
            'remaining_op_cooldown': int(remaining_op_cooldown),

            # 警告信息
            'left_warnings': left_warnings,
            'right_warnings': right_warnings,

            # 新增状态
            'is_auto_overtake': cs.get('is_auto_overtake', False),

            # 🆕 返回策略状态
            'return_strategy_enabled': cfg.get('RETURN_STRATEGY', {}).get(cfg['road_type'], {}).get('enabled', True),
            'return_strategy_state': return_strategy_state,
            'target_tracking_state': target_tracking_state,
            
            # 🆕 返回状态信息（主控制器中的简化状态）
            'return_status_info': return_status_info,
            
            # 🆕 简化的返回条件状态
            'return_conditions': {
                'net_lane_changes': cs.get('net_lane_changes', 0),
                'original_lane_number': cs.get('original_lane_number', 0),
                'current_lane_number': cfg.get('current_lane_number', 0),
                'lane_difference': cs.get('original_lane_number', 0) - cfg.get('current_lane_number', 0),
                'return_attempts': cs.get('return_attempts', 0),
                'is_auto_overtake': cs.get('is_auto_overtake', False),
                'should_return_to_lane': cfg.get('shouldReturnToLane', True),
                
                # 从返回状态信息中获取
                'need_return': return_status_info.get('need_return', False),
                'return_ready': return_status_info.get('return_ready', False),
                'returning': return_status_info.get('returning', False),
                'progress': return_status_info.get('progress', 0),
                'return_reason': return_status_info.get('return_reason', ''),
                'remaining_timeout': return_status_info.get('remaining_timeout', 0),
                'tracking_target': return_status_info.get('tracking_target', False),
                'overtaking_complete': return_status_info.get('overtaking_complete', False)
            },
            
            # 🆕 跟踪中心状态信息
            'tracking_center_status': tracking_center_status,

            # v3.7 新增参数 - 全部整数
            'highway_lead_min_speed': int(cfg.get('HIGHWAY_LEAD_MIN_SPEED', 35)),
            'normal_lead_min_speed': int(cfg.get('NORMAL_LEAD_MIN_SPEED', 20)),
            'early_overtake_speed_ratio': cfg.get('EARLY_OVERTAKE_SPEED_RATIO', 0.6),
            'early_overtake_min_vlead': int(cfg.get('EARLY_OVERTAKE_MIN_LEAD_SPEED', 50)),
            'early_overtake_min_distance': int(cfg.get('EARLY_OVERTAKE_MIN_DISTANCE', 30)),
            'early_overtake_max_distance': int(cfg.get('EARLY_OVERTAKE_MAX_DISTANCE', 100)),
            'early_overtake_min_speed_diff': int(cfg.get('EARLY_OVERTAKE_MIN_SPEED_DIFF', 20))
        }

        # 🆕 条件输出侧方车辆数据 - 只在有有效数据时输出
        # 左侧前车数据
        l_drel = vd.get('l_drel')
        if l_drel is not None and l_drel > 0:  # 只有在前车距离大于0时才认为有有效数据
            # 确保速度是整数
            lls_value = vd.get('l_vlead', 0)
            if lls_value is None:
                lls_value = 0
            status_data['lls'] = int(round(lls_value))  # 左侧前车速度
            
            status_data['lld'] = round(float(l_drel), 1)   # 左侧前车距离，保留1位小数
            
            llrs_value = vd.get('l_vrel', 0)
            if llrs_value is None:
                llrs_value = 0
            status_data['llrs'] = int(round(llrs_value))  # 左侧前车相对速度
        else:
            # 没有有效数据时，不输出这些字段或输出null
            status_data['lls'] = None
            status_data['lld'] = None
            status_data['llrs'] = None

        # 右侧前车数据
        r_drel = vd.get('r_drel')
        if r_drel is not None and r_drel > 0:  # 只有在前车距离大于0时才认为有有效数据
            # 确保速度是整数
            rls_value = vd.get('r_vlead', 0)
            if rls_value is None:
                rls_value = 0
            status_data['rls'] = int(round(rls_value))  # 右侧前车速度
            
            status_data['rld'] = round(float(r_drel), 1)   # 右侧前车距离，保留1位小数
            
            rlrs_value = vd.get('r_vrel', 0)
            if rlrs_value is None:
                rlrs_value = 0
            status_data['rlrs'] = int(round(rlrs_value))  # 右侧前车相对速度
        else:
            # 没有有效数据时，不输出这些字段或输出null
            status_data['rls'] = None
            status_data['rld'] = None
            status_data['rlrs'] = None

        return status_data
    
    def get_return_status_info(self, controller):
        """获取返回状态信息"""
        if not controller:
            return {
                'need_return': False,
                'tracking_target': False,
                'return_ready': False,
                'returning': False,
                'progress': 0,
                'return_reason': '',
                'remaining_timeout': 0
            }
        
        try:
            # 🆕 安全检查：检查return_status属性是否存在
            if not hasattr(controller, 'return_status'):
                return {
                    'need_return': False,
                    'tracking_target': False,
                    'return_ready': False,
                    'returning': False,
                    'progress': 0,
                    'return_reason': '',
                    'remaining_timeout': 0
                }
            
            return_status = controller.return_status
            
            # 计算超时剩余时间
            remaining_timeout = 0
            if return_status.get('timeout_start', 0) > 0:
                elapsed = time.time() * 1000 - return_status['timeout_start']
                remaining_timeout = max(0, return_status.get('timeout_duration', 60000) - elapsed) / 1000
            
            return {
                'need_return': return_status.get('need_return', False),
                'tracking_target': return_status.get('tracking_target', False),
                'target_side': return_status.get('target_side'),
                'target_lost': return_status.get('target_lost', False),
                'overtaking_complete': return_status.get('overtaking_complete', False),
                'return_ready': return_status.get('return_ready', False),
                'returning': return_status.get('returning', False),
                'progress': return_status.get('progress', 0),
                'return_reason': return_status.get('return_reason', ''),
                'remaining_timeout': int(remaining_timeout),
                'timeout_start': return_status.get('timeout_start', 0) > 0,
                'target_disappear_start': return_status.get('target_disappear_start', 0) > 0,
                'return_start_time': return_status.get('return_start_time', 0) > 0
            }
        except Exception as e:
            # 🆕 错误处理：防止崩溃
            print(f"获取返回状态信息失败: {e}")
            return {
                'need_return': False,
                'tracking_target': False,
                'return_ready': False,
                'returning': False,
                'progress': 0,
                'return_reason': '',
                'remaining_timeout': 0
            }