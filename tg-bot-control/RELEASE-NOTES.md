# BotControl v2.2.0 (code 43)

## 🔄 Паритет desktop-web (0.7.0), безопасность, диагностика и сквозные тесты

Пятый, завершающий этап дорожной карты `ДОРОЖНАЯ-КАРТА-TELEGRAM.md`:

- **Паритет `desktop-web` (`BotControl.exe 0.7.0`):**
  - Дисковое хранилище ботов `.bc` с ротацией последних 10 бэкапов (`BotDiskStore`) и мультибот-менеджер (`MultiBotManager`, без хранения токенов на диске).
  - Полный импорт и экспорт в формат Android `.py` (`import_android_py` / `export_android_py`), триггеры `regex`, `HTML`/`MarkdownV2` с безопасным откатом, `sendDice`, медиа по `file_id`/URL, вложенные inline-меню (`callback_query`), FSM-анкеты, подстановки `{user}/{args}`, тёмная тема и экспорт диагностики (`/api/diagnostics`).
  - PIN-защита LAN-режима (`--pin`) и исправленный CORS/Origin для `localhost`.
- **Безопасность и UX Android (`v2.2.0`):**
  - `res/xml/network_security_config.xml` (запрет глобального cleartext HTTP, разрешение только для `localhost`/`127.0.0.1`/`10.0.2.2`/`.local`).
  - Автоматический 6-значный PIN-код для Wi-Fi интерфейса файлового сервера `FileServer` (Webhook и Mini App работают без PIN).
  - Ограничение времени исполнения JS-скриптов в Rhino (2-секундный `ContextFactory` watchdog + лимит размера 64 КБ).
  - Мастер первого запуска на главном экране, предпросмотр импорта с опцией замены существующих правил и единый отчёт диагностики без токенов в «Общем логе» (`BotDiagnostics`).
- **Общий мок Telegram API и регрессия:**
  - Контрактные фикстуры и локальный мок-сервер `tools/shared-telegram-mock/` + единый скрипт полной проверки `tools/verify-release.sh`.

# BotControl v2.1.0 (code 42)

## ⚡ Inline-режим `@bot`, Telegram Stars (`XTR`), Mini Apps, HTTP API и входящие медиа

Четвёртый крупный этап дорожной карты `ДОРОЖНАЯ-КАРТА-TELEGRAM.md` (строго per-`botId`):

- **Inline-режим (`@bot запрос`):**
  - В `TelegramUpdatesParser` добавлены `inline_query`, `chosen_inline_result` и `pre_checkout_query` (всего 14 типов `allowed_updates`).
  - Реализованы `answerInlineQuery` и `InlineCatalogMatcher`: карточки из своих описаний, публичных правил, наборов бота и каталога товаров Telegram Stars с прямой кнопкой/ссылкой `/start buy_<id>`.
- **Платежи и Telegram Stars (`XTR`):**
  - Методы `sendInvoice`, `createInvoiceLink`, `answerPreCheckoutQuery`, `refundStarPayment` и проверка `PaymentPolicy`.
  - В `Stage4Engine` и `Stage4Screen`: каталог цифровых товаров, генерация платёжных ссылок, кнопка оплаты `action = "invoice"` в `MenuEditor`, авто-выдача текста/медиа, повышение роли (`vip`/`admin`) и тега в Room, журнал заказов `StarOrder` и возврат Stars в один тап.
- **Telegram Mini Apps (`web_app`):**
  - Обработка `web_app_data` (`Stage4Engine`), методы `answerWebAppQuery` и `setChatMenuButtonWebApp` (кнопка меню чата).
  - Встроенный HTML5-шаблон витрины и формы заявки на `GET /miniapp/<botId>` в `FileServer.kt` (с `Telegram.WebApp.sendData`) и поддержка внешнего HTTPS URL в `Stage4Screen`.
