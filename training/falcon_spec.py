"""
مواصفات سياسة Falcon Pilot — يجب أن يطابقها نموذجك حرفياً.

مدخل النموذج  : float32 [1, 82]   (OBS_DIM)
مخرج النموذج : float32 [1, 9]    (ACT_DIM)
  [0] joyX  [-1,1]  يمين+        [1] joyY [-1,1] أمام+
  [2] aimX  [-1,1]  دوران الكاميرا يمين+ (x ‏0.2*W بكسل كحد أقصى لكل خطوة)
  [3] aimY  [-1,1]  دوران لأسفل+ (x ‏0.1*W)
  [4] fire  >0.5 = نار       [5] jump  [6] reload  [7] heal  [8] crouch  (>0.5 = اضغط)
استخدم tanh للأربعة الأولى و sigmoid للباقي داخل النموذج.

كل الإحداثيات نسبية للمؤشر (crosshair): rx=(x-cx)/W, ry=(y-cy)/H.
في المحاكي (اللعبة التي تبنيها) اصنع نفس الصناديق التي سيعطيها كاشف الأعداء: label, conf, x1,y1,x2,y2 بالبكسل.
الأعداء: الأقرب للمؤشر أولاً. السرعات vx,vy بكسل/ثانية على الشاشة.
"""
import math
import numpy as np

OBS_DIM = 82
ACT_DIM = 9


def _clamp(v, a, b):
    return max(a, min(b, v))


def build_obs(dets, W, H, cx, cy, low_hp=False, stuck=False, mem_age=1.0, mem_rx=0.0, mem_ry=0.0,
              prev_action=(0, 0, 0, 0, 0)):
    """dets: list[dict(label, conf, x1, y1, x2, y2, vx=0, vy=0)]"""
    def cxy(d): return (d["x1"] + d["x2"]) / 2, (d["y1"] + d["y2"]) / 2
    def rx(d): return (cxy(d)[0] - cx) / W
    def ry(d): return (cxy(d)[1] - cy) / H
    def dist(d): return math.hypot(rx(d), ry(d))
    def bw(d): return (d["x2"] - d["x1"]) / W
    def bh(d): return (d["y2"] - d["y1"]) / H

    o = []
    en = sorted([d for d in dets if d["label"] == "enemy"], key=dist)
    for i in range(5):
        if i < len(en):
            d = en[i]
            o += [1, rx(d), ry(d), bw(d), bh(d),
                  _clamp(d.get("vx", 0) / W, -2, 2), _clamp(d.get("vy", 0) / H, -2, 2), d["conf"]]
        else:
            o += [0] * 8
    al = sorted([d for d in dets if d["label"] == "ally"], key=dist)
    for i in range(3):
        o += [1, rx(al[i]), ry(al[i]), bh(al[i])] if i < len(al) else [0] * 4
    for lab in ("medkit", "ammo", "weapon"):
        c = [d for d in dets if d["label"] == lab]
        if c:
            d = min(c, key=dist); o += [1, rx(d), ry(d), bh(d)]
        else:
            o += [0] * 4
    for lab in ("safe_zone", "danger_zone"):
        c = [d for d in dets if d["label"] == lab]
        if c:
            d = max(c, key=lambda e: (e["x2"] - e["x1"]) * (e["y2"] - e["y1"])); o += [1, rx(d), ry(d), bh(d)]
        else:
            o += [0] * 4
    o += [1.0 if low_hp else 0.0, 1.0 if stuck else 0.0, mem_age, mem_rx, mem_ry]
    o += list(prev_action)  # joyX, joyY, aimX, aimY, fire(0/1)
    assert len(o) == OBS_DIM, len(o)
    return np.asarray(o, dtype=np.float32)


def export_keras_to_tflite(model, path="policy.tflite"):
    """model: tf.keras.Model بمدخل (None,82) ومخرج (None,9) — ثم انسخ policy.tflite للهاتف واستورده من التطبيق."""
    import tensorflow as tf
    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    open(path, "wb").write(conv.convert())


def make_example_model():
    import tensorflow as tf
    i = tf.keras.Input(shape=(OBS_DIM,))
    x = tf.keras.layers.Dense(256, activation="relu")(i)
    x = tf.keras.layers.Dense(256, activation="relu")(x)
    move = tf.keras.layers.Dense(4, activation="tanh")(x)
    btn = tf.keras.layers.Dense(5, activation="sigmoid")(x)
    return tf.keras.Model(i, tf.keras.layers.Concatenate()([move, btn]))


if __name__ == "__main__":
    o = build_obs([{"label": "enemy", "conf": .9, "x1": 1100, "y1": 400, "x2": 1200, "y2": 600}], 2400, 1080, 1200, 540)
    print("obs", o.shape, o[:8])
