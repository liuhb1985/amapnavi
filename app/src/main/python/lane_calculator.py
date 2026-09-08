#!/usr/bin/env python3
"""
车道计算模块 - 连续位置法 + 迟滞保持优化版
采用基于左边缘连续位置计算车道，在车道线附近引入迟滞抑制抖动，
同时保留导航冲突处理、禁止/推荐车道修正、历史平滑等完整功能。
参数已调整为快速响应模式。
"""

import time
from collections import deque, Counter
from typing import Dict, Tuple, Optional, List, Any


class LaneConfig:
    """车道配置参数 - 优化版（快速响应 + 迟滞）"""
    MIN_LANE_WIDTH = 2.3
    MAX_LANE_WIDTH = 4.0
    STANDARD_HIGHWAY_WIDTH = 3.0
    STANDARD_NORMAL_WIDTH = 2.8
    MIN_ROAD_WIDTH = 5.0
    MAX_ROAD_WIDTH = 30.0
    MIN_LANE_COUNT = 1
    MAX_LANE_COUNT = 7
    CONFIDENCE_THRESHOLD = 0.6
    HIGH_CONFIDENCE_THRESHOLD = 0.8
    HYSTERESIS_THRESHOLD = 0.3
    LANE_CHANGE_THRESHOLD = 0.6

    # 滤波参数（加快响应）
    KALMAN_MEASUREMENT_VARIANCE = 1.0      # 增大测量信任
    KALMAN_PROCESS_VARIANCE = 1e-5         # 允许模型更快变化
    EMA_ALPHA = 0.4                         # 提高新数据权重

    MA_WINDOW_SIZE = 3
    LEFT_EDGE_PADDING_FACTOR = 1.15
    RIGHT_EDGE_PADDING_FACTOR = 1.15
    MIN_PADDING_DISTANCE = 0.1
    MAX_PADDING_DISTANCE = 1.5
    USE_LANE_BASED_CALCULATION = True
    MIN_LANE_WIDTH_FOR_CALCULATION = 2.0
    EDGE_VALIDATION_RATIO = 0.8
    BOUNDARY_THRESHOLD_RATIO = 0.55
    MAX_GEOMETRIC_ERROR = 1.8
    EDGE_SANITY_MIN = 0.1
    EDGE_SANITY_MAX = 30.0
    LANE_HISTORY_SIZE = 50
    EDGE_HISTORY_SIZE = 100

    # 导航冲突阈值
    NAV_CONFLICT_THRESHOLD = 1

    MIN_STABLE_SAMPLES = 5
    LANE_CHANGE_COOLDOWN = 500
    UPDATE_INTERVAL = 100
    METHOD_WEIGHTS = {
        'ratio_method': 0.20,
        'geometric_method': 0.20,
        'width_method': 0.15,
        'lane_based_method': 0.25,
        'balance_method': 0.10,
        'history_method': 0.10
    }
    BOUNDARY_THRESHOLD = 0.3
    NEAR_BOUNDARY_THRESHOLD = 0.5
    MIN_EDGE_RATIO_FOR_BOUNDARY = 0.2
    CONFIDENCE_HISTORY_SIZE = 20
    ANOMALY_DETECTION_WINDOW = 10
    NAV_LANE_ARRAY_HISTORY_SIZE = 10
    NAV_LANE_CONFIDENCE_BOOST = 0.3
    LANE_COUNT_PERCENT_ADJUST = 1.0
    MIN_VALID_LANE_WIDTH = 0.1

    # 新增属性
    WIDTH_HISTORY_SIZE = 10
    MIN_VALID_WIDTH_SAMPLES = 5
    LANE_CHANGE_HYSTERESIS = 0.3           # 两车道迟滞阈值（保留）
    LANE_POSITION_STABLE_SAMPLES = 3       # 减少平滑所需样本数，加快响应

    # 迟滞区间参数（连续位置法用）
    HYSTERESIS_LOW = 0.4                    # 迟滞下限（小数部分低于此值认为明确在车道左侧）
    HYSTERESIS_HIGH = 0.6                   # 迟滞上限（小数部分高于此值认为明确在车道右侧）


class KalmanFilter:
    """卡尔曼滤波器"""
    def __init__(self, process_variance=1e-5, measurement_variance=0.1):
        self.process_variance = process_variance
        self.measurement_variance = measurement_variance
        self.estimated_error = 1
        self.current_estimate = None
        self.last_estimate = None
        self.kalman_gain = 0

    def update(self, measurement):
        if self.current_estimate is None:
            self.current_estimate = measurement
            self.last_estimate = measurement
            return measurement
        self.estimated_error += self.process_variance
        self.kalman_gain = self.estimated_error / (self.estimated_error + self.measurement_variance)
        self.current_estimate = self.last_estimate + self.kalman_gain * (measurement - self.last_estimate)
        self.estimated_error = (1 - self.kalman_gain) * self.estimated_error
        self.last_estimate = self.current_estimate
        return self.current_estimate

    def reset(self):
        self.current_estimate = None
        self.last_estimate = None
        self.estimated_error = 1
        self.kalman_gain = 0


class ExponentialMovingAverage:
    def __init__(self, alpha=0.3):
        self.alpha = alpha
        self.value = None

    def update(self, new_value):
        if self.value is None:
            self.value = new_value
        else:
            self.value = self.alpha * new_value + (1 - self.alpha) * self.value
        return self.value

    def reset(self):
        self.value = None


class MovingAverage:
    def __init__(self, window_size=5):
        self.window_size = window_size
        self.values = deque(maxlen=window_size)

    def update(self, new_value):
        self.values.append(new_value)
        if not self.values:
            return new_value
        return sum(self.values) / len(self.values)

    def reset(self):
        self.values.clear()


