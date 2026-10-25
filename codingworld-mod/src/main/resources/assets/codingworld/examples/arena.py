# Арена: волны мобов, каждая сильнее предыдущей
cx, cz = 0, 20
Disk(cx, -1, cz, 12, "polished_andesite")
Circle(cx, 0, cz, 12, "iron_bars")
Circle(cx, 1, cz, 12, "iron_bars")

waves = [("zombie", 3), ("skeleton", 4), ("spider", 5), ("husk", 6)]

for number, (mob, count) in enumerate(waves, start=1):
    Title(f"Волна {number}", f"{count} x {mob}")
    ids = []
    for i in range(count):
        ids.append(SummonMob(cx + Random(-8, 8), 0, cz + Random(-8, 8), mob, ["glowing"]))
    # ждём, пока вся волна не погибнет (или 30 секунд)
    WaitUntil(lambda: not any(MobExists(m) for m in ids), 20 * 30)
    alive = [m for m in ids if MobExists(m)]
    if alive:
        print("Волна", number, "не побеждена, убираем:", len(alive))
        for m in alive:
            KillMob(m)
    else:
        print("Волна", number, "побеждена!")
    wait(40)

Title("Арена пройдена!")
# Совет: Gamemode("survival") и Give("diamond_sword"), чтобы сражаться самому
