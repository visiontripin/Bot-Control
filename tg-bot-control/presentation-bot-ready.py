# ============================================================
# BotControl — бот-презентация приложения (@AppBotcontrol_bot)
# Актуализировано под Android v2.2.0 (code 43) + Windows 0.7.0 (Этапы 1–5)
#
# Импорт в приложение (Android):
#   Карточка бота → Скрипты → Импорт настроек из кода →
#   Выбрать файл / вставить → Разобрать → Применить к боту → ▶ Этот бот.
# Импорт в Windows / desktop-web 0.7.0:
#   «Импорт Android .py (полный)» → выбрать файл → Применить → Запустить.
# Файл также запускается как обычный бот:
#   pip install pyTelegramBotAPI && python presentation-bot-ready.py
#
# Что внутри:
#   • /start — обложка-слайд с прогресс-баром и главным inline-меню;
#   • 3-уровневое вложенное inline-меню (≤ 12 кнопок на экран, без предупреждений):
#     слайды в том же сообщении (editMessageMedia), обзор Этапов 1–5 (v2.2.0),
#     copy_text, web_app, switch_inline_query_current_chat и ссылки;
#   • «▶️ Автотур» — все 8 слайдов презентации подряд;
#   • новые команды: /whatsnew /desktop /showcase /poll /kb /hidekb /markdown;
#   • демо-команды «фишек»: /progress /matrix /slot /typewriter /countdown
#     /spinner /quest /dice /howto /links /help;
#   • триггеры фраз («версия», «windows», «скачать»), набор FORTUNES и CLARIFY_QUESTIONS.
#
# Слайды лежат в репозитории (tg-bot-control/branding/slides), ссылки
# закреплены за тегом релиза — картинки не поменяются «под ногами».
# Аватар бота: @BotFather → /setuserpic → branding/avatar-640.png.
# ============================================================
import random
import time

import telebot
try:
    from telebot.types import (
        BotCommand, CopyTextButton, InlineKeyboardButton, InlineKeyboardMarkup,
        InputMediaPhoto, KeyboardButton, ReplyKeyboardMarkup, ReplyKeyboardRemove,
        WebAppInfo,
    )
except ImportError:
    from telebot.types import (
        BotCommand, InlineKeyboardButton, InlineKeyboardMarkup,
        InputMediaPhoto, KeyboardButton, ReplyKeyboardMarkup, ReplyKeyboardRemove,
    )

    def WebAppInfo(url):
        return {"url": url}

    def CopyTextButton(text):
        return {"text": text}

BOT_TOKEN = "ВСТАВЬ_ТОКЕН_НЕ_ИМПОРТИРУЕТСЯ"  # в приложении токен уже есть
bot = telebot.TeleBot(BOT_TOKEN)

ANSWER_COOLDOWN = 5
DEFAULT_TYPING_SECONDS = 1

GITHUB_URL = "https://github.com/visiontripin/Bot-Control"
RELEASES_URL = "https://github.com/visiontripin/Bot-Control/releases"
LATEST_APK_URL = "https://github.com/visiontripin/Bot-Control/releases/latest/download/BotControl-debug.apk"
WINDOWS_EXE_URL = "https://github.com/visiontripin/Bot-Control/releases/download/botcontrol-windows-preview-0.7.0/BotControl.exe"
SHOWCASE_BOT_URL = "https://github.com/visiontripin/Bot-Control/releases/latest/download/showcase-all-ready.py"
# Презентация на GitHub Pages (Settings → Pages → main, папка /docs)
PRESENTATION_URL = "https://visiontripin.github.io/Bot-Control/presentation.html"
PRESENTATION_PDF = "https://visiontripin.github.io/Bot-Control/presentation.pdf"


