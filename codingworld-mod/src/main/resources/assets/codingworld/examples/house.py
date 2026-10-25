# Деревня: строим несколько домиков функцией House
# House(x, y, z, ширина, глубина, высота, стены, крыша)

House(0, 0, 0)                                          # обычный домик 7x7
House(10, 0, 0, 9, 7, 5, "stone_bricks", "dark_oak_planks")
House(22, 0, 0, 7, 9, 4, "birch_planks", "red_nether_bricks")

# Дорожка перед домами
Fill(-2, -1, 10, 30, -1, 11, "dirt_path")

# Деревья за домами
for i in range(5):
    Tree(i * 7, 0, -6, Choice(["oak", "birch", "cherry"]))

# Фонари вдоль дороги
for x in range(-2, 31, 6):
    SetBlock(x, 0, 13, "oak_fence")
    SetBlock(x, 1, 13, "lantern")

print("Деревня построена!")
