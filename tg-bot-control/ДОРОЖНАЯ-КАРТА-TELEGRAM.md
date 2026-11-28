# Дорожная карта глобальных обновлений BotControl (полный охват Telegram Bot API)

> Базовая точка: **v1.7.5 (versionCode 38)** — локальный рантайм на Android + лёгкий веб-рантайм `desktop-web/` (`BotControl.exe 0.6.0`).  
> Цель дорожной карты: последовательно закрыть все непокрытые возможности Telegram Bot API (форматирование, медиа, интерактивные меню, пошаговые сценарии FSM, базу подписчиков и рассылки, модерацию групп/каналов, inline-режим, Telegram Stars / Mini Apps и паритет Windows-версии) **без поломки существующих ботов** (`smith-bot-ready.py`, `daromvpl-channel-ready.py`, `presentation-bot-ready.py`, `oracle-v5.py`).

---

## Обзор этапов

| Этап | Целевая версия | Главный фокус | Статус |
|---|---|---|---|
| **Этап 1** | **v1.8.0** | Ядро Telegram-механик: форматирование, опции отправки, медиа по `file_id`, `regex`/аргументы/плейсхолдеры, вложенные inline-меню (`I-4`), `web_app`/`copy_text`/`switch_inline`, `KeyboardOptions`/`ReplyKeyboardRemove`, `U-2`/`U-3`/`U-6` | ✅ **Выполнен (v1.8.0, code 39)** |
| **Этап 2** | **v1.9.0** | Память, база подписчиков (`BotUserEntity`), персистентный JS `db` (`BotKvEntity`), пошаговые сценарии (`FsmEngine`), роли/админы/обязательная подписка, массовые рассылки (`BroadcastEngine`), разовые даты и альбомы объявлений | ✅ **Выполнен (v1.9.0, code 40)** |
| **Этап 3** | **v2.0.0** | Группы, каналы, темы форумов, реакции, опросы, авто-модерация (`GroupModerationEngine` + `GroupsScreen`) и Webhook-адаптер | ✅ **Выполнен (v2.0.0, code 41)** |
| **Этап 4** | **v2.1.0** | Инлайн-режим `@bot`, Telegram Stars / платежи, Mini Apps (`web_app_data`) и внешние API | 📋 Запланирован |
| **Этап 5** | **v2.2.0** | Полный паритет `desktop-web` (`BotControl.exe`), единый формат `.py` ↔ `.bc`, мастер запуска и техдолг | 📋 Запланирован |

---

## Этап 1 (v1.8.0) — «Ядро Telegram-механик, форматирование, медиа и живой диалог»

Закрывает максимум ежедневных сценариев Bot API без ломки формата ботов и с сохранением обратной совместимости БД (Room `MIGRATION_5_6`).

### Этап 1A — Ядро и импорт/экспорт (✅ выполнено в v1.8.0)
- **Форматирование и параметры сообщений (`TelegramApi` + `RuleOptions`):**
  - Поддержка `parse_mode` (`HTML` / `MarkdownV2`) для обычных правил, кнопок и расписания — с безопасным авто-откатом на обычный текст при ошибке разметки HTTP 400 (`can't parse entities`), чтобы бот никогда не замолкал.
  - Флаги доставки: `disablePreview` (`link_preview_options`), `silent` (`disable_notification`), `protectContent` (`protect_content`), `replyTo` (`reply_to_message_id`), `threadId` (`message_thread_id`).
- **Вложенные inline-меню и новые типы кнопок (`I-4` + Bot API):**
  - Вложенные/переключаемые меню `action = "submenu"` и кнопка возврата `"back"` со стеком истории переходов по `(botId, chatId, messageId)` через `editMessageText` / `editMessageReplyMarkup`.
  - Новые действия в `InlineBtn`: `web_app` (Telegram Mini App), `copy_text` (кнопка копирования), `switch_inline` (`switch_inline_query_current_chat`), `llm` (локальная ИИ по кнопке), `media`, а также `edit = true` для `script`.
  - Автоматическое распознавание вложенных меню и новых кнопок в `ScriptImporter` + обратный экспорт в `SettingsExporter`.