# ---------- анимация: одно сообщение правится кадр за кадром ----------
# Кадр «photo: URL\nподпись» — картинка (sendPhoto → editMessageMedia),
# кадр без photo: под картинкой меняет только подпись.
def _preset_frames(name, text=""):
    if name == "progress":
        return [f"{text}\n{'▰' * i}{'▱' * (10 - i)} {i * 10}%" for i in range(0, 11, 2)]
    if name == "spinner":
        return [f"{s} {text}" for s in "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"]
    if name == "countdown":
        return [f"{text} {n}…" for n in (3, 2, 1)]
    if name == "typewriter":
        step = max(1, len(text) // 12)
        return [text[:i] + "▌" for i in range(step, len(text) + 1, step)]
    if name == "slot":
        return [" | ".join(random.choice(["🍒", "🍋", "⭐", "🔔", "💎"]) for _ in range(3)) for _ in range(6)]
    if name == "matrix":
        abc = "01ﾊﾐﾋｰｳｼﾅﾓﾆｻﾜﾂｵﾘ"
        rain = ["\n".join(" ".join(random.choice(abc) for _ in range(10)) for _ in range(5)) for _ in range(4)]
        return rain + ([f"\n  {text}"] if text else [])
    return [text or "…"]


def play_animation(chat_id, frames, delay=0.7, final=None, reply_markup=None,
                   message_id=None, loops=1, pack=None, mono=False, text="", user="", **kw):
    """В BotControl вызов читает импорт; здесь — рабочая реализация."""
    who = user or "друг"
    if isinstance(frames, str):
        frames = _preset_frames(frames, text)
    frames = [f.replace("{user}", who) for f in list(frames) * max(1, loops)]
    if pack:
        final = random.choice(pack)
    photo = frames[0].startswith("photo:")

    def split(f):
        if f.startswith("photo:"):
            src, _, cap = f.partition("\n")
            return src[6:].strip(), cap
        return None, f

    m_id = message_id
    for i, frame in enumerate(frames):
        src, cap = split(frame)
        last = i == len(frames) - 1 and not final
        kb = reply_markup if last else None
        if m_id is None:
            m = (bot.send_photo(chat_id, src, caption=cap, reply_markup=kb) if photo
                 else bot.send_message(chat_id, cap, reply_markup=kb, parse_mode="HTML" if mono else None))
            m_id = m.message_id
            continue
        if i > 0 or message_id is None:
            time.sleep(delay)
        try:
            if src:
                bot.edit_message_media(InputMediaPhoto(src, caption=cap), chat_id, m_id, reply_markup=kb)
            elif photo:
                bot.edit_message_caption(cap, chat_id, m_id, reply_markup=kb)
            else:
                bot.edit_message_text(cap, chat_id, m_id, reply_markup=kb)
        except Exception:
            if src and i == 0 and message_id is not None:
                try:
                    m = bot.send_photo(chat_id, src, caption=cap, reply_markup=kb)
                    m_id = m.message_id
                except Exception:
                    pass
    if final:
        time.sleep(delay)
        final = final.replace("{user}", who)
        try:
            if photo:
                bot.edit_message_caption(final, chat_id, m_id, reply_markup=reply_markup)
            else:
                bot.edit_message_text(final, chat_id, m_id, reply_markup=reply_markup)
        except Exception:
            bot.send_message(chat_id, final, reply_markup=reply_markup)


def _edit_smart(call, text, reply_markup=None):
    """Правит текст сообщения (или подпись под фото-слайдом в standalone-режиме)."""
    chat_id = call.message.chat.id
    mid = call.message.message_id
    if getattr(call.message, "content_type", "") == "photo":
        bot.edit_message_caption(text, chat_id, mid, reply_markup=reply_markup)
    else:
        bot.edit_message_text(text, chat_id, mid, reply_markup=reply_markup)


# ---------- слайды ----------
SLIDE_COVER = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/01-cover.png\n"
    "🤖 BotControl v2.2.0 — Telegram-боты прямо с телефона (и Windows 0.7.0).\n\n"
    "Без сервера и VPS: команды, 3-уровневые меню, regex, анимации, FSM-анкеты, "
    "Stars (XTR), Mini Apps, JS + Room DB и локальный ИИ на устройстве.\n\n"
    "Листай разделы кнопками 👇",
]

SLIDE_RULES = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/02-rules.png\n"
    "🧩 Команды, кнопки и скрипты\n\n"
    "• Триггеры: команда, кнопка, фраза (целые слова) и regex.\n"
    "• Действия: текст (HTML/MarkdownV2 с автооткатом), случайный набор (ShuffleBag), "
    "анимация, кубик, медиа (фото/стикер/голос), опрос send_poll, JS-скрипт или ИИ.\n"
    "• 3-уровневые inline-подменю, copy_text, web_app и switch_inline.\n"
    "• В JS Rhino: персистентная память db в Room SQLite + http.get/post по allowlist.",
]

SLIDE_ANIM = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/03-anim.png\n"
    "🎞 Анимации и медиа\n\n"
    "Сообщение оживает кадр за кадром — бот правит его сам (с учётом HTTP 429 retry_after).\n"
    "• 10 эффектов: прогресс, спиннер, точки, отсчёт, машинка, Матрица, слот, луна, часы, сердце.\n"
    "• Свои кадры: ASCII-арт, флипбук и фото-слайды (как эти карточки).\n\n"
    "Попробуй: /progress /matrix /slot /typewriter /countdown /quest /poll",
]

SLIDE_SCHEDULE = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/04-schedule.png\n"
    "⏰ Расписание, FSM и рассылки\n\n"
    "• Напоминания: зоны дней недели, разовые даты (YYYY-MM-DD), разброс jitter, ротация « | ».\n"
    "• Пошаговые анкеты FSM без кода: текст, число, телефон, фото, геолокация и ветвления.\n"
    "• CRM подписчиков (роли user/vip/admin, теги) и массовые рассылки по ролям.\n"
    "• Автозапуск после перезагрузки (BootReceiver) и посты в канал/темы форумов.",
]

