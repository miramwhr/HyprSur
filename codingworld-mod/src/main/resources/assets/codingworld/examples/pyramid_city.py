# Фигуры: пирамиды, шары, купола и цилиндры
Pyramid(0, 0, 20, 10, "sandstone")
Pyramid(25, 0, 20, 6, "gold_block", True)       # True — пустая внутри

Sphere(-20, 12, 20, 6, "glass")
HollowSphere(-20, 12, 20, 4, "sea_lantern")

Dome(0, 0, -25, 12, "white_stained_glass")
Disk(0, -1, -25, 12, "quartz_block")

for i in range(4):
    Cylinder(-30 + i * 8, 0, -10, 2, 6 + i * 4, "stone_bricks")

Circle(0, 0, 0, 18, "red_wool")
print("Город фигур готов. Полетай вокруг!")