class FastLaneCalculator:
    """
    快速车道计算器 - 连续位置法 + 迟滞保持优化版
    集成了被动变道检测和导航融合策略
    """

    def __init__(self, config=None):
        self.config = config or LaneConfig()

        # 车道宽度历史
        self.width_history = deque(maxlen=self.config.WIDTH_HISTORY_SIZE)
        self.reliable_lane_width = None
        self.last_valid_width_time = 0

        # 车道总数历史
        self.lane_count_history = deque(maxlen=self.config.LANE_HISTORY_SIZE)

        # 车道位置历史
        self.lane_position_history = deque(maxlen=self.config.LANE_HISTORY_SIZE)

        # 边缘数据历史
        self.edge_data_history = deque(maxlen=self.config.EDGE_HISTORY_SIZE)

        # 滤波状态
        self.left_edge_filter = KalmanFilter(
            process_variance=self.config.KALMAN_PROCESS_VARIANCE,
            measurement_variance=self.config.KALMAN_MEASUREMENT_VARIANCE
        )
        self.right_edge_filter = KalmanFilter(
            process_variance=self.config.KALMAN_PROCESS_VARIANCE,
            measurement_variance=self.config.KALMAN_MEASUREMENT_VARIANCE
        )
        self.lane_width_filter = KalmanFilter(
            process_variance=self.config.KALMAN_PROCESS_VARIANCE * 0.1,
            measurement_variance=self.config.KALMAN_MEASUREMENT_VARIANCE * 0.5
        )

        self.left_edge_ema = ExponentialMovingAverage(alpha=self.config.EMA_ALPHA)
        self.right_edge_ema = ExponentialMovingAverage(alpha=self.config.EMA_ALPHA)
        self.lane_width_ma = MovingAverage(window_size=self.config.MA_WINDOW_SIZE)

        # 当前状态
        self.state = {
            'current_lane': 1,
            'target_lane': 1,
            'state_confidence': 0.9,
            'last_change_time': 0,
            'min_time_between_changes': self.config.LANE_CHANGE_COOLDOWN,
            'stability_counter': 0,
            'transition_state': 'stable',
            'immediate_update_pending': False,
            'immediate_update_target': None,
            'last_immediate_update_time': 0,
            'left_edge_padding_applied': 0.0,
            'right_edge_padding_applied': 0.0,
            'dynamic_mode': 'stable',
            'post_change_end_time': 0,
            'prev_lane': None,               # 上一帧车道号（用于迟滞）
            'total_lanes': 3,                # 存储当前使用的总车道数（用于被动检测）
            'left_edge': None,               # 滤波后的左边缘
            'right_edge': None,              # 滤波后的右边缘
        }

        # 数据质量
        self.data_quality = {
            'left_edge_quality': deque(maxlen=10),
            'right_edge_quality': deque(maxlen=10),
            'lane_width_quality': deque(maxlen=10),
            'overall_quality': 0.8
        }

        # 导航数据
        self.nav_lane_array = None
        self.nav_lane_array_history = deque(maxlen=self.config.NAV_LANE_ARRAY_HISTORY_SIZE)
        self.nav_lane_confidence = 0.0
        self.last_nav_lane_update = 0

        # ========== 新增：被动变道检测器 ==========
        self.passive_detector = {
            'enabled': True,
            'left_short': deque(maxlen=10),
            'right_short': deque(maxlen=10),
            'left_long': deque(maxlen=50),
            'right_long': deque(maxlen=50),
            'confirm_count': 0,
            'confirm_threshold': 5,
            'change_threshold_factor': 0.6,
            'cooldown': 2000,                # 触发后冷却2000ms
            'last_trigger_time': 0,
            'last_direction': None,
        }

    # ------------------------------------------------------------
    # 导航数据接口
    # ------------------------------------------------------------
    def set_nav_lane_array(self, lane_array):
        current_time = time.time() * 1000
        if lane_array is None:
            self.nav_lane_array = None
            self.nav_lane_array_history.clear()
            self.nav_lane_confidence = 0.0
            self.last_nav_lane_update = current_time
            return

        if isinstance(lane_array, list) and 1 <= len(lane_array) <= self.config.MAX_LANE_COUNT:
            self.nav_lane_array = lane_array
            self.last_nav_lane_update = current_time
            self.nav_lane_array_history.append({
                'time': current_time,
                'array': lane_array.copy()
            })
            if len(self.nav_lane_array_history) >= 3:
                recent_arrays = [item['array'] for item in list(self.nav_lane_array_history)[-3:]]
                if all(arr == recent_arrays[0] for arr in recent_arrays):
                    self.nav_lane_confidence = 0.95
                else:
                    self.nav_lane_confidence = 0.7
            else:
                self.nav_lane_confidence = 0.8

    def get_lane_status(self, lane_number):
        if self.nav_lane_array is None:
            return None
        try:
            lane_number_int = int(lane_number)
        except (ValueError, TypeError):
            return None
        if lane_number_int < 1 or lane_number_int > len(self.nav_lane_array):
            return None
        return self.nav_lane_array[lane_number_int - 1]

    def is_lane_forbidden(self, lane_number):
        return self.get_lane_status(lane_number) == "X"

    def is_lane_recommended(self, lane_number):
        status = self.get_lane_status(lane_number)
        return status is not None and "*" in status

    def get_lane_type(self, lane_number):
        status = self.get_lane_status(lane_number)
        if status is None:
            return None
        return status.replace("*", "") if "*" in status else status

    # ------------------------------------------------------------
    # 边缘距离增补（仅用于车道总数计算）
    # ------------------------------------------------------------
    def _apply_edge_padding(self, raw_left, raw_right):
        if raw_left is None or raw_right is None:
            return raw_left, raw_right

        left_padding = raw_left * (self.config.LEFT_EDGE_PADDING_FACTOR - 1.0)
        left_padding = max(self.config.MIN_PADDING_DISTANCE,
                           min(self.config.MAX_PADDING_DISTANCE, left_padding))

        right_padding = raw_right * (self.config.RIGHT_EDGE_PADDING_FACTOR - 1.0)
        right_padding = max(self.config.MIN_PADDING_DISTANCE,
                            min(self.config.MAX_PADDING_DISTANCE, right_padding))

        self.state['left_edge_padding_applied'] = left_padding
        self.state['right_edge_padding_applied'] = right_padding

        padded_left = raw_left + left_padding
        padded_right = raw_right + right_padding
        return padded_left, padded_right

    def _safe_divide(self, numerator, denominator, default=0.0):
        if denominator is None or abs(denominator) < self.config.MIN_VALID_LANE_WIDTH:
            return default
        return numerator / denominator

    # ------------------------------------------------------------
    # 车道总数计算（保持原版逻辑）
    # ------------------------------------------------------------
    def _calculate_lanes_by_edge_counts(self, vehicle_data: Dict, road_type: str) -> Tuple[Optional[int], float, str]:
        left_raw = vehicle_data.get('l_edge_dist')
        right_raw = vehicle_data.get('r_edge_dist')
        if left_raw is None or right_raw is None:
            return None, 0.0, "边缘数据缺失"

        left = self._filter_left_edge(left_raw)
        right = self._filter_right_edge(right_raw)
        if left is None or right is None:
            return None, 0.0, "边缘滤波失败"

        left, right = self._apply_edge_padding(left, right)

        lane_width = self._filter_lane_width(
            vehicle_data.get('l_lane_width'),
            vehicle_data.get('r_lane_width'),
            road_type
        )
        if lane_width <= 0:
            lane_width = self.config.STANDARD_HIGHWAY_WIDTH if road_type == 'highway' else self.config.STANDARD_NORMAL_WIDTH

        total_width = left + right
        if total_width < self.config.MIN_ROAD_WIDTH:
            return 1, 0.5, "道路过窄，判定为单车道"

        estimated_lanes = int(round(total_width / lane_width))
        total_lanes = max(self.config.MIN_LANE_COUNT, min(self.config.MAX_LANE_COUNT, estimated_lanes))

        confidence = 0.8
        details = f"边缘计数法: 总宽{total_width:.1f}m/标准宽{lane_width:.1f}m={total_lanes}车道"
        return total_lanes, confidence, details

    def calculate_total_lanes(self, vehicle_data: Dict, road_type: str = 'normal') -> Tuple[int, float, str]:
        # 优先边缘计算
        lanes_by_count, count_conf, count_details = self._calculate_lanes_by_edge_counts(vehicle_data, road_type)
        if lanes_by_count is not None:
            candidate_lanes, candidate_conf, candidate_details = lanes_by_count, count_conf, count_details
        elif self.nav_lane_array is not None and self.nav_lane_confidence > 0.6:
            candidate_lanes = len(self.nav_lane_array)
            candidate_conf = self.nav_lane_confidence
            candidate_details = f"导航车道: {candidate_lanes}车道 (置信度{candidate_conf:.0%})"
        else:
            left_raw = vehicle_data.get('l_edge_dist')
            right_raw = vehicle_data.get('r_edge_dist')
            if left_raw is not None and right_raw is not None:
                total_width = left_raw + right_raw
                std_width = self.config.STANDARD_HIGHWAY_WIDTH if road_type == 'highway' else self.config.STANDARD_NORMAL_WIDTH
                if total_width < std_width * 1.2:
                    candidate_lanes = 1
                    candidate_conf = 0.7
                    candidate_details = f"单车道检测: 总宽{total_width:.1f}m < {std_width:.1f}m×1.2"
                else:
                    stable_count = self._get_stable_lane_count()
                    if stable_count is not None:
                        candidate_lanes = stable_count
                        candidate_conf = 0.8
                        candidate_details = f"历史稳定车道: {stable_count}车道"
                    else:
                        candidate_lanes = 3 if road_type == 'highway' else 2
                        candidate_conf = 0.5
                        candidate_details = f"默认车道数（{road_type}）: {candidate_lanes}车道"
            else:
                stable_count = self._get_stable_lane_count()
                if stable_count is not None:
                    candidate_lanes = stable_count
                    candidate_conf = 0.8
                    candidate_details = f"历史稳定车道: {stable_count}车道"
                else:
                    candidate_lanes = 3 if road_type == 'highway' else 2
                    candidate_conf = 0.5
                    candidate_details = f"默认车道数（{road_type}）: {candidate_lanes}车道"

        self._update_lane_count_history(candidate_lanes, candidate_conf)

        stable_lanes = self._get_stable_lane_count()
        if stable_lanes is not None:
            return stable_lanes, min(1.0, candidate_conf + 0.1), f"历史平滑: {stable_lanes}车道 (原始: {candidate_details})"
        else:
            return candidate_lanes, candidate_conf, candidate_details

    # ------------------------------------------------------------
    # 车道位置计算（核心优化：连续位置法 + 迟滞 + 导航融合）
    # ------------------------------------------------------------
    def calculate_current_lane(self, vehicle_data: Dict, total_lanes: int,
                               road_type: str = 'normal', ignore_nav: bool = False) -> Tuple[int, float, str]:
        current_time = time.time() * 1000

        # 保存当前总车道数供被动检测使用
        self.state['total_lanes'] = total_lanes

        # ---------- 即时更新挂起 ----------
        if self.state['immediate_update_pending'] and self.state['immediate_update_target'] is not None:
            target = self.state['immediate_update_target']
            if 1 <= target <= total_lanes:
                self.state.update({
                    'current_lane': target,
                    'target_lane': target,
                    'state_confidence': 0.95,
                    'last_change_time': current_time,
                    'immediate_update_pending': False,
                    'stability_counter': 0
                })
                self._update_lane_position_history(target, total_lanes, 0.95)
                return target, 0.95, f"即时更新: 车道{target}"

        # ---------- 获取边缘数据 ----------
        left_raw = vehicle_data.get('l_edge_dist')
        right_raw = vehicle_data.get('r_edge_dist')
        if left_raw is None or right_raw is None:
            stable_lane = self._get_stable_lane_position(total_lanes)
            return stable_lane, 0.5, "无边缘数据，使用历史车道"

        # 滤波
        left = self._filter_left_edge(left_raw)
        right = self._filter_right_edge(right_raw)
        if left is None or right is None:
            stable_lane = self._get_stable_lane_position(total_lanes)
            return stable_lane, 0.5, "边缘滤波失败"

        # 保存滤波后的边缘供被动检测使用
        self.state['left_edge'] = left
        self.state['right_edge'] = right

        # 车道宽度估计
        lane_width = self._filter_lane_width(
            vehicle_data.get('l_lane_width'),
            vehicle_data.get('r_lane_width'),
            road_type
        )
        if lane_width <= 0:
            lane_width = self.config.STANDARD_HIGHWAY_WIDTH if road_type == 'highway' else self.config.STANDARD_NORMAL_WIDTH

        # ---------- 连续位置法 ----------
        pos = left / lane_width                      # 连续位置（车道单位）
        raw_lane = int(pos) + 1                      # 初步车道号（1-based）
        raw_lane = max(1, min(total_lanes, raw_lane)) # 限制在有效范围内
        fraction = pos - int(pos)                     # 小数部分，表示在车道内的偏移比例

        # 计算置信度（基于距离理想中心线的偏差）
        ideal_pos = raw_lane - 0.5                    # 当前车道中心对应的 pos
        confidence = max(0.6, 1.0 - abs(pos - ideal_pos))
        confidence = min(1.0, confidence)

        # 迟滞处理：如果小数部分在迟滞区间内且上一帧有效，则保持上一帧车道
        if (self.config.HYSTERESIS_LOW < fraction < self.config.HYSTERESIS_HIGH
                and self.state.get('prev_lane') is not None):
            lane_calc = self.state['prev_lane']
            # 保持上一帧车道，置信度可适当降低
            confidence = 0.7
            details = f"连续位置+迟滞: 左{left:.1f}m, 宽{lane_width:.1f}m, pos={pos:.2f} -> 保持车道{lane_calc}"
        else:
            lane_calc = raw_lane
            details = f"连续位置法: 左{left:.1f}m, 宽{lane_width:.1f}m, pos={pos:.2f} -> 车道{lane_calc}"

        # 保存上一帧用于迟滞
        self.state['prev_lane'] = lane_calc

        # ========== 修改：导航冲突处理改为融合且不覆盖总车道数 ==========
        original_total_lanes = total_lanes  # 保存原始视觉总车道数
        if not ignore_nav and self.nav_lane_array is not None:
            nav_lanes = len(self.nav_lane_array)
            if abs(total_lanes - nav_lanes) > self.config.NAV_CONFLICT_THRESHOLD:
                # 冲突：以视觉车道总数为准，但使用相对位置重新映射车道号
                road_width = left + right
                if road_width > 0:
                    relative_pos = left / road_width   # 0=左边缘，1=右边缘
                    mapped_lane = int(relative_pos * nav_lanes) + 1
                    mapped_lane = max(1, min(nav_lanes, mapped_lane))

                    # 融合置信度：基于边缘数据质量和导航历史一致性
                    edge_confidence = min(1.0, max(0.3, 1.0 - abs(relative_pos - 0.5) * 2))
                    nav_confidence = self.nav_lane_confidence
                    fused_confidence = 0.4 * edge_confidence + 0.6 * nav_confidence
                    fused_confidence = max(0.4, min(0.9, fused_confidence))

                    # 更新车道号和置信度，但总车道数保持视觉结果（total_lanes 不变）
                    lane_calc = mapped_lane
                    confidence = fused_confidence
                    details = f"【导航冲突融合】导航{nav_lanes}车道 vs 视觉{original_total_lanes}车道，相对位置{relative_pos:.2f} -> 映射车道{lane_calc}"
                else:
                    # 道路宽度无效，降级处理
                    lane_calc = max(1, min(total_lanes, lane_calc))  # 保持视觉总数
                    confidence = 0.5
                    details = f"【导航冲突降级】导航{nav_lanes}车道，视觉总数{total_lanes}车道，保留视觉车道{lane_calc}"
            else:
                # 一致：提升置信度
                if self.nav_lane_confidence > 0.7:
                    confidence = min(0.98, confidence + 0.05)
                    details += " [导航车道数一致，置信度提升]"

        # ---------- 导航修正（禁止/推荐车道）----------
        if not ignore_nav and self.nav_lane_array is not None:
            if self.is_lane_forbidden(lane_calc):
                for offset in [-1, 1]:
                    adj = lane_calc + offset
                    if 1 <= adj <= total_lanes and not self.is_lane_forbidden(adj):
                        lane_calc = adj
                        confidence *= 0.9
                        details += f" (避开X车道至{adj})"
                        break
                else:
                    details += " (但导航均为X)"
            else:
                if self.is_lane_recommended(lane_calc):
                    confidence = min(0.98, confidence + 0.1)
                    details += " (推荐车道)"

        # 更新历史
        self._update_lane_position_history(lane_calc, total_lanes, confidence)

        # ---------- 动态平滑：高置信度直接输出，否则取历史稳定值 ----------
        if confidence > 0.85:
            final_lane = lane_calc
            final_conf = confidence
            details += " (高置信度直接输出)"
        else:
            stable_lane = self._get_stable_lane_position(total_lanes)
            if stable_lane is not None:
                final_lane = stable_lane
                final_conf = min(1.0, confidence + 0.1)
                details += f" | 历史平滑为车道{final_lane}"
            else:
                final_lane = lane_calc
                final_conf = confidence

        # 更新内部状态
        self.state['current_lane'] = final_lane
        self.state['target_lane'] = final_lane
        self.state['state_confidence'] = final_conf

        # ========== 新增：被动变道检测（使用滤波后的边缘） ==========
        self._update_passive_lane_change(left, right)

        return final_lane, final_conf, details

    # ------------------------------------------------------------
    # 新增：被动变道检测（集成自主控制器）
    # ------------------------------------------------------------
    def _update_passive_lane_change(self, left_edge, right_edge):
        """检测被动变道并自动触发即时更新"""
        detector = self.passive_detector
        if not detector['enabled']:
            return

        current_time = time.time() * 1000
        if current_time - detector['last_trigger_time'] < detector['cooldown']:
            return

        # 更新窗口
        detector['left_short'].append(left_edge)
        detector['right_short'].append(right_edge)
        detector['left_long'].append(left_edge)
        detector['right_long'].append(right_edge)

        # 窗口未满时不检测
        if len(detector['left_short']) < detector['left_short'].maxlen or \
           len(detector['left_long']) < detector['left_long'].maxlen:
            return

        # 计算均值
        short_left = sum(detector['left_short']) / len(detector['left_short'])
        long_left = sum(detector['left_long']) / len(detector['left_long'])
        short_right = sum(detector['right_short']) / len(detector['right_short'])
        long_right = sum(detector['right_long']) / len(detector['right_long'])

        lane_width = self.get_lane_width()
        threshold = lane_width * detector['change_threshold_factor']

        condition_met = False
        direction = None
        # 向左变道：左边缘减小（short_left < long_left - threshold），右边缘增大
        if short_left < long_left - threshold and short_right > long_right + threshold:
            condition_met = True
            direction = '左'
        # 向右变道：右边缘减小，左边缘增大
        elif short_right < long_right - threshold and short_left > long_left + threshold:
            condition_met = True
            direction = '右'

        if condition_met:
            detector['confirm_count'] += 1
            if detector['confirm_count'] >= detector['confirm_threshold']:
                # 触发即时更新
                total_lanes = self.state.get('total_lanes', 3)
                new_lane, desc = self.request_immediate_update(direction, self.state['current_lane'], total_lanes)
                if new_lane is not None:
                    self.state['current_lane'] = new_lane
                    detector['last_trigger_time'] = current_time
                    detector['confirm_count'] = 0
                    # 清空窗口避免残留
                    detector['left_short'].clear()
                    detector['right_short'].clear()
                    detector['left_long'].clear()
                    detector['right_long'].clear()
                    detector['last_direction'] = direction
                    # 设置动态模式为 post_change
                    self.set_dynamic_mode('post_change', current_time)
        else:
            detector['confirm_count'] = 0

    # ------------------------------------------------------------
    # 辅助方法：滤波
    # ------------------------------------------------------------
    def _filter_left_edge(self, raw_value):
        if raw_value is None:
            return None
        kalman_value = self.left_edge_filter.update(raw_value)
        ema_value = self.left_edge_ema.update(kalman_value)
        return ema_value

    def _filter_right_edge(self, raw_value):
        if raw_value is None:
            return None
        kalman_value = self.right_edge_filter.update(raw_value)
        ema_value = self.right_edge_ema.update(kalman_value)
        return ema_value

    def _filter_lane_width(self, left_width, right_width, road_type):
        if left_width is not None and right_width is not None:
            width = (left_width + right_width) / 2
        elif left_width is not None:
            width = left_width
        elif right_width is not None:
            width = right_width
        else:
            return 3.0

        if width < self.config.MIN_LANE_WIDTH or width > self.config.MAX_LANE_WIDTH:
            return 3.0

        self.width_history.append(width)
        if len(self.width_history) >= self.config.MIN_VALID_WIDTH_SAMPLES:
            kalman_val = self.lane_width_filter.update(width)
            ma_val = self.lane_width_ma.update(kalman_val)
            self.reliable_lane_width = ma_val
        else:
            self.reliable_lane_width = width

        return self.reliable_lane_width

    def _is_edge_quality_bad(self, edge_type):
        # 预留接口，暂不启用
        return False

    # ------------------------------------------------------------
    # 辅助方法：历史管理
    # ------------------------------------------------------------
    def _update_lane_count_history(self, lane_count, confidence):
        self.lane_count_history.append({
            'count': lane_count,
            'confidence': confidence,
            'time': time.time() * 1000
        })

    def _get_stable_lane_count(self):
        if len(self.lane_count_history) < self.config.MIN_STABLE_SAMPLES:
            return None
        recent = [item['count'] for item in list(self.lane_count_history)[-10:]]
        if not recent:
            return None
        counter = Counter(recent)
        most_common = counter.most_common(1)[0]
        if most_common[1] >= 3:
            return most_common[0]
        return None

    def _update_lane_position_history(self, lane, total_lanes, confidence):
        self.lane_position_history.append({
            'lane': lane,
            'total_lanes': total_lanes,
            'confidence': confidence,
            'time': time.time() * 1000
        })

    def _get_stable_lane_position(self, total_lanes):
        mode = self.state.get('dynamic_mode', 'stable')
        if mode == 'changing':
            if self.lane_position_history:
                return self.lane_position_history[-1]['lane']
            return self.state['current_lane']
        elif mode == 'post_change':
            required = 2
        else:
            required = self.config.LANE_POSITION_STABLE_SAMPLES

        if len(self.lane_position_history) < required:
            return self.state['current_lane']
        recent = [item['lane'] for item in list(self.lane_position_history)[-5:]
                  if item['total_lanes'] == total_lanes]
        if not recent:
            return self.state['current_lane']
        counter = Counter(recent)
        most_common = counter.most_common(1)[0]
        if most_common[1] >= required:
            return most_common[0]
        return self.state['current_lane']

    # ------------------------------------------------------------
    # 状态管理
    # ------------------------------------------------------------
    def set_dynamic_mode(self, mode, current_time=None):
        self.state['dynamic_mode'] = mode
        if mode == 'post_change' and current_time:
            self.state['post_change_end_time'] = current_time + 5000

    def request_immediate_update(self, direction, current_lane, total_lanes):
        if direction == "左":
            new_lane = max(1, current_lane - 1)
        elif direction == "右":
            new_lane = min(total_lanes, current_lane + 1)
        else:
            return None, f"无效方向: {direction}"
        if 1 <= new_lane <= total_lanes:
            self.state['immediate_update_pending'] = True
            self.state['immediate_update_target'] = new_lane
            self.left_edge_filter.reset()
            self.right_edge_filter.reset()
            self.left_edge_ema.reset()
            self.right_edge_ema.reset()
            self.lane_width_filter.reset()
            self.lane_width_ma.reset()
            return new_lane, f"已请求即时更新: {current_lane}→{new_lane}"
        else:
            return None, f"目标车道{new_lane}超出范围"

    def has_immediate_update_pending(self):
        return self.state.get('immediate_update_pending', False)

    def clear_immediate_update(self):
        self.state['immediate_update_pending'] = False
        self.state['immediate_update_target'] = None

    def start_fast_mode(self):
        self.left_edge_filter.reset()
        self.right_edge_filter.reset()
        self.left_edge_ema.reset()
        self.right_edge_ema.reset()
        self.lane_width_filter.reset()
        self.lane_width_ma.reset()
        self.state['immediate_update_pending'] = False

    def end_fast_mode(self):
        pass

    def reset_filters(self):
        self.left_edge_filter.reset()
        self.right_edge_filter.reset()
        self.lane_width_filter.reset()
        self.left_edge_ema.reset()
        self.right_edge_ema.reset()
        self.lane_width_ma.reset()
        self.state = {
            'current_lane': 1,
            'target_lane': 1,
            'state_confidence': 0.9,
            'last_change_time': 0,
            'min_time_between_changes': self.config.LANE_CHANGE_COOLDOWN,
            'stability_counter': 0,
            'transition_state': 'stable',
            'immediate_update_pending': False,
            'immediate_update_target': None,
            'last_immediate_update_time': 0,
            'left_edge_padding_applied': 0.0,
            'right_edge_padding_applied': 0.0,
            'dynamic_mode': 'stable',
            'post_change_end_time': 0,
            'prev_lane': None,
            'total_lanes': 3,
            'left_edge': None,
            'right_edge': None,
        }
        self.lane_count_history.clear()
        self.lane_position_history.clear()
        self.edge_data_history.clear()
        self.data_quality = {
            'left_edge_quality': deque(maxlen=10),
            'right_edge_quality': deque(maxlen=10),
            'lane_width_quality': deque(maxlen=10),
            'overall_quality': 0.8
        }

    # 新增方法（从新版补充）
    def update_state(self, new_state: Dict[str, Any]):
        for key, value in new_state.items():
            if key in self.state:
                self.state[key] = value

    def get_state(self) -> Dict[str, Any]:
        return self.state.copy()

    def get_lane_width(self) -> float:
        """返回可靠的车道宽度估计值"""
        if self.reliable_lane_width is not None:
            return self.reliable_lane_width
        # 降级使用配置的默认值
        return 3.0

    def get_current_lane(self) -> int:
        return self.state['current_lane']

    def get_target_lane(self) -> int:
        return self.state['target_lane']

    def is_lane_change_pending(self) -> bool:
        return self.state['immediate_update_pending']

    def set_target_lane(self, lane_number: int):
        if 1 <= lane_number <= self.config.MAX_LANE_COUNT:
            self.state.update({
                'target_lane': lane_number,
                'immediate_update_pending': True,
                'immediate_update_target': lane_number
            })

    def get_lane_confidence(self) -> float:
        return self.state['state_confidence']

    def get_lane_details(self) -> str:
        return f"车道: {self.state['current_lane']}, 置信度: {self.state['state_confidence']:.2f}"

    # 以下为原版遗留方法，保留占位
    def _lane_based_total_lanes_calculation(self, left_edge, right_edge, lane_width, road_type):
        return 3, 0.5, "已废弃"
    def _detect_boundary_position(self, left_edge, right_edge, lane_width, total_lanes):
        return None, ""
    def _validate_lane_position(self, lane, total_lanes, left_edge, right_edge, lane_width):
        return True, ""
    def _ratio_method(self, left_edge, right_edge, lane_width, total_lanes):
        return 1, 0.1
    def _geometry_method(self, left_edge, right_edge, lane_width, total_lanes):
        return 1, 0.1
    def _width_method(self, left_edge, right_edge, lane_width, total_lanes):
        return 1, 0.1
    def _balance_method(self, left_edge, right_edge, lane_width, total_lanes):
        return 1, 0.1
    def _history_method(self, total_lanes):
        return 1, 0.1
    def _weighted_vote(self, method_results, details, road_type):
        return 3, 0.5
    def _weighted_vote_lane(self, method_results, total_lanes):
        return 1, 0.5
    def _update_data_quality(self, left_edge, right_edge):
        pass
    def _calculate_ratio_confidence(self, lane_count, total_width, lane_width):
        return 0.5
    def _calculate_geometry_confidence(self, lane_count, left_lanes, right_lanes):
        return 0.5
    def _calculate_history_confidence(self, lane_count):
        return 0.5
    def _calculate_final_confidence(self, fusion_confidence, candidate_lane, left_edge, right_edge, lane_width, total_lanes):
        return fusion_confidence
    def _apply_state_machine(self, candidate_lane, confidence, current_time):
        return candidate_lane
    def _validate_change(self, old_lane, new_lane, new_confidence):
        return True
    def _smooth_update(self, new_lane, new_confidence):
        pass
    def _estimate_by_history(self):
        return 3