SLIDE_IMPORT = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/05-import.png\n"
    "📥 Единый импорт и экспорт (.py ↔ .bc)\n\n"
    "• Android: разбор Python (telebot / aiogram) с предпросмотром, заменой старых правил и обратным экспортом.\n"
    "• Windows 0.7.0: мультиботы в .bc на диске (ротация 10 бэкапов) + полный импорт/экспорт Android .py.\n"
    "• Мастер первого запуска за 3 шага и промт для любой LLM.\n"
    "• Полный демо-конфиг всех фич: showcase-all-ready.py (/showcase).",
]

SLIDE_AI = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/06-ai.png\n"
    "🧠 ИИ на устройстве и безопасность v2.2.0\n\n"
    "• Локальная LLM (MediaPipe / LiteRT-LM) + обработка входящих фото, документов и голосовых.\n"
    "• Сеть HTTPS-only (network_security_config: HTTP только для localhost/.local).\n"
    "• Wi-Fi сервер (:8090) под 6-значным PIN-кодом сеанса, JS watchdog 2 с.\n"
    "• «Общий лог → 🩺 Диагностика» — единый отчёт без токенов в 1 клик.",
]

SLIDE_LISTINGS = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/07-listings.png\n"
    "📢 Группы, каналы, Stars и Mini Apps\n\n"
    "• Модерация групп и форумов: кнопка-капча, антиспам/стоп-слова/антифлуд, авто-заявки, Webhook.\n"
    "• Объявления с альбомами (sendMediaGroup) и лимитами.\n"
    "• Inline-режим (@бот запрос), приём платежей Telegram Stars (XTR) и возвраты.\n"
    "• Встроенная HTML5-витрина Mini App (/miniapp/<botId>) и web_app_data.",
]

SLIDE_HOWTO = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/08-start.png\n"
    "🚀 Как начать за 2 минуты\n\n"
    "1. Скачай Android APK (v2.2.0) или BotControl.exe (0.7.0) — кнопка «🔗 Ссылки и ПК».\n"
    "2. Получи токен: @BotFather → /newbot.\n"
    "3. Нажми «+ Добавить бота» и вставь токен.\n"
    "4. Импортируй showcase-all-ready.py или presentation-bot-ready.py → ▶ Запустить!",
]

# /start: обложка, подпись «грузится», затем меню
INTRO_FRAMES = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/01-cover.png\n"
    "Загружаю презентацию BotControl v2.2.0…\n▰▱▱▱▱▱▱▱▱▱ 10%",
    "Загружаю презентацию BotControl v2.2.0…\n▰▰▰▰▱▱▱▱▱▱ 40%",
    "Загружаю презентацию BotControl v2.2.0…\n▰▰▰▰▰▰▰▱▱▱ 70%",
    "Загружаю презентацию BotControl v2.2.0…\n▰▰▰▰▰▰▰▰▰▰ 100%",
]

# «▶️ Автотур»: все слайды по очереди
TOUR_FRAMES = [
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/01-cover.png\n"
    "▶️ Автотур · 1/8 · BotControl v2.2.0 + Windows 0.7.0",
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/02-rules.png\n"
    "▶️ Автотур · 2/8 · Команды, 3-уровневые меню, JS и Room DB",
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/03-anim.png\n"
    "▶️ Автотур · 3/8 · Анимации и фото-слайды",
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/04-schedule.png\n"
    "▶️ Автотур · 4/8 · Расписание, анкеты FSM и рассылки",
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/05-import.png\n"
    "▶️ Автотур · 5/8 · Единый импорт/экспорт (.py ↔ .bc)",
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/06-ai.png\n"
    "▶️ Автотур · 6/8 · Локальный ИИ, PIN, HTTPS и 🩺 Диагностика",
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/07-listings.png\n"
    "▶️ Автотур · 7/8 · Группы, Telegram Stars (XTR) и Mini Apps",
    "photo: https://raw.githubusercontent.com/visiontripin/Bot-Control/botcontrol-v2.2.0-code43/tg-bot-control/branding/slides/08-start.png\n"
    "▶️ Автотур · 8/8 · Как начать за 2 минуты",
]

# ---------- демо «фишек» ----------
FORTUNES = [  # Предсказания слот-машины
    "🍒 🍒 🍒 — Джекпот! Сегодня твой бот запустится с первого раза.",
    "⭐ ⭐ ⭐ — Звезда на GitHub приносит удачу 😉",
    "💎 💎 💎 — Редкая удача: ни одной ошибки в 🩺 Диагностике.",
    "🔔 🍋 🍒 — Почти! Крути ещё: /slot",
]

CLARIFY_QUESTIONS = [
    "Не совсем понял тебя, {user}. Выбери раздел в /start или открой /help 👇",
    "Попробуй команды /whatsnew, /desktop, /showcase, /tour или /poll ✨",
]

