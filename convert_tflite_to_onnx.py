import tf2onnx
import tensorflow as tf
import sys

TFLITE_PATH = "dw_delta_416_v26s.tflite"
ONNX_PATH = "dw_delta_416_v26s.onnx"

print(f"Loading {TFLITE_PATH}...")
interpreter = tf.lite.Interpreter(model_path=TFLITE_PATH)
interpreter.allocate_tensors()

print("\n=== Inputs ===")
for i in interpreter.get_input_details():
    print(f"  {i['name']}: shape={i['shape']} dtype={i['dtype']}")

print("\n=== Outputs ===")
for o in interpreter.get_output_details():
    print(f"  {o['name']}: shape={o['shape']} dtype={o['dtype']}")

print(f"\nConverting to {ONNX_PATH}...")
try:
    model_proto, _ = tf2onnx.convert.from_tflite(TFLITE_PATH)
except AttributeError:
    import tf2onnx.tflite
    model_proto, _ = tf2onnx.tflite.tflite_to_onnx(TFLITE_PATH)

with open(ONNX_PATH, "wb") as f:
    f.write(model_proto.SerializeToString())

print(f"✅ Saved to {ONNX_PATH}")
import os
print(f"File size: {os.path.getsize(ONNX_PATH) / 1024 / 1024:.2f} MB")