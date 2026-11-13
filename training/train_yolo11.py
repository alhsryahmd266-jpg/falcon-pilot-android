"""تدريب YOLO11 على داتاسيت Roboflow ثم تصدير TFLite للهاتف.
pip install ultralytics   (يُفضّل Colab/GPU)
python train_yolo11.py path/to/data.yaml"""
import sys
from ultralytics import YOLO
data = sys.argv[1]
m = YOLO("yolo11s.pt")
m.train(data=data, imgsz=640, epochs=120, batch=16, patience=30)
best = YOLO(m.trainer.best)
best.export(format="tflite", imgsz=640)   # FP32/FP16 — لا تستخدم int8 (غير مدعوم في التطبيق)
print("خذ ملف best_saved_model/best_float32.tflite (أو float16) وأعد تسميته إن شئت، ثم استورده من التطبيق (3)")