- **Внешние HTTP API и входящие медиа:**
  - В JS-песочнице `BotScript` добавлены `http.get(url, headers?)` и `http.post(url, bodyOrObj, headers?)` через `SafeHttpClient`, работающие строго по белому списку доменов `httpAllowedDomains`, плюс вебхуки на оплаты и заявки Mini App.
  - Входящие фото/документы/голосовые автоматически кэшируются в `bot_<id>/media/in_*` (до 20 МБ) и передаются в JS-скрипты (`e.caption`, `e.mimeType`, `e.fileName`, `e.fileSize`, `e.localPath`), плейсхолдеры и контекст локального ИИ (`BotBrain`).

# BotControl v2.0.0 (code 41)

## 🛡 Группы, каналы, темы форумов, авто-модерация и Webhook-адаптер

Третий крупный этап дорожной карты `ДОРОЖНАЯ-КАРТА-TELEGRAM.md` (строго per-`botId`):

- **Расширенные обновления `getUpdates` и Webhook-адаптер (`TelegramUpdates.kt` + `WebhookQueue`):**
  - Поддержка всех 11 типов `allowed_updates`: `message`, `edited_message`, `channel_post`, `edited_channel_post`, `callback_query`, `my_chat_member`, `chat_member`, `chat_join_request`, `message_reaction`, `poll`, `poll_answer`.
  - Опциональный локальный приёмник `POST /telegram-webhook/<botId>` в `FileServer` (с проверкой `X-Telegram-Bot-Api-Secret-Token`) плюс `setWebhook` / `getWebhookInfo` в интерфейсе.
- **Администрирование и авто-модерация групп (`GroupModerationEngine` + `GroupsScreen`):**
  - Методы Bot API: `banChatMember`, `unbanChatMember`, `restrictChatMember`, `promoteChatMember`, `approveChatJoinRequest`, `declineChatJoinRequest`, `getChat`, `getChatMemberCount`, `getChatAdministrators`, `createForumTopic`.
  - Настраиваемые модули на бота: авто-приветствие новых участников (с таймером удаления), кнопка-капча при входе (`restrictChatMember` + таймаут мут/кик), антиспам (стоп-слова, блокировка ссылок и `@упоминаний` с белым списком доменов, антифлуд `FloodTracker`, обход для админов группы), авто-одобрение заявок (`chat_join_request`) и быстрые инструменты управления чатом/участником/темой форума в экране «🛡 Группы, каналы и модерация».
- **Темы супергрупп (форумы), область правил, реакции и опросы:**
  - Маршрутизация `message_thread_id` в правилах (`RuleOptions.targetThreadId`), расписании (`ScheduleEvent.threadId`), темах по умолчанию и в ответах/медиа/опросах/`sendDice`/`AnimPlayer`.
  - Фильтр области чата для правил (`RuleOptions.chatScope`: везде / ЛС / группы / каналы), авто-реакция `RuleOptions.autoReactEmoji` (`setMessageReaction`), реакция бота на `message_reaction` и логирование/ответ на `poll` / `poll_answer`.

# BotControl v1.9.0 (code 40)

## 🧠 Память, пошаговые анкеты (FSM), база подписчиков, роли и массовые рассылки

Второй крупный этап дорожной карты `ДОРОЖНАЯ-КАРТА-TELEGRAM.md` (Room `version = 7`, `MIGRATION_6_7`):

- **База подписчиков (`BotUserEntity` в Room, per-`botId` + экран `UsersScreen`):**
  - Автоматический учёт пользователей и чатов при каждом сообщении и нажатии кнопки (`chatId`, `userId`, `username`, `firstName`, `chatType`, время первого/последнего визита, счётчик сообщений).
  - Назначение ролей (`user` / `vip` / `admin`), тегов, ручной бан (игнорирование спамеров) и просмотр/очистка переменных `{переменная}`.
