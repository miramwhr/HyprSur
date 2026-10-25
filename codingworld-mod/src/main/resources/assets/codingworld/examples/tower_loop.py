# Радужная башня: цикл for меняет цвет каждого этажа
colors = ["red", "orange", "yellow", "lime", "light_blue", "blue", "purple", "magenta"]

x, z = 0, 5
height = 32

for y in range(height):
    color = colors[y % len(colors)]          # % — остаток: цвета повторяются по кругу
    Walls(x, y, z, x + 4, y, z + 4, color + "_concrete")
    if y % 4 == 3:
        Particle(x + 2, y, z + 2, "happy_villager", 15)
    wait(2)

# Крыша и маяк наверху
Fill(x, height, z, x + 4, height, z + 4, "glass")
SetBlock(x + 2, height + 1, z + 2, "beacon")
Firework(x + 2, height + 2, z + 2, "yellow")
print("Башня высотой", height, "готова!")
