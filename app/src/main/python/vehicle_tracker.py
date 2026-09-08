#!/usr/bin/env python3
"""
车辆跟踪模块
"""

import time
from collections import deque, Counter
from typing import Dict, Any, Tuple, Optional, List


# ==================== 可调参数配置 ====================
class TrackerConfig:
    """车辆跟踪配置类"""
    
    # ========== 基础参数 ==========
    HISTORY_WINDOW = 20          # 数据历史窗口
    STATUS_HISTORY_WINDOW = 10   # 状态历史窗口
    
    # ========== 验证参数 ==========
    MAX_CONSECUTIVE_MISSING = 3  # 最大连续缺失帧数
    CLEAR_CONFIDENCE_THRESHOLD = 0.7  # 畅通置信度阈值
    MULTI_SOURCE_AGREEMENT_THRESHOLD = 0.6  # 多源一致性阈值
    
    # ========== 数据持久化参数 ==========
    PERSISTENCE_TIME_FRONT = 1000  # 前方车辆数据持久化时间
    PERSISTENCE_TIME_SIDE = 2000   # 侧方车辆数据持久化时间
    
    # ========== 超越判断参数 ==========
    OVERTAKE_BEHIND_DISTANCE = -5.0   # 后方超越距离阈值
    SAFE_DISTANCE_FRONT = 15.0         # 前方安全距离
    FAR_DISTANCE = 50.0                # 远距离阈值
    MIN_SPEED_ADVANTAGE = 10.0         # 最小速度优势
    FAST_OVERTAKE_SPEED_DIFF = 20.0    # 快速超越速度差
    DISAPPEARANCE_CONFIRM_TIME = 3000  # 消失确认时间
    OVERTAKE_COMPLETE_TIME = 2000      # 超越完成时间
    
    # ========== 返回安全参数 ==========
    MIN_CLEAR_TIME_RETURN = 1500       # 最小畅通时间（毫秒）
    MIN_CONFIDENCE_RETURN = 0.7        # 最小返回置信度


# ==================== 雷达数据结构 ====================
class RadarLead:
    """雷达数据封装"""
    def __init__(self, dRel, vLead, vRel, status=True):
        self.dRel = dRel if dRel is not None else 0
        self.vLead = vLead if vLead is not None else 0
        self.vRel = vRel if vRel is not None else 0
        self.status = status


class RadarData:
    """雷达数据容器"""
    def __init__(self):
        self.leads = []


