#!/usr/bin/env python3
"""
手机画面中心裁剪视频流 - scrcpy + ffmpeg + OpenCV
自动适应屏幕方向与分辨率，通过 config.json 配置
"""

import json
import subprocess
import sys
import os
import re
import signal
import time
import atexit
import threading
from pathlib import Path

import cv2
import numpy as np

ADB = "adb"

CONFIG_PATH = Path(__file__).parent / "config.json"
DEFAULT_CONFIG = {
    "crop_size": 600,
    "fps": 15,
    "bitrate": "8M",
    "display": True,
    "save_path": "",
    "show_fps": True,
    "scrcpy_path": "",
    "ffmpeg_path": "",
    "adb_path": "",
}

# ---- helpers ----------------------------------------------------------------

def load_config(path):
    if path.exists():
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
        return {**DEFAULT_CONFIG, **cfg}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(DEFAULT_CONFIG, f, indent=2, ensure_ascii=False)
    return dict(DEFAULT_CONFIG)


def adb_cmd(*args):
    r = subprocess.run([ADB] + list(args), capture_output=True)
    r.stdout = r.stdout.decode("utf-8", errors="replace")
    r.stderr = r.stderr.decode("utf-8", errors="replace")
    return r


def check_device():
    r = adb_cmd("devices")
    for line in r.stdout.strip().splitlines()[1:]:
        if line.strip() and "device" in line and "offline" not in line:
            return True
    return False


def get_rotation():
    r = adb_cmd("shell", "wm", "rotation")
    if r.returncode == 0 and r.stdout.strip().isdigit():
        return int(r.stdout.strip())

    r = adb_cmd("shell", "dumpsys", "window")
    m = re.search(r"mCurrentRotation[=:](\d)", r.stdout)
    if m:
        return int(m.group(1))

    r = adb_cmd("shell", "dumpsys", "input")
    m = re.search(r"SurfaceOrientation[=:](\d)", r.stdout)
    if m:
        return int(m.group(1))

    return 0


def get_display_info():
    if not check_device():
        raise RuntimeError("No device connected. Run 'adb devices' to check.")

    r = adb_cmd("shell", "wm", "size")
    m = re.search(r"(\d+)x(\d+)", r.stdout)
    if not m:
        raise RuntimeError("Cannot parse screen size: " + r.stdout)
    w, h = int(m.group(1)), int(m.group(2))

    rotation = get_rotation()
    return w, h, rotation


def kill_procs(*procs):
    for p in procs:
        if p and p.poll() is None:
            p.terminate()
            try:
                p.wait(timeout=3)
            except subprocess.TimeoutExpired:
                p.kill()


# ---- main -------------------------------------------------------------------

