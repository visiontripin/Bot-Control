# ============================================================
# BotControl — Showcase Bot («Демо всех функций» v2.2.0 / desktop-web 0.7.0)
#
# Импорт в Android:
#   Карточка бота → Скрипты → Импорт настроек из кода →
#   Выбрать файл → Разобрать → Применить к боту → ▶ Этот бот → /start
#
# Импорт в Windows / desktop-web 0.7.0:
#   «Импорт Android .py (полный)» → выбрать этот файл → Применить → Запустить
#
# Что демонстрирует этот бот:
#   • ИИ на устройстве (SYSTEM_PROMPT, температура, Top-K, MAX_HISTORY, /ai)
#   • Поведение, паузы «печатает…», кулдаун и уточняющие вопросы (CLARIFY_QUESTIONS)
#   • Команды (/start, /help, /kb, /hidekb, /dice, /poll, /photo, /anim, /progress,
#     /countdown, /typewriter, /slot, /script, /http, /markdown, /secret, /ai, /features)
#   • Текстовые правила: кнопки нижней клавиатуры, фразы («привет»), regex (^заказ\s+#?\d+$)
#   • Нижняя клавиатура (ReplyKeyboardMarkup + request_contact + request_location + placeholder)
#   • 3-уровневое вложенное inline-меню + ВСЕ типы кнопок:
#     url, web_app, copy_text, switch_inline_query_current_chat, toast, edit_message_text,
#     вкл/выкл расписания, фото-слайды, JS-скрипт, ask_llm и подменю
#   • Наборы случайных ответов (JOKES, TIPS, SLOT_PRIZES)
#   • Анимации правкой сообщения: пресеты (progress, spinner, matrix, countdown,
#     typewriter, slot, heart), ASCII-кадры (ANIM_ROCKET) и фото-слайды (ANIM_SLIDE_1/2)
#   • Медиа (send_photo), кубики (send_dice), опросы (send_poll), parse_mode HTML/MarkdownV2,
#     disable_web_page_preview, disable_notification, protect_content, reply_to_message_id
#   • JS-песочница Rhino (run_script) с Room KV памятью (db.incr / db.incrGlobal) и http.get
#   • Расписание (get_today_schedule) с зонами дней недели, ротацией " | " и меню
# ============================================================

BOT_TOKEN = "НЕ_ИМПОРТИРУЕТСЯ"

# ---------- 1. Локальный ИИ и поведение ----------
SYSTEM_PROMPT = (
    "РОЛЬ: Ты — гид по платформе BotControl. Показываешь возможности локальных Telegram-ботов.\n"
    "ПРАВИЛА:\n"
    "1. Отвечай по-русски, кратко (2–4 предложения) и по делу.\n"
    "2. Если спрашивают про настройку — называй точный раздел меню приложения."
)

LLM_MAX_TOKENS = 160
LLM_MAX_TOP_K = 40
MAX_HISTORY = 6
ANSWER_COOLDOWN = 5
DEFAULT_TYPING_SECONDS = 2
LISTINGS_MODE = False

payload = {
    "temperature": 0.7,
    "max_tokens": LLM_MAX_TOKENS,
}

# ---------- 2. Наборы ответов и уточняющие вопросы ----------
JOKES = [  # Шутки про ботов
    "Почему бот никогда не опаздывает, {user}? Он живёт по get_today_schedule! ⏰",
    "Локальный ИИ на телефоне: работает даже в метро без интернета (почти) 🚇",
    "У разработчика два состояния: «всё сломалось» и «я ничего не трогал, оно само заработало» 🤖",
    "Самый надёжный сервер — тот, что лежит у тебя в кармане 📱",
]

TIPS = [  # Советы по BotControl
    "💡 Включите «Без повторов до конца круга» в Наборах ответов — шутки не будут дублироваться.",
    "🔒 Wi-Fi сервер (:8090) защищён 6-значным PIN-кодом, а /miniapp и /telegram-webhook работают напрямую.",
    "⚡ В JS-скриптах память db.set/db.get сохраняется в SQLite Room даже после перезапуска телефона.",
    "🩺 Для поиска ошибок используйте «Общий лог → 🩺 Диагностика» — отчёт формируется без токенов.",
]