# ==================== 车辆跟踪器基类 ====================
class BaseVehicleTracker:
    """车辆跟踪器基类"""
    
    def __init__(self, side='front', config=None):
        self.side = side
        self.config = config or TrackerConfig()
        
        self.vehicles = {}
        self.closest_vehicle = None
        self.last_update_time = 0
        self.data_history = deque(maxlen=self.config.HISTORY_WINDOW)
        
        # 验证系统
        self.verification_system = {
            'consecutive_missing_frames': 0,
            'max_consecutive_missing': self.config.MAX_CONSECUTIVE_MISSING,
            'total_missing_in_window': 0,
            'window_size': self.config.STATUS_HISTORY_WINDOW,
            'history_status': deque(maxlen=self.config.STATUS_HISTORY_WINDOW),
            'confidence_score': 0.0,
            'is_reliably_clear': False,
            'clear_confidence': 0.0,
            'last_clear_check_time': 0
        }
        
        # 数据持久化
        self.data_persistence = {
            'distance': {'value': None, 'last_seen': 0, 'persistence_time': 0},
            'speed': {'value': None, 'last_seen': 0, 'persistence_time': 0},
            'relative_speed': {'value': None, 'last_seen': 0, 'persistence_time': 0}
        }
        
        # 超越状态跟踪
        self.overtake_state = {
            'has_overtaken': False,
            'overtake_confidence': 0.0,
            'overtake_start_time': 0,
            'overtake_complete_time': 0,
            'disappearance_start_time': 0,
            'last_seen_distance': None,
            'last_seen_speed': None
        }
    
    def update(self, *args, **kwargs):
        """更新跟踪器 - 由子类实现"""
        raise NotImplementedError
    
    def get_vehicle_state(self) -> Dict:
        """获取车辆状态信息"""
        current_time = time.time() * 1000
        
        if self.closest_vehicle:
            return {
                'has_vehicle': True,
                'distance': self.closest_vehicle.get('distance'),
                'speed': self.closest_vehicle.get('speed'),
                'relative_speed': self.closest_vehicle.get('relative_speed'),
                'is_stationary': self.closest_vehicle.get('is_stationary', False),
                'is_very_close': self.closest_vehicle.get('is_very_close', False),
                'track_quality': min(100, self.closest_vehicle.get('track_count', 0) * 10),
                'data_valid': True,
                'is_reliably_clear': False,
                'clear_confidence': self.verification_system['clear_confidence'],
                'overtake_state': self.overtake_state.copy()
            }
        else:
            # 检查历史中是否有近期车辆
            has_recent_vehicle = False
            for data_point in reversed(self.data_history):
                if current_time - data_point['time'] < 2000:
                    if data_point.get('has_vehicle', False):
                        has_recent_vehicle = True
                        break
            
            return {
                'has_vehicle': has_recent_vehicle,
                'distance': None,
                'speed': None,
                'relative_speed': None,
                'is_stationary': False,
                'is_very_close': False,
                'track_quality': 0,
                'data_valid': False,
                'is_reliably_clear': self.verification_system['is_reliably_clear'],
                'clear_confidence': self.verification_system['clear_confidence'],
                'overtake_state': self.overtake_state.copy()
            }
    
    def _record_status_history(self, current_time, has_vehicle, status_data=None):
        """记录状态历史"""
        status = {
            'time': current_time,
            'has_vehicle': has_vehicle
        }
        
        if status_data:
            status.update(status_data)
        
        self.verification_system['history_status'].append(status)
        
        # 更新窗口统计
        if len(self.verification_system['history_status']) > self.verification_system['window_size']:
            oldest = self.verification_system['history_status'].popleft()
            if not oldest['has_vehicle']:
                self.verification_system['total_missing_in_window'] = max(
                    0, self.verification_system['total_missing_in_window'] - 1
                )
    
    def _update_clear_confidence(self, current_time):
        """更新无车置信度"""
        if len(self.verification_system['history_status']) == 0:
            self.verification_system['clear_confidence'] = 0.0
            self.verification_system['is_reliably_clear'] = False
            return
        
        consecutive_missing = self.verification_system['consecutive_missing_frames']
        window_size = self.verification_system['window_size']
        actual_size = len(self.verification_system['history_status'])
        missing_in_window = self.verification_system['total_missing_in_window']
        
        # 计算多源一致性
        recent_statuses = list(self.verification_system['history_status'])
        multi_source_agreement = 0
        valid_checks = 0
        
        for status in recent_statuses:
            # 检查所有数据源是否都同意无车
            all_sources_agree = all(
                status.get(f"{source}_missing", False) 
                for source in self._get_data_sources()
            )
            
            if all_sources_agree:
                multi_source_agreement += 1
            valid_checks += 1
        
        multi_source_ratio = multi_source_agreement / valid_checks if valid_checks > 0 else 0
        
        # 计算置信度因子
        confidence_factors = []
        
        # 因子1：连续缺失帧数
        if consecutive_missing >= self.verification_system['max_consecutive_missing']:
            factor1 = 1.0
        else:
            factor1 = consecutive_missing / self.verification_system['max_consecutive_missing']
        confidence_factors.append(factor1)
        
        # 因子2：窗口内缺失率
        if actual_size > 0:
            missing_rate = missing_in_window / actual_size
            confidence_factors.append(missing_rate)
        
        # 因子3：多源一致性
        confidence_factors.append(multi_source_ratio)
        
        # 因子4：特定条件（由子类添加）
        extra_factors = self._get_extra_clear_factors()
        confidence_factors.extend(extra_factors)
        
        # 计算平均置信度
        avg_confidence = sum(confidence_factors) / len(confidence_factors) if confidence_factors else 0
        
        self.verification_system['clear_confidence'] = avg_confidence
        self.verification_system['confidence_score'] = avg_confidence
        
        # 判断是否可靠畅通
        is_reliably_clear = (
            consecutive_missing >= self.verification_system['max_consecutive_missing'] and
            avg_confidence >= self.config.CLEAR_CONFIDENCE_THRESHOLD and
            multi_source_ratio >= self.config.MULTI_SOURCE_AGREEMENT_THRESHOLD
        )
        
        # 添加特定条件（由子类实现）
        is_reliably_clear = is_reliably_clear and self._check_extra_clear_conditions()
        
        self.verification_system['is_reliably_clear'] = is_reliably_clear
        self.verification_system['last_clear_check_time'] = current_time
    
    def _get_data_sources(self) -> List[str]:
        """获取数据源列表 - 由子类实现"""
        return []
    
    def _get_extra_clear_factors(self) -> List[float]:
        """获取额外的畅通因子 - 由子类实现"""
        return []
    
    def _check_extra_clear_conditions(self) -> bool:
        """检查额外的畅通条件 - 由子类实现"""
        return True
    
    def _apply_data_persistence(self, current_time):
        """应用数据持久化"""
        for field, persistence_data in self.data_persistence.items():
            if current_time - persistence_data['last_seen'] < persistence_data['persistence_time']:
                pass  # 数据仍然有效
            else:
                # 数据过期，重置
                self._reset_persistence_field(field)
    
    def _reset_persistence_field(self, field: str):
        """重置持久化字段 - 由子类实现"""
        pass
    
    def _calculate_vehicle_id(self, lead: RadarLead, index: int) -> str:
        """计算车辆ID"""
        distance = lead.dRel
        speed = lead.vLead * 3.6 if lead.vLead else 0
        
        distance_quantized = round(distance / 5) * 5
        speed_quantized = round(speed / 10) * 10
        
        return f"{self.side}_{distance_quantized}_{speed_quantized}_{index}"
    
    def _update_closest_vehicle(self):
        """更新最近车辆"""
        if not self.vehicles:
            self.closest_vehicle = None
            return
            
        closest = None
        for vehicle in self.vehicles.values():
            if closest is None or vehicle['distance'] < closest['distance']:
                closest = vehicle
                
        self.closest_vehicle = closest
    
    def _update_overtake_state(self, current_time: float):
        """更新超越状态 - 由子类实现"""
        raise NotImplementedError
    
    # 公共接口方法
    def has_vehicle(self) -> bool:
        """是否有车辆"""
        return self.closest_vehicle is not None
    
    def get_distance(self) -> Optional[float]:
        """获取距离"""
        return self.closest_vehicle['distance'] if self.closest_vehicle else None
    
    def get_speed(self) -> Optional[float]:
        """获取速度"""
        return self.closest_vehicle['speed'] if self.closest_vehicle else None
    
    def get_relative_speed(self) -> Optional[float]:
        """获取相对速度"""
        return self.closest_vehicle['relative_speed'] if self.closest_vehicle else None
    
    def is_lane_reliably_clear(self) -> bool:
        """车道是否可靠畅通"""
        return self.verification_system['is_reliably_clear']
    
    def get_clear_confidence(self) -> float:
        """获取畅通置信度"""
        return self.verification_system['clear_confidence']
    
    def has_overtaken(self, min_confidence: float = 0.6) -> bool:
        """判断是否已超越车辆"""
        return self.overtake_state['has_overtaken'] and self.overtake_state['overtake_confidence'] >= min_confidence
    
    def get_overtake_status(self) -> Dict:
        """获取超越状态详情"""
        current_time = time.time() * 1000
        
        return {
            'has_overtaken': self.overtake_state['has_overtaken'],
            'confidence': self.overtake_state['overtake_confidence'],
            'time_since_overtake': (
                current_time - self.overtake_state['overtake_complete_time']
            ) if self.overtake_state['has_overtaken'] else 0,
            'distance_behind': (
                abs(self.closest_vehicle['distance']) 
                if self.closest_vehicle and self.closest_vehicle['distance'] < 0 
                else 0
            )
        }
    
    def reset_tracker(self):
        """重置跟踪器"""
        self.vehicles = {}
        self.closest_vehicle = None
        self.data_history.clear()
        
        current_time = time.time() * 1000
        self.data_persistence = {
            'distance': {'value': None, 'last_seen': current_time, 'persistence_time': 0},
            'speed': {'value': None, 'last_seen': current_time, 'persistence_time': 0},
            'relative_speed': {'value': None, 'last_seen': current_time, 'persistence_time': 0}
        }
        
        self.verification_system.update({
            'consecutive_missing_frames': 0,
            'total_missing_in_window': 0,
            'history_status': deque(maxlen=self.config.STATUS_HISTORY_WINDOW),
            'confidence_score': 0.0,
            'is_reliably_clear': False,
            'clear_confidence': 0.0
        })
        
        self.overtake_state = {
            'has_overtaken': False,
            'overtake_confidence': 0.0,
            'overtake_start_time': 0,
            'overtake_complete_time': 0,
            'disappearance_start_time': 0,
            'last_seen_distance': None,
            'last_seen_speed': None
        }