def main():
    cfg = load_config(CONFIG_PATH)
    crop_size = cfg["crop_size"]
    fps = cfg["fps"]
    bitrate = cfg["bitrate"]
    do_display = cfg["display"]
    save_path = cfg.get("save_path", "")
    show_fps = cfg.get("show_fps", True)
    scrcpy_path = cfg.get("scrcpy_path", "") or "scrcpy"
    ffmpeg_path = cfg.get("ffmpeg_path", "") or "ffmpeg"
    global ADB
    ADB = cfg.get("adb_path", "") or "adb"

    # 1. 获取屏幕信息
    print("📱 获取屏幕信息...")
    try:
        w, h, rot = get_display_info()
    except Exception as e:
        print(f"❌ {e}")
        print("   adb devices 是否检测到设备？")
        sys.exit(1)

    # scrcpy 的 --crop 坐标基于当前显示方向
    disp_w, disp_h = w, h
    max_crop = min(disp_w, disp_h)
    if crop_size > max_crop:
        print(f"⚠️  crop_size({crop_size}) 超出屏幕较小边({max_crop})，自动调整为 {max_crop}")
        crop_size = max_crop

    crop_x = (disp_w - crop_size) // 2
    crop_y = (disp_h - crop_size) // 2

    print(f"📺  分辨率: {disp_w}x{disp_h}, 旋转: {rot}°")
    print(f"✂️  裁剪: {crop_size}x{crop_size} @ ({crop_x},{crop_y})")
    print(f"⚡  帧率: {fps}, 码率: {bitrate}")

    # 2. 启动 scrcpy（仅流，不显示窗口）
    scrcpy_cmd = [
        scrcpy_path,
        "-n", "--no-audio",
        "--record", "-",
        "--record-format", "mkv",
        "--crop", f"{crop_size}:{crop_size}:{crop_x}:{crop_y}",
        "--max-fps", str(fps),
        "--video-bit-rate", bitrate,
    ]

    try:
        scrcpy_proc = subprocess.Popen(
            scrcpy_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except FileNotFoundError:
        print("❌ 未找到 scrcpy，请将其添加到 PATH")
        sys.exit(1)

    # 3. ffmpeg 解码 H.264 → raw BGR 帧
    ffmpeg_cmd = [
        ffmpeg_path,
        "-loglevel", "error",
        "-fflags", "nobuffer",
        "-flags", "low_delay",
        "-analyzeduration", "0",
        "-probesize", "32",
        "-f", "matroska", "-i", "pipe:0",
        "-f", "rawvideo",
        "-pix_fmt", "bgr24",
        "-s", f"{crop_size}x{crop_size}",
        "pipe:1",
    ]

    try:
        ffmpeg_proc = subprocess.Popen(
            ffmpeg_cmd,
            stdin=scrcpy_proc.stdout,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except FileNotFoundError:
        kill_procs(scrcpy_proc)
        print("❌ 未找到 ffmpeg，请将其添加到 PATH")
        sys.exit(1)

    scrcpy_proc.stdout.close()

    # 4. 可选 - 保存视频
    writer = None
    if save_path:
        save_path = Path(save_path)
        save_path.parent.mkdir(parents=True, exist_ok=True)
        fourcc = cv2.VideoWriter.fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(save_path), fourcc, fps, (crop_size, crop_size))

    # 5. 读取帧 → OpenCV 显示
    frame_size = crop_size * crop_size * 3
    procs = [scrcpy_proc, ffmpeg_proc]
    atexit.register(lambda: kill_procs(*procs))
    signal.signal(signal.SIGINT, lambda s, f: (kill_procs(*procs), sys.exit(0)))

    print("▶️  开始推流... (按 q 退出)")

    # 读取 stderr 用于调试
    def log_stderr(name, stream):
        for line in iter(stream.readline, b""):
            text = line.decode("utf-8", errors="replace").rstrip()
            if text and "warning" not in text.lower():
                print(f"  [{name}] {text}")
    t1 = threading.Thread(target=log_stderr, args=("scrcpy", scrcpy_proc.stderr), daemon=True)
    t2 = threading.Thread(target=log_stderr, args=("ffmpeg", ffmpeg_proc.stderr), daemon=True)
    t1.start(); t2.start()

    fps_timer = time.perf_counter()
    fps_count = 0
    fps_display = "?"
    frame_count = 0

    try:
        while True:
            raw = ffmpeg_proc.stdout.read(frame_size)
            if not raw or len(raw) < frame_size:
                scrcpy_ret = scrcpy_proc.poll()
                ffmpeg_ret = ffmpeg_proc.poll()
                print(f"⚠️  流已断开 (scrcpy={scrcpy_ret}, ffmpeg={ffmpeg_ret})")
                break

            img = np.frombuffer(raw, dtype=np.uint8).reshape((crop_size, crop_size, 3))

            if show_fps and do_display:
                frame_count += 1
                now = time.perf_counter()
                if now - fps_timer >= 1.0:
                    fps_display = str(frame_count)
                    frame_count = 0
                    fps_timer = now
                cv2.putText(img, f"{fps_display} FPS", (8, 28),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 200, 0), 2)

            if writer:
                writer.write(img)

            if do_display:
                cv2.imshow("Phone Stream", img)
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    break

    except KeyboardInterrupt:
        pass
    finally:
        kill_procs(*procs)
        if writer:
            writer.release()
        cv2.destroyAllWindows()
        print("\n⏹️  推流结束")


if __name__ == "__main__":
    main()
