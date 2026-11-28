'use strict';
// The generated format is deliberately data-only. No JavaScript/Python is evaluated.
const GENERATED_PROMPT = `Ты проектировщик Telegram-ботов для локального приложения BotControl Windows v0.7.0. Составь настройки бота по описанию ниже.

Верни ТОЛЬКО один корректный JSON-объект без Markdown и пояснений. Никакого Python, JavaScript, токенов, паролей и персональных данных. Бот запускается локально, языковая модель после импорта не используется. Не обещай функции, отсутствующие в формате; если они запрошены, не выдумывай для них поля.

Формат:
- Верхний уровень: {"format":"botcontrol","version":1,"name":"Название","scenarios":[...],"buttons":[...],"schedule":[]}. При наличии анкет добавь "fsmFlows":[...].
- Сценарий: {"id":"уникальный-id","name":"Название","text":"Ответ"}. Можно добавить "parseMode":"HTML" или "MarkdownV2". Доступны плейсхолдеры: {user}, {username}, {user_id}, {chat_id}, {args}.
- Условие запуска (необязательно): "trigger":{"type":"command","value":"/help"}, {"type":"contains","value":"привет"} или {"type":"regex","value":"^заказ\\s+\\d+$"}. Команда начинается с /, до 32 латинских букв, цифр и _. Для кнопочного сценария trigger не добавляй.
- Варианты действия (в одном сценарии — только один тип):
  1) Обычный текст ("text":"...")
  2) Случайный ответ: "replies":["Первый","Второй"] и "text":"" (1–500 вариантов)
  3) Анимация: "animation":{"preset":"spinner","text":"Загрузка","interval":700,"loops":1,"count":5,"final":"Готово","frames":""} и "text":"" (preset: spinner, progress, dots, countdown, typewriter, matrix, slot, moon, clock, heart, custom)
  4) Кубик: "diceEmoji":"🎲" (один из 🎲 🎯 🏀 ⚽ 🎳 🎰) и "text":""
  5) Медиа по file_id или URL: "media":{"type":"photo","ref":"AgAC..."} (type: photo, sticker, animation, document, video, voice, audio), подпись в "text".
- Ссылки под сообщением: "links":[{"label":"Сайт","url":"https://example.com"}], максимум 3 (только http/https без логина/пароля).
- Интерактивные inline-кнопки: "inlineButtons":[{"id":"b1","label":"Подробнее","action":"text","text":"Текст ответа","toast":"Готово"}] (action: text, pack, dice, url, copy_text, submenu).
- Кнопка клавиатуры: {"id":"уникальный-id","label":"Надпись","scenarioId":"id-сценария"}. Названия кнопок уникальны без учёта регистра и не пусты.
- Не создавай расписание без точного числового chat ID пользователя. По умолчанию schedule: [].
- Минимум одна кнопка с корректной привязкой или один сценарий с непустым trigger. Все id уникальны. Не добавляй лишних полей.

Пример:
{"format":"botcontrol","version":1,"name":"Приветствие","scenarios":[{"id":"welcome","name":"Приветствие","text":"Здравствуйте!","trigger":{"type":"command","value":"/start"}},{"id":"help","name":"Помощь","text":"Выберите кнопку."}],"buttons":[{"id":"help-button","label":"Помощь","scenarioId":"help"}],"schedule":[]}

Описание желаемого бота (замени текст ниже своим описанием):
[Напиши, что должен делать бот, какие нужны команды, ответы, кнопки и анимации.]`;

function parseGenerated(text) {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > 900000) throw Error('Ответ слишком большой: максимум 900 КБ.');
  let input = text.replace(/^\uFEFF/, '').trim();
  const fenced = input.match(/^```(?:json)?[ \t]*\r?\n([\s\S]*?)\r?\n```[ \t]*$/i);
  if (fenced) input = fenced[1];
  let bot;
  try { bot = JSON.parse(input); } catch (_) { throw Error('Ожидается один корректный JSON-объект без пояснений.'); }
  const keys = (o, allowed, context) => {
    if (!o || typeof o !== 'object' || Array.isArray(o)) throw Error('Ожидается объект: ' + context);
    const extra = Object.keys(o).filter(k => !allowed.includes(k));
    if (extra.length) throw Error('Неподдерживаемые поля в ' + context + ': ' + extra.join(', '));
  };
  const checkInline = (list) => {
    for (const btn of list) {
      keys(btn, ['id','label','action','text','toast','url','row','parseMode','diceEmoji','packReplies','submenu'], 'inline-кнопке');
      if (btn.submenu != null) {
        if (!Array.isArray(btn.submenu)) throw Error('submenu должен быть массивом.');
        checkInline(btn.submenu);
      }
    }
  };
  keys(bot, ['format','version','name','scenarios','buttons','schedule','fsmFlows'], 'боте');
  if (!Array.isArray(bot.scenarios) || !Array.isArray(bot.buttons)) throw Error('Нужны массивы scenarios и buttons.');
  for (const s of bot.scenarios) {
    keys(s, ['id','name','text','parseMode','trigger','replies','animation','diceEmoji','media','fsmFlowId','links','inlineButtons'], 'сценарии');
    if (s.animation != null && s.replies != null) throw Error('Сценарий не может быть одновременно анимацией и набором.');
    if (s.trigger != null) keys(s.trigger, ['type','value'], 'условии');
    if (s.animation != null) keys(s.animation, ['preset','text','interval','loops','count','final','frames'], 'анимации');
    if (s.media != null) keys(s.media, ['type','ref'], 'медиа');
    if (s.links != null) { if (!Array.isArray(s.links)) throw Error('links должен быть массивом.'); for (const l of s.links) keys(l, ['label','url'], 'ссылке'); }
    if (s.inlineButtons != null) { if (!Array.isArray(s.inlineButtons)) throw Error('inlineButtons должен быть массивом.'); checkInline(s.inlineButtons); }
  }
  for (const b of bot.buttons) keys(b, ['id','label','scenarioId'], 'кнопке');
  if (bot.schedule != null) {
    if (!Array.isArray(bot.schedule)) throw Error('schedule должен быть массивом.');
    for (const e of bot.schedule) keys(e, ['id','time','chatId','days','scenarioId'], 'напоминании');
  }
  if (bot.fsmFlows != null) {
    if (!Array.isArray(bot.fsmFlows)) throw Error('fsmFlows должен быть массивом.');
    for (const f of bot.fsmFlows) {
      keys(f, ['id','name','steps','summaryTemplate'], 'анкете FSM');
      if (Array.isArray(f.steps)) {
        for (const st of f.steps) keys(st, ['id','prompt','type','varName','options'], 'шаге FSM');
      }
    }
  }
  return bot;
}
if (typeof module !== 'undefined' && module.exports) module.exports = {parseGenerated, GENERATED_PROMPT};
