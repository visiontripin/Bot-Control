# Негативная регрессионная фикстура (I-1 / I-3):
# - play_animation(...) без def распознаётся нативно (НЕ ломается)
# - вызов выдуманной функции unknown_helper(...) БЕЗ def НЕ должен молча
#   давать тишину: импортёр обязан выдать warning «действие не распознано»
#   для команды /broken и ветки callback_data="broken_cb"

BOT_TOKEN = "НЕ_ИМПОРТИРУЕТСЯ"


def menu_kb():
    markup = InlineKeyboardMarkup()
    markup.row(InlineKeyboardButton("Сломанная кнопка", callback_data="broken_cb"))
    return markup


@bot.message_handler(commands=["start"])
def cmd_start(msg):
    bot.send_message(msg.chat.id, "Старт", reply_markup=menu_kb())


@bot.message_handler(commands=["wave"])
def cmd_wave(msg):
    play_animation(msg.chat.id, "spinner", delay=0.6, text="Работает без def")


@bot.message_handler(commands=["broken"])
def cmd_broken(msg):
    unknown_helper(msg.chat.id, "эта функция не объявлена")


@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    if call.data == "broken_cb":
        unknown_cb_helper(call.message.chat.id)
