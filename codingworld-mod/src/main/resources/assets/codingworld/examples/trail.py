# Волшебный след: под игроком появляются цветные блоки
# Полетай низко над землёй. Остановить — кнопка ■ Стоп.
colors = ["red_stained_glass", "orange_stained_glass", "yellow_stained_glass",
          "lime_stained_glass", "light_blue_stained_glass", "purple_stained_glass"]
n = 0

def on_player_move(x, y, z):
    global n
    ground = TopY(x, z)
    if y - ground < 6:                 # только если летим низко
        SetBlock(x, ground + 1, z, colors[n % len(colors)])
        Particle(x, ground + 2, z, "end_rod", 5)
        n += 1

def every_second():
    ActionBar(f"Блоков в следе: {n}")

Every(20, every_second)
print("След включён!")
