# Oracle v5 (эталон v1.7.5, раздел 2.10 гайда):
# - нативный play_animation(...) БЕЗ объявления def play_animation(...)
# - слот-машина + итог из набора, JS run_script, кубики send_dice, ask_llm
# - фото-слайд в inline-кнопке с message_id и сохранением меню
# - расписание с ротацией вариантов через " | " и меню под напоминанием

BOT_TOKEN = "НЕ_ИМПОРТИРУЕТСЯ"

SYSTEM_PROMPT = (
    "РОЛЬ: Дружелюбный гид и помощник.\n"
    "ПРАВИЛА:\n"
    "1. Отвечай коротко (2–4 предложения), на русском языке.\n"
    "2. Не выдумывай факты, если не знаешь — честно скажи."
)

LLM_MAX_TOKENS = 128
LLM_MAX_TOP_K = 40
MAX_HISTORY = 4
ANSWER_COOLDOWN = 15
DEFAULT_TYPING_SECONDS = 3

payload = {
    "temperature": 0.7,
    "max_tokens": LLM_MAX_TOKENS,
}

JOKES = [  # Шутки дня
    "Шутка 1, {user}!",
    "Шутка 2!",
]

SLOT_PRIZES = [  # Призы слот-машины
    "💎 Джекпот! Поздравляю, {user}!",
    "🍒 Почти получилось! Попробуй ещё раз.",
]

CLARIFY_QUESTIONS = [
    "Не совсем понял тебя, {user}. Уточни или открой /help 👇",
    "Сформулируй чуть конкретнее? Можно также спросить ИИ: /chat вопрос",
]

ANIM_ASCII_WAVE = [
    "  ~     ~  \n ~(ツ)~ ",
    "   ~   ~   \n  ~(ツ)~",
]

ANIM_SLIDE_1 = [
    "photo: https://example.com/slide1.jpg\nСлайд 1: Добро пожаловать!",
]


def chat_keyboard():
    markup = ReplyKeyboardMarkup(resize_keyboard=True)
    markup.row(KeyboardButton("🎲 Шутка"), KeyboardButton("🎰 Испытать удачу"))
    markup.row(KeyboardButton("🧮 Калькулятор 2+2"), KeyboardButton("⏳ Отсчёт"))
    return markup


def start_keyboard():
    markup = InlineKeyboardMarkup()
    markup.row(
        InlineKeyboardButton("▶️ Вкл. напоминания", callback_data="reminders_on"),
        InlineKeyboardButton("⏹ Выкл. напоминания", callback_data="reminders_off"),
    )
    markup.row(
        InlineKeyboardButton("💬 Сменить текст", callback_data="edit_tip"),
        InlineKeyboardButton("🎲 Шутка (править)", callback_data="edit_joke"),
    )
    markup.row(
        InlineKeyboardButton("🎞 Слайд (в сообщении)", callback_data="slide_1"),
        InlineKeyboardButton("🎲 Кубик", callback_data="roll_dice"),
    )
    markup.row(
        InlineKeyboardButton("⚡ JS-время", callback_data="js_time"),
        InlineKeyboardButton("🌐 Сайт", url="https://example.com"),
    )
    return markup


@bot.message_handler(commands=["start"])
def cmd_start(msg):
    bot.send_message(
        msg.chat.id,
        "Привет, {user}! Я {bot}. Выбирай кнопки ниже 👇",
        reply_markup=start_keyboard(),
    )


@bot.message_handler(commands=["help"])
def cmd_help(msg):
    bot.send_message(
        msg.chat.id,
        "Команды:\n/start — меню\n/help — справка\n/chat вопрос — спросить ИИ",
    )


@bot.message_handler(func=lambda m: m.text == "🎲 Шутка")
def on_btn_joke(msg):
    bot.send_message(msg.chat.id, random.choice(JOKES))


@bot.message_handler(func=lambda m: m.text == "🎰 Испытать удачу")
def on_btn_slot(msg):
    play_animation(msg.chat.id, "slot", delay=0.5, pack=SLOT_PRIZES)


@bot.message_handler(func=lambda m: m.text == "🧮 Калькулятор 2+2")
def on_btn_calc(msg):
    run_script("function handle(e) { return 'Привет, ' + e.user + '! 2 + 2 = ' + (2 + 2); }")


@bot.message_handler(func=lambda m: m.text == "⏳ Отсчёт")
def on_btn_countdown(msg):
    play_animation(msg.chat.id, "countdown", delay=1.0, text="Запуск через", final="🚀 Поехали!")


@bot.message_handler(commands=["wave"])
def cmd_wave(msg):
    play_animation(msg.chat.id, ANIM_ASCII_WAVE, delay=0.7, loops=2, mono=True, final="Готово!")


@bot.message_handler(commands=["dice"])
def cmd_dice(msg):
    bot.send_dice(msg.chat.id, emoji="🎯")


@bot.message_handler(func=lambda m: "совет" in (m.text or "").lower())
def on_advice(msg):
    bot.send_message(msg.chat.id, ask_llm(msg.text))


reminders_on = True


@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    global reminders_on
    if call.data == "reminders_on":
        if reminders_on:
            bot.answer_callback_query(call.id, "Уже включены")
            return
        reminders_on = True
        bot.answer_callback_query(call.id, "Напоминания включены")
        bot.edit_message_text("▶️ Напоминания активны.", call.message.chat.id, call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "reminders_off":
        if not reminders_on:
            bot.answer_callback_query(call.id, "Уже выключены")
            return
        reminders_on = False
        bot.answer_callback_query(call.id, "Напоминания выключены")
        bot.edit_message_text("⏹ Напоминания остановлены.", call.message.chat.id, call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "edit_tip":
        bot.answer_callback_query(call.id, "Обновлено")
        bot.edit_message_text("💡 Подсказка обновлена прямо в этом сообщении!", call.message.chat.id, call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "edit_joke":
        bot.answer_callback_query(call.id, "Новая шутка")
        bot.edit_message_text(random.choice(JOKES), call.message.chat.id, call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "slide_1":
        bot.answer_callback_query(call.id, "Открываю слайд")
        play_animation(call.message.chat.id, ANIM_SLIDE_1, message_id=call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "roll_dice":
        bot.answer_callback_query(call.id, "Бросаю кубик!")
        bot.send_dice(call.message.chat.id, emoji="🎲")
    elif call.data == "js_time":
        bot.answer_callback_query(call.id, "Вычисляю")
        run_script("function handle(e) { return 'Время на устройстве: ' + new Date().toLocaleTimeString(); }")


def get_today_schedule(now):
    wd = now.weekday()
    events = [
        (9, 0, "☀️ Доброе утро! | ☕ Бодрого дня!", start_keyboard()),
    ]
    if wd < 5:
        events.append((13, 0, "🍽 Время перерыва!"))
        if wd == 4:
            events.append((17, 30, "🎉 Пятница, можно подводить итоги недели!"))
    if wd >= 5:
        events.append((11, 0, "🌿 Хороших выходных!"))
    return events


bot.set_my_commands([
    BotCommand("start", "Главное меню"),
    BotCommand("help", "Справка по командам"),
    BotCommand("wave", "ASCII-анимация"),
    BotCommand("dice", "Бросить мишень 🎯"),
])
