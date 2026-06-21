import json
import queue
import socket
import struct
import sys
import threading
import time
from collections import deque
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ADB_PORT = 3842
CONFIG_FILE = Path(__file__).parent / "config.json"

_config = json.loads(CONFIG_FILE.read_text()) if CONFIG_FILE.exists() else {}
CROP_SIZE = _config.get("crop_size", 416)
MODEL_INPUT = 640
HC = CROP_SIZE // 2
TARGET_CLASSES = [0]
CONF_THRESH = _config.get("conf_threshold", 0.5)
IOU_THRESH = _config.get("iou_threshold", 0.45)
DETECT_BODY = _config.get("detect_body", True)
DETECT_HEAD = _config.get("detect_head", True)
KPT_CONF_THRESH = 0.3
LINE_THICKNESS = _config.get("line_thickness", 2)

def rgba_to_bgr(data):
    img = np.frombuffer(data, dtype=np.uint8).reshape(CROP_SIZE, CROP_SIZE, 4)
    return cv2.cvtColor(img, cv2.COLOR_RGBA2BGR)

def preprocess(img):
    img = cv2.resize(img, (MODEL_INPUT, MODEL_INPUT))
    img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    img = img.astype(np.float32) / 255.0
    img = np.transpose(img, (2, 0, 1))
    return np.expand_dims(img, axis=0)

def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))

def nms(boxes, scores, iou_thresh):
    if len(boxes) == 0:
        return []
    x1 = boxes[:, 0]
    y1 = boxes[:, 1]
    x2 = boxes[:, 2]
    y2 = boxes[:, 3]
    areas = (x2 - x1) * (y2 - y1)
    order = scores.argsort()[::-1]
    keep = []
    while len(order) > 0:
        i = order[0]
        keep.append(i)
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        w = np.maximum(0.0, xx2 - xx1)
        h = np.maximum(0.0, yy2 - yy1)
        inter = w * h
        ovr = inter / (areas[i] + areas[order[1:]] - inter)
        inds = np.where(ovr <= iou_thresh)[0]
        order = order[inds + 1]
    return keep

def postprocess_det(output, conf_thresh, iou_thresh, img_w, img_h, model_input=416):
    output = output.squeeze()
    cx, cy, w, h = output[:4]
    cls_logits = output[4:]
    cls_scores = sigmoid(cls_logits)
    max_scores = cls_scores.max(axis=0)
    max_ids = cls_scores.argmax(axis=0)
    scale_x = img_w / model_input
    scale_y = img_h / model_input
    x1 = ((cx - w / 2) * scale_x).clip(0, img_w)
    y1 = ((cy - h / 2) * scale_y).clip(0, img_h)
    x2 = ((cx + w / 2) * scale_x).clip(0, img_w)
    y2 = ((cy + h / 2) * scale_y).clip(0, img_h)
    mask = max_scores > conf_thresh
    boxes_arr = np.stack([x1, y1, x2, y2], axis=1)[mask]
    scores_arr = max_scores[mask]
    ids_arr = max_ids[mask]
    if len(boxes_arr) == 0:
        return [], [], []
    keep = nms(boxes_arr, scores_arr, iou_thresh)
    return boxes_arr[keep], scores_arr[keep], ids_arr[keep]

def postprocess_pose(output, conf_thresh, iou_thresh, img_w, img_h, model_input=640):
    output = output.squeeze()
    cx, cy, w, h = output[:4]
    cls_scores = sigmoid(output[4])
    kpts_all = output[5:].reshape(17, 3, -1)
    kpts_xy = kpts_all[:, :2, :]
    kpt_conf = kpts_all[:, 2, :]
    scale = img_w / model_input
    x1 = ((cx - w / 2) * scale).clip(0, img_w)
    y1 = ((cy - h / 2) * scale).clip(0, img_h)
    x2 = ((cx + w / 2) * scale).clip(0, img_w)
    y2 = ((cy + h / 2) * scale).clip(0, img_h)
    mask = cls_scores > conf_thresh
    if not mask.any():
        return [], [], [], []
    boxes_arr = np.stack([x1, y1, x2, y2], axis=1)[mask]
    scores_arr = cls_scores[mask]
    kpts_arr = kpts_xy[:, :, mask].transpose(2, 0, 1) * scale
    kptc_arr = kpt_conf[:, mask].transpose(1, 0)
    keep = nms(boxes_arr, scores_arr, iou_thresh)
    return boxes_arr[keep], scores_arr[keep], kpts_arr[keep], kptc_arr[keep]