# ------------------------------------------------------------
# 车道边缘验证器
# ------------------------------------------------------------
class LaneEdgeValidator:
    def __init__(self, config=None):
        self.config = config or LaneConfig()
        self.edge_data_history = {
            'left': deque(maxlen=10),
            'right': deque(maxlen=10)
        }
        self.validation_results = {
            'left': {'valid': False, 'reason': '未验证', 'confidence': 0.0},
            'right': {'valid': False, 'reason': '未验证', 'confidence': 0.0}
        }
        self.left_filter = KalmanFilter(
            process_variance=self.config.KALMAN_PROCESS_VARIANCE,
            measurement_variance=self.config.KALMAN_MEASUREMENT_VARIANCE
        )
        self.right_filter = KalmanFilter(
            process_variance=self.config.KALMAN_PROCESS_VARIANCE,
            measurement_variance=self.config.KALMAN_MEASUREMENT_VARIANCE
        )

    def validate_turn_direction(self, direction: str, vehicle_data: Dict, config: Dict) -> Tuple[bool, str, float]:
        if direction not in ['left', 'right']:
            return False, "未知方向", 0.0
        if direction == 'left':
            edge_key = 'l_edge_dist'
            width_key = 'l_lane_width'
            chinese_dir = '左'
            filter_obj = self.left_filter
        else:
            edge_key = 'r_edge_dist'
            width_key = 'r_lane_width'
            chinese_dir = '右'
            filter_obj = self.right_filter
        raw_edge = vehicle_data.get(edge_key)
        raw_width = vehicle_data.get(width_key, 3.0)
        if raw_edge is None:
            return False, f"无{chinese_dir}侧边缘距离数据", 0.0
        filtered_edge = filter_obj.update(raw_edge)
        width_filter = KalmanFilter(
            process_variance=self.config.KALMAN_PROCESS_VARIANCE * 0.1,
            measurement_variance=self.config.KALMAN_MEASUREMENT_VARIANCE * 0.5
        )
        filtered_width = width_filter.update(raw_width)
        if filtered_width < self.config.MIN_VALID_LANE_WIDTH:
            filtered_width = 3.0
        if filtered_edge < filtered_width * self.config.BOUNDARY_THRESHOLD_RATIO:
            return False, f"{chinese_dir}侧边缘距离{filtered_edge:.1f}m < 车道宽度{filtered_width:.1f}m×{self.config.BOUNDARY_THRESHOLD_RATIO}", 0.0
        required_distance = filtered_width * self.config.EDGE_VALIDATION_RATIO
        if filtered_edge > required_distance:
            confidence = min(1.0, (filtered_edge - required_distance) / filtered_width + 0.6)
            self.validation_results[direction] = {
                'valid': True,
                'reason': f"{chinese_dir}侧边缘{filtered_edge:.1f}m > 车道宽度{filtered_width:.1f}m×{self.config.EDGE_VALIDATION_RATIO}",
                'confidence': confidence
            }
            return True, self.validation_results[direction]['reason'], confidence
        else:
            confidence = max(0.0, filtered_edge / required_distance) if required_distance > 0 else 0.0
            self.validation_results[direction] = {
                'valid': False,
                'reason': f"{chinese_dir}侧边缘{filtered_edge:.1f}m <= 车道宽度{filtered_width:.1f}m×{self.config.EDGE_VALIDATION_RATIO}",
                'confidence': confidence
            }
            return False, self.validation_results[direction]['reason'], confidence

    def get_direction_availability(self, vehicle_data: Dict, config: Dict) -> Dict[str, Dict]:
        results = {}
        for direction in ['left', 'right']:
            is_available, reason, confidence = self.validate_turn_direction(direction, vehicle_data, config)
            results[direction] = {
                'available': is_available,
                'reason': reason,
                'confidence': confidence,
                'history_size': len(self.edge_data_history[direction])
            }
        return results


