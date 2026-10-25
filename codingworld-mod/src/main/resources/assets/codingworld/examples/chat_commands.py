# События: пиши в чат (клавиша T) — код ответит!
# Команды: дом, ночь, день, дождь, свинья, взрыв, стоп

def on_chat(message):
    msg = message.lower()
    x, y, z = PlayerX, PlayerY, PlayerZ
    if msg == "дом":
        House(x + 3, TopY(x + 3, z) + 1, z + 3)
    elif msg == "ночь":
        SetTime("night")
    elif msg == "день":
        SetTime("day")
    elif msg == "дождь":
        SetWeather("rain")
    elif msg == "свинья":
        SummonMob(x, y, z + 2, "pig", {"name": "Пятачок"})
    elif msg == "взрыв":
        Explosion(x, TopY(x, z + 8), z + 8, 4, breaks=False)
    elif msg == "стоп":
        Say("Пока!")
        Stop()
    else:
        Say("Не знаю команду: " + message)

def on_mob_death(mob, x, y, z):
    print("Умер моб:", mob, "в", x, y, z)

Say("Напиши в чат: дом, ночь, день, дождь, свинья, взрыв или стоп")