QUEST_FRAMES = [
    "🌲🌲🌲🌲🌲\n🌲  🧍  🌲\n🌲🌲🌲🌲🌲\nТы у входа в лес.",
    "🌲🌲🌲🌲🌲\n🌲🧍    🌲\n🌲🌲  🌲🌲\nТропинка ведёт вглубь…",
    "🌲🌲🌲🌲🌲\n🌲    🏚️🌲\n🌲🌲🧍🌲🌲\nСтарая хижина. Дверь приоткрыта.",
    "  🏚️\n  🧍\n  🗝️\nНа столе — ключ.",
]

HOWTO_FRAMES = [
    "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK v2.2.0 или BotControl.exe 0.7.0",
    "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK v2.2.0 или BotControl.exe 0.7.0\n2️⃣ @BotFather → /newbot → токен",
    "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK v2.2.0 или BotControl.exe 0.7.0\n2️⃣ @BotFather → /newbot → токен\n"
    "3️⃣ Приложение → «+ Добавить бота»",
    "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK v2.2.0 или BotControl.exe 0.7.0\n2️⃣ @BotFather → /newbot → токен\n"
    "3️⃣ Приложение → «+ Добавить бота»\n4️⃣ Импорт showcase-all-ready.py или конструктор",
]


# ---------- клавиатуры ----------
def chat_keyboard():
    kb = ReplyKeyboardMarkup(
        resize_keyboard=True,
        one_time_keyboard=False,
        input_field_placeholder="Нажми кнопку или введи команду…",
    )
    kb.row(KeyboardButton("✨ Новое v2.2.0"), KeyboardButton("▶️ Автотур"))
    kb.row(KeyboardButton("🎰 Слот удачи"), KeyboardButton("📊 Опрос"))
    kb.row(KeyboardButton("🚀 Как начать"), KeyboardButton("🔗 Ссылки и ПК"))
    return kb


def main_menu():
    kb = InlineKeyboardMarkup()
    kb.row(InlineKeyboardButton("🧩 Команды", callback_data="p_rules"),
           InlineKeyboardButton("🎞 Анимации", callback_data="p_anim"))
    kb.row(InlineKeyboardButton("⏰ Расписание и FSM", callback_data="p_schedule"),
           InlineKeyboardButton("📥 Импорт .py/.bc", callback_data="p_import"))
    kb.row(InlineKeyboardButton("🧠 ИИ и Защита", callback_data="p_ai"),
           InlineKeyboardButton("📢 Группы и Stars", callback_data="p_listings"))
    kb.row(InlineKeyboardButton("✨ Новое v2.2.0 ▸", callback_data="p_whatsnew"),
           InlineKeyboardButton("▶️ Автотур", callback_data="p_tour"))
    kb.row(InlineKeyboardButton("🚀 Как начать", callback_data="p_howto"),
           InlineKeyboardButton("🎲 Кубик", callback_data="p_dice"))
    kb.row(InlineKeyboardButton("⬇️ Скачать APK", url=LATEST_APK_URL),
           InlineKeyboardButton("🔗 Ссылки и ПК ▸", callback_data="p_links"))
    return kb


def features_menu():
    kb = InlineKeyboardMarkup()
    kb.row(InlineKeyboardButton("1️⃣ Меню, regex, опросы", callback_data="f_s1"),
           InlineKeyboardButton("2️⃣ FSM, CRM, рассылки", callback_data="f_s2"))
    kb.row(InlineKeyboardButton("3️⃣ Группы, капча, API", callback_data="f_s3"),
           InlineKeyboardButton("4️⃣ Stars, Mini App, HTTP", callback_data="f_s4"))
    kb.row(InlineKeyboardButton("5️⃣ ПК 0.7.0 и Защита", callback_data="f_s5"),
           InlineKeyboardButton("📊 Про опрос /poll", callback_data="f_poll"))
    kb.row(InlineKeyboardButton("🛠 Кнопки v2.2.0 (ур. 3) ▸", callback_data="f_deep"),
           InlineKeyboardButton("⬅️ На слайды", callback_data="p_home"))
    return kb


def deep_demo_menu():
    kb = InlineKeyboardMarkup()
    kb.row(InlineKeyboardButton("📋 Скопировать тег", copy_text=CopyTextButton(text="BOTCONTROL-V2.2")),
           InlineKeyboardButton("🔍 Inline-поиск", switch_inline_query_current_chat="демо "))
    kb.row(InlineKeyboardButton("📱 Слайды (Web App)", web_app=WebAppInfo(url=PRESENTATION_URL)),
           InlineKeyboardButton("🧩 Демо всех фич (.py)", url=SHOWCASE_BOT_URL))
    kb.row(InlineKeyboardButton("🎰 Крутить слот удачи", callback_data="f_slot"),
           InlineKeyboardButton("⬅️ На слайды", callback_data="p_home"))
    return kb


