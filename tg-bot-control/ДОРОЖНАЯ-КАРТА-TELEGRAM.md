# Дорожная карта глобальных обновлений BotControl (полный охват Telegram Bot API)

> Базовая точка: **v1.7.5 (versionCode 38)** — локальный рантайм на Android + лёгкий веб-рантайм `desktop-web/` (`BotControl.exe 0.6.0`).  
> Цель дорожной карты: последовательно закрыть все непокрытые возможности Telegram Bot API (форматирование, медиа, интерактивные меню, пошаговые сценарии FSM, базу подписчиков и рассылки, модерацию групп/каналов, inline-режим, Telegram Stars / Mini Apps и паритет Windows-версии) **без поломки существующих ботов** (`smith-bot-ready.py`, `daromvpl-channel-ready.py`, `presentation-bot-ready.py`, `oracle-v5.py`).

---

## Обзор этапов

| Этап | Целевая версия | Главный фокус | Статус |
|---|---|---|---|
| **Этап 1** | **v1.8.0** | Ядро Telegram-механик: форматирование, опции отправки, медиа по `file_id`, `regex`/аргументы/плейсхолдеры, вложенные inline-меню (`I-4`), `web_app`/`copy_text`/`switch_inline`, `U-2`/`U-3` | 🚧 **Этап 1A в работе** |
| **Этап 2** | **v1.9.0** | Память, база подписчиков, пошаговые сценарии (FSM), роли/подписки и массовые рассылки | 📋 Запланирован |
| **Этап 3** | **v2.0.0** | Группы, каналы, темы форумов, реакции, опросы и авто-модерация | 📋 Запланирован |
| **Этап 4** | **v2.1.0** | Инлайн-режим `@bot`, Telegram Stars / платежи, Mini Apps (`web_app_data`) и внешние API | 📋 Запланирован |
| **Этап 5** | **v2.2.0** | Полный паритет `desktop-web` (`BotControl.exe`), единый формат `.py` ↔ `.bc`, мастер запуска и техдолг | 📋 Запланирован |

---

## Этап 1 (v1.8.0) — «Ядро Telegram-механик, форматирование, медиа и живой диалог»

Закрывает максимум ежедневных сценариев Bot API без ломки формата ботов и с сохранением обратной совместимости БД (Room `MIGRATION_5_6`).

### Этап 1A — Ядро и импорт/экспорт (выполняется сейчас)
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

### Этап 1B — Полировка v1.8.0 (следующий шаг этапа 1)
- Настройка раскладки Reply-клавиатуры: ряды, `one_time_keyboard`, `input_field_placeholder`, `ReplyKeyboardRemove`, спецкнопки `request_contact` и `request_location`.
- Конструктор опросов (`poll`) и редактор `scope` команд прямо в UI `BotSettingsScreen` / `LocalBotScreen`.
- `U-6`: встроенная карточка/справка поддерживаемых конструкций импорта и быстрый предпросмотр предупреждений до сохранения.
- Расширенные команды/подсказки в `ГАЙД-СОЗДАНИЕ-БОТА-LLM.md`, `Промт-создание-ботов.md`, `Инструкция-код-бота.md`.

---

## Этап 2 (v1.9.0) — «Память, пошаговые сценарии (FSM), роли и рассылки»

1. **База подписчиков и чатов (`BotUserEntity` в Room, per-`botId`):**
   - Автоматический учёт пользователей/чатов (`userId`, `chatId`, `username`, `firstName`, `lastSeenAt`, `role`, `isBlocked`, `varsJson`).
   - Экран «Подписчики» в приложении: поиск, фильтры, экспорт, бан/роль.
2. **Массовые рассылки и отложенные посты:**
   - Рассылка по всей активной базе подписчиков бота или по сегменту (тег/роль), с rate-limit троттлингом (до ~25 msg/sec, уважение `TelegramRateLimit` 429) и автоматической пометкой недоступных (`403 blocked`).
   - Одноразовые отложенные задачи по конкретной дате/времени (в дополнение к еженедельному расписанию).
3. **Роли, права доступа и проверка подписки:**
   - Список `adminIds` на бота, флаг правила/кнопки «только для админов/владельца».
   - Встроенная проверка обязательной подписки на канал/группу через `getChatMemberStatus` (экран блокировки с кнопкой «Я подписался — проверить»).
4. **Универсальный конструктор пошаговых диалогов (FSM) и персистентный JS `db`:**
   - Цепочки шагов (анкеты, заявки, заказы, квизы): запрос → валидация типа (`text`/`number`/`phone`/`photo`/`location`/`button`) → сохранение в `{var}` → ветвление/отправка итоговой заявки админу или в канал.
   - Персистентное KV-хранилище `db.get(key)` / `db.set(key, value)` (per-`botId` + `userId`) внутри `BotScript`.
5. **Развитие «Объявлений» (`ListingEngine`):**
   - Публикация мульти-фото постов альбомом (`sendMediaGroupTo`) с отдельной кнопкой/ссылкой связи, редактирование фото в визарде, лимит объявлений на пользователя, уведомления админам с кнопкой «Удалить из канала».

---

## Этап 3 (v2.0.0) — «Группы, каналы, форумы и авто-модерация»

1. **Расширенные обновления `getUpdates` (+ опциональный Webhook-адаптер):**
   - `allowed_updates`: `edited_message`, `channel_post`, `edited_channel_post`, `my_chat_member`, `chat_member`, `chat_join_request`, `message_reaction`, `poll`, `poll_answer`.
2. **Администрирование и модерация групп:**
   - Методы Bot API: `banChatMember`, `unbanChatMember`, `restrictChatMember`, `promoteChatMember`, `approveChatJoinRequest`, `declineChatJoinRequest`, `getChat`, `getChatAdministrators`.
   - Готовые модули правил: авто-приветствие и кнопка-капча для новых участников, антиспам/стоп-слова/удаление ссылок с авто-мутом, авто-приём заявок.
3. **Темы супергрупп (форумы), реакции и опросы:**
   - Полная маршрутизация `message_thread_id` в правилах и расписании, авто-реакции (`setMessageReaction`), реакция бота на `message_reaction` и `poll_answer`.

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