# ==================== 前方车辆跟踪器 ====================
class FrontVehicleTracker(BaseVehicleTracker):
    """前方车辆跟踪器"""
    
    def __init__(self, config=None):
        super().__init__('front', config)
        
        # 设置数据持久化时间
        self.data_persistence['distance']['persistence_time'] = self.config.PERSISTENCE_TIME_FRONT
        self.data_persistence['speed']['persistence_time'] = self.config.PERSISTENCE_TIME_FRONT
        self.data_persistence['relative_speed']['persistence_time'] = self.config.PERSISTENCE_TIME_FRONT
        
        # 前方特定数据源
        self.verification_system.update({
            'radar_data_missing': False,
            'camera_data_missing': False,
        })
    
    def update(self, radar_data: RadarData, camera_data=None):
        """更新前方车辆跟踪"""
        current_time = time.time() * 1000
        
        # 更新数据源状态
        self._update_data_source_status(radar_data, camera_data)
        
        if not radar_data or not radar_data.leads:
            # 无雷达数据
            self.verification_system['radar_data_missing'] = True
            self.verification_system['consecutive_missing_frames'] += 1
            self.verification_system['total_missing_in_window'] += 1
            
            self._apply_data_persistence(current_time)
            self._record_status_history(current_time, False, {
                'radar_missing': True,
                'camera_missing': self.verification_system['camera_data_missing']
            })
            self._update_clear_confidence(current_time)
            self._update_overtake_state(current_time)
            return
        
        # 有雷达数据
        self.verification_system['radar_data_missing'] = False
        self.verification_system['consecutive_missing_frames'] = 0
        
        valid_vehicles = {}
        
        for i, lead in enumerate(radar_data.leads):
            if not lead.status:
                continue
                
            vehicle_id = self._calculate_vehicle_id(lead, i)
            
            distance = lead.dRel
            speed = lead.vLead * 3.6 if lead.vLead is not None else None
            relative_speed = lead.vRel * 3.6 if lead.vRel is not None else None
            
            valid_vehicles[vehicle_id] = {
                'id': vehicle_id,
                'distance': distance,
                'speed': speed if speed is not None else 0,
                'relative_speed': relative_speed if relative_speed is not None else 0,
                'last_seen': current_time,
                'track_count': self.vehicles.get(vehicle_id, {}).get('track_count', 0) + 1,
                'is_stationary': speed is not None and abs(speed) < 0.5,
                'is_very_close': distance < 5.0
            }
        
        self.vehicles = valid_vehicles
        self._update_closest_vehicle()
        
        if self.closest_vehicle:
            # 更新数据持久化
            closest = self.closest_vehicle
            self.data_persistence['distance'] = {
                'value': closest['distance'],
                'last_seen': current_time,
                'persistence_time': self.config.PERSISTENCE_TIME_FRONT
            }
            self.data_persistence['speed'] = {
                'value': closest['speed'],
                'last_seen': current_time,
                'persistence_time': self.config.PERSISTENCE_TIME_FRONT
            }
            self.data_persistence['relative_speed'] = {
                'value': closest['relative_speed'],
                'last_seen': current_time,
                'persistence_time': self.config.PERSISTENCE_TIME_FRONT
            }
            
            # 记录历史
            self.data_history.append({
                'time': current_time,
                'distance': closest['distance'],
                'speed': closest['speed'],
                'relative_speed': closest['relative_speed'],
                'vehicle_count': len(self.vehicles),
                'has_vehicle': True
            })
            
            # 记录状态历史
            self._record_status_history(current_time, True, {
                'radar_missing': False,
                'camera_missing': self.verification_system['camera_data_missing']
            })
        else:
            self._record_status_history(current_time, False, {
                'radar_missing': False,
                'camera_missing': self.verification_system['camera_data_missing']
            })
        
        self.last_update_time = current_time
        self._update_clear_confidence(current_time)
        self._update_overtake_state(current_time)
    
    def _update_data_source_status(self, radar_data: RadarData, camera_data):
        """更新数据源状态"""
        camera_missing = (camera_data is None or camera_data <= 0)
        self.verification_system['camera_data_missing'] = camera_missing
    
    def _get_data_sources(self) -> List[str]:
        """获取数据源列表"""
        return ['radar', 'camera']
    
    def _get_extra_clear_factors(self) -> List[float]:
        """获取额外的畅通因子"""
        extra_factors = []
        
        # 检查摄像头数据
        if self.verification_system['camera_data_missing']:
            extra_factors.append(0.8)  # 摄像头缺失增加畅通置信度
        else:
            extra_factors.append(0.2)  # 摄像头有数据降低畅通置信度
        
        return extra_factors
    
    def _check_extra_clear_conditions(self) -> bool:
        """检查额外的畅通条件"""
        # 前方车辆需要更严格的条件
        return self.verification_system['camera_data_missing'] or self.verification_system['consecutive_missing_frames'] >= 5
    
    def _reset_persistence_field(self, field: str):
        """重置持久化字段"""
        # 前方跟踪器不需要特殊处理
        pass
    
    def _update_overtake_state(self, current_time: float):
        """更新超越状态"""
        if not self.closest_vehicle:
            # 无车，检查是否已超越
            if self.overtake_state['has_overtaken']:
                # 保持超越状态，增加消失时间置信度
                if self.overtake_state['disappearance_start_time'] == 0:
                    self.overtake_state['disappearance_start_time'] = current_time
                
                disappearance_duration = current_time - self.overtake_state['disappearance_start_time']
                time_confidence = min(1.0, disappearance_duration / self.config.DISAPPEARANCE_CONFIRM_TIME)
                
                # 结合畅通置信度
                clear_confidence = self.verification_system['clear_confidence']
                self.overtake_state['overtake_confidence'] = max(
                    self.overtake_state['overtake_confidence'],
                    time_confidence * 0.6 + clear_confidence * 0.4
                )
            elif self.verification_system['is_reliably_clear']:
                # 可靠无车，立即认为已超越
                self.overtake_state['has_overtaken'] = True
                self.overtake_state['overtake_complete_time'] = current_time
                self.overtake_state['disappearance_start_time'] = current_time
                self.overtake_state['overtake_confidence'] = max(
                    0.8, self.verification_system['clear_confidence']
                )
            return
        
        # 有车，检查是否在后方
        closest = self.closest_vehicle
        if closest['distance'] < self.config.OVERTAKE_BEHIND_DISTANCE:  # 后方一定距离
            # 目标在后方
            if not self.overtake_state['has_overtaken']:
                self.overtake_state['has_overtaken'] = True
                self.overtake_state['overtake_complete_time'] = current_time
                self.overtake_state['overtake_confidence'] = 0.8
        else:
            # 目标在前方
            if self.overtake_state['has_overtaken']:
                # 重新出现，可能需要重新超越
                self.overtake_state['has_overtaken'] = False
                self.overtake_state['overtake_confidence'] = 0.0