# ------------------------------------------------------------
# 应急车道检测器
# ------------------------------------------------------------
class EmergencyLaneDetector:
    def __init__(self):
        self.detection_history = deque(maxlen=20)

    def detect_emergency_lane(self, current_lane: int, total_lanes: int, vehicle_data: Dict, config: Dict) -> Dict:
        if current_lane != total_lanes:
            return {
                'is_emergency': False,
                'reason': f"不是最右侧车道（车道{current_lane}/{total_lanes}）",
                'confidence': 1.0,
                'lane': current_lane,
                'total_lanes': total_lanes
            }
        roadcate = config.get('roadcate', 0)
        if roadcate != 1:
            return {
                'is_emergency': False,
                'reason': f"非应急车道高速公路（roadcate={roadcate}）",
                'confidence': 0.8,
                'lane': current_lane,
                'total_lanes': total_lanes
            }
        result = {
            'is_emergency': True,
            'reason': f"应急车道高速公路，车道{total_lanes}为应急车道",
            'confidence': 0.9,
            'lane': current_lane,
            'total_lanes': total_lanes
        }
        self.detection_history.append({
            'time': time.time(),
            'lane': current_lane,
            'is_emergency': True,
            'roadcate': roadcate,
            'reason': result['reason']
        })
        return result