def links_menu():
    kb = InlineKeyboardMarkup()
    kb.row(InlineKeyboardButton("⬇️ Android APK v2.2.0", url=LATEST_APK_URL),
           InlineKeyboardButton("🖥 Windows .exe 0.7.0", url=WINDOWS_EXE_URL))
    kb.row(InlineKeyboardButton("🧩 Демо всех фич (.py)", url=SHOWCASE_BOT_URL),
           InlineKeyboardButton("📦 Все релизы", url=RELEASES_URL))
    kb.row(InlineKeyboardButton("📊 Презентация (HTML)", url=PRESENTATION_URL),
           InlineKeyboardButton("📄 Презентация (PDF)", url=PRESENTATION_PDF))
    kb.row(InlineKeyboardButton("⭐ GitHub проекта", url=GITHUB_URL),
           InlineKeyboardButton("⬅️ На слайды", callback_data="p_home"))
    return kb


# ---------- команды ----------
@bot.message_handler(commands=["start"])
def cmd_start(msg):
    play_animation(msg.chat.id, INTRO_FRAMES, 0.8,
                   "👋 Привет, {user}! Это BotControl v2.2.0 — Telegram-боты прямо с телефона "
                   "(и настольная версия Windows 0.7.0).\n\n"
                   "Этот бот — живая презентация: всё настроено в конструкторе без VPS. "
                   "Листай слайды или открой «✨ Новое v2.2.0 ▸» 👇",
                   reply_markup=main_menu(),
                   user=msg.from_user.first_name or "друг")


@bot.message_handler(commands=["tour"])
def cmd_tour(msg):
    play_animation(msg.chat.id, TOUR_FRAMES, 3.0,
                   "🏁 Это все 8 слайдов, {user}. Нажми «✨ Новое v2.2.0 ▸» для деталей по этапам 1–5 👇",
                   reply_markup=main_menu(),
                   user=msg.from_user.first_name or "друг")


WHATSNEW_TEXT = (
    "✨ Что нового в BotControl v2.2.0 (Этапы 1–5) и Windows 0.7.0:\n\n"
    "• Этап 1: 3-уровневые inline-подменю, regex, HTML/MarkdownV2, опросы, медиа, copy_text/web_app.\n"
    "• Этап 2: JS + SQLite Room db, анкеты FSM, CRM подписчиков, роли, рассылки, разовые даты.\n"
    "• Этап 3: группы/форумы, кнопка-капча, антиспам/антифлуд, авто-заявки и Webhook.\n"
    "• Этап 4: Inline @бот, оплаты Telegram Stars (XTR), Mini Apps (/miniapp), http.get/post и вложения.\n"
    "• Этап 5: паритет Windows 0.7.0 (.bc + .py), HTTPS-only, PIN на Wi-Fi сервер, watchdog Rhino 2 с и 🩺 Диагностика.\n\n"
    "Выбери этап ниже для подробностей 👇"
)

DESKTOP_TEXT = (
    "🖥 BotControl для Windows / Linux / Termux (v0.7.0):\n\n"
    "• Не требует Python (готовый BotControl.exe) — веб-редактор на 127.0.0.1:8765.\n"
    "• Мультибот и автосохранение в .bc на диске с ротацией последних 10 бэкапов.\n"
    "• Полный импорт и экспорт Android .py ↔ .bc, regex, HTML/MarkdownV2, медиа, кубик, вложенные inline-кнопки и анкеты FSM.\n"
    "• Тёмная тема, экспорт диагностики и PIN-защита в LAN-режиме (--pin)."
)

LINKS_TEXT = (
    "🔗 BotControl v2.2.0 (Android) + 0.7.0 (Windows) — открытый проект.\n\n"
    "• Прямое скачивание Android APK v2.2.0 и демо-бота showcase-all-ready.py;\n"
    "• Готовый BotControl.exe 0.7.0 для Windows (без установки Python);\n"
    "• Онлайн-презентация в HTML и PDF."
)

poll_question = "Какая возможность BotControl для вас самая полезная?"
poll_options = [
    "Боты на телефоне без VPS",
    "Локальный ИИ + JS/DB",
    "Группы, Stars и Mini Apps",
    "Версия для Windows 0.7.0",
]


@bot.message_handler(commands=["whatsnew"])
def cmd_whatsnew(msg):
    bot.send_message(msg.chat.id, WHATSNEW_TEXT, reply_markup=features_menu())


@bot.message_handler(commands=["desktop"])
def cmd_desktop(msg):
    bot.send_message(msg.chat.id, DESKTOP_TEXT, reply_markup=links_menu())


@bot.message_handler(commands=["showcase"])
def cmd_showcase(msg):
    bot.send_message(msg.chat.id,
                     "🧩 Полный демо-бот всех функций: showcase-all-ready.py\n\n"
                     "Внутри собраны 18 команд, 3-уровневые меню, ASCII/фото-анимации, JS-счётчики в Room DB, "
                     "HTTP-запрос к GitHub API, опросы, regex, расписание и нижняя клавиатура с контактом/гео.\n"
                     "Импортируется без единого предупреждения и в Android v2.2.0, и в Windows 0.7.0!",
                     reply_markup=links_menu())