- **Медиа-ответы и расширенный `TelegramApi` (`U-4` + медиа):**
  - Расширенный `TgMessage`: `username`, `userId`, `chatType`, `messageId`, `messageThreadId`, `caption`, `fileId`, `replyToText`, `locationLat/Lon`, `contactPhone`.
  - Исходящие методы Bot API: `sendSticker`, `sendAnimation` (GIF), `sendDocument`, `sendVideo`, `sendVoice`, `sendAudio`, `sendPoll`, `sendMediaGroup` (альбом фото), `sendLocation`, `copyMessage`, `forwardMessage`, `setMessageReaction`, `pinChatMessage`, `editMessageReplyMarkup`, `getChatMemberStatus`.
  - Новые типы действий правил: `media` (отправка стикера/GIF/файла/видео/голосового/аудио по `file_id` или URL) и `poll` (опрос/викторина).
  - Поддержка `scope` (`default`, `all_private_chats`, `all_group_chats`, `all_chat_administrators`) и `languageCode` в `setMyCommands`.
- **Умные триггеры, плейсхолдеры и планировщик (`U-2`, `U-3`):**
  - Триггер `regex` + опция пословного совпадения `wholeWord` (Unicode `\p{L}\p{N}_`, аналог W5 из `desktop-web`) для правил «Содержит».
  - Плейсхолдеры в ответах: `{user}`, `{username}`, `{user_id}`, `{chat_id}`, `{args}`, `{arg1}`, `{arg2}`, `{arg3}`, `{bot}`, `{date}`, `{time}`.
  - Расширение Rhino JS-песочницы (`BotScript`): поля `e.username`, `e.userId`, `e.chatType`, `e.args`, `e.messageId`, `e.replyToText`, `e.kind`, `e.fileId`.
  - `U-2` (`ShuffleBag`): режим перебора наборов ответов «без повторов до конца списка».
  - `U-3` (`ScheduleJitter`): случайный разброс времени напоминаний `±N` минут + защита от слипания.
- **UI и диагностика:**
  - Настройки форматирования/флагов/медиа/regex в `LocalBotScreen`, редактор подменю и новых типов кнопок в `MenuEditor`, `shuffleBag` в `PacksScreen`, `jitterMin` в `RemindersScreen`, отображение метаданных в `SimulateScreen` и `ButtonsScreen`.

### Этап 1B — Полировка v1.8.0 (✅ выполнено в v1.8.0)
- Настройка раскладки Reply-клавиатуры: ряды, `one_time_keyboard`, `input_field_placeholder`, `ReplyKeyboardRemove`, спецкнопки `request_contact` и `request_location`.
- Конструктор опросов (`poll`) и редактор `scope` команд прямо в UI `BotSettingsScreen` / `LocalBotScreen`.
- `U-6`: встроенная карточка/справка поддерживаемых конструкций импорта и быстрый предпросмотр предупреждений до сохранения.
- Расширенные команды/подсказки в `ГАЙД-СОЗДАНИЕ-БОТА-LLM.md`, `Промт-создание-ботов.md`, `Инструкция-код-бота.md`.

---

## Этап 2 (v1.9.0) — «Память, пошаговые сценарии (FSM), роли и рассылки» (✅ выполнено в v1.9.0)

1. **База подписчиков и чатов (`BotUserEntity` в Room, `MIGRATION_6_7`, per-`botId`):**
   - Автоматический учёт пользователей/чатов (`userId`, `chatId`, `username`, `firstName`, `chatType`, `firstSeenAt`, `lastSeenAt`, `msgCount`, `role`, `tags`, `isBlocked`, `isBanned`, `varsJson`).
   - Экран `UsersScreen` («👥 Подписчики, роли и теги»): поиск, фильтры (`Все`, `VIP`, `Админ`, `Бан`, `403`), ручное добавление по ID, редактирование ролей/тегов/бана и переменных `{var}`.
