# Спавн мобов по условию: if / elif / else

# Ночью — зомби, днём — коровы
if IsNight:
    mob = "zombie"
else:
    mob = "cow"
print("Сейчас", "ночь" if IsNight else "день", "-> призываем", mob)

for i in range(5):
    SummonMob(i * 2, 0, 6, mob)

# Если мобов слишком много — убираем лишних
if CountMobs() > 20:
    KillMobs("all")
    print("Слишком много мобов, всех убрали")
elif CountMobs(mob) >= 5:
    print("Мобов", mob, "хватает:", CountMobs(mob))
else:
    print("Мобов мало")

# Моб с опциями: подсветка, без ИИ (статуя), имя и броня
boss = SummonMob(0, 0, 10, "zombie", {"name": "Босс", "glowing": True, "noai": True,
                                       "health": 80, "armor": "diamond", "item": "diamond_sword"})
print("У босса", MobHealth(boss), "здоровья")

# Если игрок близко к боссу — оживить его
WaitUntil(lambda: DistanceToPlayer(0, 0, 10) < 8, 20 * 30)
if MobExists(boss):
    SetMobAI(boss, True)
    Title("Босс проснулся!", "Беги!")