def extract_args():
    show = '--no-show' not in sys.argv
    others = [a for a in sys.argv[1:] if not a.startswith('--')]
    model_path = others[0] if others else str(Path(__file__).parent / "yolov8n-pose.onnx")
    return model_path, show

def load_local_config():
    global CROP_SIZE, HC
    try:
        if CONFIG_FILE.exists():
            c = json.loads(CONFIG_FILE.read_text())
            CROP_SIZE = c.get("crop_size", CROP_SIZE)
            HC = CROP_SIZE // 2
            print(f"Config loaded: crop_size={CROP_SIZE}")
    except Exception as e:
        print(f"Read config failed (use defaults): {e}")

def recv_exact(sock, n, ev):
    buf = b""
    while len(buf) < n:
        if ev.is_set():
            return None
        try:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        except socket.timeout:
            continue
    return buf

def main():
    model_path, show = extract_args()
    load_local_config()
    print(f"Loading {model_path}...")
    if not Path(model_path).exists():
        print(f"Model not found: {model_path}")
        sys.exit(1)
    providers = ['DmlExecutionProvider', 'CPUExecutionProvider']
    if 'DmlExecutionProvider' not in ort.get_available_providers():
        providers = ['CPUExecutionProvider']
    sess = ort.InferenceSession(model_path, providers=providers)
    input_name = sess.get_inputs()[0].name
    input_shape = sess.get_inputs()[0].shape
    output_shape = sess.get_outputs()[0].shape
    global MODEL_INPUT
    MODEL_INPUT = input_shape[2]
    is_pose = output_shape[1] == 56
    print(f"Model loaded, using {sess.get_providers()[0]} input={MODEL_INPUT}x{MODEL_INPUT} {'pose' if is_pose else 'detect'}")

    frame_cv = threading.Condition()
    frame_queue = deque(maxlen=2)
    cmd_queue = queue.Queue()
    exit_event = threading.Event()
    aim_count = 0

    display_img = None
    display_lock = threading.Lock()

    if show:
        with display_lock:
            display_img = np.zeros((CROP_SIZE, CROP_SIZE, 3), dtype=np.uint8)
            cv2.putText(display_img, "Connecting...", (10, CROP_SIZE // 2),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 1)

        def display_loop():
            cv2.namedWindow("ScreenCap Aim")
            try:
                while not exit_event.is_set():
                    with display_lock:
                        img = display_img
                    cv2.imshow("ScreenCap Aim", img)
                    key = cv2.waitKey(1) & 0xFF
                    if key == ord('q'):
                        exit_event.set()
                        break
            except Exception as e:
                print(f"Display error: {e}")
            finally:
                cv2.destroyAllWindows()
        threading.Thread(target=display_loop, daemon=True).start()

    def receiver_loop():
        rc = 0
        er = 0
        while not exit_event.is_set():
            while not exit_event.is_set():
                try:
                    sock = socket.socket()
                    sock.settimeout(10)
                    sock.connect(("127.0.0.1", ADB_PORT))
                    break
                except ConnectionRefusedError:
                    print("Waiting for server...")
                    time.sleep(1)
            if exit_event.is_set():
                return

            sock.settimeout(0.05)
            print(f"Receiver connected! crop={CROP_SIZE}")

            frames_received = 0
            last_server_ts = 0
            try:
                while not exit_event.is_set():
                    try:
                        cmd = cmd_queue.get_nowait()
                        sock.sendall(cmd.encode())
                    except queue.Empty:
                        pass

                    try:
                        size_buf = recv_exact(sock, 4, exit_event)
                        if not size_buf or exit_event.is_set():
                            raise ConnectionError("disconnected")
                        frame_size = struct.unpack(">I", size_buf)[0]

                        ts_buf = recv_exact(sock, 8, exit_event)
                        if not ts_buf or exit_event.is_set():
                            raise ConnectionError("disconnected")
                        server_ts_ns = struct.unpack(">Q", ts_buf)[0]

                        data = recv_exact(sock, frame_size, exit_event)
                        if not data or exit_event.is_set():
                            raise ConnectionError("disconnected")

                        with frame_cv:
                            frame_queue.append(data)
                            frame_cv.notify()

                        if last_server_ts != 0:
                            server_delta_ms = (server_ts_ns - last_server_ts) / 1_000_000
                            if server_delta_ms > 80:
                                print(f"LARGE GAP: server={server_delta_ms:.0f}ms")
                        last_server_ts = server_ts_ns
                        frames_received += 1

                    except socket.timeout:
                        continue

            except (ConnectionError, OSError) as e:
                print(f"Receiver: {e}")
            finally:
                sock.close()
                rc += 1
                er = 0 if frames_received > 0 else er + 1
                print(f"Disconnected (reconnect #{rc}, frames={frames_received})")
                if er >= 3:
                    print("Server stopped, exiting.")
                    exit_event.set()
                time.sleep(0.5)

    threading.Thread(target=receiver_loop, daemon=True).start()

    fps_timer = time.perf_counter()
    fps_counter = 0
    fps_display = 0
    printed = False

    while not exit_event.is_set():
        data = None
        with frame_cv:
            while not frame_queue and not exit_event.is_set():
                frame_cv.wait(timeout=2.0)
            if exit_event.is_set():
                break
            if frame_queue:
                data = frame_queue.pop()
                frame_queue.clear()

        if data is None:
            continue

        img = rgba_to_bgr(data)
        input_blob = preprocess(img)
        output = sess.run(None, {input_name: input_blob})[0]
        img_h, img_w = img.shape[:2]
        if is_pose:
            boxes, scores, kpts, kptc = postprocess_pose(output, CONF_THRESH, IOU_THRESH, img_w, img_h)
        else:
            boxes, scores, class_ids = postprocess_det(output, CONF_THRESH, IOU_THRESH, img_w, img_h, MODEL_INPUT)
            kpts = np.zeros((len(boxes), 17, 2))
            kptc = np.zeros((len(boxes), 17))

        fps_counter += 1
        if time.perf_counter() - fps_timer >= 1.0:
            fps_display = fps_counter
            fps_counter = 0
            fps_timer = time.perf_counter()

        if show and not exit_event.is_set():
            if is_pose:
                for box, kpt, kptc_i in zip(boxes, kpts, kptc):
                    x1, y1, x2, y2 = map(int, box)
                    cv2.rectangle(img, (x1, y1), (x2, y2), (0, 255, 0), LINE_THICKNESS)
                    for (kx, ky), kc in zip(kpt, kptc_i):
                        if kc > KPT_CONF_THRESH:
                            cv2.circle(img, (int(kx), int(ky)), LINE_THICKNESS, (0, 0, 255), -1)
                    nose = kpt[0]
                    cv2.putText(img, f"nose=({int(nose[0])},{int(nose[1])})", (x1, y1 - 4),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 255), 1)
            else:
                for box, cls, conf in zip(boxes, class_ids, scores):
                    if int(cls) not in TARGET_CLASSES:
                        continue
                    x1, y1, x2, y2 = map(int, box)
                    cv2.rectangle(img, (x1, y1), (x2, y2), (0, 255, 0), LINE_THICKNESS)
                    cv2.putText(img, f"person:{conf:.2f}", (x1, y1 - 4),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 0), 1)
            cv2.putText(img, f"FPS:{fps_display} det:{len(boxes)}", (5, 20),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)
            with display_lock:
                display_img = img

        if len(boxes) > 0:
            aim_points = []
            if is_pose:
                for kpt, kptc_i in zip(kpts, kptc):
                    if DETECT_HEAD and kptc_i[0] > KPT_CONF_THRESH:
                        aim_points.append((int(kpt[0, 0]), int(kpt[0, 1])))
                    if DETECT_BODY:
                        ok = kptc_i[5] > KPT_CONF_THRESH and kptc_i[6] > KPT_CONF_THRESH
                        cx_b = int((kpt[5, 0] + kpt[6, 0]) / 2) if ok else 0
                        cy_b = int((kpt[5, 1] + kpt[6, 1]) / 2) if ok else 0
                        if cx_b > 0 and cy_b > 0:
                            aim_points.append((cx_b, cy_b))
            else:
                for box, cls, conf in zip(boxes, class_ids, scores):
                    if int(cls) not in TARGET_CLASSES:
                        continue
                    x1, y1, x2, y2 = map(int, box)
                    if DETECT_BODY:
                        aim_points.append(((x1 + x2) // 2, (y1 + y2) // 2))
                    if DETECT_HEAD:
                        aim_points.append(((x1 + x2) // 2, y1 + int((y2 - y1) * 0.15)))

            if aim_points:
                cx = CROP_SIZE // 2
                cy = CROP_SIZE // 2
                aim_point = min(aim_points, key=lambda p: (p[0] - cx)**2 + (p[1] - cy)**2)
                cmd_queue.put(f"{aim_point[0]} {aim_point[1]}\n")
                aim_count += 1
                if not printed:
                    print(f"First aim! target=({aim_point[0]},{aim_point[1]})")
                    printed = True


if __name__ == "__main__":
    main()
