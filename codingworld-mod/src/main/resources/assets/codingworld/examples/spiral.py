# Спиральная лестница вокруг столба — немного математики (sin и cos)
import math

cx, cz = 0, 12
radius = 5
steps = 120

Cylinder(cx, 0, cz, 1, steps // 4 + 2, "quartz_pillar")

for i in range(steps):
    angle = i * 0.25                          # угол в радианах
    x = cx + round(radius * math.cos(angle))
    z = cz + round(radius * math.sin(angle))
    y = i // 4
    SetBlock(x, y, z, "smooth_quartz")
    if i % 10 == 0:
        SetBlock(x, y + 1, z, "torch")
    wait(1)

print("Лестница из", steps, "ступенек готова")