SLOT_PRIZES = [  # Призы слот-машины
    "💎 Джекпот, {user}! Выпала полная комбинация!",
    "🏆 Отличный спин, {user}! Бонус +100 к настроению.",
    "🍒 Почти в цель! Крути ещё раз через /slot.",
    "⭐ Удачный знак дня — пора запускать своего бота!",
]

CLARIFY_QUESTIONS = [
    "Не нашёл подходящего правила, {user}. Нажми /start для меню или /help для списка команд 👇",
    "Попробуй написать «привет», «заказ #42» или задай вопрос ИИ через /ai твой вопрос 🤖",
    "Команды демо-бота всегда доступны через /help или нижнюю клавиатуру (/kb) ✨",
]

# ---------- 3. Кадры анимаций и фото-слайды ----------
ANIM_ROCKET = [
    "  🚀\n  ||\n 🌍",
    "  🚀\n  🔥\n 🌍",
    " 🚀\n🔥\n\n🌍",
    "🚀✨\n\n\n🌍",
]

ANIM_SLIDE_1 = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/main/tg-bot-control/branding/slides/01-cover.png\n"
    "🖼 Слайд 1/2: BotControl v2.2.0 — боты на Android и Windows (desktop-web 0.7.0) без VPS!",
]

ANIM_SLIDE_2 = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/main/tg-bot-control/branding/slides/03-anim.png\n"
    "🖼 Слайд 2/2: Анимации, 3-уровневые подменю, JS + Room DB, FSM, Stars XTR и Mini Apps.",
]

# ---------- 4. Нижняя клавиатура чата ----------
def chat_keyboard():
    markup = ReplyKeyboardMarkup(
        resize_keyboard=True,
        one_time_keyboard=False,
        input_field_placeholder="Выбери демо-функцию BotControl…",
    )
    markup.row(
        KeyboardButton("🎲 Шутка"),
        KeyboardButton("🎰 Слот"),
        KeyboardButton("🧮 Счётчик JS"),
    )
    markup.row(
        KeyboardButton("🚀 Ракета"),
        KeyboardButton("🎯 Дартс"),
        KeyboardButton("📋 Главное меню"),
    )
    markup.row(
        KeyboardButton("📞 Мой контакт", request_contact=True),
        KeyboardButton("📍 Моя геопозиция", request_location=True),
    )
    return markup

# ---------- 5. Inline-меню (3 уровня вложенности) ----------
def main_keyboard():
    markup = InlineKeyboardMarkup()
    markup.row(
        InlineKeyboardButton("▶️ Вкл. расписание", callback_data="rem_on"),
        InlineKeyboardButton("⏹ Выкл. расписание", callback_data="rem_off"),
    )
    markup.row(
        InlineKeyboardButton("🎞 Анимации ▸", callback_data="open_anim"),
        InlineKeyboardButton("⚡ JS + Память DB", callback_data="cb_js"),
    )
    markup.row(
        InlineKeyboardButton("🖼 Фото-слайд", callback_data="cb_slide_1"),
        InlineKeyboardButton("🤖 Спросить ИИ", callback_data="cb_llm"),
    )
    markup.row(
        InlineKeyboardButton("📋 Копировать тег", copy_text=CopyTextButton(text="BOTCONTROL-V2.2")),
        InlineKeyboardButton("🔍 Inline-поиск", switch_inline_query_current_chat="демо "),
    )
    markup.row(
        InlineKeyboardButton("📱 Презентация", web_app=WebAppInfo(url="https://visiontripin.github.io/Bot-Control/presentation.html")),
        InlineKeyboardButton("🌐 Релизы GitHub", url="https://github.com/visiontripin/Bot-Control/releases"),
    )
    return markup


def anim_keyboard():
    markup = InlineKeyboardMarkup()
    markup.row(
        InlineKeyboardButton("⏳ Прогресс", callback_data="cb_prog"),
        InlineKeyboardButton("🌀 Спиннер", callback_data="cb_spin"),
        InlineKeyboardButton("❤️ Сердце", callback_data="cb_heart"),
    )
    markup.row(
        InlineKeyboardButton("🟩 Матрица", callback_data="cb_matrix"),
        InlineKeyboardButton("🖼 Слайд 2", callback_data="cb_slide_2"),
    )
    markup.row(
        InlineKeyboardButton("⚙️ Уровень 3: Игры и советы ▸", callback_data="open_deep"),
    )
    return markup