- **Пошаговые диалоги без кода (`FsmEngine` + экран `FsmScreen`):**
  - Конструктор анкет, заявок, заказов и квизов: шаги с проверкой типа (`text`, `number`, `phone`, `photo`, `location`, `button`, `any`), ветвление по ответам (`branchMap` и `DONE`), кнопки `⬅️ Назад` (`/back`) и `❌ Отмена` (`/cancel`).
  - Собранные ответы сохраняются в `{переменные}` пользователя и автоматически отправляются админам и/или в канал (вместе с приложенными фото). Запуск по команде/фразе или по inline-кнопке (`action = "fsm"`).
- **Персистентное хранилище `db` в JS-скриптах (`BotKvEntity`):**
  - В песочнице `BotScript` (`ClassShutter { false }`) доступен объект `db`: пользовательские `db.get/set/del/incr/keys` и общие для бота `db.getGlobal/setGlobal/delGlobal/incrGlobal/globalKeys`, сохраняющиеся в SQLite Room между запусками.
- **Роли, админы и обязательная подписка на канал (`AccessPolicy`):**
  - Настройка списка Telegram ID администраторов и обязательного канала подписки в `BotSettingsScreen`.
  - В правилах (`LocalBotScreen`) доступны флаги **«Только для администраторов»**, **«Минимальная роль (VIP+ / Админ)»** и **«Разрешить без проверки подписки»**.
  - Автоматическая проверка `getChatMemberStatus` с кешем 60 с и кнопками «📢 Подписаться» + «✅ Проверить подписку».
- **Массовые рассылки и разовые даты (`BroadcastEngine` + `BroadcastScreen`):**
  - Рассылка по всей активной аудитории или сегментам (роль / тег) с подстановкой `{переменных}`, `parse_mode`, медиа и inline-меню, троттлингом ~22 сообщ./с, паузой по HTTP 429 `retry_after` и автоматической пометкой `403 blocked`.
  - Одноразовые напоминания/посты по конкретной дате `YYYY-MM-DD` (`ScheduleEvent.onceDate` в `RemindersScreen`) с авто-выключением после срабатывания.
- **Улучшения «Объявлений» (`ListingEngine`):**
  - Лимит активных объявлений на пользователя, публикация 2+ фото альбомом (`sendMediaGroup`) + контактная кнопка, смена фото объявления (`lst_editpic_`), удаление админом и уведомление администраторам при удалении.

# BotControl v1.8.0 (code 39)

## 🚀 Ядро Telegram Bot API: форматирование, медиа, опросы, подменю и умные триггеры

Первый крупный этап дорожной карты `ДОРОЖНАЯ-КАРТА-TELEGRAM.md` (Room `version = 6`, `MIGRATION_5_6`):

- **Форматирование и опции доставки (`RuleOptions` + `TelegramApi`):**
  - Поддержка `parse_mode` (`HTML` / `MarkdownV2`) для правил, кнопок и расписания с автоматическим откатом на обычный текст при ошибке разметки HTTP 400 (`can't parse entities`).
  - Флаги доставки: «Без превью ссылок» (`link_preview_options`), «Тихое уведомление» (`disable_notification`), «Запретить пересылку/копирование» (`protect_content`), «Ответ с цитатой» (`reply_parameters`), тема форума (`message_thread_id`) и скрытие нижней клавиатуры (`ReplyKeyboardRemove`).
- **Вложенные inline-меню (`I-4`) и новые типы кнопок:**
  - Многоуровневые inline-подменю (`submenu` до 3 уровней + автоматическая кнопка `⬅️ Назад` со стеком переходов по `(botId, chatId, messageId)` через `editMessageText` / `editMessageReplyMarkup`).
  - Новые действия в `MenuEditor`: `web_app` (Telegram Mini App), `copy_text` (кнопка копирования), `switch_inline` (подстановка запроса в чат), `llm` (локальный ИИ по кнопке) и `media` — с полным импортом и обратным экспортом в Python (`ScriptImporter` ↔ `SettingsExporter`).
