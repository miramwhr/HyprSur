# Мини-игра: найди алмаз в лабиринте!
Clear(0, 0, 0, 40, 4, 40)
Maze(0, 0, 0, 12, 12, 3, "hedge")
Fill(0, -1, 0, 24, -1, 24, "moss_block")

# Награда в дальнем углу
SetBlock(23, 0, 23, "diamond_block")
Particle(23, 1, 23, "happy_villager", 40)

Gamemode("adventure")
Teleport(1, 0, -1)
Look(0, 0)
Title("Лабиринт", "Дойди до алмаза!")
start = Ticks()

WaitUntil(lambda: PlayerNear(23, 0, 23, 2))
seconds = (Ticks() - start) // 20
Title("Победа!", f"Время: {seconds} сек")
Firework(23, 1, 23, "lime")
Give("diamond", 3)
print("Лабиринт пройден за", seconds, "секунд")
wait(60)
Gamemode("spectator")
