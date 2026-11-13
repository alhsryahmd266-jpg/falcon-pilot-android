# Falcon Pilot
لاعب آلي أوفلاين: التقاط شاشة حي (MediaProjection) + نموذج YOLO محلي (TFLite) + لمس عبر AccessibilityService. بدون حقن وبدون أدوات خارجية.

## فئات النموذج (labels.txt بنفس ترتيب التدريب)
enemy, ally, medkit, ammo, weapon, safe_zone, danger_zone, low_health, joystick, btn_fire, btn_jump, btn_reload, btn_crouch, btn_heal, ui_tap
- أي فئة تبدأ بـ `ui_tap` تُضغط تلقائياً (جاهز/إعادة/متابعة).
- الأزرار btn_* و joystick تُعلّم التطبيق مكان أزرار اللعبة تلقائياً.
## تصدير النموذج
`yolo export model=best.pt format=tflite imgsz=640` (FP32/FP16) ثم استورده من التطبيق.