# ------------------------------------------------------------
# 增强车道位置系统（主入口）
# ------------------------------------------------------------
class EnhancedLanePositionSystem:
    def __init__(self, config=None):
        self.config = config or LaneConfig()
        self.lane_calculator = FastLaneCalculator(self.config)
        self.edge_validator = LaneEdgeValidator(self.config)
        self.emergency_detector = EmergencyLaneDetector()
        self.system_state = {
            'current_lane': 1,
            'total_lanes': 3,
            'lane_confidence': 0.8,
            'last_update_time': 0,
            'update_interval': self.config.UPDATE_INTERVAL,
            'last_result': None,
            'immediate_update_requested': False,
            'fast_mode': False,
            'fast_mode_start_time': 0,
            'fast_mode_duration': 3000,
        }

    # 导航接口
    def set_nav_lane_array(self, lane_array):
        return self.lane_calculator.set_nav_lane_array(lane_array)

    def get_lane_status(self, lane_number):
        return self.lane_calculator.get_lane_status(lane_number)

    def is_lane_forbidden(self, lane_number):
        return self.lane_calculator.is_lane_forbidden(lane_number)

    def is_lane_recommended(self, lane_number):
        return self.lane_calculator.is_lane_recommended(lane_number)

    # 新增：获取车道宽度
    def get_lane_width(self) -> float:
        return self.lane_calculator.get_lane_width()

    # 核心更新接口
    def update_lane_position(self, vehicle_data: Dict, config: Dict) -> Dict:
        current_time = time.time() * 1000

        force_update = (
            self.system_state.get('immediate_update_requested', False) or
            self.lane_calculator.has_immediate_update_pending()
        )

        if not force_update:
            time_since_last = current_time - self.system_state['last_update_time']
            if time_since_last < self.system_state['update_interval']:
                if self.system_state['last_result'] is not None:
                    return self.system_state['last_result']

        road_type = config.get('road_type', 'normal')
        lane_count_mode = config.get('lane_count_mode', 'auto')

        if lane_count_mode == 'manual':
            total_lanes = config.get('lane_count', 3)
            lanes_conf = 1.0
            lanes_details = f"手动模式: 车道数设置为{total_lanes}"
            ignore_nav = True
        else:
            total_lanes, lanes_conf, lanes_details = self.lane_calculator.calculate_total_lanes(
                vehicle_data, road_type
            )
            ignore_nav = False

        in_fast_mode = self.system_state['fast_mode']
        if in_fast_mode:
            if current_time - self.system_state['fast_mode_start_time'] > self.system_state['fast_mode_duration']:
                self.end_fast_mode()
                in_fast_mode = False

        if in_fast_mode:
            current_lane, lane_conf, lane_details = self.lane_calculator.calculate_current_lane(
                vehicle_data, total_lanes, road_type, ignore_nav=ignore_nav
            )
            calc_mode = "快速模式"
        else:
            current_lane, lane_conf, lane_details = self.lane_calculator.calculate_current_lane(
                vehicle_data, total_lanes, road_type, ignore_nav=ignore_nav
            )
            calc_mode = "正常模式"

        overall_conf = (lanes_conf + lane_conf) / 2

        edge_availability = self.edge_validator.get_direction_availability(vehicle_data, config)

        emergency_info = self.emergency_detector.detect_emergency_lane(
            current_lane, total_lanes, vehicle_data, config
        )

        result = {
            'config': config.copy(),
            'current_lane': current_lane,
            'lane_count': total_lanes,
            'overall_confidence': overall_conf,
            'lane_count_details': lanes_details,
            'lane_position_details': f"{calc_mode}: {lane_details}",
            'edge_availability': edge_availability,
            'emergency_lane_info': emergency_info,
            'time_since_last_update': current_time - self.system_state['last_update_time'],
            'system_timestamp': current_time,
            'immediate_update_applied': force_update,
            'calculation_mode': calc_mode,
            'lane_count_confidence': lanes_conf,
            'lane_position_confidence': lane_conf,
        }

        self.system_state.update({
            'current_lane': current_lane,
            'total_lanes': total_lanes,
            'lane_confidence': overall_conf,
            'last_update_time': current_time,
            'last_result': result,
            'immediate_update_requested': False,
        })

        return result

    # 快速模式控制
    def start_fast_mode(self):
        self.system_state['fast_mode'] = True
        self.system_state['fast_mode_start_time'] = time.time() * 1000
        self.lane_calculator.start_fast_mode()

    def end_fast_mode(self):
        self.system_state['fast_mode'] = False
        self.lane_calculator.end_fast_mode()

    # 即时更新接口
    def request_immediate_lane_update(self, direction: str, current_config: Dict) -> Tuple[int, str]:
        current_lane = current_config.get('current_lane_number', 1)
        total_lanes = current_config.get('lane_count', 3)
        new_lane, desc = self.lane_calculator.request_immediate_update(direction, current_lane, total_lanes)
        self.system_state['immediate_update_requested'] = True
        return new_lane, desc

    # 配置调整接口
    def set_edge_padding_enabled(self, enabled: bool):
        pass

    def set_edge_padding_percent(self, percent: float):
        pass

    def set_left_edge_padding_factor(self, factor: float):
        if 0.5 <= factor <= 2.0:
            self.lane_calculator.config.LEFT_EDGE_PADDING_FACTOR = factor

    def set_right_edge_padding_factor(self, factor: float):
        if 0.5 <= factor <= 2.0:
            self.lane_calculator.config.RIGHT_EDGE_PADDING_FACTOR = factor

    def set_lane_count_percent_adjust(self, percent: float) -> Tuple[bool, str]:
        if 0.5 <= percent <= 2.0:
            self.config.LANE_COUNT_PERCENT_ADJUST = percent
            return True, f"车道总数百分比调整已设置为{percent:.2f}"
        else:
            return False, f"无效百分比: {percent}"

    def reset_system(self):
        self.lane_calculator.reset_filters()
        self.system_state.update({
            'current_lane': 1,
            'total_lanes': 3,
            'lane_confidence': 0.8,
            'last_update_time': 0,
            'last_result': None,
            'immediate_update_requested': False,
            'fast_mode': False,
        })

    # 保留原接口：车道边缘验证（供外部调用）
    def validate_lane_change(self, direction: str, vehicle_data: Dict, config: Dict) -> Tuple[bool, str, float]:
        return self.edge_validator.validate_turn_direction(direction, vehicle_data, config)


__all__ = [
    'LaneConfig',
    'FastLaneCalculator',
    'LaneEdgeValidator',
    'EmergencyLaneDetector',
    'EnhancedLanePositionSystem',
]