#!/usr/bin/env python3
"""
自动超车控制器
"""

import sys
import os
import time
import platform

current_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, current_dir)

def main():
    """主函数"""
    try:
        from auto_overtake_controller import AutoOvertakeController
    except ImportError as e:
        print(f"导入模块失败: {e}")
        print("请确保所有依赖模块都在当前目录中")
        return 1
    
    is_android = hasattr(sys, 'getandroidapilevel') or 'ANDROID_ARGUMENT' in os.environ
    is_chaquopy = 'chaquopy' in sys.modules or 'chaquopy' in str(sys.path)
    
    enable_signal_handling = not (is_android or is_chaquopy)
    
    if enable_signal_handling:
        try:
            import signal
            
            def signal_handler(signum, frame):
                """信号处理函数"""
                print(f"\n接收到信号 {signum}，正在关闭...")
                sys.exit(0)
            
            # 设置信号处理器
            signal.signal(signal.SIGINT, signal_handler)   # Ctrl+C
            signal.signal(signal.SIGTERM, signal_handler)  # 终止信号
            
            print(f"信号处理已启用 (平台: {platform.system()})")
            
        except (ImportError, ValueError, AttributeError) as e:
            print(f"注意: 无法设置信号处理器 ({e})，将使用替代关闭机制")
            enable_signal_handling = False
    else:
        print(f"注意: Android/Chaquopy 环境，禁用信号处理")
    
    controller = None
    try:
        print("=" * 50)
        print("现代汽车自动超车控制器")
        print("=" * 50)
        
        controller = AutoOvertakeController()
        
        controller.start()
        
    except KeyboardInterrupt:
        if enable_signal_handling:
            # 在桌面环境中，通过 Ctrl+C 触发
            print("\n用户中断操作，正在关闭...")
        else:
            print("\n程序被中断，正在关闭...")
    except Exception as e:
        print(f"\n运行错误: {e}")
        import traceback
        traceback.print_exc()
    finally:
        if controller:
            try:
                controller.stop()
            except Exception as e:
                print(f"关闭控制器时出错: {e}")
        
        print("\n程序已退出")
        return 0

if __name__ == "__main__":
    sys.exit(main())