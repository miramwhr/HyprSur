# Зоопарк: функция строит загон и заселяет животных
def pen(x, z, animal, count):
    Fill(x, -1, z, x + 6, -1, z + 6, "grass_block")
    Walls(x, 0, z, x + 6, 0, z + 6, "oak_fence")
    SetBlock(x + 3, 0, z, "oak_fence_gate")
    for i in range(count):
        SummonMob(x + 1 + i % 5, 0, z + 2 + i // 5, animal)
    SetBlock(x + 3, 1, z - 1, "oak_sign")

animals = {"cow": 3, "pig": 4, "sheep": 5, "chicken": 6, "rabbit": 4, "fox": 2}

x = 0
for animal, count in animals.items():
    pen(x, 5, animal, count)
    print("Загон:", animal, "x", count)
    x += 9

print("Всего животных:", sum(animals.values()))