@bot.message_handler(commands=["kb"])
def cmd_kb(msg):
    bot.send_message(msg.chat.id,
                     "⌨️ Быстрая клавиатура презентации включена! Скрыть — /hidekb.",
                     reply_markup=chat_keyboard())


@bot.message_handler(commands=["hidekb"])
def cmd_hidekb(msg):
    bot.send_message(msg.chat.id,
                     "🙈 Клавиатура скрыта. Вернуть — /kb.",
                     reply_markup=ReplyKeyboardRemove())


@bot.message_handler(commands=["help"])
def cmd_help(msg):
    bot.send_message(msg.chat.id,
                     "📖 Что умеет бот-презентация BotControl v2.2.0:\n\n"
                     "/start — обложка и 3-уровневое меню разделов\n"
                     "/whatsnew — обзор всех 5 этапов (v1.8.0–v2.2.0)\n"
                     "/desktop — настольная версия Windows 0.7.0\n"
                     "/showcase — демо-бот всех функций (showcase-all-ready.py)\n"
                     "/tour — автотур по всем 8 слайдам\n"
                     "/howto — как запустить своего бота за 2 минуты\n"
                     "/links — скачать APK, BotControl.exe и презентацию\n"
                     "/kb и /hidekb — показать/скрыть быструю клавиатуру\n"
                     "/poll — интерактивный опрос Telegram\n"
                     "/markdown — пример разметки MarkdownV2\n\n"
                     "🎞 Анимации и игры:\n"
                     "/progress /spinner /matrix /slot /typewriter /countdown /quest /dice",
                     reply_markup=main_menu())


@bot.message_handler(commands=["links"])
def cmd_links(msg):
    bot.send_message(msg.chat.id, LINKS_TEXT, reply_markup=links_menu())


@bot.message_handler(commands=["howto"])
def cmd_howto(msg):
    play_animation(msg.chat.id, HOWTO_FRAMES, 1.2,
                   "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK v2.2.0 или BotControl.exe 0.7.0\n"
                   "2️⃣ @BotFather → /newbot → токен\n3️⃣ Приложение → «+ Добавить бота»\n"
                   "4️⃣ Импорт showcase-all-ready.py или конструктор\n5️⃣ ▶ Запустить — готово ✅",
                   mono=False, reply_markup=links_menu())


@bot.message_handler(commands=["poll"])
def cmd_poll(msg):
    bot.send_poll(msg.chat.id, poll_question, poll_options, is_anonymous=False, allows_multiple_answers=True)


@bot.message_handler(commands=["markdown"])
def cmd_markdown(msg):
    bot.send_message(msg.chat.id,
                     "*BotControl v2\\.2\\.0* — _живая презентация_ `@AppBotcontrol_bot` \\| код `showcase-all-ready.py`",
                     parse_mode="MarkdownV2")


@bot.message_handler(commands=["progress"])
def cmd_progress(msg):
    play_animation(msg.chat.id, "progress", 0.6, "✅ Готово! Так выглядит прогресс-бар.", text="Загрузка")


@bot.message_handler(commands=["spinner"])
def cmd_spinner(msg):
    play_animation(msg.chat.id, "spinner", 0.6, "✅ Данные получены.", text="Связываюсь с сервером", loops=2)


@bot.message_handler(commands=["matrix"])
def cmd_matrix(msg):
    play_animation(msg.chat.id, "matrix", 0.6, "Wake up, {user}… BotControl имеет тебя 🐇",
                   text="WAKE UP", user=msg.from_user.first_name or "друг")


@bot.message_handler(commands=["slot"])
def cmd_slot(msg):
    play_animation(msg.chat.id, "slot", 0.5, pack=FORTUNES)


@bot.message_handler(commands=["typewriter"])
def cmd_typewriter(msg):
    play_animation(msg.chat.id, "typewriter", 0.5, "BotControl v2.2.0: твой бот живёт в телефоне. ✍️",
                   text="BotControl v2.2.0: твой бот живёт в телефоне.")


@bot.message_handler(commands=["countdown"])
def cmd_countdown(msg):
    play_animation(msg.chat.id, "countdown", 1.0, "🚀 Поехали!", text="Старт через")


@bot.message_handler(commands=["quest"])
def cmd_quest(msg):
    play_animation(msg.chat.id, QUEST_FRAMES, 1.5, "🗝 Ты нашёл ключ! Конец главы 1.\nЕщё: /tour", mono=True)


@bot.message_handler(commands=["dice"])
def cmd_dice(msg):
    bot.send_dice(msg.chat.id, emoji="🎲")


# ---------- кнопки нижней клавиатуры и фразы ----------
@bot.message_handler(func=lambda m: m.text == "✨ Новое v2.2.0")
def btn_whatsnew(msg):
    bot.send_message(msg.chat.id, WHATSNEW_TEXT, reply_markup=features_menu())


