package com.miramwh.codingworld.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Descriptions of every world function and live variable. Used for argument checking,
 * the in-game help panel, autocompletion and the website.
 */
public final class ApiDocs {
    private ApiDocs() {}

    public record Fn(String category, String name, String params, String desc, String example) {
        public String signature() {
            return name + "(" + params + ")";
        }
    }

    public record Var(String name, String desc) {}

    public static final List<Fn> FUNCTIONS = new ArrayList<>();
    public static final Map<String, Fn> BY_NAME = new LinkedHashMap<>();
    public static final List<Var> VARIABLES = new ArrayList<>();
    public static final List<String[]> EVENTS = new ArrayList<>();

    private static String cat;

    private static void c(String category) {
        cat = category;
    }

    private static void f(String name, String params, String desc, String example) {
        Fn fn = new Fn(cat, name, params, desc, example);
        FUNCTIONS.add(fn);
        BY_NAME.put(name, fn);
    }

    private static void v(String name, String desc) {
        VARIABLES.add(new Var(name, desc));
    }

    static {
        c("Блоки");
        f("SetBlock", "x, y, z, block", "Ставит блок в точку (x, y, z). Можно указать состояние: \"oak_stairs[facing=east]\".", "SetBlock(0, 0, 0, \"diamond_block\")");
        f("GetBlock", "x, y, z", "Возвращает название блока в точке, например \"stone\" или \"air\".", "if GetBlock(0, -1, 0) == \"grass_block\":\n    print(\"Под нами трава\")");
        f("IsBlock", "x, y, z, block", "True, если в точке стоит этот блок.", "if IsBlock(5, 0, 5, \"gold_block\"):\n    Say(\"Золото на месте!\")");
        f("IsAir", "x, y, z", "True, если в точке пусто (воздух).", "if IsAir(0, 0, 0):\n    SetBlock(0, 0, 0, \"stone\")");
        f("BreakBlock", "x, y, z, drop=True", "Ломает блок, как будто его сломал игрок. drop=False — без выпадения предмета.", "BreakBlock(0, 0, 0)");
        f("Fill", "x1, y1, z1, x2, y2, z2, block", "Заполняет прямоугольную область блоками (до 1 000 000 блоков за раз).", "Fill(0, 0, 0, 9, 0, 9, \"stone\")");
        f("FillHollow", "x1, y1, z1, x2, y2, z2, block", "Строит пустую коробку: стены, пол и потолок, а внутри воздух.", "FillHollow(0, 0, 0, 6, 4, 6, \"glass\")");
        f("Walls", "x1, y1, z1, x2, y2, z2, block", "Строит четыре стены без пола и потолка.", "Walls(0, 0, 0, 8, 3, 8, \"cobblestone\")");
        f("Floor", "x1, z1, x2, z2, y, block", "Кладёт горизонтальный пол на высоте y.", "Floor(-5, -5, 5, 5, -1, \"oak_planks\")");
        f("Replace", "x1, y1, z1, x2, y2, z2, old, new", "Заменяет в области один блок на другой. Возвращает число замен.", "n = Replace(-10, -1, -10, 10, -1, 10, \"grass_block\", \"sand\")\nprint(\"Заменено:\", n)");
        f("Clear", "x1, y1, z1, x2, y2, z2", "Очищает область (ставит воздух).", "Clear(0, 0, 0, 20, 20, 20)");
        f("CountBlocks", "x1, y1, z1, x2, y2, z2, block", "Считает, сколько таких блоков в области.", "print(CountBlocks(0, 0, 0, 10, 10, 10, \"stone\"))");
        f("TopY", "x, z", "Высота самого верхнего непустого блока в столбце (x, z).", "y = TopY(10, 10)\nSetBlock(10, y + 1, 10, \"torch\")");
        f("Line", "x1, y1, z1, x2, y2, z2, block", "Проводит прямую линию из блоков между двумя точками.", "Line(0, 0, 0, 10, 10, 0, \"gold_block\")");
        f("Copy", "x1, y1, z1, x2, y2, z2", "Копирует область в буфер. Возвращает число блоков.", "Copy(0, 0, 0, 5, 5, 5)");
        f("Paste", "x, y, z", "Вставляет скопированную область, (x, y, z) — угол вставки.", "Copy(0, 0, 0, 5, 5, 5)\nPaste(20, 0, 0)");

        c("Фигуры и постройки");
        f("Sphere", "x, y, z, radius, block", "Сплошной шар с центром (x, y, z).", "Sphere(0, 10, 0, 5, \"glass\")");
        f("HollowSphere", "x, y, z, radius, block", "Пустой шар (только оболочка).", "HollowSphere(0, 10, 0, 8, \"glass\")");
        f("Dome", "x, y, z, radius, block", "Купол — верхняя половина пустого шара.", "Dome(0, 0, 0, 10, \"white_stained_glass\")");
        f("Cylinder", "x, y, z, radius, height, block", "Сплошной цилиндр (столб), основание в (x, y, z).", "Cylinder(0, 0, 0, 3, 10, \"quartz_block\")");
        f("HollowCylinder", "x, y, z, radius, height, block", "Труба — пустой цилиндр.", "HollowCylinder(0, 0, 0, 4, 12, \"stone_bricks\")");
        f("Circle", "x, y, z, radius, block", "Кольцо (окружность) на высоте y.", "Circle(0, 0, 0, 10, \"red_wool\")");
        f("Disk", "x, y, z, radius, block", "Закрашенный круг на высоте y.", "Disk(0, -1, 0, 8, \"blue_concrete\")");
        f("Pyramid", "x, y, z, size, block, hollow=False", "Пирамида: (x, y, z) — центр основания, size — половина ширины.", "Pyramid(0, 0, 0, 10, \"sandstone\")");
        f("House", "x, y, z, width=7, depth=7, height=4, wall=\"oak_planks\", roof=\"spruce_planks\"", "Готовый домик с дверью, окнами, полом и крышей. (x, y, z) — угол дома.", "House(0, 0, 0)\nHouse(12, 0, 0, 9, 7, 5, \"stone_bricks\", \"dark_oak_planks\")");
        f("Tower", "x, y, z, height, radius=3, block=\"stone_bricks\"", "Круглая башня с зубцами наверху.", "Tower(0, 0, 0, 15)");
        f("Tree", "x, y, z, kind=\"oak\"", "Сажает дерево: oak, birch, spruce, jungle, acacia, dark_oak, cherry, mangrove, azalea.", "for i in range(5):\n    Tree(i * 6, 0, 10, \"birch\")");
        f("Maze", "x, y, z, width, depth, height=3, block=\"hedge\"", "Строит случайный лабиринт. width и depth — число клеток, block — материал стен (\"hedge\" = листва).", "Maze(0, 0, 0, 10, 10)");
        f("Stairs", "x, y, z, length, direction=\"north\", block=\"stone_brick_stairs\"", "Лестница вверх в сторону north/south/east/west.", "Stairs(0, 0, 0, 10, \"east\")");

        c("Мобы и существа");
        f("SummonMob", "x, y, z, mob, options=None", "Призывает моба и возвращает его номер (id). options — список или словарь: \"glowing\", \"noai\", \"baby\", \"silent\", \"invulnerable\", \"fire\", \"persistent\", \"nogravity\", {\"name\": \"Вася\", \"health\": 40, \"effect\": \"speed\", \"item\": \"diamond_sword\", \"armor\": \"iron\"}.", "zombie = SummonMob(5, 0, 5, \"zombie\", [\"glowing\", \"noai\"])\nSummonMob(0, 0, 0, \"pig\", {\"name\": \"Хрюша\", \"baby\": True})");
        f("Summon", "x, y, z, entity, nbt=\"\"", "Призывает любую сущность (лодку, стрелу, вагонетку...) с NBT-данными как в команде /summon.", "Summon(0, 5, 0, \"falling_block\", \"{BlockState:{Name:'minecraft:sand'}}\")");
        f("SpawnItem", "x, y, z, item, count=1", "Бросает предмет на землю.", "SpawnItem(0, 1, 0, \"diamond\", 5)");
        f("KillMobs", "mob=\"all\", radius=0", "Убирает мобов (не игроков). radius=0 — во всём мире. Возвращает количество.", "KillMobs(\"zombie\")\nKillMobs(\"all\", 20)");
        f("CountMobs", "mob=\"all\", radius=0", "Считает мобов. radius — расстояние от игрока, 0 — весь мир.", "if CountMobs(\"zombie\") < 5:\n    SummonMob(0, 0, 0, \"zombie\")");
        f("FindMobs", "mob=\"all\", radius=32", "Список id мобов рядом с игроком.", "for m in FindMobs(\"cow\"):\n    SetMobGlow(m)");
        f("NearestMob", "mob=\"all\", radius=64", "id ближайшего к игроку моба или None.", "m = NearestMob(\"zombie\")\nif m:\n    KillMob(m)");
        f("MobExists", "id", "True, если моб с таким id жив.", "while MobExists(boss):\n    wait(20)\nSay(\"Босс побеждён!\")");
        f("MobType", "id", "Тип моба, например \"zombie\".", "print(MobType(m))");
        f("MobPos", "id", "Координаты моба списком [x, y, z].", "x, y, z = MobPos(m)");
        f("MobX", "id", "Координата X моба.", "print(MobX(m))");
        f("MobY", "id", "Координата Y моба.", "print(MobY(m))");
        f("MobZ", "id", "Координата Z моба.", "print(MobZ(m))");
        f("MoveMob", "id, x, y, z", "Телепортирует моба.", "MoveMob(m, 0, 10, 0)");
        f("MobWalkTo", "id, x, y, z, speed=1.0", "Моб сам идёт к точке (если у него есть ИИ).", "MobWalkTo(m, 10, 0, 10)");
        f("PushMob", "id, vx, vy, vz", "Толкает моба (задаёт скорость). vy=1 — подбросить вверх.", "PushMob(m, 0, 1.5, 0)");
        f("MobHealth", "id", "Здоровье моба.", "print(MobHealth(m))");
        f("SetMobHealth", "id, health", "Задаёт здоровье (и максимум, если нужно больше).", "SetMobHealth(m, 100)");
        f("SetMobName", "id, name", "Даёт мобу имя над головой.", "SetMobName(m, \"Король\")");
        f("SetMobGlow", "id, on=True", "Подсветка моба (видно сквозь стены).", "SetMobGlow(m)");
        f("SetMobAI", "id, on", "Включает/выключает ИИ: без ИИ моб стоит статуей.", "SetMobAI(m, False)");
        f("SetBaby", "id, on=True", "Делает моба малышом.", "SetBaby(m)");
        f("SetMobSpeed", "id, speed", "Скорость ходьбы (обычно 0.2–0.3).", "SetMobSpeed(m, 0.5)");
        f("MobEffect", "id, effect, seconds=10, level=1", "Накладывает эффект на моба.", "MobEffect(m, \"levitation\", 5)");
        f("MobRide", "id, mount_id", "Сажает моба верхом на другого.", "chicken = SummonMob(0, 0, 0, \"chicken\")\nzombie = SummonMob(0, 0, 0, \"zombie\", [\"baby\"])\nMobRide(zombie, chicken)");
        f("KillMob", "id", "Убивает одного моба.", "KillMob(m)");

        c("Игрок");
        f("Teleport", "x, y, z", "Телепортирует игрока (камеру).", "Teleport(0, 20, 0)");
        f("Move", "dx, dy, dz", "Сдвигает игрока на столько блоков.", "Move(0, 5, 0)");
        f("Look", "yaw, pitch", "Поворачивает взгляд: yaw 0=юг, 90=запад, 180=север, -90=восток; pitch -90=вверх, 90=вниз.", "Look(180, 30)");
        f("LookAt", "x, y, z", "Поворачивает взгляд на точку.", "LookAt(0, 0, 0)");
        f("PlayerPos", "", "Координаты игрока [x, y, z] (целые).", "x, y, z = PlayerPos()");
        f("LookingAt", "", "Блок под прицелом [x, y, z] или None (до 100 блоков).", "p = LookingAt()\nif p:\n    SetBlock(p[0], p[1], p[2], \"gold_block\")");
        f("Give", "item, count=1", "Даёт предмет в инвентарь.", "Give(\"diamond_sword\")\nGive(\"cake\", 3)");
        f("ClearInventory", "", "Очищает инвентарь.", "ClearInventory()");
        f("HasItem", "item", "Сколько таких предметов у игрока (0 — нет).", "if HasItem(\"diamond\") > 0:\n    Say(\"Богач!\")");
        f("SetHealth", "health", "Задаёт здоровье игрока (20 = 10 сердец).", "SetHealth(10)");
        f("Heal", "", "Полностью лечит игрока.", "Heal()");
        f("Feed", "", "Полностью насыщает игрока.", "Feed()");
        f("Effect", "effect, seconds=10, level=1", "Накладывает эффект: speed, jump_boost, night_vision, levitation, invisibility, regeneration...", "Effect(\"night_vision\", 600)");
        f("ClearEffects", "", "Снимает все эффекты.", "ClearEffects()");
        f("Gamemode", "mode", "Режим: \"spectator\" (наблюдатель), \"creative\", \"survival\", \"adventure\".", "Gamemode(\"creative\")");
        f("SetXP", "levels", "Задаёт уровень опыта.", "SetXP(30)");
        f("AddXP", "levels", "Добавляет уровни опыта.", "AddXP(5)");
        f("SetSpawn", "x, y, z", "Точка возрождения игрока.", "SetSpawn(0, 0, 0)");
        f("SetFlySpeed", "speed", "Скорость полёта (обычно 0.05, наблюдатель — 0.1).", "SetFlySpeed(0.2)");
        f("Launch", "vx, vy, vz", "Подбрасывает игрока (в режиме выживания/творчества).", "Launch(0, 2, 0)");
        f("PlayerInArea", "x1, y1, z1, x2, y2, z2", "True, если игрок внутри области.", "if PlayerInArea(0, 0, 0, 10, 10, 10):\n    Title(\"Ты в зоне!\")");
        f("PlayerNear", "x, y, z, radius", "True, если игрок ближе radius блоков к точке.", "if PlayerNear(0, 0, 0, 5):\n    Say(\"Близко!\")");
        f("Players", "", "Список имён всех игроков на сервере.", "print(Players())");

        c("Мир и погода");
        f("SetTime", "time", "Время суток: число 0–24000 или \"day\", \"noon\", \"evening\", \"night\", \"midnight\", \"morning\".", "SetTime(\"night\")");
        f("GetTime", "", "Текущее время суток 0–23999 (то же, что переменная Time).", "print(GetTime())");
        f("SetWeather", "kind, seconds=600", "Погода: \"clear\", \"rain\", \"thunder\".", "SetWeather(\"thunder\")");
        f("GetWeather", "", "Текущая погода: \"clear\", \"rain\" или \"thunder\".", "if GetWeather() == \"rain\":\n    SetWeather(\"clear\")");
        f("DayCycle", "on", "Включает или останавливает смену дня и ночи.", "DayCycle(False)");
        f("Gamerule", "name, value", "Меняет правило игры, например \"doMobSpawning\", \"keepInventory\".", "Gamerule(\"keepInventory\", True)");
        f("Difficulty", "level", "Сложность: \"peaceful\", \"easy\", \"normal\", \"hard\".", "Difficulty(\"peaceful\")");
        f("Command", "text", "Выполняет любую команду Minecraft (без /). Возвращает результат.", "Command(\"weather clear\")\nCommand(\"give @p apple 5\")");
        f("SetWorldSpawn", "x, y, z", "Точка появления мира.", "SetWorldSpawn(0, 0, 0)");
        f("Biome", "x, y, z", "Название биома в точке.", "print(Biome(0, 0, 0))");
        f("LightLevel", "x, y, z", "Уровень освещённости 0–15.", "if LightLevel(0, 0, 0) < 8:\n    SetBlock(0, 0, 0, \"torch\")");

        c("Эффекты");
        f("Explosion", "x, y, z, power=3, fire=False, breaks=True", "Взрыв. power 4 — как динамит. breaks=False — без разрушений.", "Explosion(0, 0, 0, 5)");
        f("Lightning", "x, y, z", "Удар молнии.", "Lightning(0, 0, 0)");
        f("Particle", "x, y, z, particle=\"flame\", count=20, spread=0.5", "Частицы: flame, heart, smoke, cloud, end_rod, happy_villager, firework, explosion, note, soul_fire_flame...", "Particle(0, 2, 0, \"heart\", 30)");
        f("Sound", "sound, x=None, y=None, z=None, volume=1, pitch=1", "Проигрывает звук (без координат — у игрока). Например \"entity.cat.ambient\", \"block.note_block.pling\".", "Sound(\"entity.player.levelup\")");
        f("Firework", "x, y, z, color=\"red\", power=1", "Запускает фейерверк. Цвета: red, orange, yellow, green, blue, purple, white, pink...", "Firework(0, 0, 0, \"blue\")");

        c("Сообщения и консоль");
        f("print", "*values", "Пишет в консоль справа (стандартный print из Python).", "print(\"Привет!\", PlayerX)");
        f("Say", "text", "Сообщение в игровой чат.", "Say(\"Всем привет!\")");
        f("Title", "text, subtitle=\"\", seconds=3", "Большая надпись по центру экрана.", "Title(\"Уровень 1\", \"Найди алмаз\")");
        f("ActionBar", "text", "Надпись над панелью предметов.", "ActionBar(\"Очки: \" + str(score))");
        f("ClearConsole", "", "Очищает консоль справа.", "ClearConsole()");
        f("Help", "name=None", "Справка по функции в консоли. Без аргумента — список всех функций.", "Help(\"SummonMob\")");

        c("Время и ожидание");
        f("wait", "ticks=1", "Пауза: 20 тиков = 1 секунда. Мир продолжает жить.", "for i in range(10):\n    SetBlock(0, i, 0, \"stone\")\n    wait(5)");
        f("Wait", "ticks=1", "То же, что wait.", "Wait(20)");
        f("WaitSeconds", "seconds", "Пауза в секундах (можно дробные).", "WaitSeconds(1.5)");
        f("sleep", "seconds", "То же, что WaitSeconds (как time.sleep).", "sleep(2)");
        f("WaitUntil", "condition, timeout=0", "Ждёт, пока функция condition не вернёт True. timeout — максимум тиков (0 — без ограничения).", "WaitUntil(lambda: PlayerY > 30)\nSay(\"Ты высоко!\")");
        f("Ticks", "", "Сколько тиков прошло с запуска программы.", "print(Ticks())");
        f("Stop", "", "Останавливает программу.", "if PlayerY < -50:\n    Stop()");

        c("События");
        f("OnEvent", "event, func=None", "Подписывает функцию на событие: tick, chat, block_break, block_place, mob_death, player_move, key. Можно как декоратор: @OnEvent(\"chat\").", "def hello(msg):\n    print(\"Ты написал:\", msg)\nOnEvent(\"chat\", hello)");
        f("Every", "ticks, func", "Вызывает func каждые ticks тиков, пока программа работает.", "def spawn():\n    SummonMob(0, 0, 0, \"chicken\")\nEvery(40, spawn)");
        f("After", "ticks, func", "Вызывает func один раз через ticks тиков.", "After(100, lambda: Say(\"Прошло 5 секунд\"))");

        c("Полезное");
        f("Random", "a, b", "Случайное целое число от a до b включительно.", "x = Random(-10, 10)");
        f("RandomFloat", "", "Случайное дробное число от 0 до 1.", "if RandomFloat() < 0.5:\n    print(\"Орёл\")");
        f("Choice", "items", "Случайный элемент списка.", "SetBlock(0, 0, 0, Choice([\"red_wool\", \"blue_wool\", \"lime_wool\"]))");
        f("randint", "a, b", "То же, что Random (как random.randint).", "n = randint(1, 6)");
        f("Distance", "x1, y1, z1, x2, y2, z2", "Расстояние между двумя точками.", "print(Distance(0, 0, 0, 3, 4, 0))");
        f("DistanceToPlayer", "x, y, z", "Расстояние от игрока до точки.", "if DistanceToPlayer(0, 0, 0) < 3:\n    Say(\"Ты у старта\")");
        f("Blocks", "search=\"\"", "Список названий блоков (с фильтром).", "print(Blocks(\"wool\"))");
        f("Mobs", "search=\"\"", "Список названий мобов (с фильтром).", "print(Mobs())");

        v("PlayerX", "Координата X игрока (целое число)");
        v("PlayerY", "Координата Y игрока (высота ног)");
        v("PlayerZ", "Координата Z игрока");
        v("Xplayer", "То же, что PlayerX");
        v("Yplayer", "То же, что PlayerY");
        v("Zplayer", "То же, что PlayerZ");
        v("PlayerExactX", "Точная X игрока (дробная)");
        v("PlayerExactY", "Точная Y игрока (дробная)");
        v("PlayerExactZ", "Точная Z игрока (дробная)");
        v("PlayerYaw", "Поворот головы по горизонтали (градусы)");
        v("PlayerPitch", "Наклон головы (−90 вверх, 90 вниз)");
        v("PlayerHealth", "Здоровье игрока (0–20)");
        v("PlayerFood", "Сытость игрока (0–20)");
        v("PlayerXP", "Уровень опыта");
        v("PlayerName", "Имя игрока");
        v("PlayerMode", "Режим игры: spectator, creative, survival, adventure");
        v("PlayerOnGround", "True, если игрок стоит на земле");
        v("PlayerInWater", "True, если игрок в воде");
        v("PlayerSneaking", "True, если игрок крадётся (Shift)");
        v("PlayerBlock", "Блок под ногами игрока");
        v("PlayerDimension", "Измерение: overworld, the_nether, the_end");
        v("LookX", "X блока под прицелом (или None)");
        v("LookY", "Y блока под прицелом (или None)");
        v("LookZ", "Z блока под прицелом (или None)");
        v("LookBlock", "Название блока под прицелом (или None)");
        v("Time", "Время суток 0–23999 (0 — утро, 6000 — полдень, 13000 — ночь)");
        v("Day", "Номер игрового дня");
        v("IsDay", "True днём");
        v("IsNight", "True ночью");
        v("Weather", "Погода: \"clear\", \"rain\", \"thunder\"");
        v("IsRaining", "True, если идёт дождь");
        v("IsThundering", "True, если гроза");
        v("MobCount", "Сколько всего мобов в мире");
        v("PlayerCount", "Сколько игроков на сервере");
        v("Tick", "Тиков с запуска программы (20 в секунду)");

        EVENTS.add(new String[]{"tick", "on_tick()", "Каждый игровой тик (20 раз в секунду)."});
        EVENTS.add(new String[]{"chat", "on_chat(message)", "Игрок написал сообщение в чат."});
        EVENTS.add(new String[]{"block_break", "on_block_break(x, y, z, block)", "Игрок сломал блок."});
        EVENTS.add(new String[]{"block_place", "on_block_place(x, y, z, block)", "Игрок поставил блок."});
        EVENTS.add(new String[]{"mob_death", "on_mob_death(mob, x, y, z)", "Умер моб (mob — тип, например \"zombie\")."});
        EVENTS.add(new String[]{"player_move", "on_player_move(x, y, z)", "Игрок перешёл на другой блок."});
        EVENTS.add(new String[]{"key", "on_key(key)", "Нажата клавиша (когда редактор закрыт): \"g\", \"h\", \"1\", \"up\", \"space\"..."});
    }
}