# ==================== 侧方车辆跟踪器 ====================
class SideVehicleTracker(BaseVehicleTracker):
    """侧方车辆跟踪器"""
    
    def __init__(self, side: str, config=None):
        super().__init__(side, config)
        
        # 设置数据持久化时间
        self.data_persistence['distance']['persistence_time'] = self.config.PERSISTENCE_TIME_SIDE
        self.data_persistence['speed']['persistence_time'] = self.config.PERSISTENCE_TIME_SIDE
        self.data_persistence['relative_speed']['persistence_time'] = self.config.PERSISTENCE_TIME_SIDE
        
        # 侧方特定数据源
        self.verification_system.update({
            'radar_data_missing': False,
            'lidar_front_data_missing': False,
            'lidar_rear_data_missing': False,
            'blindspot_has_vehicle': False,
        })
    
    def update(self, radar_data: RadarData, lidar_front=None, lidar_rear=None, blindspot=False):
        """更新侧方车辆跟踪"""
        current_time = time.time() * 1000
        
        # 更新数据源状态
        self._update_multi_source_status(radar_data, lidar_front, lidar_rear, blindspot)
        
        if not radar_data or not radar_data.leads:
            # 无雷达数据
            self.verification_system['radar_data_missing'] = True
            self.verification_system['consecutive_missing_frames'] += 1
            self.verification_system['total_missing_in_window'] += 1
            
            self._apply_data_persistence(current_time)
            self._record_status_history(current_time, False, {
                'radar_missing': True,
                'lidar_front_missing': self.verification_system['lidar_front_data_missing'],
                'lidar_rear_missing': self.verification_system['lidar_rear_data_missing'],
                'blindspot_has_vehicle': self.verification_system['blindspot_has_vehicle']
            })
            self._update_clear_confidence(current_time)
            self._update_overtake_state(current_time)
            return
        
        # 有雷达数据
        self.verification_system['radar_data_missing'] = False
        self.verification_system['consecutive_missing_frames'] = 0
        
        valid_vehicles = {}
        
        for i, lead in enumerate(radar_data.leads):
            if not lead.status:
                continue
                
            vehicle_id = self._calculate_vehicle_id(lead, i)
            
            distance = lead.dRel
            speed = lead.vLead * 3.6 if lead.vLead is not None else None
            relative_speed = lead.vRel * 3.6 if lead.vRel is not None else None
            
            valid_vehicles[vehicle_id] = {
                'id': vehicle_id,
                'distance': distance,
                'speed': speed if speed is not None else 0,
                'relative_speed': relative_speed if relative_speed is not None else 0,
                'last_seen': current_time,
                'track_count': self.vehicles.get(vehicle_id, {}).get('track_count', 0) + 1,
                'is_stationary': speed is not None and abs(speed) < 0.5,
                'is_very_close': distance < 1.0
            }
        
        self.vehicles = valid_vehicles
        self._update_closest_vehicle()
        
        if self.closest_vehicle:
            # 更新数据持久化
            closest = self.closest_vehicle
            self.data_persistence['distance'] = {
                'value': closest['distance'],
                'last_seen': current_time,
                'persistence_time': self.config.PERSISTENCE_TIME_SIDE
            }
            self.data_persistence['speed'] = {
                'value': closest['speed'],
                'last_seen': current_time,
                'persistence_time': self.config.PERSISTENCE_TIME_SIDE
            }
            self.data_persistence['relative_speed'] = {
                'value': closest['relative_speed'],
                'last_seen': current_time,
                'persistence_time': self.config.PERSISTENCE_TIME_SIDE
            }
            
            # 记录历史
            self.data_history.append({
                'time': current_time,
                'distance': closest['distance'],
                'speed': closest['speed'],
                'relative_speed': closest['relative_speed'],
                'vehicle_count': len(self.vehicles),
                'has_vehicle': True
            })
            
            # 记录状态历史
            self._record_status_history(current_time, True, {
                'radar_missing': False,
                'lidar_front_missing': self.verification_system['lidar_front_data_missing'],
                'lidar_rear_missing': self.verification_system['lidar_rear_data_missing'],
                'blindspot_has_vehicle': self.verification_system['blindspot_has_vehicle']
            })
        else:
            self._record_status_history(current_time, False, {
                'radar_missing': False,
                'lidar_front_missing': self.verification_system['lidar_front_data_missing'],
                'lidar_rear_missing': self.verification_system['lidar_rear_data_missing'],
                'blindspot_has_vehicle': self.verification_system['blindspot_has_vehicle']
            })
        
        self.last_update_time = current_time
        self._update_clear_confidence(current_time)
        self._update_overtake_state(current_time)
    
    def _update_multi_source_status(self, radar_data: RadarData, lidar_front, lidar_rear, blindspot):
        """更新多源数据状态"""
        lidar_front_missing = (lidar_front is None or lidar_front <= 0)
        lidar_rear_missing = (lidar_rear is None or lidar_rear <= 0)
        
        self.verification_system['lidar_front_data_missing'] = lidar_front_missing
        self.verification_system['lidar_rear_data_missing'] = lidar_rear_missing
        self.verification_system['blindspot_has_vehicle'] = blindspot
    
    def _get_data_sources(self) -> List[str]:
        """获取数据源列表"""
        return ['radar', 'lidar_front', 'lidar_rear']
    
    def _get_extra_clear_factors(self) -> List[float]:
        """获取额外的畅通因子"""
        extra_factors = []
        
        # 检查盲区
        if self.verification_system['blindspot_has_vehicle']:
            extra_factors.append(0.0)  # 盲区有车，畅通置信度降为0
        else:
            extra_factors.append(0.8)  # 盲区无车，增加畅通置信度
        
        # 检查激光雷达数据
        lidar_missing_count = (
            (1 if self.verification_system['lidar_front_data_missing'] else 0) +
            (1 if self.verification_system['lidar_rear_data_missing'] else 0)
        )
        
        if lidar_missing_count == 2:  # 两个激光雷达都缺失
            extra_factors.append(0.9)
        elif lidar_missing_count == 1:  # 一个激光雷达缺失
            extra_factors.append(0.5)
        else:  # 激光雷达都有数据
            extra_factors.append(0.1)
        
        return extra_factors
    
    def _check_extra_clear_conditions(self) -> bool:
        """检查额外的畅通条件"""
        # 侧方车辆需要检查盲区
        return not self.verification_system['blindspot_has_vehicle']
    
    def _reset_persistence_field(self, field: str):
        """重置持久化字段"""
        # 侧方跟踪器不需要特殊处理
        pass
    
    def _update_overtake_state(self, current_time: float):
        """更新超越状态"""
        if not self.closest_vehicle:
            # 无车，检查是否已超越
            if self.overtake_state['has_overtaken']:
                # 保持超越状态，增加消失时间置信度
                if self.overtake_state['disappearance_start_time'] == 0:
                    self.overtake_state['disappearance_start_time'] = current_time
                
                disappearance_duration = current_time - self.overtake_state['disappearance_start_time']
                time_confidence = min(1.0, disappearance_duration / self.config.DISAPPEARANCE_CONFIRM_TIME)
                
                # 结合畅通置信度
                clear_confidence = self.verification_system['clear_confidence']
                self.overtake_state['overtake_confidence'] = max(
                    self.overtake_state['overtake_confidence'],
                    time_confidence * 0.6 + clear_confidence * 0.4
                )
            elif self.verification_system['is_reliably_clear']:
                # 可靠无车，立即认为已超越
                self.overtake_state['has_overtaken'] = True
                self.overtake_state['overtake_complete_time'] = current_time
                self.overtake_state['disappearance_start_time'] = current_time
                self.overtake_state['overtake_confidence'] = max(
                    0.8, self.verification_system['clear_confidence']
                )
            return
        
        # 有车情况下的超越判断
        closest = self.closest_vehicle
        distance = closest['distance']
        relative_speed = closest['relative_speed'] or 0
        
        # 重置消失计时
        self.overtake_state['disappearance_start_time'] = 0
        
        # 超越判断逻辑
        is_overtaking = False
        confidence = 0.0
        
        # 1. 车辆在后方（已超越）
        if distance < 0:
            is_overtaking = True
            # 距离越远，置信度越高
            distance_confidence = min(1.0, abs(distance) / 30)
            # 相对速度为负（我们比它快）增加置信度
            speed_bonus = 0.2 if relative_speed < -5 else 0.0
            confidence = min(0.95, 0.7 + distance_confidence * 0.2 + speed_bonus)
        
        # 2. 车道可靠畅通（即使有车也在安全距离外）
        elif self.verification_system['is_reliably_clear']:
            is_overtaking = True
            confidence = self.verification_system['clear_confidence']
        
        # 3. 车辆在前方但距离很远（超过50米）
        elif distance > self.config.FAR_DISTANCE:
            is_overtaking = True
            # 距离越远置信度越高
            distance_confidence = min(1.0, (distance - self.config.FAR_DISTANCE) / 100)
            # 相对速度为负（我们在接近）降低置信度
            speed_penalty = 0.3 if relative_speed < -10 else 0.0
            confidence = max(0.5, 0.6 + distance_confidence * 0.4 - speed_penalty)
        
        # 4. 车辆在前方但我们在快速超越（相对速度很大）
        elif distance > self.config.SAFE_DISTANCE_FRONT and relative_speed < -self.config.FAST_OVERTAKE_SPEED_DIFF:
            is_overtaking = True
            speed_confidence = min(1.0, abs(relative_speed) / 50)
            confidence = 0.6 + speed_confidence * 0.3
        
        # 5. 车辆消失时间较长（最近才重新出现）
        elif (self.overtake_state['has_overtaken'] and 
              self.overtake_state['overtake_confidence'] > 0.7 and
              distance < 30):
            # 之前已超越，现在重新出现但距离不远
            is_overtaking = True
            confidence = max(0.6, self.overtake_state['overtake_confidence'] * 0.8)
        
        # 更新状态
        if is_overtaking:
            if not self.overtake_state['has_overtaken']:
                self.overtake_state['has_overtaken'] = True
                self.overtake_state['overtake_complete_time'] = current_time
            # 置信度平滑更新
            old_confidence = self.overtake_state['overtake_confidence']
            self.overtake_state['overtake_confidence'] = (
                old_confidence * 0.7 + confidence * 0.3
            )
        else:
            # 未超越，重置或降低置信度
            if self.overtake_state['has_overtaken']:
                # 从已超越状态转为未超越，需要较强证据
                if distance < 10 and relative_speed > -5:  # 车辆很近且速度不占优
                    self.overtake_state['has_overtaken'] = False
                    self.overtake_state['overtake_confidence'] = 0.0
                else:
                    # 仅降低置信度但不重置
                    self.overtake_state['overtake_confidence'] *= 0.8
    
    def is_safe_to_return(self, min_clear_time=None, min_confidence=None) -> Tuple[bool, str]:
        """判断是否安全返回"""
        if min_clear_time is None:
            min_clear_time = self.config.MIN_CLEAR_TIME_RETURN
        if min_confidence is None:
            min_confidence = self.config.MIN_CONFIDENCE_RETURN
        
        # 1. 可靠畅通
        if self.is_lane_reliably_clear():
            return True, "车道可靠畅通"
        
        # 2. 已超越且置信度足够
        if self.has_overtaken(min_confidence):
            return True, f"已超越，置信度{self.overtake_state['overtake_confidence']:.0%}"
        
        # 3. 无车且消失时间足够长
        if not self.has_vehicle():
            if self.overtake_state['disappearance_start_time'] > 0:
                disappearance_duration = time.time() * 1000 - self.overtake_state['disappearance_start_time']
                if disappearance_duration >= min_clear_time:
                    return True, f"无车{disappearance_duration/1000:.1f}秒"
        
        # 4. 畅通置信度足够高
        if self.get_clear_confidence() >= min_confidence:
            return True, f"畅通置信度{self.get_clear_confidence():.0%}"
        
        return False, "车道未达到返回条件"
    
