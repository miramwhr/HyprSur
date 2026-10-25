# Следим за игроком: переменные PlayerX, PlayerY, PlayerZ обновляются сами
# Полетай вокруг (Esc — закрыть редактор, K — открыть снова). Стоп — кнопка ■.

print("Старт:", PlayerX, PlayerY, PlayerZ)
last_zone = ""

while True:
    if PlayerY > 30:
        zone = "небо"
    elif PlayerY < 0:
        zone = "под землёй"
    elif PlayerX == 25:
        zone = "ровно на X = 25!"
    elif PlayerInArea(0, 0, 0, 10, 10, 10):
        zone = "в кубе 10x10"
    else:
        zone = "на земле"

    if zone != last_zone:
        ActionBar("Ты: " + zone + f"  ({PlayerX}, {PlayerY}, {PlayerZ})")
        print("Зона:", zone)
        last_zone = zone

    if LookBlock == "diamond_block":
        ActionBar("Ты смотришь на алмаз!")
    wait(10)