2. **Массовые рассылки и отложенные посты (`BroadcastEngine` + `BroadcastScreen`):**
   - Сегментация рассылок (`ALL`, `ROLE_USER`, `ROLE_VIP`, `ROLE_ADMIN`, `TAG`), поддержка плейсхолдеров (включая `{переменные}`), `parse_mode`, медиа и inline-кнопок.
   - Rate-limit троттлинг (~22 msg/s), пауза по `retry_after` при HTTP 429, автоматическая пометка `isBlocked = true` при HTTP 403, живой прогресс-бар и кнопка остановки.
   - Одноразовые отложенные напоминания/посты по конкретной дате (`ScheduleEvent.onceDate`, `YYYY-MM-DD`) с автоматическим выключением после успешного срабатывания.
3. **Роли, права доступа и проверка подписки (`AccessPolicy`):**
   - Список `adminIds` на бота, обязательный канал подписки (`requiredChannel`, ссылка, свой текст) в `BotSettingsScreen`.
   - Флаги правил в `LocalBotScreen` (`RuleOptions.adminOnly`, `requiredRole`, `skipSubCheck`) и автоматическая проверка `getChatMemberStatus` (кеш 60 с) с кнопкой «✅ Проверить подписку» (`sub_check`).
4. **Универсальный конструктор пошаговых диалогов (`FsmEngine` + `FsmScreen`) и персистентный JS `db` (`BotKvEntity`):**
   - Пошаговые анкеты без кода: шаги с валидацией типов (`text`, `number`, `phone`, `photo`, `location`, `button`, `any`), ветвлением (`branchMap` / `"DONE"`), навигацией `/back` и `/cancel`, сохранением ответов в `{var}` и отправкой сводки админам/в канал; действие inline-кнопки `"fsm"`.
   - Персистентное KV-хранилище `db` (`get`, `set`, `del`, `incr`, `keys`, `getGlobal`, `setGlobal`, `delGlobal`, `incrGlobal`, `globalKeys`) внутри песочницы `BotScript` (`ClassShutter { false }`), хранящееся в Room по `(botId, scopeKey, kvKey)`.
5. **Развитие «Объявлений» (`ListingEngine`):**
   - Лимит активных объявлений на пользователя (`listingMaxPerUser`), публикация 2+ фото одним альбомом (`sendMediaGroup` + контактная кнопка, `listingUseAlbum`), смена фото (`lst_editpic_`) и текста (`lst_edit_`), права админа на удаление и уведомления админам при удалении объявления.

---

## Этап 3 (v2.0.0) — «Группы, каналы, форумы и авто-модерация» (✅ выполнено в v2.0.0, code 41)

1. **Расширенные обновления `getUpdates` (+ опциональный Webhook-адаптер):**
   - Единый парсер `TelegramUpdatesParser` + `MiniJson` (`TelegramUpdates.kt`): 11 типов `allowed_updates` (`message`, `edited_message`, `channel_post`, `edited_channel_post`, `callback_query`, `my_chat_member`, `chat_member`, `chat_join_request`, `message_reaction`, `poll`, `poll_answer`).
   - Опциональный локальный Webhook-приёмник: `WebhookQueue` + эндпоинт `POST /telegram-webhook/<botId>` в `FileServer.kt` (с проверкой `X-Telegram-Bot-Api-Secret-Token` по per-`botId` секрету), а также методы `setWebhook` и `getWebhookInfo` в `TelegramApi.kt`.