def deep_keyboard():
    markup = InlineKeyboardMarkup()
    markup.row(
        InlineKeyboardButton("💡 Случайный совет", callback_data="cb_tip"),
        InlineKeyboardButton("🎯 Дартс", callback_data="cb_dice"),
    )
    markup.row(
        InlineKeyboardButton("🎰 Крутить слот", callback_data="cb_slot"),
        InlineKeyboardButton("✏️ Обновить текст", callback_data="cb_edit"),
    )
    return markup

# ---------- 6. Обработчики команд ----------
@bot.message_handler(commands=["start"])
def cmd_start(msg):
    bot.send_message(
        msg.chat.id,
        "<b>Привет, {user}!</b> 👋 Я <b>{bot}</b> — демонстрация всех возможностей "
        "<b>BotControl v2.2.0</b>.\n\n"
        "• Нажимай inline-кнопки ниже (3 уровня вложенности, <code>copy_text</code>, "
        "<code>web_app</code>, <code>switch_inline</code>, слайды, JS + БД).\n"
        "• Вызови /kb для нижней клавиатуры с запросом контакта/гео.\n"
        "• Полный список всех демо-команд — /help, настройка UI-модулей — /features.",
        parse_mode="HTML",
        disable_web_page_preview=True,
        reply_markup=main_keyboard(),
    )


@bot.message_handler(commands=["help"])
def cmd_help(msg):
    bot.send_message(
        msg.chat.id,
        "📖 Все демо-команды:\n"
        "/start — главное меню (HTML + 3-уровневое inline-меню)\n"
        "/kb — показать нижнюю клавиатуру (кнопки, контакт, гео)\n"
        "/hidekb — скрыть нижнюю клавиатуру (ReplyKeyboardRemove)\n"
        "/anim — ASCII-анимация запуска ракеты\n"
        "/progress — прогресс-бар\n"
        "/countdown — обратный отсчёт\n"
        "/typewriter — эффект печатной машинки\n"
        "/slot — слот-машина с призами из набора\n"
        "/dice — бросок дартс 🎯\n"
        "/poll — опрос (send_poll)\n"
        "/photo — картинка по URL с подписью (send_photo)\n"
        "/script — JS-песочница Rhino + память db в Room\n"
        "/http — внешний HTTP GET из JS (GitHub API)\n"
        "/markdown — ответ с разметкой MarkdownV2\n"
        "/secret — тихое защищённое сообщение с ответом на ваше\n"
        "/ai вопрос — запрос к локальной модели ИИ на телефоне\n"
        "/features — какие модули включаются прямо в UI приложения\n\n"
        "Также попробуй написать: «привет» или «заказ #42»!",
    )


@bot.message_handler(commands=["kb"])
def cmd_kb(msg):
    bot.send_message(
        msg.chat.id,
        "⌨️ Нижняя клавиатура включена! Для скрытия используй /hidekb.",
        reply_markup=chat_keyboard(),
    )


@bot.message_handler(commands=["hidekb"])
def cmd_hidekb(msg):
    bot.send_message(
        msg.chat.id,
        "🙈 Нижняя клавиатура скрыта. Вернуть — /kb.",
        reply_markup=ReplyKeyboardRemove(),
    )


@bot.message_handler(commands=["anim"])
def cmd_anim(msg):
    play_animation(msg.chat.id, ANIM_ROCKET, delay=0.6, loops=1, mono=True, final="🛰 Орбита достигнута, {user}!")


@bot.message_handler(commands=["progress"])
def cmd_progress(msg):
    play_animation(msg.chat.id, "progress", delay=0.5, text="Проверка систем BotControl", final="✅ Все модули в норме!")


@bot.message_handler(commands=["countdown"])
def cmd_countdown(msg):
    play_animation(msg.chat.id, "countdown", delay=0.9, text="Старт через", final="🚀 Поехали!")


