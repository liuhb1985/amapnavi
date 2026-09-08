#!/usr/bin/env python3
"""
Web界面模块
"""

import json
import os
import traceback
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

class WebInterface:
    """Web界面处理器"""
    
    def __init__(self, controller):
        self.controller = controller
        self.web_server = None

    def create_web_handler(self):
        """创建Web处理器"""
        controller = self.controller

        class OvertakeHTTPHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                """处理GET请求"""
                try:
                    # 处理静态文件请求（如图片）
                    if self.path.startswith('/images/') or self.path.startswith('/static/'):
                        self.serve_static_file()
                    elif self.path == '/':
                        self.send_html_response()
                    elif self.path == '/status':
                        self.send_json_status()
                    else:
                        self.send_error(404, "Page not found")
                except Exception as e:
                    print(f"处理GET请求时出错: {e}")
                    traceback.print_exc()
                    try:
                        self.send_error(500, f"Internal server error: {str(e)}")
                    except ConnectionError:
                        # 客户端已断开，忽略
                        pass

            def serve_static_file(self):
                """提供静态文件服务（如图片）"""
                try:
                    # 获取文件相对路径（去掉开头的斜杠）
                    file_path = self.path.lstrip('/')
                    # 构建完整的文件路径（当前目录下的文件）
                    base_dir = os.path.dirname(__file__)
                    full_path = os.path.join(base_dir, file_path)
                    # 规范化路径，防止路径遍历攻击
                    full_path = os.path.abspath(full_path)
                    
                    # 安全检查：确保文件在 base_dir 内
                    if not full_path.startswith(os.path.abspath(base_dir)):
                        self.send_error(403, "Forbidden")
                        return
                    
                    if not os.path.exists(full_path) or not os.path.isfile(full_path):
                        self.send_error(404, "File not found")
                        return
                    
                    # 根据文件扩展名设置 Content-Type
                    ext = os.path.splitext(full_path)[1].lower()
                    content_type = {
                        '.png': 'image/png',
                        '.jpg': 'image/jpeg',
                        '.jpeg': 'image/jpeg',
                        '.gif': 'image/gif',
                        '.svg': 'image/svg+xml',
                        '.css': 'text/css',
                        '.js': 'application/javascript',
                        '.html': 'text/html',
                    }.get(ext, 'application/octet-stream')
                    
                    with open(full_path, 'rb') as f:
                        content = f.read()
                    
                    self.send_response(200)
                    self.send_header('Content-Type', content_type)
                    self.send_header('Content-Length', str(len(content)))
                    self.end_headers()
                    try:
                        self.wfile.write(content)
                    except ConnectionError:
                        # 客户端断开连接，忽略
                        pass
                except Exception as e:
                    print(f"提供静态文件时出错: {e}")
                    try:
                        self.send_error(500, f"Internal server error: {str(e)}")
                    except ConnectionError:
                        pass

            def do_POST(self):
                """处理POST请求"""
                try:
                    content_length = int(self.headers.get('Content-Length', 0))
                    post_data = self.rfile.read(content_length).decode('utf-8')
                    data = json.loads(post_data) if post_data else {}

                    if self.path == '/control':
                        self.handle_control(data)
                    elif self.path == '/overtake':
                        self.handle_overtake(data)
                    elif self.path == '/config':
                        self.handle_config(data)
                    elif self.path == '/params':
                        self.handle_params(data)
                    else:
                        self.send_error(404, "Interface not found")
                except Exception as e:
                    print(f"处理POST请求时出错: {e}")
                    traceback.print_exc()
                    try:
                        self.send_error(400, f"Request parse error: {str(e)}")
                    except ConnectionError:
                        pass

            def handle_control(self, data):
                """处理控制命令"""
                try:
                    if not controller:
                        self.send_json_response({'status': 'error', 'message': '控制器未初始化'})
                        return
                        
                    cmd_type = data.get('type', '')
                    value = data.get('value', '')
                    
                    if cmd_type == 'SPEED':
                        # 🆕 安全检查
                        if hasattr(controller, 'change_speed'):
                            controller.change_speed(value)
                            
                    self.send_json_response({'status': 'success', 'command': f'{cmd_type}: {value}'})
                except Exception as e:
                    print(f"处理控制命令时出错: {e}")
                    self.send_json_response({'status': 'error', 'message': f'控制命令失败: {str(e)}'})

            def handle_overtake(self, data):
                """处理超车命令"""
                try:
                    if 'manual' in data:
                        # 🆕 添加安全检查
                        if not controller or not hasattr(controller, 'control_state'):
                            self.send_json_response({'status': 'error', 'message': '控制器未初始化'})
                            return
                            
                        controller.control_state['overtakeState'] = f"准备手动{data['manual']}变道"
                        controller.control_state['overtakeReason'] = "执行用户手动变道指令"
                        controller.manual_overtake(data['manual'])
                        self.send_json_response({'status': 'success', 'action': f'manual_{data["manual"]}'})
                        
                    elif 'cancel' in data:
                        # 🆕 添加安全检查
                        if not controller or not hasattr(controller, 'control_state'):
                            self.send_json_response({'status': 'error', 'message': '控制器未初始化'})
                            return
                            
                        controller.control_state['overtakeState'] = "取消超车"
                        controller.control_state['overtakeReason'] = "用户取消超车操作"
                        controller.cancel_overtake()
                        self.send_json_response({'status': 'success', 'action': 'cancel'})
                        
                    elif 'auto' in data:
                        new_auto = bool(data['auto'])
                        
                        # 🆕 增强安全检查
                        if not controller:
                            self.send_json_response({'status': 'error', 'message': '控制器未初始化'})
                            return
                            
                        if not hasattr(controller, 'vehicle_data'):
                            self.send_json_response({'status': 'error', 'message': '车辆数据未初始化'})
                            return
                        
                        # 检查盲区设备状态
                        vehicle_data = getattr(controller, 'vehicle_data', {})
                        system_control = vehicle_data.get('system_auto_control', 0)
                        
                        if system_control == 3:
                            # 缺少盲区设备，禁止开启超车
                            self.send_json_response({
                                'status': 'error', 
                                'message': '为了安全变道，请连接盲区设备，再打开超车功能。'
                            })
                            return
                            
                        # 🆕 使用控制器的方法更新配置，以便同步返回功能
                        success = controller.update_config('autoOvertakeEnabled', new_auto)
                        
                        if success:
                            status_text = "已启用" if new_auto else "已关闭"
                            
                            # 🆕 安全更新控制状态
                            if hasattr(controller, 'control_state'):
                                controller.control_state['overtakeState'] = f"高速公路自动超车{status_text}"
                                controller.control_state['overtakeReason'] = f"用户{'开启' if new_auto else '关闭'}高速公路自动超车"
                            
                            self.send_json_response({
                                'status': 'success', 
                                'autoOvertake': getattr(controller, 'config', {}).get('autoOvertakeEnabled', False)
                            })
                        else:
                            self.send_json_response({'status': 'error', 'message': '更新配置失败'})
                            
                    elif 'autol' in data:
                        new_autol = bool(data['autol'])
                        
                        # 🆕 增强安全检查
                        if not controller:
                            self.send_json_response({'status': 'error', 'message': '控制器未初始化'})
                            return
                            
                        if not hasattr(controller, 'config'):
                            self.send_json_response({'status': 'error', 'message': '配置未初始化'})
                            return
                            
                        if not hasattr(controller, 'vehicle_data'):
                            self.send_json_response({'status': 'error', 'message': '车辆数据未初始化'})
                            return
                        
                        # 检查盲区设备状态
                        vehicle_data = getattr(controller, 'vehicle_data', {})
                        system_control = vehicle_data.get('system_auto_control', 0)
                        
                        if system_control == 3:
                            # 缺少盲区设备，禁止开启超车
                            self.send_json_response({
                                'status': 'error', 
                                'message': '为了安全变道，请连接盲区设备，再打开超车功能。'
                            })
                            return
                            
                        controller.config['autoOvertakeEnabledL'] = new_autol
                        
                        # 🆕 安全保存配置
                        if hasattr(controller, 'save_persistent_config'):
                            controller.save_persistent_config()
                        
                        status_text = "已启用" if new_autol else "已关闭"
                        
                        # 🆕 安全更新控制状态
                        if hasattr(controller, 'control_state'):
                            controller.control_state['overtakeState'] = f"普通道路自动超车{status_text}"
                            controller.control_state['overtakeReason'] = f"用户{'开启' if new_autol else '关闭'}普通道路自动超车"
                        
                        self.send_json_response({
                            'status': 'success', 
                            'autoOvertakeL': controller.config['autoOvertakeEnabledL']
                        })
                        
                    elif 'return' in data:
                        new_return = bool(data['return'])
                        
                        # 🆕 增强安全检查
                        if not controller:
                            self.send_json_response({'status': 'error', 'message': '控制器未初始化'})
                            return
                            
                        if not hasattr(controller, 'config'):
                            self.send_json_response({'status': 'error', 'message': '配置未初始化'})
                            return
                            
                        if not hasattr(controller, 'control_state'):
                            self.send_json_response({'status': 'error', 'message': '控制状态未初始化'})
                            return
                        
                        # 🆕 严格的权限检查
                        current_road_type = controller.config.get('road_type')
                        
                        # 1. 只能在高速公路上操作返回功能
                        if current_road_type != 'highway':
                            self.send_json_response({
                                'status': 'error', 
                                'message': '只能在高速公路上开启返回功能'
                            })
                            return
                        
                        # 2. 必须先开启高速超车
                        if not controller.config.get('autoOvertakeEnabled', True):
                            self.send_json_response({
                                'status': 'error', 
                                'message': '请先开启高速公路超车功能'
                            })
                            return
                        
                        # 3. 记录用户意图
                        controller.config['last_return_state'] = new_return
                        
                        # 4. 更新当前状态
                        old_return = controller.config['shouldReturnToLane']
                        controller.config['shouldReturnToLane'] = new_return
                        
                        # 5. 同步到返回策略配置
                        if 'RETURN_STRATEGY' in controller.config and 'highway' in controller.config['RETURN_STRATEGY']:
                            controller.config['RETURN_STRATEGY']['highway']['enabled'] = new_return
                        
                        # 🆕 安全保存配置
                        if hasattr(controller, 'save_persistent_config'):
                            controller.save_persistent_config()
                        
                        # 6. 状态反馈
                        if new_return:
                            controller.control_state.update({
                                'overtakeState': "返回功能已开启",
                                'overtakeReason': "返回原车道功能已启用",
                                'system_status_detail': "返回功能启用"
                            })
                        else:
                            controller.control_state.update({
                                'overtakeState': "返回功能已关闭",
                                'overtakeReason': "返回原车道功能已关闭",
                                'system_status_detail': "返回功能关闭"
                            })
                            if old_return:
                                # 🆕 安全重置
                                if hasattr(controller, 'reset_net_lane_changes'):
                                    controller.reset_net_lane_changes()
                        
                        self.send_json_response({
                            'status': 'success', 
                            'returnToLane': controller.config['shouldReturnToLane']
                        })
                        
                except Exception as e:
                    # 🆕 捕获所有异常，防止崩溃
                    print(f"处理超车命令时出错: {e}")
                    traceback.print_exc()
                    self.send_json_response({
                        'status': 'error',
                        'message': f'处理请求时出错: {str(e)}'
                    })

            def handle_config(self, data):
                """处理配置更新"""
                try:
                    if not controller:
                        self.send_json_response({'status': 'error', 'message': '控制器未初始化'})
                        return
                        
                    if 'lane_count_mode' in data:
                        mode = data['lane_count_mode']
                        if mode in ['manual', 'auto', 'op']:
                            controller.config['lane_count_mode'] = mode
                            
                            # 🆕 安全计算
                            if hasattr(controller, 'calculate_lane_count'):
                                controller.calculate_lane_count()
                                
                            # 🆕 安全保存
                            if hasattr(controller, 'save_persistent_config'):
                                controller.save_persistent_config()

                    if 'lanes' in data and controller.config.get('lane_count_mode') == 'manual':
                        lanes = int(data['lanes'])
                        if 1 <= lanes <= 5:
                            controller.config['lane_count'] = lanes
                            controller.config['manual_lane_count'] = lanes
                            
                            # 🆕 安全保存
                            if hasattr(controller, 'save_persistent_config'):
                                controller.save_persistent_config()

                    if 'manual_lane_count' in data and controller.config.get('lane_count_mode') == 'manual':
                        lanes = int(data['manual_lane_count'])
                        if 1 <= lanes <= 5:
                            controller.config['manual_lane_count'] = lanes
                            controller.config['lane_count'] = lanes
                            
                            # 🆕 安全保存
                            if hasattr(controller, 'save_persistent_config'):
                                controller.save_persistent_config()

                    if 'road_type' in data:
                        new_road_type = data['road_type']
                        old_road_type = controller.config.get('road_type', 'normal')
                        
                        # 获取当前自动检测的道路类型
                        auto_road_type = None
                        if hasattr(controller, 'last_roadcate') and controller.last_roadcate is not None:
                            if controller.last_roadcate == 0 or controller.last_roadcate == 1:
                                auto_road_type = 'highway'
                            else:
                                auto_road_type = 'normal'
                        
                        # 判断模式切换逻辑
                        if auto_road_type is not None:
                            if new_road_type == auto_road_type:
                                # 用户设置与自动检测一致，切换到自动模式
                                controller.road_type_mode = 'auto'
                                controller.last_manual_road_type = None
                                controller.config['road_type'] = auto_road_type  # 使用自动检测值
                                # 移除配置中的手动设置记录
                                if 'last_manual_road_type' in controller.config:
                                    del controller.config['last_manual_road_type']
                            else:
                                # 用户设置与自动检测不一致，切换到手动模式
                                controller.road_type_mode = 'manual'
                                controller.last_manual_road_type = new_road_type
                                controller.config['road_type'] = new_road_type  # 使用用户设置值
                                # 保存手动设置到配置
                                controller.config['last_manual_road_type'] = new_road_type
                        else:
                            # 还没有自动检测数据，直接使用用户设置
                            controller.road_type_mode = 'manual'
                            controller.last_manual_road_type = new_road_type
                            controller.config['road_type'] = new_road_type
                            # 保存手动设置到配置
                            controller.config['last_manual_road_type'] = new_road_type
                        
                        # 如果从高速公路切换到普通公路，重置净变道次数
                        if old_road_type == 'highway' and new_road_type == 'normal':
                            # 🆕 安全重置
                            if hasattr(controller, 'reset_net_lane_changes'):
                                controller.reset_net_lane_changes()
                        
                        # 🆕 安全计算和保存
                        if hasattr(controller, 'calculate_lane_count'):
                            controller.calculate_lane_count()
                            
                        if hasattr(controller, 'save_persistent_config'):
                            controller.save_persistent_config()
                        
                        # 返回模式信息
                        road_name = "高速公路" if controller.config.get('road_type') == 'highway' else "普通公路"
                        mode_text = "自动" if getattr(controller, 'road_type_mode', 'auto') == 'auto' else "手动"
                        
                        # 🆕 安全更新控制状态
                        if hasattr(controller, 'control_state'):
                            controller.control_state['system_status_detail'] = f"已设置为{road_name}({mode_text}模式)"
                            controller.control_state['overtakeState'] = f"{road_name}模式已启用"
                        
                        self.send_json_response({
                            'status': 'success', 
                            'message': f'已切换到{road_name}({mode_text}模式)',
                            'road_type': controller.config.get('road_type', 'normal'),
                            'mode': getattr(controller, 'road_type_mode', 'auto')
                        })
                        return

                    self.send_json_response({'status': 'success', 'config': controller.config})
                except Exception as e:
                    print(f"处理配置更新时出错: {e}")
                    self.send_json_response({'status': 'error', 'message': f'配置更新失败: {str(e)}'})

            def handle_params(self, data):
                """处理参数更新"""
                try:
                    if not controller or not hasattr(controller, 'config'):
                        self.send_json_response({'status': 'error', 'message': '控制器或配置未初始化'})
                        return
                        
                    param_map = {
                        'highwayMinSpeed': 'HIGHWAY_MIN_SPEED',
                        'normalMinSpeed': 'NORMAL_ROAD_MIN_SPEED',
                        'speedRatio': 'CRUISE_SPEED_RATIO_THRESHOLD',
                        'followTimeGap': 'FOLLOW_TIME_GAP_THRESHOLD',
                        'maxFollowTime': 'MAX_FOLLOW_TIME',
                        'minLaneWidth': 'MIN_LANE_WIDTH',
                        'safeLaneWidth': 'SAFE_LANE_WIDTH',
                        'sideLeadDist': 'SIDE_LEAD_DISTANCE_MIN',
                        'sideRelSpeed': 'SIDE_RELATIVE_SPEED_THRESHOLD',
                        'leadRelSpeed': 'LEAD_RELATIVE_SPEED_THRESHOLD',
                        'highwayLeadMinSpeed': 'HIGHWAY_LEAD_MIN_SPEED',
                        'normalLeadMinSpeed': 'NORMAL_LEAD_MIN_SPEED',
                        'earlyOvertakeSpeedRatio': 'EARLY_OVERTAKE_SPEED_RATIO',
                        'earlyOvertakeMinLeadSpeed': 'EARLY_OVERTAKE_MIN_LEAD_SPEED',
                        'earlyOvertakeMinDistance': 'EARLY_OVERTAKE_MIN_DISTANCE',
                        'earlyOvertakeMaxDistance': 'EARLY_OVERTAKE_MAX_DISTANCE',
                        'earlyOvertakeMinSpeedDiff': 'EARLY_OVERTAKE_MIN_SPEED_DIFF'
                    }

                    for web_key, config_key in param_map.items():
                        if web_key in data:
                            if web_key == 'maxFollowTime':
                                minutes = float(data[web_key])
                                controller.config[config_key] = int(minutes * 60 * 1000)
                            elif web_key == 'speedRatio' or web_key == 'earlyOvertakeSpeedRatio':
                                controller.config[config_key] = float(data[web_key]) / 100.0
                            else:
                                controller.config[config_key] = float(data[web_key])

                    # 🆕 安全保存配置
                    if hasattr(controller, 'save_persistent_config'):
                        controller.save_persistent_config()
                        
                    self.send_json_response({'status': 'success', 'message': 'Parameters saved'})
                except Exception as e:
                    print(f"处理参数更新时出错: {e}")
                    self.send_json_response({'status': 'error', 'message': f'参数保存失败: {str(e)}'})

            def send_html_response(self):
                """发送HTML页面"""
                try:
                    html = self.get_html_content()
                    self.send_response(200)
                    self.send_header('Content-type', 'text/html; charset=utf-8')
                    self.end_headers()
                    try:
                        self.wfile.write(html.encode('utf-8'))
                    except ConnectionError:
                        # 客户端断开，忽略
                        pass
                except Exception as e:
                    print(f"发送HTML响应时出错: {e}")
                    try:
                        self.send_error(500, f"Internal server error: {str(e)}")
                    except ConnectionError:
                        pass

            def send_json_status(self):
                """发送JSON状态数据"""
                try:
                    if not controller:
                        self.send_error(500, "Controller not available")
                        return
                        
                    # 🆕 安全检查
                    if not hasattr(controller, 'get_status_data'):
                        self.send_error(500, "get_status_data method not available")
                        return
                        
                    status_data = controller.get_status_data()
                    
                    self.send_response(200)
                    self.send_header('Content-type', 'application/json; charset=utf-8')
                    self.end_headers()
                    try:
                        self.wfile.write(json.dumps(status_data, ensure_ascii=False).encode('utf-8'))
                    except ConnectionError:
                        # 客户端断开，忽略
                        pass
                except Exception as e:
                    print(f"发送状态数据时出错: {e}")
                    traceback.print_exc()
                    try:
                        self.send_error(500, f"Internal server error: {str(e)}")
                    except ConnectionError:
                        pass

            def send_json_response(self, data):
                """发送JSON响应"""
                try:
                    self.send_response(200)
                    self.send_header('Content-type', 'application/json; charset=utf-8')
                    self.end_headers()
                    try:
                        self.wfile.write(json.dumps(data, ensure_ascii=False).encode('utf-8'))
                    except ConnectionError:
                        # 客户端断开，忽略
                        pass
                except Exception as e:
                    print(f"发送JSON响应时出错: {e}")
                    try:
                        self.send_error(500, f"Internal server error: {str(e)}")
                    except ConnectionError:
                        pass

            def get_html_content(self):
                """获取HTML文件内容"""
                try:
                    html_file_path = os.path.join(os.path.dirname(__file__), 'web_interface.html')
                    with open(html_file_path, 'r', encoding='utf-8') as f:
                        return f.read()
                except FileNotFoundError:
                    return "<html><body><h1>Error: HTML interface file not found</h1></body></html>"
                except Exception as e:
                    return f"<html><body><h1>Error loading HTML: {str(e)}</h1></body></html>"

            def log_message(self, format, *args):
                """禁用访问日志"""
                pass

        return OvertakeHTTPHandler

    def start_web_server(self):
        """启动Web服务器"""
        try:
            handler = self.create_web_handler()
            self.web_server = HTTPServer(('0.0.0.0', 8088), handler)
            print(f"Web服务器已启动，访问地址: http://0.0.0.0:8088")
            self.web_server.serve_forever()
        except Exception as e:
            print(f"启动Web服务器失败: {e}")
            traceback.print_exc()