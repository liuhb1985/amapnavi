#!/usr/bin/env python3
"""
返回原车道策略
"""

import time

class ReturnStrategy:
    """简化版返回策略 - 只提供配置和辅助方法"""
    def __init__(self, config):
        self.config = config
        
        # 🆕 不再需要自己的跟踪器
        self.tracking_center = None
        
        # 简化的返回状态
        self.return_state = {
            'current_stage': 'idle',
            'stage_progress': 0,
            'reason': ''
        }

    def set_tracking_center(self, tracking_center):
        """设置车辆跟踪中心"""
        self.tracking_center = tracking_center
    
    def get_return_state(self):
        """获取返回策略状态 - 简化版"""
        return self.return_state
    
    def get_return_timeout(self, road_type):
        """获取返回超时时间"""
        if 'RETURN_STRATEGY' in self.config and road_type in self.config['RETURN_STRATEGY']:
            return self.config['RETURN_STRATEGY'][road_type].get('return_timeout', 60000)
        return 60000  # 默认60秒
    
    def should_enable_return(self, road_type):
        """是否启用返回"""
        if 'RETURN_STRATEGY' in self.config and road_type in self.config['RETURN_STRATEGY']:
            return self.config['RETURN_STRATEGY'][road_type].get('enabled', True)
        return True
    
    def get_target_tracking_state(self, control_state):
        """获取目标跟踪状态 - 简化版"""
        if control_state['net_lane_changes'] == 0:
            return {
                'tracking': False,
                'target_detected': False,
                'details': '无需跟踪目标车辆',
                'progress': 0
            }
        
        # 确定目标方向
        if control_state['net_lane_changes'] > 0:
            target_side = 'right'
        else:
            target_side = 'left'
        
        if not self.tracking_center:
            return {
                'tracking': False,
                'target_detected': False,
                'details': '跟踪中心未初始化',
                'progress': 0
            }
        
        # 获取跟踪状态
        state = self.tracking_center.get_vehicle_state(target_side)
        if not state:
            return {
                'tracking': False,
                'target_detected': False,
                'details': f'无法获取{target_side}侧状态',
                'progress': 0
            }
        
        return {
            'tracking': True,
            'target_detected': state.get('has_vehicle', False),
            'side': target_side,
            'distance': state.get('distance'),
            'relative_speed': state.get('relative_speed'),
            'is_reliably_clear': state.get('is_reliably_clear', False),
            'details': f"{'有车' if state.get('has_vehicle', False) else '无车'}",
            'progress': 50 if state.get('has_vehicle', False) else 80
        }
    
    def update_stage(self, stage, reason, progress):
        """更新阶段状态"""
        self.return_state.update({
            'current_stage': stage,
            'reason': reason,
            'stage_progress': progress
        })