2. **Администрирование и модерация групп (`GroupModerationEngine` + `ModerationPolicy` + `GroupsScreen`):**
   - Методы Bot API в `TelegramApi.kt`: `banChatMember`, `unbanChatMember`, `restrictChatMember`, `promoteChatMember`, `approveChatJoinRequest`, `declineChatJoinRequest`, `getChat`, `getChatMemberCount`, `getChatAdministrators`, `createForumTopic`.
   - Готовые модули на бота (`GroupModerationConfig` в `LocalBotStore`): приветствие новых участников с автоудалением, кнопка-капча при входе (`cap_<chatId>_<userId>`, `restrictChatMember` + таймаут мут/кик), антиспам (стоп-слова, блокировка ссылок/`@упоминаний` с белым списком доменов, антифлуд `FloodTracker`, обход для админов по `adminIds` + кэшу `getChatAdministrators`), авто-приём заявок (`chat_join_request`) и быстрое управление чатами/темами/участниками в `GroupsScreen` (`Routes.GROUPS`).
3. **Темы супергрупп (форумы), реакции и опросы:**
   - Маршрутизация `message_thread_id` в `RuleOptions.targetThreadId`, `ScheduleEvent.threadId` и `GroupModerationConfig.defaultThreadId` (`ModerationPolicy.resolveThreadId`), фильтр области чата правил (`RuleOptions.chatScope`: везде / ЛС / группы / каналы), поддержка `messageThreadId` в `sendMessage`, медиа, опросах, `sendDice` и `AnimPlayer`, а также экспорт `message_thread_id` в `SettingsExporter`.
   - Авто-реакция `RuleOptions.autoReactEmoji` (`setMessageReaction`), триггеры на входящие реакции `message_reaction` и логирование/ответ на `poll` / `poll_answer`.
   - Регрессионный набор `Stage3Test.kt` (20 проверок) и проверка изоляции `check-bot-isolation.py`.

---

## Этап 4 (v2.1.0) — «Инлайн-режим, Telegram Stars, Mini Apps и внешние интеграции»

1. **Inline Mode (`@bot запрос`):**
   - Обработка `inline_query` и `chosen_inline_result`, метод `answerInlineQuery` (карточки из наборов, правил и каталогов).
2. **Платежи и Telegram Stars (`XTR`):**
   - `sendInvoice`, `createInvoiceLink`, `answerPreCheckoutQuery`, обработка `successful_payment` и `refundStarPayment`, автоматическая выдача контента/роли после оплаты.
3. **Telegram Mini Apps (`web_app`):**
   - Обработка `web_app_data`, метод `answerWebAppQuery`, встроенный локальный шаблон витрины/формы или подключение внешнего HTTPS Mini App.
4. **Внешние API-интеграции и обработка входящих медиа:**
   - Безопасный `http.get/post` с белым списком доменов в настройках бота (вебхуки в CRM, Google Таблицы, внешние сервисы).
   - Передача входящих фото/документов/голосовых в сценарии и мультимодальные локальные модели.

---

## Этап 5 (v2.2.0) — «Паритет `desktop-web` (`BotControl.exe`) и закрытие техдолга»

1. **Настольная/веб-версия (`tg-bot-control/desktop-web/`):**
   - Мультиботы (`U-W1`) и хранение конфигов на диске с ротацией бэкапов (`U-W2`).
   - Обработка `callback_query`, вложенные inline-меню, `parse_mode`, медиа по `file_id`, `sendDice`, `regex`, FSM и база подписчиков.
   - Полноценный импорт/экспорт Android `.py` ↔ `.bc` (`U-X1`) и общий Telegram-мок сервер для сквозных автотестов (`U-X2`).
2. **Инфраструктура, безопасность и UX (`U-A1..U-A4`, `A10`, `S-1`):**
   - Release-подпись и per-ABI сборки (`U-A1`), мастер первого запуска (`U-A2`), предпросмотр/diff импорта (`U-A3`), упрощённая навигация (`U-A4`).
   - Физическая очистка архивных серверных экранов, `networkSecurityConfig` для локальных подсетей (`A10`), единый экспорт диагностики (`U-X3`).
   - Напоминание владельцу: отозвать старый токен `8709443116` из истории git через `@BotFather` (`/revoke`, `S-1`).
