# Редстоун, свои крафты и AllClear()

# 1. Дорожка из редстоун-пыли с лампами
Fill(-2, -1, 4, 16, -1, 4, "stone")
Line(1, 0, 4, 14, 0, 4, "redstone_wire")
SetBlock(15, 0, 4, "redstone_lamp")
SetSignal(0, 0, 4, 15)          # сигнал силы 15 в начале дорожки
wait(10)
print("Сила в начале:", RedstonePower(1, 0, 4))
print("Сила в конце:", RedstonePower(14, 0, 4))
if IsPowered(15, 0, 4):
    print("Лампа горит!")

# 2. Рычаг, который мы переключаем из кода
SetBlock(0, 0, 8, "lever[face=floor]")
SetBlock(1, 0, 8, "redstone_lamp")
for i in range(4):
    state = Toggle(0, 0, 8)
    print("Рычаг:", "вкл" if state else "выкл")
    wait(10)

# 3. Следим за сигналом: ломай пыль или ставь обратно
def changed(power):
    print("Сигнал у лампы теперь", power)
    if power == 0:
        Say("Цепь разорвана!")
WatchRedstone(14, 0, 4, changed)

# 4. Свои рецепты крафта (верстак 3x3)
craft("dirt", "dirt", "dirt",
      "dirt", "diamond", "dirt",
      "dirt", "dirt", "dirt", "diamond_block")
Craft(None, "stick", None,
      None, "stick", None,
      None, "stick", None, "blaze_rod", 2)
CraftShapeless(["sand", "gunpowder"], "tnt", 2)
print("Рецепты добавлены: попробуй их в верстаке")

# 5. Через минуту выключаем все события
After(1200, AllClear)
