# Свои функции, списки, словари и обработка ошибок

def column(x, z, height, block="stone"):
    """Ставит столб и возвращает его высоту"""
    for y in range(height):
        SetBlock(x, y, z, block)
    return height

def staircase(x, z, n):
    total = 0
    for i in range(n):
        total += column(x + i, z, i + 1, "bricks")
    return total

blocks = staircase(0, 8, 8)
print("Использовано блоков:", blocks)

# Список координат и цикл по нему
points = [(0, 12), (4, 12), (8, 12), (12, 12)]
for i, (x, z) in enumerate(points):
    column(x, z, 3 + i * 2, "oak_log")
    SetBlock(x, 3 + i * 2, z, "glowstone")

# Словарь: блок -> сколько штук
counts = {}
for b in ["stone", "dirt", "stone", "gold_block", "stone"]:
    counts[b] = counts.get(b, 0) + 1
print(counts)

# Списковое включение (list comprehension)
squares = [n * n for n in range(1, 11)]
print("Квадраты:", squares)

# try / except: ошибки не ломают программу
try:
    SetBlock(0, 0, 0, "несуществующий_блок")
except WorldError as e:
    print("Поймали ошибку:", e)

print(f"Сумма квадратов = {sum(squares)}, максимум = {max(squares)}")
