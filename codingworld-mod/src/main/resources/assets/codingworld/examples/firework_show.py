# Салют: ставим ночь и запускаем фейерверки по кругу
import math

SetTime("night")
colors = ["red", "orange", "yellow", "lime", "light_blue", "purple", "pink", "white"]

for wave in range(4):
    for i in range(12):
        a = i / 12 * 2 * math.pi
        x = round(15 * math.cos(a))
        z = round(15 * math.sin(a)) + 15
        Firework(x, 0, z, colors[(i + wave) % len(colors)], Random(1, 2))
        wait(3)
    Sound("entity.firework_rocket.twinkle")
    wait(20)

Title("С праздником!", "Кодинг-мир")
WaitSeconds(3)
SetTime("day")