@bot.message_handler(func=lambda m: m.text == "▶️ Автотур")
def btn_tour(msg):
    play_animation(msg.chat.id, TOUR_FRAMES, 3.0,
                   "🏁 Это все 8 слайдов, {user}. Нажми «✨ Новое v2.2.0 ▸» для деталей по этапам 1–5 👇",
                   reply_markup=main_menu(),
                   user=msg.from_user.first_name or "друг")


@bot.message_handler(func=lambda m: m.text == "🎰 Слот удачи")
def btn_slot(msg):
    play_animation(msg.chat.id, "slot", 0.5, pack=FORTUNES)


@bot.message_handler(func=lambda m: m.text == "📊 Опрос")
def btn_poll(msg):
    bot.send_poll(msg.chat.id, poll_question, poll_options, is_anonymous=False, allows_multiple_answers=True)


@bot.message_handler(func=lambda m: m.text == "🚀 Как начать")
def btn_howto(msg):
    play_animation(msg.chat.id, HOWTO_FRAMES, 1.2,
                   "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK v2.2.0 или BotControl.exe 0.7.0\n"
                   "2️⃣ @BotFather → /newbot → токен\n3️⃣ Приложение → «+ Добавить бота»\n"
                   "4️⃣ Импорт showcase-all-ready.py или конструктор\n5️⃣ ▶ Запустить — готово ✅",
                   mono=False, reply_markup=links_menu())


@bot.message_handler(func=lambda m: m.text == "🔗 Ссылки и ПК")
def btn_links(msg):
    bot.send_message(msg.chat.id, LINKS_TEXT, reply_markup=links_menu())


@bot.message_handler(func=lambda m: "версия" in (m.text or "").lower())
def on_version_phrase(msg):
    bot.send_message(msg.chat.id,
                     "📦 Актуальный релиз: Android v2.2.0 (code 43) и Windows 0.7.0! Подробнее — /whatsnew",
                     reply_markup=links_menu())


@bot.message_handler(func=lambda m: "скачать" in (m.text or "").lower())
def on_download_phrase(msg):
    bot.send_message(msg.chat.id, LINKS_TEXT, reply_markup=links_menu())


@bot.message_handler(func=lambda m: "windows" in (m.text or "").lower())
def on_windows_phrase(msg):
    bot.send_message(msg.chat.id, DESKTOP_TEXT, reply_markup=links_menu())


