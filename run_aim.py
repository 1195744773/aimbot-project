import json
import socket
import subprocess
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
PC_CONFIG = SCRIPT_DIR / "config.json"
PHONE_CONFIG_PATH = "/storage/emulated/0/ScreenCapServer/config.json"
YOLO_SCRIPT = SCRIPT_DIR / "yolo_aim.py"
ADB = SCRIPT_DIR / "platform-tools" / "adb.exe"

# Step 1: pull phone config
print("Pulling config from phone...")
subprocess.run([str(ADB), "pull", PHONE_CONFIG_PATH, str(PC_CONFIG)],
               check=False, capture_output=True)

# Step 2: read config
if PC_CONFIG.exists():
    cfg = json.loads(PC_CONFIG.read_text())
else:
    cfg = {}

model_path = cfg.get("model_path", str(SCRIPT_DIR / "yolov8n.onnx"))
crop_size = cfg.get("crop_size", 416)
frame_rate = cfg.get("frame_rate", 30)
print(f"Config loaded: crop={crop_size} fps={frame_rate}")

# Step 3: setup ADB forward (only if not already active)
def forward_active():
    r = subprocess.run([str(ADB), "forward", "--list"], capture_output=True, text=True, check=False)
    return "tcp:3842" in r.stdout

if not forward_active():
    print("Setting up ADB forward...")
    subprocess.run([str(ADB), "forward", "--remove", "tcp:3842"], check=False, capture_output=True)
    subprocess.run([str(ADB), "forward", "tcp:3842", "tcp:3842"], check=False)
else:
    print("ADB forward ready.")

# Step 4: launch yolo_aim (use python.exe, not pythonw.exe, so output is visible)
python = sys.executable.replace("pythonw.exe", "python.exe")
print(f"Launching: {python} {YOLO_SCRIPT} {model_path}")
ret = subprocess.run([python, str(YOLO_SCRIPT), model_path])
print(f"YOLO aim exited (code={ret.returncode})")
sys.exit(ret.returncode)
