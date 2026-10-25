# Кодинг-мир (Coding World) — мод для Minecraft 1.20.1 (Forge 47.x)

Новый тип мира **«Кодинг»**: вы в режиме наблюдателя, слева редактор кода на Python, справа консоль, по центру живой мир.
Пишете код — и мир меняется: блоки, постройки, мобы, погода, время, игрок, события.

## Установка
1. Установите Forge **47.x для Minecraft 1.20.1** (forge-1.20.1-47.3.0 или новее).
2. Положите `codingworld-1.20.1-1.0.0.jar` в папку `.minecraft/mods`.
3. Запустите игру → Одиночная игра → Создать новый мир → вкладка **«Мир»** → **Тип мира: Кодинг** → Создать.

## Управление
- Редактор открывается сам при входе в Кодинг-мир. **K** — открыть, **Esc** — закрыть (чтобы полетать).
- **Ctrl+Enter** или **F5** — запустить код, кнопка **■ Стоп** — остановить.
- **Tab** — отступ или выбор подсказки автодополнения, **Shift+Tab** — убрать отступ.
- **Ctrl+Z / Ctrl+Y** — отмена / повтор, **Ctrl+/** — закомментировать, **Ctrl+D** — дублировать строку, **Ctrl+S** — сохранить.
- Тяните мышью по миру в центре — камера поворачивается.
- **Примеры ▼** — 14 готовых программ. **Справка** — все функции с описанием, клик вставляет пример.
- Код сохраняется в `.minecraft/codingworld/main.py`.
- Ошибки показываются в консоли с номером строки, строка подсвечивается красным, есть подсказки «Может быть, SetBlock?».

Земля в Кодинг-мире на высоте **Y = 0** (первый блок над травой ставится на Y = 0).

## Python
Встроенный интерпретатор Python (написан внутри мода, ничего ставить не нужно):
`if / elif / else`, `for`, `while`, `break / continue`, `def` (аргументы по умолчанию, `*args`, `**kwargs`), `return`, `lambda`,
`global / nonlocal`, списки, словари, кортежи, множества, срезы, списковые включения, f-строки, `format`, `%`,
`try / except / finally / raise`, `import math / random / time`, `print`, `len`, `range`, `sorted`, `sum`, `min`, `max`, `enumerate`, `zip`, `map`, `filter` и др.
Код выполняется в отдельном потоке и не подвешивает игру; `wait(тики)` делает паузу, пока мир живёт.

## Функции мира (117) и переменные (35)
Блоки, фигуры (Sphere, Dome, Pyramid, House, Tower, Maze, Tree...), мобы (SummonMob с опциями, MobWalkTo, PushMob...),
игрок (Teleport, Give, Effect, Gamemode...), мир (SetTime, SetWeather, Gamerule, Command...), эффекты (Explosion, Lightning, Particle, Firework),
сообщения (Say, Title, ActionBar), ожидание (wait, WaitUntil), события (`on_chat`, `on_tick`, `on_block_break`, `on_mob_death`, `on_player_move`, `on_key`, Every, After).
Переменные обновляются сами: `PlayerX`, `PlayerY`, `PlayerZ` (и `Xplayer`...), `PlayerHealth`, `Time`, `IsNight`, `Weather`, `MobCount`, `LookBlock`...

Полный список — во встроенной «Справке», через `Help()` в коде или на сайте мода.

```python
if Xplayer == 25:
    Say("Ты на X = 25!")

for y in range(20):
    SetBlock(0, y, 0, "gold_block")
    wait(2)

zombie = SummonMob(5, 0, 5, "zombie", ["glowing", "noai"])
```

## Сетевая игра
На сервере код могут запускать только операторы (OP). В одиночной игре — всегда.

## Сборка
Java 17+ (для компиляции нужен JDK 17), доступ к maven.minecraftforge.net:
```
gradle build
```
Готовый jar: `build/libs/codingworld-1.20.1-1.0.0.jar`.