@bot.message_handler(commands=["typewriter"])
def cmd_typewriter(msg):
    play_animation(msg.chat.id, "typewriter", delay=0.5, text="Локальный бот работает прямо на твоём телефоне!")


@bot.message_handler(commands=["slot"])
def cmd_slot(msg):
    play_animation(msg.chat.id, "slot", delay=0.5, pack=SLOT_PRIZES)


@bot.message_handler(commands=["dice"])
def cmd_dice(msg):
    bot.send_dice(msg.chat.id, emoji="🎯")


@bot.message_handler(commands=["poll"])
def cmd_poll(msg):
    bot.send_poll(
        msg.chat.id,
        "Какая возможность BotControl для вас главная?",
        ["Локальный ИИ без VPS", "Анимации и 3-уровневые меню", "JS-скрипты + SQLite db", "Расписание, FSM и Stars"],
        is_anonymous=False,
        allows_multiple_answers=True,
    )


@bot.message_handler(commands=["photo"])
def cmd_photo(msg):
    bot.send_photo(
        msg.chat.id,
        "https://raw.githubusercontent.com/visiontripin/Bot-Control/main/tg-bot-control/branding/slides/08-start.png",
        caption="🖼 <b>BotControl v2.2.0</b> — отправлено командой <code>/photo</code>",
        parse_mode="HTML",
        protect_content=True,
    )


@bot.message_handler(commands=["script"])
def cmd_script(msg):
    run_script(
        "function handle(e) { "
        "var myHits = db.incr('cmd_hits', 1); "
        "var total = db.incrGlobal('all_hits', 1); "
        "return '⚡ JS Rhino Sandbox\\nПользователь: ' + e.user + "
        "' (@' + (e.username || 'нет') + ')\\nАргументы: ' + (e.args || 'пусто') + "
        "'\\nТвоих запусков (Room DB): ' + myHits + '\\nВсего по боту: ' + total; }"
    )


@bot.message_handler(commands=["http"])
def cmd_http(msg):
    run_script(
        "function handle(e) { "
        "var r = http.get('https://api.github.com/repos/visiontripin/Bot-Control', {'User-Agent': 'BotControl'}); "
        "if (!r.ok) return '🌐 HTTP-мост: ' + (r.error || ('HTTP ' + r.status)) + "
        "'\\n💡 Добавь api.github.com в раздел «Инлайн, Stars, Mini App и API → Белый список доменов».'; "
        "return '🌐 GitHub Repo: ' + (r.json ? (r.json.full_name + ' ★ ' + r.json.stargazers_count) : 'OK ' + r.status); }"
    )


@bot.message_handler(commands=["markdown"])
def cmd_markdown(msg):
    bot.send_message(
        msg.chat.id,
        "*MarkdownV2* _демо_ для `{user}` \\| код `BotControl 2\\.2\\.0`",
        parse_mode="MarkdownV2",
    )


@bot.message_handler(commands=["secret"])
def cmd_secret(msg):
    bot.send_message(
        msg.chat.id,
        "🔐 Это тихое сообщение (disable_notification=True), защищённое от пересылки (protect_content=True) и привязанное к вашему сообщению!",
        disable_notification=True,
        protect_content=True,
        reply_to_message_id=msg.message_id,
    )


@bot.message_handler(commands=["ai"])
def cmd_ai(msg):
    bot.send_message(msg.chat.id, ask_llm(msg.text))


@bot.message_handler(commands=["features"])
def cmd_features(msg):
    bot.send_message(
        msg.chat.id,
        "🧩 Что ещё доступно в интерфейсе BotControl v2.2.0 поверх этого бота:\n"
        "• 👥 Подписчики и роли (user / vip / admin) + авто-пометка 403\n"
        "• 📋 Пошаговые анкеты (FSM) с ветвлениями и фото/гео\n"
        "• 📣 Массовые рассылки по ролям и тегам\n"
        "• 🛡 Модерация групп/форумов: капча, антиспам, заявки, реакции, Webhook\n"
        "• ⚡ Inline-режим @бот, оплаты Telegram Stars (XTR) и Mini App /miniapp/<botId>\n"
        "• 🔒 Сеть HTTPS-only, 6-значный PIN Wi-Fi сервера (:8090) и 🩺 Диагностика в «Общем логе»",
    )

