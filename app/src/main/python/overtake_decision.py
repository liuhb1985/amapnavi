#!/usr/bin/env python3
"""
超车决策模块
"""

import time
from collections import deque
from typing import Dict, Tuple, List, Optional


class OvertakeDecisionEngine:
    def __init__(self, config=None):
        self.config = config or self._get_default_config()
        
        self.last_overtake_direction = None
        self.consecutive_overtake_count = 0
        self.last_overtake_completion_time = 0
        
        self.tracking_center = None
        self.lane_position_system = None
        self.return_strategy = None
        
        self.decision_stats = {
            'total_decisions': 0,
            'positive_decisions': 0,
            'negative_decisions': 0,
            'avg_decision_time': 0.0
        }
    
    def _get_default_config(self):
        return {
            'MIN_OVERTAKE_INTERVAL': 8000,
            'OVERTAKE_COOLDOWN_BASE': 5000,
            'OVERTAKE_COOLDOWN_SUCCESS': 8000,
            'OVERTAKE_COOLDOWN_FAILED': 3000,
            'OVERTAKE_COOLDOWN_CONDITION': 4000,
            'HIGHWAY_MIN_SPEED': 60.0,
            'NORMAL_ROAD_MIN_SPEED': 30.0,
            'HIGHWAY_LEAD_MIN_SPEED': 40.0,
            'NORMAL_LEAD_MIN_SPEED': 30.0,
            'LEAD_RELATIVE_SPEED_THRESHOLD': -3.0,
            'CRUISE_SPEED_RATIO_THRESHOLD': 0.85,
            'FOLLOW_TIME_GAP_THRESHOLD': 3.0,
            'MAX_FOLLOW_TIME': 300000,
            'CONDITION_STABLE_DURATION': 2000,
            'CONDITION_MET_THRESHOLD': 3,
            'EARLY_OVERTAKE_SPEED_RATIO': 0.85,
            'EARLY_OVERTAKE_MIN_LEAD_SPEED': 40.0,
            'EARLY_OVERTAKE_MIN_SPEED_DIFF': 10.0,
            'EARLY_OVERTAKE_MIN_DISTANCE': 60.0,
            'EARLY_OVERTAKE_MAX_DISTANCE': 200.0,
            'MIN_LANE_WIDTH': 2.3,
            'SAFE_LANE_WIDTH': 3.0,
            'SIDE_LEAD_DISTANCE_MIN': 20.0,
            'MIN_SPEED_ADVANTAGE': 5.0,
            'PENALTY_THRESHOLD': 60,
            'MIN_EFFECTIVENESS_THRESHOLD': 60,
            'HIGHWAY_EFFECTIVENESS_THRESHOLD': 65,
            'EFFECTIVENESS_DISTANCE_LIMIT': 50.0,
            'OP_CONTROL_COOLDOWN': 3000,
            'TRIGGER_SENSITIVITY': {
                'speed_ratio': 0.95,
                'time_gap': 0.8,
                'vrel': 0.9,
                'distance': 0.7
            },
            'QUICK_TRIGGER_TIMEOUT': 3000,
            'SIDE_VS_FRONT_SAFETY_MARGIN': 5.0
        }
    
    def _get_config(self, key, default=None):
        return self.config.get(key, default)
    
    def set_tracking_center(self, tracking_center):
        self.tracking_center = tracking_center
    
    def set_lane_position_system(self, lane_position_system):
        self.lane_position_system = lane_position_system
    
    def check_side_vs_front_distance(self, direction, vehicle_data):
        front_distance = vehicle_data.get('drel')
        if front_distance is None or front_distance <= 0:
            return True, "前方无车辆"
        
        if direction == "左":
            left_distance = None
            
            lf_drel = vehicle_data.get('lf_drel')
            if lf_drel is not None and lf_drel > 0:
                left_distance = lf_drel
            elif vehicle_data.get('l_drel') is not None and vehicle_data['l_drel'] > 0:
                left_distance = vehicle_data['l_drel']
            elif vehicle_data.get('lb_drel') is not None and vehicle_data['lb_drel'] > 0:
                left_distance = vehicle_data['lb_drel']
            
            if left_distance is not None:
                SAFETY_MARGIN = self._get_config('SIDE_VS_FRONT_SAFETY_MARGIN', 5.0)
                if left_distance < (front_distance - SAFETY_MARGIN):
                    return False, f"左侧车辆({left_distance:.1f}m)比前车({front_distance:.1f}m)更近"
            
            return True, f"左侧车辆距离检查通过（前车:{front_distance:.1f}m）"
        
        else:
            right_distance = None
            
            rf_drel = vehicle_data.get('rf_drel')
            if rf_drel is not None and rf_drel > 0:
                right_distance = rf_drel
            elif vehicle_data.get('r_drel') is not None and vehicle_data['r_drel'] > 0:
                right_distance = vehicle_data['r_drel']
            elif vehicle_data.get('rb_drel') is not None and vehicle_data['rb_drel'] > 0:
                right_distance = vehicle_data['rb_drel']
            
            if right_distance is not None:
                SAFETY_MARGIN = self._get_config('SIDE_VS_FRONT_SAFETY_MARGIN', 5.0)
                if right_distance < (front_distance - SAFETY_MARGIN):
                    return False, f"右侧车辆({right_distance:.1f}m)比前车({front_distance:.1f}m)更近"
            
            return True, f"右侧车辆距离检查通过（前车:{front_distance:.1f}m）"
    
    def check_side_vs_front_distance_comprehensive(self, direction, vehicle_data):
        front_distances = []
        
        drel = vehicle_data.get('drel')
        if drel is not None and drel > 0:
            front_distances.append(drel)
        
        lf_drel = vehicle_data.get('lf_drel')
        rf_drel = vehicle_data.get('rf_drel')
        if lf_drel is not None and lf_drel > 0:
            front_distances.append(lf_drel)
        if rf_drel is not None and rf_drel > 0:
            front_distances.append(rf_drel)
        
        if not front_distances:
            return True, "前方无车辆数据"
        
        min_front_distance = min(front_distances)
        
        side_distances = []
        
        if direction == "左":
            sources = [
                ('l_drel', '雷达'),
                ('lf_drel', '左前激光雷达'),
                ('lb_drel', '左后激光雷达')
            ]
        else:
            sources = [
                ('r_drel', '雷达'),
                ('rf_drel', '右前激光雷达'),
                ('rb_drel', '右后激光雷达')
            ]
        
        for field, source_name in sources:
            distance = vehicle_data.get(field)
            if distance is not None and distance > 0:
                side_distances.append((distance, source_name))
        
        if not side_distances:
            return True, f"无{direction}侧车辆数据（前车距离:{min_front_distance:.1f}m）"
        
        for side_distance, source_name in side_distances:
            SAFETY_MARGIN = self._get_config('SIDE_VS_FRONT_SAFETY_MARGIN', 5.0)
            
            if side_distance < (min_front_distance - SAFETY_MARGIN):
                return False, (f"{source_name}检测到{direction}侧车辆({side_distance:.1f}m) "
                             f"比前车({min_front_distance:.1f}m)更近")
            
            if side_distance < 10:
                if side_distance < min_front_distance:
                    return False, (f"{source_name}检测到{direction}侧车辆过近({side_distance:.1f}m)，"
                                 f"即使比前车({min_front_distance:.1f}m)远也不安全")
        
        closest_side = min([d for d, _ in side_distances])
        return True, (f"{direction}侧车辆距离安全（最远侧车:{closest_side:.1f}m, "
                     f"最近前车:{min_front_distance:.1f}m）")
    
    def can_turn_left_in_manual_mode(self, vehicle_data, config):
        if config.get('lane_count_mode') != 'manual':
            return True, "自动模式，使用原逻辑"

        total_lanes = config.get('lane_count', 3)
        if total_lanes < 2:
            return False, f"总车道数{total_lanes}，无左边车道"

        right_edge_dist = vehicle_data.get('r_edge_dist')
        if right_edge_dist is None or right_edge_dist <= 0:
            return False, "无有效的右边缘距离数据"

        right_width = vehicle_data.get('r_lane_width', 3.0)
        if right_width < 2.5 or right_width > 4.0:
            if config.get('road_type') == 'highway':
                right_width = 3.0
            else:
                right_width = 2.8

        lanes_on_right = right_edge_dist / right_width
        expected_right_lanes = total_lanes - 1
        buffer = 0.3
        threshold = expected_right_lanes - buffer

        if lanes_on_right < threshold:
            return True, f"右侧{lanes_on_right:.1f}车道<{threshold:.1f}，允许向左变道"
        else:
            return False, f"右侧{lanes_on_right:.1f}车道≥{threshold:.1f}，可能已在最左车道"
    
    def has_vehicle_in_direction(self, vehicle_data, direction):
        if direction == "":
            lead1_flag = vehicle_data.get('lead1')
            if lead1_flag is not None:
                return bool(lead1_flag)
            
            drel = vehicle_data.get('drel')
            if drel is not None and drel >= 0:
                return True
            
            vlead = vehicle_data.get('vlead')
            if vlead is not None and vlead >= 0:
                return True
            
            vrel = vehicle_data.get('vrel')
            if vrel is not None:
                return True
            
            lf_drel = vehicle_data.get('lf_drel')
            rf_drel = vehicle_data.get('rf_drel')
            if (lf_drel is not None and lf_drel > 0) or (rf_drel is not None and rf_drel > 0):
                return True
            
            if self.tracking_center:
                return self.tracking_center.vehicle_exists("front")
            
            return False
            
        elif direction == "左":
            l_lead_flag = vehicle_data.get('l_lead')
            if l_lead_flag is not None:
                return bool(l_lead_flag)
            
            l_drel = vehicle_data.get('l_drel')
            if l_drel is not None and l_drel >= 0:
                return True
            
            lf_drel = vehicle_data.get('lf_drel')
            lb_drel = vehicle_data.get('lb_drel')
            if (lf_drel is not None and lf_drel > 0) or (lb_drel is not None and lb_drel > 0):
                return True
            
            if self.tracking_center:
                return self.tracking_center.vehicle_exists("left")
            
            return False
            
        elif direction == "右":
            r_lead_flag = vehicle_data.get('r_lead')
            if r_lead_flag is not None:
                return bool(r_lead_flag)
            
            r_drel = vehicle_data.get('r_drel')
            if r_drel is not None and r_drel >= 0:
                return True
            
            rf_drel = vehicle_data.get('rf_drel')
            rb_drel = vehicle_data.get('rb_drel')
            if (rf_drel is not None and rf_drel > 0) or (rb_drel is not None and rb_drel > 0):
                return True
            
            if self.tracking_center:
                return self.tracking_center.vehicle_exists("right")
            
            return False
        
        return False
    
    def _legacy_has_vehicle_in_direction(self, vehicle_data, direction):
        if direction == "":
            lead1_flag = vehicle_data.get('lead1')
            if lead1_flag is not None:
                return bool(lead1_flag)
            
            drel = vehicle_data.get('drel')
            vlead = vehicle_data.get('vlead')
            vrel = vehicle_data.get('vrel')
            
            if drel is not None and drel >= 0:
                return True
            if vlead is not None and vlead >= 0:
                return True
            if vrel is not None:
                return True
            return False
            
        elif direction == "左":
            l_lead_flag = vehicle_data.get('l_lead')
            if l_lead_flag is not None:
                return bool(l_lead_flag)
            
            l_drel = vehicle_data.get('l_drel')
            l_vlead = vehicle_data.get('l_vlead')
            l_vrel = vehicle_data.get('l_vrel')
            
            if l_drel is not None and l_drel >= 0:
                return True
            if l_vlead is not None and l_vlead >= 0:
                return True
            if l_vrel is not None:
                return True
            return False
            
        elif direction == "右":
            r_lead_flag = vehicle_data.get('r_lead')
            if r_lead_flag is not None:
                return bool(r_lead_flag)
            
            r_drel = vehicle_data.get('r_drel')
            r_vlead = vehicle_data.get('r_vlead')
            r_vrel = vehicle_data.get('r_vrel')
            
            if r_drel is not None and r_drel >= 0:
                return True
            if r_vlead is not None and r_vlead >= 0:
                return True
            if r_vrel is not None:
                return True
            return False
        
        return False
    
    def check_op_control_cooldown(self, control_state):
        current_time = time.time() * 1000

        if control_state.get('op_control_cooldown', 0) > 0:
            elapsed = current_time - control_state.get('last_op_control_end_time', 0)
            if elapsed < control_state['op_control_cooldown']:
                return True
            else:
                control_state['op_control_cooldown'] = 0

        return False
    
    def update_following_status(self, vehicle_data, control_state):
        now = time.time() * 1000

        has_vehicle = self.has_vehicle_in_direction(vehicle_data, "")
        
        if not has_vehicle:
            if control_state.get('follow_start_time') is not None:
                control_state['follow_start_time'] = None
                control_state['is_following_slow_vehicle'] = False
                control_state['max_follow_time_reached'] = False
            return
        
        time_gap = self.calculate_time_gap(vehicle_data)
        speed_ratio = vehicle_data['v_ego_kph'] / vehicle_data['v_cruise_kph'] if vehicle_data['v_cruise_kph'] > 0 else 1.0

        vrel_value = vehicle_data.get('vrel')
        vrel_condition = False
        if vrel_value is not None:
            vrel_condition = vrel_value < self._get_config('LEAD_RELATIVE_SPEED_THRESHOLD', -3.0)

        is_following = (
            vrel_condition or
            (0 < time_gap <= self._get_config('FOLLOW_TIME_GAP_THRESHOLD', 3.0)) or
            speed_ratio < self._get_config('CRUISE_SPEED_RATIO_THRESHOLD', 0.85)
        )

        if is_following:
            if control_state.get('follow_start_time') is None:
                control_state['follow_start_time'] = now
                control_state['is_following_slow_vehicle'] = True
            follow_duration = now - control_state['follow_start_time']
            max_follow_time = self._get_config('MAX_FOLLOW_TIME', 300000)
            if follow_duration >= max_follow_time and not control_state.get('max_follow_time_reached', False):
                control_state['max_follow_time_reached'] = True
                minutes = int(max_follow_time / 60000)
                control_state['overtakeReason'] = f"跟车时间超过{minutes}分钟"
        else:
            if control_state.get('follow_start_time') is not None:
                control_state['follow_start_time'] = None
                control_state['is_following_slow_vehicle'] = False
                control_state['max_follow_time_reached'] = False
    
    def calculate_time_gap(self, vehicle_data):
        if not self.has_vehicle_in_direction(vehicle_data, ""):
            return 0

        distance = vehicle_data.get('drel')
        if distance is None or distance <= 0 or vehicle_data.get('v_ego_kph', 0) <= 0:
            return 0

        v_ego_ms = vehicle_data['v_ego_kph'] / 3.6
        time_gap = distance / v_ego_ms if v_ego_ms > 0 else 0
        return int(time_gap)
    
    def check_lead_vehicle_min_speed(self, vehicle_data, control_state):
        if not self.has_vehicle_in_direction(vehicle_data, ""):
            return True
        
        lead_speed = vehicle_data.get('vlead')
        
        if lead_speed is None:
            return True
        
        road_type = self.config.get('road_type', 'normal')
        if road_type == 'highway':
            min_speed = self._get_config('HIGHWAY_LEAD_MIN_SPEED', 40.0)
            if lead_speed < min_speed:
                control_state['overtakeReason'] = f"高速公路前车速度{lead_speed}km/h低于{min_speed}km/h"
                control_state['last_overtake_result'] = 'condition'
                return False
        else:
            min_speed = self._get_config('NORMAL_LEAD_MIN_SPEED', 30.0)
            if lead_speed < min_speed:
                control_state['overtakeReason'] = f"普通道路前车速度{lead_speed}km/h低于{min_speed}km/h"
                control_state['last_overtake_result'] = 'condition'
                return False
        
        return True
    
    def check_early_overtake_conditions(self, vehicle_data, control_state):
        road_type = self.config.get('road_type', 'normal')
        if road_type != 'highway':
            return False
        
        system_auto_control = vehicle_data.get('system_auto_control', 0)
        if system_auto_control >= 1:
            return False
        
        if not vehicle_data.get('IsOnroad', False):
            return False
        
        if not vehicle_data.get('engaged', False):
            return False
        
        highway_min_speed = self._get_config('HIGHWAY_MIN_SPEED', 60.0)
        if vehicle_data['v_ego_kph'] < highway_min_speed:
            return False
        
        now = time.time() * 1000
        current_cooldown = self.calculate_dynamic_cooldown(control_state)
        if now - control_state.get('lastOvertakeTime', 0) < current_cooldown and not control_state.get('quick_trigger_enabled', False):
            return False
        
        speed_ratio = vehicle_data['v_ego_kph'] / vehicle_data['v_cruise_kph'] if vehicle_data['v_cruise_kph'] > 0 else 1.0
        if speed_ratio >= 0.99:
            return False
        
        if not self.has_vehicle_in_direction(vehicle_data, ""):
            return False
        
        current_speed = vehicle_data['v_ego_kph']
        lead_speed = vehicle_data.get('vlead')
        lead_distance = vehicle_data.get('drel')
        
        if lead_speed is None or lead_distance is None:
            return False
        
        if lead_speed <= 0 or lead_distance <= 0:
            return False
        
        if not self.check_lead_vehicle_min_speed(vehicle_data, control_state):
            return False
        
        speed_difference = current_speed - lead_speed
        
        speed_ratio = lead_speed / current_speed if current_speed > 0 else 1
        is_slow_vehicle = speed_ratio <= self._get_config('EARLY_OVERTAKE_SPEED_RATIO', 0.85)
        
        is_not_traffic_jam = lead_speed >= self._get_config('EARLY_OVERTAKE_MIN_LEAD_SPEED', 40.0)
        is_significant_slowdown = speed_difference >= self._get_config('EARLY_OVERTAKE_MIN_SPEED_DIFF', 10.0)
        is_proper_distance = (self._get_config('EARLY_OVERTAKE_MIN_DISTANCE', 60.0) <= lead_distance <= 
                            self._get_config('EARLY_OVERTAKE_MAX_DISTANCE', 200.0))
        
        if (is_slow_vehicle and is_not_traffic_jam and 
            is_significant_slowdown and is_proper_distance):
            return True
        
        return False
    
    def check_condition_stability(self, current_conditions, control_state):
        current_time = time.time() * 1000
        
        if not current_conditions:
            control_state['condition_stability_timer'] = 0
            control_state['condition_met_count'] = 0
            control_state['stable_condition_flags'] = {}
            control_state['quick_trigger_enabled'] = False
            return False
        
        condition_changed = False
        current_flags = {cond: True for cond in current_conditions}
        
        if control_state.get('stable_condition_flags', {}) != current_flags:
            condition_changed = True
            control_state['stable_condition_flags'] = current_flags
        
        if condition_changed:
            control_state['condition_stability_timer'] = current_time
            control_state['condition_met_count'] = control_state.get('condition_met_count', 0) + 1
        else:
            if control_state.get('condition_stability_timer', 0) == 0:
                control_state['condition_stability_timer'] = current_time
            
            stable_duration = current_time - control_state['condition_stability_timer']
            
            condition_stable_duration = self._get_config('CONDITION_STABLE_DURATION', 2000)
            condition_met_threshold = self._get_config('CONDITION_MET_THRESHOLD', 3)
            
            if (stable_duration >= condition_stable_duration or 
                control_state.get('condition_met_count', 0) >= condition_met_threshold):
                
                control_state['quick_trigger_enabled'] = True
                control_state['quick_trigger_start'] = current_time
                return True
        
        control_state['last_condition_check_time'] = current_time
        return False
    
    def get_trigger_conditions(self, vehicle_data, control_state):
        conditions = []

        current_time = time.time() * 1000
        
        min_interval = self._get_config('MIN_OVERTAKE_INTERVAL', 8000)
        if current_time - self.last_overtake_completion_time < min_interval:
            return conditions

        has_lead_vehicle = self.has_vehicle_in_direction(vehicle_data, "")
        lead_speed = vehicle_data.get('vlead')
        lead_distance = vehicle_data.get('drel')
        lead_relative_speed = vehicle_data.get('vrel')
        
        if not has_lead_vehicle or lead_speed is None or lead_distance is None or lead_relative_speed is None:
            return conditions
        
        if self.check_early_overtake_conditions(vehicle_data, control_state):
            conditions.append("远距离超车触发（前车过慢）")

        if control_state.get('max_follow_time_reached', False):
            max_follow_time = self._get_config('MAX_FOLLOW_TIME', 300000)
            follow_minutes = int(max_follow_time / 60000)
            conditions.append(f"最大跟车时间触发（>{follow_minutes}分钟）")

        speed_ratio = vehicle_data['v_ego_kph'] / vehicle_data['v_cruise_kph'] if vehicle_data['v_cruise_kph'] > 0 else 1.0
        trigger_conditions = []

        vrel_value = lead_relative_speed
        vrel_threshold = self._get_config('LEAD_RELATIVE_SPEED_THRESHOLD', -3.0)
        if vrel_value < vrel_threshold:
            vrel_int = int(round(vrel_value))
            trigger_conditions.append(f"前车较慢: {vrel_int}km/h")

        time_gap = self.calculate_time_gap(vehicle_data)
        time_gap_threshold = self._get_config('FOLLOW_TIME_GAP_THRESHOLD', 2.0)
        if 0 < time_gap <= time_gap_threshold:
            trigger_conditions.append(f"跟车时距: {time_gap}秒")

        speed_ratio_threshold = self._get_config('CRUISE_SPEED_RATIO_THRESHOLD', 0.85)
        if speed_ratio < speed_ratio_threshold:
            speed_ratio_percent = int(round(speed_ratio * 100))
            trigger_conditions.append(f"速度比例: {speed_ratio_percent}%")

        if trigger_conditions:
            conditions.extend(trigger_conditions)
        
        return conditions
    
    def check_overtake_conditions(self, vehicle_data, control_state):
        now = time.time() * 1000
            
        min_interval = self._get_config('MIN_OVERTAKE_INTERVAL', 8000)
        if now - self.last_overtake_completion_time < min_interval:
            remaining = (min_interval - (now - self.last_overtake_completion_time)) / 1000
            control_state['overtakeReason'] = f"超车间隔保护，请等待{remaining:.1f}秒"
            control_state['last_overtake_result'] = 'condition'
            return False

        system_auto_control = vehicle_data.get('system_auto_control', 0)
        road_type = self.config.get('road_type', 'normal')
        if system_auto_control >= 1:
            if system_auto_control == 2 and road_type == 'highway':
                control_state['overtakeReason'] = "路段禁止超车"
                control_state['last_overtake_result'] = 'condition'
                return False
            elif system_auto_control == 3:
                control_state['overtakeReason'] = "缺少盲区设备"
                control_state['last_overtake_result'] = 'condition'
                return False
            elif system_auto_control == 4:
                control_state['overtakeReason'] = "车道领航控制中"
                control_state['last_overtake_result'] = 'condition'
                return False
            else:
                control_state['overtakeReason'] = "OP自动控制中"
                control_state['last_overtake_result'] = 'condition'
                return False

        if vehicle_data.get('system_auto_control', 0) == 1:
            control_state['overtakeReason'] = "OP自动控制中"
            control_state['last_overtake_result'] = 'condition'
            return False

        if self.check_op_control_cooldown(control_state):
            control_state['last_overtake_result'] = 'condition'
            return False

        if not vehicle_data.get('IsOnroad', False):
            control_state['overtakeReason'] = "OP不在线"
            control_state['last_overtake_result'] = 'condition'
            return False

        if not vehicle_data.get('engaged', False):
            control_state['overtakeReason'] = "巡航未激活"
            control_state['last_overtake_result'] = 'condition'
            return False

        if (control_state.get('overtakingCompleted', False) and 
            not control_state.get('isOvertaking', False) and
            not control_state.get('lane_change_in_progress', False)):
            control_state['overtakingCompleted'] = False
            control_state['follow_start_time'] = None
            control_state['is_following_slow_vehicle'] = False
            control_state['max_follow_time_reached'] = False

        if self.check_early_overtake_conditions(vehicle_data, control_state):
            control_state['overtakeReason'] = "前车速度过慢，触发远距离超车"
            control_state['condition_stability_timer'] = 0
            control_state['condition_met_count'] = 0
            control_state['quick_trigger_enabled'] = False
            return True

        if not self.check_lead_vehicle_min_speed(vehicle_data, control_state):
            return False

        if not self.has_vehicle_in_direction(vehicle_data, ""):
            control_state['overtakeReason'] = "前方无车辆"
            control_state['last_overtake_result'] = 'condition'
            control_state['condition_stability_timer'] = 0
            control_state['condition_met_count'] = 0
            return False

        speed_ratio = vehicle_data['v_ego_kph'] / vehicle_data['v_cruise_kph'] if vehicle_data['v_cruise_kph'] > 0 else 1.0
        if speed_ratio >= 0.99:
            current_speed = int(round(vehicle_data['v_ego_kph']))
            cruise_speed = int(round(vehicle_data['v_cruise_kph']))
            control_state['overtakeReason'] = f"当前速度{current_speed}km/h已达到巡航速度{cruise_speed}km/h的{speed_ratio*100:.0f}%"
            control_state['last_overtake_result'] = 'condition'
            control_state['condition_stability_timer'] = 0
            control_state['condition_met_count'] = 0
            return False

        if road_type == 'highway':
            highway_min_speed = self._get_config('HIGHWAY_MIN_SPEED', 60.0)
            if vehicle_data['v_ego_kph'] < highway_min_speed:
                current_speed = int(round(vehicle_data['v_ego_kph']))
                min_speed = int(round(highway_min_speed))
                control_state['overtakeReason'] = f"高速公路车速{current_speed}km/h低于最低超车速度{min_speed}km/h"
                control_state['last_overtake_result'] = 'condition'
                return False
        else:
            normal_min_speed = self._get_config('NORMAL_ROAD_MIN_SPEED', 30.0)
            if vehicle_data['v_ego_kph'] < normal_min_speed:
                current_speed = int(round(vehicle_data['v_ego_kph']))
                min_speed = int(round(normal_min_speed))
                control_state['overtakeReason'] = f"普通公路车速{current_speed}km/h低于最低超车速度{min_speed}km/h"
                control_state['last_overtake_result'] = 'condition'
                return False

        current_cooldown = self.calculate_dynamic_cooldown(control_state)
        if now - control_state.get('lastOvertakeTime', 0) < current_cooldown and not control_state.get('quick_trigger_enabled', False):
            remaining = (current_cooldown - (now - control_state['lastOvertakeTime'])) / 1000
            reason_suffix = ""
            if control_state.get('last_overtake_result') == 'success':
                reason_suffix = "（成功超车后冷却）"
            elif control_state.get('last_overtake_result') == 'failed':
                reason_suffix = "（超车失败后快速重试）"
            elif control_state.get('last_overtake_result') == 'condition':
                reason_suffix = "（条件不满足冷却）"

            control_state['overtakeReason'] = f"超车冷却中，请等待{remaining:.0f}秒{reason_suffix}"
            return False

        current_conditions = self.get_trigger_conditions(vehicle_data, control_state)
        
        if not current_conditions:
            control_state['overtakeReason'] = "未满足任何超车触发条件"
            control_state['last_overtake_result'] = 'condition'
            return False
        
        conditions_stable = self.check_condition_stability(current_conditions, control_state)
        
        if conditions_stable or control_state.get('quick_trigger_enabled', False):
            if control_state.get('quick_trigger_enabled', False):
                quick_trigger_timeout = self._get_config('QUICK_TRIGGER_TIMEOUT', 3000)
                if now - control_state.get('quick_trigger_start', now) > quick_trigger_timeout:
                    control_state['quick_trigger_enabled'] = False
            
            if current_conditions:
                trigger_reason = ", ".join(current_conditions)
                control_state['overtakeReason'] = f"触发超车: {trigger_reason} | 条件稳定"
                return True
        else:
            if current_conditions:
                stable_duration = now - control_state.get('condition_stability_timer', 0) if control_state.get('condition_stability_timer', 0) > 0 else 0
                remaining_time = max(0, self._get_config('CONDITION_STABLE_DURATION', 2000) - stable_duration) / 1000
                condition_count = control_state.get('condition_met_count', 0)
                
                if remaining_time > 0:
                    control_state['overtakeReason'] = f"条件满足，等待稳定({remaining_time:.0f}s)"
                else:
                    control_state['overtakeReason'] = f"条件满足，等待稳定"
            else:
                control_state['overtakeReason'] = "未满足任何超车触发条件"
            
            control_state['last_overtake_result'] = 'condition'
            return False

        control_state['overtakeReason'] = "未满足任何超车触发条件"
        control_state['last_overtake_result'] = 'condition'
        return False
    
    def calculate_dynamic_cooldown(self, control_state):
        base_cooldown = self._get_config('OVERTAKE_COOLDOWN_BASE', 5000)

        last_result = control_state.get('last_overtake_result')
        if last_result == 'success':
            cooldown = self._get_config('OVERTAKE_COOLDOWN_SUCCESS', 8000)
            control_state['consecutive_failures'] = 0
            self.consecutive_overtake_count = 0
        elif last_result == 'failed':
            cooldown = self._get_config('OVERTAKE_COOLDOWN_FAILED', 3000)
            control_state['consecutive_failures'] = control_state.get('consecutive_failures', 0) + 1
        elif last_result == 'condition':
            cooldown = self._get_config('OVERTAKE_COOLDOWN_CONDITION', 4000)
            control_state['consecutive_failures'] = control_state.get('consecutive_failures', 0) + 1
        else:
            cooldown = base_cooldown

        if control_state.get('consecutive_failures', 0) > 3:
            penalty = min(10000, control_state['consecutive_failures'] * 2000)
            cooldown += penalty

        road_type = self.config.get('road_type', 'normal')
        if road_type == 'highway':
            cooldown = max(5000, cooldown * 0.8)
        else:
            cooldown = cooldown * 1.2

        control_state['dynamic_cooldown'] = cooldown
        return cooldown
    
    def is_target_lane_reliably_clear(self, direction, vehicle_data=None):
        if not self.tracking_center:
            return False
        
        direction_map = {
            "左": "left",
            "右": "right"
        }
        
        track_direction = direction_map.get(direction)
        if not track_direction:
            return False
        
        return self.tracking_center.is_lane_reliably_clear(track_direction)
    
    def evaluate_overtake_effectiveness(self, vehicle_data, direction):
        if not self.tracking_center:
            return 0, ["无法获取跟踪中心数据"]
        
        direction_map = {
            "左": "left",
            "右": "right"
        }
        
        track_direction = direction_map.get(direction)
        if not track_direction:
            return 0, ["未知方向"]
        
        state = self.tracking_center.get_vehicle_state(track_direction)
        if not state:
            return 0, ["无法获取车辆状态"]
        
        effectiveness = 100
        reasons = []
        
        distance_limit = self._get_config('EFFECTIVENESS_DISTANCE_LIMIT', 50.0)
        
        if state.get('is_reliably_clear', False):
            effectiveness = 100
            reasons.append(f"{direction}侧车道可靠畅通，超车效果最佳")
            return effectiveness, reasons
        
        if not state['has_vehicle']:
            effectiveness = 95
            reasons.append("目标车道无车，超车效果很好")
        else:
            distance = state.get('distance')
            
            if distance is not None and distance > distance_limit:
                effectiveness = 90
                reasons.append(f"{direction}侧有远处车辆({distance:.0f}m > {distance_limit:.0f}m)，基本不影响通行")
                effectiveness = max(0, min(100, effectiveness))
                reasons.append(f"有效性评分: {effectiveness:.0f}/100")
                return effectiveness, reasons
            
            current_speed = vehicle_data['v_ego_kph']
            target_speed = state.get('speed')
            if target_speed is None:
                target_speed = current_speed + 10
            
            front_state = self.tracking_center.get_vehicle_state('front')
            if front_state and front_state['has_vehicle']:
                current_lead_speed = front_state.get('speed')
                if current_lead_speed is None:
                    current_lead_speed = current_speed
            else:
                current_lead_speed = current_speed
            
            if distance is not None:
                reasons.append(f"侧方车辆距离: {distance:.0f}m")
            
            speed_advantage = target_speed - current_lead_speed
            
            if speed_advantage >= 10:
                effectiveness = 90
                reasons.append(f"速度优势充足: +{speed_advantage:.1f}km/h")
            elif speed_advantage >= 5:
                effectiveness = 80
                reasons.append(f"速度优势良好: +{speed_advantage:.1f}km/h")
            elif speed_advantage >= 0:
                effectiveness = 70
                reasons.append(f"速度优势一般: +{speed_advantage:.1f}km/h")
            else:
                effectiveness = 50
                reasons.append(f"速度劣势: {speed_advantage:.1f}km/h")
            
            if distance is not None and 0 < distance < 20:
                effectiveness -= max(0, (20 - distance) * 2)
                reasons.append(f"车辆过近: {distance:.1f}m")
            
            relative_speed = state.get('relative_speed')
            if relative_speed is not None and relative_speed < -10:
                effectiveness -= abs(relative_speed) * 0.5
                reasons.append(f"车辆较慢: {relative_speed:.1f}km/h")
        
        if direction == self.last_overtake_direction:
            effectiveness -= 15
            reasons.append("与上次超车方向相同，降低优先级")
        
        road_type = self.config.get('road_type', 'normal')
        if direction == "右" and road_type == 'highway':
            effectiveness -= 5
            reasons.append("高速公路右侧车道通常较慢")
        
        effectiveness = min(100, effectiveness * 1.1)
        effectiveness = max(0, min(100, effectiveness))
        reasons.append(f"有效性评分: {effectiveness:.0f}/100")
        
        return effectiveness, reasons
    
    def is_overtake_effective(self, vehicle_data, direction):
        if not self.tracking_center:
            effectiveness, reasons = self.evaluate_overtake_effectiveness(vehicle_data, direction)
            return effectiveness >= 60, effectiveness, reasons
        
        effectiveness, reasons = self.evaluate_overtake_effectiveness(vehicle_data, direction)
        
        min_effectiveness = self._get_config('MIN_EFFECTIVENESS_THRESHOLD', 60)
        
        direction_map = {
            "左": "left",
            "右": "right"
        }
        track_direction = direction_map.get(direction)
        if track_direction and self.tracking_center.is_lane_reliably_clear(track_direction):
            min_effectiveness = 50
        
        is_effective = effectiveness >= min_effectiveness
        
        reasons.append(f"有效性阈值: {min_effectiveness}")
        
        return is_effective, effectiveness, reasons
    
    def evaluate_lane_suitability(self, vehicle_data, side):
        current_lane = self.config.get('current_lane_number', 1)
        total_lanes = self.config.get('lane_count', 3)

        if side == "left":
            target_lane = current_lane - 1
            track_direction = "left"
            direction_text = "左"
        else:
            target_lane = current_lane + 1
            track_direction = "right"
            direction_text = "右"

        if target_lane == total_lanes:
            is_emergency, reason = self._check_emergency_lane_simple(target_lane, vehicle_data)
            if is_emergency:
                return 0, [reason]

        penalty_score = 0
        analysis = []

        # 检查目标车道是否为推荐车道 - 给予额外奖励分数
        if self.lane_position_system and self.lane_position_system.is_lane_recommended(target_lane):
            # 推荐车道给予额外分数奖励（在主控制器中已经加了20分，这里再加10分）
            penalty_score -= 30  # 总共给予30分奖励
            analysis.append(f"★推荐车道★: 额外+30分")

        if self.lane_position_system and self.lane_position_system.is_lane_forbidden(target_lane):
            return 0, [f"车道{target_lane}为禁止通行车道(X)"]

        if self.tracking_center and self.tracking_center.is_lane_reliably_clear(track_direction):
            suitability_score = 95
            confidence = self.tracking_center.get_clear_confidence(track_direction)
            analysis.append(f"{direction_text}侧车道可靠畅通（置信度{confidence:.0%}）")
            
            if confidence > 0.9:
                suitability_score = 100
                analysis.append("畅通置信度极高")
            
            return suitability_score, analysis
        
        is_distance_safe, distance_reason = self.check_side_vs_front_distance_comprehensive(
            direction_text, vehicle_data
        )
        if not is_distance_safe:
            penalty_score += 50
            analysis.append(f"距离对比不安全: {distance_reason}")
        
        lane_width = vehicle_data.get(f'{side[0]}_lane_width', 3.0)
        min_lane_width = self._get_config('MIN_LANE_WIDTH', 2.3)
        safe_lane_width = self._get_config('SAFE_LANE_WIDTH', 3.0)
        
        if lane_width < min_lane_width:
            penalty_score += 80
            analysis.append(f"车道过窄: {lane_width:.1f}m")
        elif lane_width < safe_lane_width:
            penalty_score += (safe_lane_width - lane_width) * 0.3 * 10
            analysis.append(f"车道略窄: {lane_width:.1f}m")
        else:
            analysis.append(f"车道宽度正常: {lane_width:.1f}m")

        road_type = self.config.get('road_type', 'normal')
        if road_type == 'highway':
            if side == "left" and target_lane == 1:
                analysis.append("快车道 - 超车优先")
                penalty_score -= 5
            elif side == "right" and target_lane == total_lanes:
                analysis.append("最右侧车道通常较慢")
                penalty_score += 5

        if self.tracking_center:
            state = self.tracking_center.get_vehicle_state(track_direction)
            if state:
                if not state['has_vehicle']:
                    penalty_score -= 20
                    analysis.append("侧方无车辆，可能畅通")
                else:
                    distance = state.get('distance')
                    side_lead_distance_min = self._get_config('SIDE_LEAD_DISTANCE_MIN', 20.0)
                    if distance is not None and distance > 50:
                        analysis.append(f"侧方有远处车辆({distance:.0f}m)，影响较小")
                    else:
                        if distance is not None and distance > 0:
                            if distance < side_lead_distance_min:
                                penalty_score += (side_lead_distance_min - distance) * 0.4
                                analysis.append(f"侧前车过近: {distance:.1f}m")
                            else:
                                distance_advantage = distance - side_lead_distance_min
                                penalty_score -= min(distance_advantage * 0.5, 20)
                                analysis.append(f"侧前车安全距离: {distance:.1f}m")
                        
                        relative_speed = state.get('relative_speed')
                        min_speed_advantage = self._get_config('MIN_SPEED_ADVANTAGE', 5.0)
                        if relative_speed is not None:
                            if relative_speed < -min_speed_advantage:
                                penalty_score += abs(relative_speed) * 0.3
                                analysis.append(f"侧前车较慢: {relative_speed:.1f}km/h")
                            elif relative_speed > min_speed_advantage:
                                speed_advantage = min(relative_speed * 0.8, 25)
                                penalty_score -= speed_advantage
                                analysis.append(f"侧前车较快: +{relative_speed:.1f}km/h")
                            else:
                                analysis.append(f"侧前车速度相当: {relative_speed:.1f}km/h")
        
        penalty_score = max(0, penalty_score)
        suitability_score = max(0, 100 - penalty_score)
        
        suitability_score = min(100, suitability_score * 1.1)
        analysis.insert(0, f"适合度评分: {suitability_score:.0f}/100")
        
        return suitability_score, analysis
    
    def get_current_lane_penalty(self, vehicle_data):
        penalty = 0
        analysis = []

        if self.tracking_center:
            state = self.tracking_center.get_vehicle_state('front')
            if state and state['has_vehicle']:
                relative_speed = state.get('relative_speed')
                min_speed_advantage = self._get_config('MIN_SPEED_ADVANTAGE', 5.0)
                if relative_speed is not None and relative_speed < -min_speed_advantage:
                    speed_penalty = abs(relative_speed) * 0.2
                    penalty += speed_penalty
                    analysis.append(f"当前前车较慢: {relative_speed}km/h → +{speed_penalty:.0f}惩罚")
                
                distance = state.get('distance')
                if distance is not None and distance > 0:
                    time_gap = self.calculate_time_gap({'drel': distance, 'v_ego_kph': vehicle_data.get('v_ego_kph', 60)})
                    follow_time_gap_threshold = self._get_config('FOLLOW_TIME_GAP_THRESHOLD', 3.0)
                    if time_gap > 0 and time_gap < follow_time_gap_threshold:
                        distance_penalty = (follow_time_gap_threshold - time_gap) * 10
                        penalty += distance_penalty
                        analysis.append(f"跟车时间较近: {time_gap}秒 → +{distance_penalty:.0f}惩罚")
        
        return penalty, analysis
    
    def get_available_overtake_directions(self, vehicle_data, control_state=None):
        current_lane = int(self.config.get('current_lane_number', 1))
        total_lanes = int(self.config.get('lane_count', 3))
        
        available_directions = []
        
        if current_lane < 1 or current_lane > total_lanes:
            return available_directions
        
        if total_lanes < 2:
            return available_directions
        
        if current_lane > 1:
            target_left_lane = current_lane - 1
            
            can_left = True
            left_check_reason = ""
            
            if self.config.get('lane_count_mode', 'auto') == 'manual':
                can_left, left_check_reason = self.can_turn_left_in_manual_mode(vehicle_data, self.config)
                
                if control_state is not None:
                    control_state['manual_left_check'] = {
                        'allowed': can_left,
                        'reason': left_check_reason
                    }
            
            if can_left:
                # 检查目标车道是否为禁止车道
                if self.lane_position_system and self.lane_position_system.is_lane_forbidden(target_left_lane):
                    can_left = False
                    left_check_reason = f"车道{target_left_lane}为禁止通行车道(X)"
                    if control_state is not None:
                        control_state['left_change_blocked_reason'] = left_check_reason
                
                if can_left and target_left_lane == total_lanes:
                    is_emergency, reason = self._check_emergency_lane_simple(target_left_lane, vehicle_data)
                    if is_emergency:
                        if control_state is not None:
                            control_state['left_change_blocked_reason'] = reason
                        can_left = False
                
                if can_left:
                    left_lane_width = vehicle_data.get('l_lane_width', 3.0)
                    min_width = self._get_config('MIN_LANE_WIDTH', 2.3)
                    
                    if left_lane_width >= min_width:
                        # 检查目标车道是否为推荐车道，如果是则优先添加
                        is_recommended = False
                        if self.lane_position_system:
                            is_recommended = self.lane_position_system.is_lane_recommended(target_left_lane)
                        
                        if is_recommended:
                            # 推荐车道添加到列表前面，优先考虑
                            available_directions.insert(0, "左")
                            if control_state is not None:
                                control_state['left_change_recommended'] = True
                        else:
                            available_directions.append("左")
            else:
                if control_state is not None:
                    control_state['left_change_blocked_reason'] = left_check_reason
        
        if current_lane < total_lanes:
            target_right_lane = current_lane + 1
            
            # 检查目标车道是否为禁止车道
            if self.lane_position_system and self.lane_position_system.is_lane_forbidden(target_right_lane):
                if control_state is not None:
                    control_state['right_change_blocked_reason'] = f"车道{target_right_lane}为禁止通行车道(X)"
            else:
                is_emergency = False
                if target_right_lane == total_lanes:
                    is_emergency, _ = self._check_emergency_lane_simple(target_right_lane, vehicle_data)
                
                if not is_emergency:
                    can_right = True
                    right_check_reason = ""
                    
                    if self.lane_position_system:
                        if self.lane_position_system.is_lane_forbidden(target_right_lane):
                            can_right = False
                            right_check_reason = f"车道{target_right_lane}为禁止通行车道(X)"
                            if control_state is not None:
                                control_state['right_change_blocked_reason'] = right_check_reason
                    
                    if can_right:
                        right_lane_width = vehicle_data.get('r_lane_width', 3.0)
                        min_width = self._get_config('MIN_LANE_WIDTH', 2.3)
                        
                        if right_lane_width >= min_width:
                            # 检查目标车道是否为推荐车道，如果是则优先添加
                            is_recommended = False
                            if self.lane_position_system:
                                is_recommended = self.lane_position_system.is_lane_recommended(target_right_lane)
                            
                            if is_recommended:
                                # 推荐车道添加到列表前面，优先考虑
                                available_directions.insert(0, "右")
                                if control_state is not None:
                                    control_state['right_change_recommended'] = True
                            else:
                                available_directions.append("右")
        
        # 如果已经有推荐车道，确保它们在最前面
        recommended_directions = []
        non_recommended_directions = []
        
        for direction in available_directions:
            if direction == "左":
                target_lane = current_lane - 1
            else:
                target_lane = current_lane + 1
            
            if self.lane_position_system and self.lane_position_system.is_lane_recommended(target_lane):
                recommended_directions.append(direction)
            else:
                non_recommended_directions.append(direction)
        
        # 重新组合列表，推荐车道在前面
        available_directions = recommended_directions + non_recommended_directions
        
        return available_directions
    
    def _check_emergency_lane_simple(self, lane_number, vehicle_data):
        total_lanes = self.config.get('lane_count', 3)
        
        if lane_number != total_lanes:
            return False, f"不是最右侧车道（车道{lane_number}/{total_lanes}）"
        
        roadcate = self.config.get('roadcate', 0)
        if roadcate != 1:
            return False, f"非应急车道高速公路（roadcate={roadcate}）"
        
        return True, f"应急车道高速公路，车道{total_lanes}为应急车道"
    
    def record_overtake_completion(self, direction):
        self.last_overtake_direction = direction
        self.last_overtake_completion_time = time.time() * 1000
        self.consecutive_overtake_count += 1
    
    def check_edge_validation_for_turn(self, direction, vehicle_data, config):
        if direction not in ["左", "右"]:
            return False, "未知方向"
        
        if self.lane_position_system:
            eng_direction = "left" if direction == "左" else "right"
            is_valid, reason, confidence = self.lane_position_system.validate_lane_change(
                eng_direction, vehicle_data, config
            )
            return is_valid, reason
        
        if direction == "左":
            edge_dist = vehicle_data.get('l_edge_dist')
            lane_width = vehicle_data.get('l_lane_width', 3.0)
        else:
            edge_dist = vehicle_data.get('r_edge_dist')
            lane_width = vehicle_data.get('r_lane_width', 3.0)
        
        if edge_dist is None:
            return False, "无边缘距离数据"
        
        EDGE_VALIDATION_RATIO = 0.7
        expected_min_distance = lane_width * EDGE_VALIDATION_RATIO
        
        if edge_dist > expected_min_distance:
            available_lanes = edge_dist / lane_width
            return True, f"{direction}侧边缘{edge_dist:.1f}m > 车道宽度{lane_width:.1f}m*{EDGE_VALIDATION_RATIO}"
        else:
            return False, f"{direction}侧边缘{edge_dist:.1f}m <= 车道宽度{lane_width:.1f}m*{EDGE_VALIDATION_RATIO}"
    
    def update_decision_stats(self, decision_result, decision_time):
        self.decision_stats['total_decisions'] += 1
        if decision_result:
            self.decision_stats['positive_decisions'] += 1
        else:
            self.decision_stats['negative_decisions'] += 1
        
        old_avg = self.decision_stats['avg_decision_time']
        count = self.decision_stats['total_decisions']
        self.decision_stats['avg_decision_time'] = (
            old_avg * (count - 1) + decision_time
        ) / count
    
    def get_decision_stats(self):
        return self.decision_stats.copy()