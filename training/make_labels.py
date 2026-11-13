"""يقرأ data.yaml من داتاسيت Roboflow ويكتب labels.txt بنفس ترتيب التدريب.
الاستخدام:  python make_labels.py data.yaml
يخمّن الأدوار من الأسماء؛ عدّل أي سطر بصيغة  اسمك=الدور  (enemy, ally, medkit, ammo, weapon, safe_zone, danger_zone, low_health)"""
import sys, re
try:
    import yaml
    names = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))["names"]
    if isinstance(names, dict):
        names = [names[k] for k in sorted(names, key=int)]
except Exception:
    txt = open(sys.argv[1], encoding="utf-8").read()
    m = re.search(r"names\s*:\s*\[(.*?)\]", txt, re.S)
    names = [x.strip().strip("'\"") for x in m.group(1).split(",")]
with open("labels.txt", "w", encoding="utf-8") as f:
    for n in names:
        f.write(n + "\n")
print("classes:", names)
print("labels.txt written. التطبيق يخمّن الأدوار تلقائياً؛ للتحكم اكتب:  اسمك=enemy")