# ---------- 7. Кнопки нижней клавиатуры, фразы и regex ----------
@bot.message_handler(func=lambda m: m.text == "🎲 Шутка")
def btn_joke(msg):
    bot.send_message(msg.chat.id, random.choice(JOKES))


@bot.message_handler(func=lambda m: m.text == "🎰 Слот")
def btn_slot(msg):
    play_animation(msg.chat.id, "slot", delay=0.5, pack=SLOT_PRIZES)


@bot.message_handler(func=lambda m: m.text == "🧮 Счётчик JS")
def btn_js(msg):
    run_script(
        "function handle(e) { "
        "var n = db.incr('btn_clicks', 1); "
        "return '🧮 Кнопка нажата уже ' + n + ' раз(а), ' + e.user + '!'; }"
    )


@bot.message_handler(func=lambda m: m.text == "🚀 Ракета")
def btn_rocket(msg):
    play_animation(msg.chat.id, ANIM_ROCKET, delay=0.6, mono=True, final="🚀 Ракета выведена на орбиту!")


@bot.message_handler(func=lambda m: m.text == "🎯 Дартс")
def btn_darts(msg):
    bot.send_dice(msg.chat.id, emoji="🎯")


@bot.message_handler(func=lambda m: m.text == "📋 Главное меню")
def btn_main_menu(msg):
    bot.send_message(msg.chat.id, "📋 Главное меню демо-бота:", reply_markup=main_keyboard())


@bot.message_handler(func=lambda m: "привет" in (m.text or "").lower())
def on_hello(msg):
    bot.send_message(msg.chat.id, "Привет, {user}! Сработал триггер «Содержит слово». Жми /start 👋")


@bot.message_handler(func=lambda m: "объясни" in (m.text or "").lower())
def on_explain_llm(msg):
    bot.send_message(msg.chat.id, ask_llm(msg.text))


@bot.message_handler(regexp=r"^заказ\s+#?\d+$")
def on_order_regex(msg):
    bot.send_message(
        msg.chat.id,
        "🔎 Сработал <b>regex-триггер</b> (<code>^заказ\\s+#?\\d+$</code>) на текст: <i>{text}</i>",
        parse_mode="HTML",
    )

# ---------- 8. Обработчик нажатий на inline-кнопки ----------
bot_running = False