- **Медиа-ответы, опросы и расширенный `TelegramApi` (`U-4`):**
  - Входящий `TgMessage` читает `username`, `userId`, `chatType`, `messageId`, `messageThreadId`, `caption`, `fileId`, `replyToText`, `location` и `contact`.
  - Исходящие методы Bot API: `sendSticker`, `sendAnimation`, `sendDocument`, `sendVideo`, `sendVoice`, `sendAudio`, `sendPoll`, `sendMediaGroup`, `sendLocation`, `copyMessage`, `forwardMessage`, `setMessageReaction`, `pinChatMessage`, `editMessageReplyMarkup`, `getChatMemberStatus`.
  - Новые действия правил в `LocalBotScreen`: **«🖼 Медиа»** (по `file_id`, URL или файлу) и **«📊 Опрос»** (`sendPoll`).
- **Меню команд и клавиатура чата:**
  - `setMyCommandsScoped`: поддержка `scope` (`default`, `all_private_chats`, `all_group_chats`, `all_chat_administrators`) и `language_code` в `BotSettingsScreen` и при импорте/экспорте.
  - Настройка раскладки Reply-клавиатуры (`KeyboardOptions` в `ButtonsScreen`): количество колонок (`1..4`), `one_time_keyboard`, `input_field_placeholder`, спец-кнопки запроса телефона (`request_contact`) и геолокации (`request_location`).
- **Умные триггеры, плейсхолдеры и планировщик (`U-2`, `U-3`, `U-6`):**
  - Триггер `regex` (с живой проверкой синтаксиса) и опция пословного совпадения `wholeWord` (`\p{L}\p{N}_`).
  - Расширенные плейсхолдеры `{user}`, `{username}`, `{user_id}`, `{chat_id}`, `{args}`, `{arg1..3}`, `{bot}`, `{date}`, `{time}` и новые поля JS-скриптов `handle(e)` (`e.username`, `e.userId`, `e.chatType`, `e.args`, `e.messageId`, `e.replyToText`, `e.kind`, `e.fileId`).
  - `ShuffleBag` (`U-2`: перебор набора без повторов до исчерпания) в `PackEditScreen` и `ScheduleJitter` (`U-3`: случайный разброс `±N` мин) + `threadId` / `parseMode` в `RemindersScreen`.
  - Встроенная памятка поддерживаемых конструкций импорта (`U-6`) в `ImportScreen` и обновлённые промты/гайды (`PromptScreen`, `ГАЙД-СОЗДАНИЕ-БОТА-LLM.md`, `Промт-создание-ботов.md`, `Инструкция-код-бота.md`).

# BotControl v1.7.5 (code 38)

## 🧩 Исправления импорта, расписания, диагностики и документации для LLM-ботов

- **Нативное распознавание `play_animation(...)` без `def play_animation(...)` (`I-1`).** `ScriptImporter` теперь распознаёт стандартный вызов `play_animation(...)`, даже если помощник не объявлен в файле через `def`, а при вызове других неизвестных функций с пустым результатом — явно предупреждает в разделе «Замечания», а не теряет правило молча.
- **Подхват наборов из библиотеки бота при импорте (`I-2`).** Выражения `random.choice(PACK)` / `play_animation(..., pack=PACK)` при импорте фрагмента без повторного объявления списка `PACK = [...]` теперь автоматически сопоставляются с существующими наборами текущего бота и общими наборами библиотеки (`ℹ️ Набор ... взят из библиотеки бота`).
- **Честная отчётность импортёра и явные предупреждения об обрезках (`I-3`, `I-5`).** `ImportScreen` больше не пропускает молча правила с пустым текстом — считает и показывает пропущенные правила в сводке; `ScriptImporter` явно предупреждает при обрезке `CLARIFY_QUESTIONS` (> 50), наборов (> 500), `SYSTEM_PROMPT` (> 16 000), запасного ответа (> 4 000), кнопок клавиатуры (> 12 / 32 симв.), inline-меню (> 12 кнопок / 8 рядов / 64 симв.), `setMyCommands` (> 20) и выходе параметров анимации за пределы диапазона.
- **Единая ежедневная ротация `" | "` в расписании (`U-1`).** Разделитель вариантов `" | "` (`DAY_OF_YEAR`) теперь работает одинаково и в постах в канал, и в личных напоминаниях (`ScheduleEvent.pickVariant`).
- **Лог пропущенных кадров и `retry_after` в `AnimPlayer` (`U-5`).** Пропущенные `message is not modified` кадры и паузы HTTP 429 фиксируются в журнале с номером кадра.
- **Документация и регрессионные фикстуры (`D-8`, `D-9`, `D-10`).** Обновлён `ГАЙД-СОЗДАНИЕ-БОТА-LLM.md` и `Инструкция-код-бота.md`, добавлены фикстуры `oracle-v5.py` и `oracle-broken-nohelper.py` в `tools/import-fixtures/`.