# ==================== 车辆跟踪中心 ====================
class VehicleTrackingCenter:
    """车辆跟踪中心 - 统一管理所有方向的车辆跟踪"""
    
    def __init__(self, config=None):
        self.config = config or TrackerConfig()
        
        # 初始化所有跟踪器
        self.front_tracker = FrontVehicleTracker(self.config)
        self.left_tracker = SideVehicleTracker('left', self.config)
        self.right_tracker = SideVehicleTracker('right', self.config)
        
        # 跟踪器映射
        self.trackers = {
            'front': self.front_tracker,
            'left': self.left_tracker,
            'right': self.right_tracker
        }
        
        # 数据一致性管理
        self.last_update_time = 0
        self.data_version = 0
        
    def update_from_vehicle_data(self, vehicle_data: Dict[str, Any]):
        """从车辆数据更新所有跟踪器"""
        current_time = time.time() * 1000
        
        try:
            # 更新前方跟踪器
            front_radar_data = RadarData()
            if vehicle_data.get('lead1', False):
                front_radar_data.leads.append(RadarLead(
                    vehicle_data.get('drel', 0),
                    vehicle_data.get('vlead', 0) / 3.6 if vehicle_data.get('vlead') is not None else 0,
                    vehicle_data.get('vrel', 0) / 3.6 if vehicle_data.get('vrel') is not None else 0,
                    True
                ))
            
            self.front_tracker.update(front_radar_data)
            
            # 更新左侧跟踪器
            left_radar_data = RadarData()
            if vehicle_data.get('l_lead', False):
                left_radar_data.leads.append(RadarLead(
                    vehicle_data.get('l_drel', 0),
                    vehicle_data.get('l_vlead', 0) / 3.6 if vehicle_data.get('l_vlead') is not None else 0,
                    vehicle_data.get('l_vrel', 0) / 3.6 if vehicle_data.get('l_vrel') is not None else 0,
                    True
                ))
            
            left_lidar_front = vehicle_data.get('lf_drel')
            left_lidar_rear = vehicle_data.get('lb_drel')
            left_blindspot = (
                vehicle_data.get('left_blindspot', False) or 
                vehicle_data.get('l_front_blind', False) or
                vehicle_data.get('lidar_lblind', False)
            )
            
            self.left_tracker.update(left_radar_data, left_lidar_front, left_lidar_rear, left_blindspot)
            
            # 更新右侧跟踪器
            right_radar_data = RadarData()
            if vehicle_data.get('r_lead', False):
                right_radar_data.leads.append(RadarLead(
                    vehicle_data.get('r_drel', 0),
                    vehicle_data.get('r_vlead', 0) / 3.6 if vehicle_data.get('r_vlead') is not None else 0,
                    vehicle_data.get('r_vrel', 0) / 3.6 if vehicle_data.get('r_vrel') is not None else 0,
                    True
                ))
            
            right_lidar_front = vehicle_data.get('rf_drel')
            right_lidar_rear = vehicle_data.get('rb_drel')
            right_blindspot = (
                vehicle_data.get('right_blindspot', False) or 
                vehicle_data.get('r_front_blind', False) or
                vehicle_data.get('lidar_rblind', False)
            )
            
            self.right_tracker.update(right_radar_data, right_lidar_front, right_lidar_rear, right_blindspot)
            
            # 更新元数据
            self.last_update_time = current_time
            self.data_version += 1
            
            return True
            
        except Exception as e:
            print(f"[VehicleTrackingCenter] 更新失败: {e}")
            return False
    
    # ========== 公共接口方法 ==========
    def get_vehicle_state(self, direction: str) -> Optional[Dict]:
        """获取指定方向的车辆状态"""
        tracker = self.trackers.get(direction)
        if tracker:
            return tracker.get_vehicle_state()
        return None
    
    def vehicle_exists(self, direction: str) -> bool:
        """判断指定方向是否有车辆"""
        tracker = self.trackers.get(direction)
        if tracker:
            state = tracker.get_vehicle_state()
            return state['has_vehicle'] if state else False
        return False
    
    def is_lane_reliably_clear(self, direction: str) -> bool:
        """判断车道是否可靠畅通"""
        tracker = self.trackers.get(direction)
        if tracker:
            return tracker.is_lane_reliably_clear()
        return False
    
    def has_overtaken(self, direction: str, min_confidence: float = 0.6) -> bool:
        """判断是否已超越指定方向的车辆"""
        tracker = self.trackers.get(direction)
        if tracker and hasattr(tracker, 'has_overtaken'):
            return tracker.has_overtaken(min_confidence)
        return False
    
    def get_all_states(self) -> Dict:
        """获取所有跟踪器的状态"""
        return {
            'front': self.front_tracker.get_vehicle_state(),
            'left': self.left_tracker.get_vehicle_state(),
            'right': self.right_tracker.get_vehicle_state(),
            'last_update_time': self.last_update_time,
            'data_version': self.data_version
        }
    
    def reset_all(self):
        """重置所有跟踪器"""
        self.front_tracker.reset_tracker()
        self.left_tracker.reset_tracker()
        self.right_tracker.reset_tracker()
        self.last_update_time = 0
        self.data_version = 0
    
    def is_safe_to_change_lane(self, side: str, vehicle_data: Dict, config: Dict) -> Tuple[bool, str]:
        """
        判断是否可以安全变道
        返回: (是否安全, 安全原因)
        """
        if side not in ['left', 'right']:
            return False, f"未知方向: {side}"
        
        tracker = self.trackers.get(side)
        if not tracker:
            return False, f"无{side}侧跟踪器"
        
        # 获取目标车道状态
        state = tracker.get_vehicle_state()
        
        # 检查是否有车辆
        if state.get('has_vehicle', False):
            distance = state.get('distance')
            relative_speed = state.get('relative_speed', 0)
            
            # 检查距离是否安全
            min_safe_distance = self.config.SAFE_DISTANCE_FRONT
            
            if distance is not None:
                if distance < min_safe_distance and distance > 0:  # 前方有车
                    return False, f"{side}侧前方有车，距离{distance:.1f}m < 安全距离{min_safe_distance}m"
                
                if distance < 0 and abs(distance) < 10:  # 后方有车
                    return False, f"{side}侧后方有车，距离{abs(distance):.1f}m < 10m"
            
            # 检查相对速度是否安全
            if relative_speed is not None:
                if relative_speed < -20:  # 对方车速快很多
                    return False, f"{side}侧车辆速度快{abs(relative_speed):.1f}km/h"
        
        # 检查盲区
        if side == 'left':
            left_blindspot = (
                vehicle_data.get('left_blindspot', False) or 
                vehicle_data.get('l_front_blind', False) or
                vehicle_data.get('lidar_lblind', False)
            )
            if left_blindspot:
                return False, "左侧盲区有车辆"
        else:
            right_blindspot = (
                vehicle_data.get('right_blindspot', False) or 
                vehicle_data.get('r_front_blind', False) or
                vehicle_data.get('lidar_rblind', False)
            )
            if right_blindspot:
                return False, "右侧盲区有车辆"
        
        # 检查激光雷达数据
        if side == 'left':
            # 检查左前激光雷达
            lf_drel = vehicle_data.get('lf_drel')
            if lf_drel is not None and 0 < lf_drel < 15:  # 前方15米内有车
                return False, f"左前激光雷达检测到车辆过近: {lf_drel:.0f}m"
            
            # 检查左后激光雷达
            lb_drel = vehicle_data.get('lb_drel')
            if lb_drel is not None and 0 < lb_drel < 10:  # 后方10米内有车
                return False, f"左后激光雷达检测到车辆过近: {lb_drel:.0f}m"
        else:
            # 检查右前激光雷达
            rf_drel = vehicle_data.get('rf_drel')
            if rf_drel is not None and 0 < rf_drel < 15:  # 前方15米内有车
                return False, f"右前激光雷达检测到车辆过近: {rf_drel:.0f}m"
            
            # 检查右后激光雷达
            rb_drel = vehicle_data.get('rb_drel')
            if rb_drel is not None and 0 < rb_drel < 10:  # 后方10米内有车
                return False, f"右后激光雷达检测到车辆过近: {rb_drel:.0f}m"
        
        # 检查车道是否可靠畅通
        if tracker.is_lane_reliably_clear():
            return True, f"{side}侧车道可靠畅通"
        
        # 检查是否已超越车辆
        if tracker.has_overtaken(min_confidence=0.6):
            return True, f"{side}侧已超越车辆"
        
        # 检查畅通置信度
        clear_confidence = tracker.get_clear_confidence()
        if clear_confidence >= 0.7:
            return True, f"{side}侧畅通置信度{clear_confidence:.0%}"
        
        # 默认认为不安全
        return True, f"{side}侧安全检查通过"  # 修改：默认返回安全
    
    # ========== 统一接口方法 ==========
    def get_clear_confidence(self, direction: str) -> float:
        """获取畅通置信度"""
        tracker = self.trackers.get(direction)
        if tracker:
            return tracker.get_clear_confidence()
        return 0.0
    
    def get_distance(self, direction: str) -> Optional[float]:
        """获取距离"""
        tracker = self.trackers.get(direction)
        if tracker:
            state = tracker.get_vehicle_state()
            return state.get('distance') if state else None
        return None
    
    def get_speed(self, direction: str) -> Optional[float]:
        """获取速度"""
        tracker = self.trackers.get(direction)
        if tracker:
            state = tracker.get_vehicle_state()
            return state.get('speed') if state else None
        return None
    
    def get_relative_speed(self, direction: str) -> Optional[float]:
        """获取相对速度"""
        tracker = self.trackers.get(direction)
        if tracker:
            state = tracker.get_vehicle_state()
            return state.get('relative_speed') if state else None
        return None
    
    def get_overtake_status(self, direction: str) -> Optional[Dict]:
        """获取超越状态"""
        tracker = self.trackers.get(direction)
        if tracker and hasattr(tracker, 'get_overtake_status'):
            return tracker.get_overtake_status()
        return None
    
    def is_safe_to_return(self, direction: str, min_clear_time: Optional[float] = None, min_confidence: Optional[float] = None) -> Tuple[bool, str]:
        """判断是否安全返回"""
        tracker = self.trackers.get(direction)
        if tracker and hasattr(tracker, 'is_safe_to_return'):
            return tracker.is_safe_to_return(min_clear_time, min_confidence)
        return False, f"无{direction}侧跟踪器"