@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    global bot_running
    if call.data == "rem_on":
        if bot_running:
            bot.answer_callback_query(call.id, "Расписание уже включено")
            return
        bot_running = True
        bot.answer_callback_query(call.id, "Расписание включено")
        bot.edit_message_text("▶️ Напоминания по расписанию включены.", call.message.chat.id, call.message.message_id, reply_markup=main_keyboard())
    elif call.data == "rem_off":
        if not bot_running:
            bot.answer_callback_query(call.id, "Расписание уже выключено")
            return
        bot_running = False
        bot.answer_callback_query(call.id, "Расписание выключено")
        bot.edit_message_text("⏹ Напоминания по расписанию остановлены.", call.message.chat.id, call.message.message_id, reply_markup=main_keyboard())
    elif call.data == "open_anim":
        bot.answer_callback_query(call.id, "Уровень 2: анимации")
        bot.edit_message_text("🎞 Уровень 2 — выбери эффект, слайд или перейди на 3-й уровень:", call.message.chat.id, call.message.message_id, reply_markup=anim_keyboard())
    elif call.data == "open_deep":
        bot.answer_callback_query(call.id, "Уровень 3")
        bot.edit_message_text("⚙️ Уровень 3 вложенного меню:", call.message.chat.id, call.message.message_id, reply_markup=deep_keyboard())
    elif call.data == "cb_js":
        bot.answer_callback_query(call.id, "Запуск JS + Room DB")
        run_script("function handle(e) { var c = db.incr('cb_clicks', 1); return '⚡ Inline JS: нажатий = ' + c + ' (пользователь: ' + e.user + ')'; }")
    elif call.data == "cb_slide_1":
        bot.answer_callback_query(call.id, "Слайд 1")
        play_animation(call.message.chat.id, ANIM_SLIDE_1, message_id=call.message.message_id, reply_markup=main_keyboard())
    elif call.data == "cb_slide_2":
        bot.answer_callback_query(call.id, "Слайд 2")
        play_animation(call.message.chat.id, ANIM_SLIDE_2, message_id=call.message.message_id, reply_markup=anim_keyboard())
    elif call.data == "cb_llm":
        bot.answer_callback_query(call.id, "Думаю…")
        bot.send_message(call.message.chat.id, ask_llm("Кратко расскажи о преимуществах локального Telegram-бота на телефоне."))
    elif call.data == "cb_prog":
        bot.answer_callback_query(call.id, "Прогресс-бар")
        play_animation(call.message.chat.id, "progress", delay=0.5, text="Выполнение", final="✅ Готово!")
    elif call.data == "cb_spin":
        bot.answer_callback_query(call.id, "Спиннер")
        play_animation(call.message.chat.id, "spinner", delay=0.5, text="Загрузка данных…", final="✨ Данные получены!")
    elif call.data == "cb_heart":
        bot.answer_callback_query(call.id, "Пульс")
        play_animation(call.message.chat.id, "heart", delay=0.5, final="❤️ BotControl работает!")
    elif call.data == "cb_matrix":
        bot.answer_callback_query(call.id, "Матрица")
        play_animation(call.message.chat.id, "matrix", delay=0.5, text="BOTCONTROL", final="🟩 Добро пожаловать в Матрицу!")
    elif call.data == "cb_tip":
        bot.answer_callback_query(call.id, "Совет дня")
        bot.send_message(call.message.chat.id, random.choice(TIPS))
    elif call.data == "cb_dice":
        bot.answer_callback_query(call.id, "Бросаю дартс!")
        bot.send_dice(call.message.chat.id, emoji="🎯")
    elif call.data == "cb_slot":
        bot.answer_callback_query(call.id, "Крутим барабан!")
        play_animation(call.message.chat.id, "slot", delay=0.5, pack=SLOT_PRIZES)
    elif call.data == "cb_edit":
        bot.answer_callback_query(call.id, "Обновлено!")
        bot.edit_message_text("✏️ Текст этого сообщения изменён прямо на уровне 3!", call.message.chat.id, call.message.message_id, reply_markup=deep_keyboard())

# ---------- 9. Расписание (ежедневно / будни / Пн–Чт / Пт / выходные) ----------
def get_today_schedule(now):
    events = [
        (9, 0, "☀️ Доброе утро, {user}! Боты на связи. | ☕ Бодрого дня, {user}! Напоминания работают."),
    ]
    wd = now.weekday()
    if wd < 5:
        events.append((13, 0, "🍽 Время перерыва! Открой меню демо-бота:", main_keyboard()))
        if wd == 4:
            events.append((18, 0, "🎉 Пятница! Итоги недели готовы."))
        else:
            events.append((18, 0, "🌇 Рабочий день завершается — проверь статус ботов."))
    if wd in (5, 6):
        events.append((11, 0, "🌿 Выходной режим расписания!"))
    return sorted(events, key=lambda item: (item[0], item[1]))

# ---------- 10. Меню команд Telegram (15 команд <= лимита 20) ----------
bot.set_my_commands([
    BotCommand("start", "Главное меню и 3-уровневые кнопки"),
    BotCommand("help", "Список всех демо-команд"),
    BotCommand("kb", "Нижняя клавиатура (кнопки, контакт, гео)"),
    BotCommand("hidekb", "Скрыть нижнюю клавиатуру"),
    BotCommand("anim", "ASCII-анимация запуска ракеты"),
    BotCommand("progress", "Анимация прогресс-бара"),
    BotCommand("countdown", "Обратный отсчёт"),
    BotCommand("typewriter", "Эффект печатной машинки"),
    BotCommand("slot", "Слот-машина с призами"),
    BotCommand("dice", "Бросить дартс 🎯"),
    BotCommand("poll", "Опрос Telegram"),
    BotCommand("photo", "Фото-слайд по URL"),
    BotCommand("script", "JS-скрипт + память Room DB"),
    BotCommand("http", "HTTP GET запрос из JS"),
    BotCommand("features", "Все модули UI v2.2.0"),
])