# BotControl v1.7.4 (code 37)

## ⏳ Исправление ошибки Telegram HTTP 429 (Too Many Requests)

- **Точное чтение `retry_after` и пауза по токену (`TelegramRateLimit`).** При HTTP 429 приложение и настольный раннер (`desktop-web`) теперь извлекают `parameters.retry_after` и заголовок `Retry-After`, выдерживают запрошенную паузу (с экспоненциальным fallback без `retry_after`), ведут локальный per-token кулдаун и не бьют по `api.telegram.org` внутри окна ограничения.
- **Устранены причины 429 после обновления и автозапуска:**
  - `LocalBotService`: per-`botId` `Job` и `Mutex` на запуск (предыдущий long poll и планировщик отменяются вместе через `call.cancel()` + `coroutineScope`), защита от запуска двух профилей с одним токеном, удаление лишних запросов при старте (`setMyCommands` вызывается только при изменении меню, `deleteWebhook` — только если Telegram сообщил об активном webhook), остановка `ForegroundService` при выключении всех ботов и явная проверка HTTP 401/404 вместо поиска подстроки `"401"`/`"404"`.
  - `TelegramApi`: общий пул `OkHttpClient`, отменяемые запросы, отмена лишнего 700 мс повтора при HTTP-ошибках `validateToken()`, пропуск `sendChatAction` во время 429-кулдауна и мягкий повтор коротких пауз `retry_after` (1–15 с).
  - `LocalSetupScreen` и `AnimPlayer`: защита от создания дублей профиля с одинаковым токеном, возможность сохранить профиль при HTTP 429 с автоожиданием паузы и обработка 429 на первом кадре анимаций.
  - Понятное объяснение в интерфейсе и FAQ: ограничение HTTP 429 привязано к токену бота, поэтому смена VPN и Wi-Fi не помогает — достаточно подождать указанное время.

# BotControl v1.7.3 (code 36)

## 🔗 Единый релиз в `main`

- Объединены ветки разработки в `main`: уборка репозитория и презентация из
  `v1.7.0`, конструктор кнопок клавиатуры из `v1.7.1`, исправления Android и
  Windows из `v1.7.2` + `BotControl.exe 0.6.0`.
- Актуальные ссылки на репозиторий `visiontripin/Bot-Control`, GitHub Pages
  (`https://visiontripin.github.io/Bot-Control/`) и прямые ссылки на
  скачивание APK, Windows-версии `BotControl.exe`, PDF-презентации и
  готовых конфигов ботов.
- Релизы Android больше не помечаются как pre-release: работают постоянные
  ссылки `releases/latest` и `releases/latest/download/BotControl-debug.apk`.
- Обновлена HTML/PDF-презентация (`docs/presentation.html`,
  `docs/presentation.pdf`), слайды (`branding/slides/`), лендинг
  (`docs/index.html`) и страница Matrix Links (`task.txt` /
  `docs/matrix-links.html`).