# ---------- кнопки: слайды и вложенные подменю ----------
@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    chat_id = call.message.chat.id
    mid = call.message.message_id
    if call.data == "p_home":
        play_animation(chat_id, SLIDE_COVER, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_rules":
        play_animation(chat_id, SLIDE_RULES, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_anim":
        play_animation(chat_id, SLIDE_ANIM, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_schedule":
        play_animation(chat_id, SLIDE_SCHEDULE, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_import":
        play_animation(chat_id, SLIDE_IMPORT, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_ai":
        play_animation(chat_id, SLIDE_AI, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_listings":
        play_animation(chat_id, SLIDE_LISTINGS, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_howto":
        play_animation(chat_id, SLIDE_HOWTO, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_tour":
        bot.answer_callback_query(call.id, "▶️ Автотур: 8 слайдов")
        play_animation(chat_id, TOUR_FRAMES, 3.0,
                       "🏁 Это все 8 разделов. Выбирай, что посмотреть подробнее 👇",
                       message_id=mid, reply_markup=main_menu())
    elif call.data == "p_dice":
        bot.answer_callback_query(call.id, "🎲 Бросаю!")
        bot.send_dice(chat_id, emoji="🎲")
    elif call.data == "p_whatsnew":
        bot.answer_callback_query(call.id, "Этапы 1–5 (v2.2.0)")
        _edit_smart(call,
                    "✨ Обзор всех возможностей BotControl v2.2.0 (Этапы 1–5) + Windows 0.7.0.\n"
                    "Выбери этап или открой кнопки уровня 3:",
                    reply_markup=features_menu())
    elif call.data == "p_links":
        bot.answer_callback_query(call.id, "Ссылки на скачивание")
        _edit_smart(call,
                    "🔗 Прямые ссылки: Android APK v2.2.0, BotControl.exe 0.7.0, демо-бот showcase-all-ready.py и презентация:",
                    reply_markup=links_menu())
    elif call.data == "f_s1":
        bot.answer_callback_query(call.id, "Этап 1 · v1.8.0")
        _edit_smart(call,
                    "1️⃣ Этап 1 (v1.8.0 — ядро и меню):\n"
                    "• Вложенные inline-подменю до 3 уровней с авто-кнопкой «⬅️ Назад»;\n"
                    "• Кнопки copy_text, web_app и switch_inline_query_current_chat;\n"
                    "• Regex-триггеры, поиск по целым словам (wholeWord), HTML/MarkdownV2;\n"
                    "• Опросы (send_poll), медиа по file_id/URL, ShuffleBag и ScheduleJitter.",
                    reply_markup=features_menu())
    elif call.data == "f_s2":
        bot.answer_callback_query(call.id, "Этап 2 · v1.9.0")
        _edit_smart(call,
                    "2️⃣ Этап 2 (v1.9.0 — FSM, CRM и память JS):\n"
                    "• Персистентная память db (Room SQLite) для JS-скриптов Rhino;\n"
                    "• Конструктор пошаговых анкет FSM (текст, число, телефон, фото, гео, ветвления);\n"
                    "• База подписчиков, роли (user/vip/admin), проверка подписки на канал;\n"
                    "• Массовые рассылки и разовые даты в расписании (YYYY-MM-DD).",
                    reply_markup=features_menu())
    elif call.data == "f_s3":
        bot.answer_callback_query(call.id, "Этап 3 · v2.0.0")
        _edit_smart(call,
                    "3️⃣ Этап 3 (v2.0.0 — группы, форумы и защита):\n"
                    "• Приветствие новых участников и кнопка-капча;\n"
                    "• Антиспам: стоп-слова, фильтр ссылок, антифлуд;\n"
                    "• Авто-одобрение заявок (chat_join_request), темы форумов, триггеры реакций;\n"
                    "• Опциональный локальный Webhook POST /telegram-webhook/<botId>.",
                    reply_markup=features_menu())
    elif call.data == "f_s4":
        bot.answer_callback_query(call.id, "Этап 4 · v2.1.0")
        _edit_smart(call,
                    "4️⃣ Этап 4 (v2.1.0 — Stars, Mini Apps и API):\n"
                    "• Inline-режим (@бот запрос) с каталогом ответов;\n"
                    "• Оплаты Telegram Stars (XTR): счета, авто-выдача ролей/доступа, возвраты;\n"
                    "• Встроенный Mini App (/miniapp/<botId>) и приём web_app_data;\n"
                    "• Безопасный http.get/post по белому списку доменов и передача входящих медиа.",
                    reply_markup=features_menu())
    elif call.data == "f_s5":
        bot.answer_callback_query(call.id, "Этап 5 · v2.2.0 + ПК 0.7.0")
        _edit_smart(call,
                    "5️⃣ Этап 5 (v2.2.0 + Windows 0.7.0):\n"
                    "• Windows 0.7.0: мультиботы, .bc на диске (10 бэкапов), полный .py ↔ .bc;\n"
                    "• Android: HTTPS-only (network_security_config), 6-значный PIN Wi-Fi сервера (:8090);\n"
                    "• Лимит времени JS Rhino (2 с), мастер первого запуска и предпросмотр импорта;\n"
                    "• «Общий лог → 🩺 Диагностика» — единый отчёт без токенов.",
                    reply_markup=features_menu())
    elif call.data == "f_poll":
        bot.answer_callback_query(call.id, "Нажми /poll!")
        _edit_smart(call,
                    "📊 Опрос реализован нативно через bot.send_poll():\n"
                    "Нажми /poll (или кнопку «📊 Опрос» в нижней клавиатуре /kb) — бот отправит интерактивный опрос Telegram с множественным выбором.",
                    reply_markup=features_menu())
    elif call.data == "f_deep":
        bot.answer_callback_query(call.id, "Уровень 3")
        _edit_smart(call,
                    "🛠 Уровень 3: демонстрация клиентских кнопок v2.2.0 (copy_text, switch_inline, web_app) и ссылки:",
                    reply_markup=deep_demo_menu())
    elif call.data == "f_slot":
        bot.answer_callback_query(call.id, "Крутим!")
        play_animation(chat_id, "slot", 0.5, pack=FORTUNES)


if __name__ == "__main__":
    bot.set_my_commands([
        BotCommand("start", "Презентация и меню"),
        BotCommand("whatsnew", "Что нового в v2.2.0 (Этапы 1–5)"),
        BotCommand("desktop", "Версия для Windows 0.7.0"),
        BotCommand("showcase", "Демо-бот всех функций (.py)"),
        BotCommand("tour", "Автотур по слайдам"),
        BotCommand("howto", "Как запустить своего бота"),
        BotCommand("links", "GitHub, APK и BotControl.exe"),
        BotCommand("kb", "Включить быструю клавиатуру"),
        BotCommand("hidekb", "Скрыть клавиатуру"),
        BotCommand("poll", "Интерактивный опрос"),
        BotCommand("markdown", "Демо MarkdownV2"),
        BotCommand("help", "Все команды"),
        BotCommand("progress", "Анимация: прогресс-бар"),
        BotCommand("matrix", "Анимация: Матрица"),
        BotCommand("slot", "Анимация: слот-машина"),
        BotCommand("quest", "Анимация: ASCII-квест"),
        BotCommand("dice", "Кубик"),
    ])
    bot.infinity_polling()