- В `FaqScreen` убрана устаревшая подсказка про удалённую кнопку
  «+ Добавить сервер» и добавлена актуальная информация о настольном
  локальном редакторе (`desktop-web/`).

# BotControl v1.7.2 (code 35) — исправления по итогам тестирования

Единый релиз исправлений для Android и Windows (`BotControl.exe` 0.6.0,
тег `botcontrol-windows-preview-0.6.0`). Полный список с приоритетами —
`tg-bot-control/СПИСОК-ИСПРАВЛЕНИЙ.md`, протокол тестов —
`tg-bot-control/РЕЦЕНЗИЯ-ТЕСТИРОВАНИЕ.md`.

## 🛠 Windows (BotControl.exe 0.6.0)

- **Сообщения больше не теряются.** Сбой ответа одному пользователю (бот
  заблокирован, чат недоступен) не обрывает пакет: остальные сообщения
  обрабатываются, причина сбоя видна в журнале с chat ID.
- **Остановка до 5 секунд и кнопка «Перезапустить».** Long poll больше не
  держит «Остановку…» до 20 секунд; перезапуск — одна кнопка (токен
  подставляется из памяти запущенного процесса).
- **localhost работает.** `http://localhost:8765/` открывается как и
  `http://127.0.0.1:8765/`; чужие адреса получают страницу с подсказкой.
- **Честные ошибки.** «Токен не принят Telegram» и «Нет связи с
  api.telegram.org» — теперь разные сообщения; причина ошибки API
  показывается в журнале.
- **Фразы-триггеры по целым словам.** «кот» не срабатывает на «скот»,
  «привет» — на «приветствую»; фразы-смайлы и знаки ищутся как подстрока.
- **Напоминания надёжнее.** Окно допуска 10 минут, повтор отправки при
  сбое, в журнале видно имя сценария и статус отправки.
- **Команда /chatid.** Бот присылает ваш chat ID.
- **Импорт списков из Android .py.** Кадры анимаций (`ANIM_*`, `*_FRAMES`,
  `photo:`) и служебные списки отсеиваются; диалог показывает превью.

## 🤖 Android

- **Автозапуск после перезагрузки (A2).** Включённые боты поднимаются сами
  после включения телефона (`BootReceiver`, переключатель в карточке бота).
- **Фоновая работа (A3).** Кнопка «🔋 Разрешить работу в фоне» — исключение
  из оптимизации батареи.
- **Параметры ИИ (A6).** Для моделей LiteRT-LM (`.litertlm`) температура и
  Top-K честно помечены как задаваемые движком.
- **Журнал (A8).** Кнопка «📤 Поделиться журналом».
- **Безопасность (S-2).** В CI добавлена проверка на живые токены ботов
  (`tools/check-no-tokens.py`).

# BotControl v1.7.1 (code 34)

- Кнопка клавиатуры и её действие (текстовый ответ или набор) настраиваются
  в одном редакторе (`ButtonsScreen`).
- При редактировании названия обновляется связанное правило; существующие
  сложные действия сохраняются.
- Добавлен локальный веб-редактор `tg-bot-control/desktop-web/` для
  Windows/Linux/Termux.

# BotControl v1.7.0 (code 33)

- Удалена устаревшая серверная часть (`server/`, `docker-compose.yml`,
  `.env.example`, `scripts/run_server.sh`, `scripts/tunnel_*`,
  `docs/CONNECT.md`, `docs/INSTALL.md`).
- Слиты дубли конфигов: `daromvpl-channel-ready.py` и `smith-bot-ready.py`.
- Кнопка «+ Добавить сервер» и вход в серверный режим убраны из UI.
- Создана HTML/PDF-презентация (`docs/presentation.html`, `docs/presentation.pdf`).

# BotControl v1.6.5 (code 32)

- **Версия в журнале:** строка запуска выглядит так:
  «🚀 [@бот] Бот запущен (правил: N) · BotControl v1.6.5».
- Включены все исправления изоляции ботов из v1.6.3–v1.6.4.
