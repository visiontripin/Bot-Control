"""Local BotControl editor and Telegram polling runtime; no external dependencies."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import hashlib
import json
import platform
import re
import random
import secrets
from collections import deque
from datetime import datetime
import ipaddress
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from urllib.parse import urlsplit
import webbrowser
from animations import frames, validate_animation
from android_import import extract_lists, import_android_py, export_android_py

APP_VERSION = '0.7.0'
ROOT = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
ALLOWED_DICE = ('🎲', '🎯', '🏀', '⚽', '🎳', '🎰')
ALLOWED_MEDIA = {
    'photo': ('sendPhoto', 'photo'),
    'sticker': ('sendSticker', 'sticker'),
    'animation': ('sendAnimation', 'animation'),
    'document': ('sendDocument', 'document'),
    'video': ('sendVideo', 'video'),
    'voice': ('sendVoice', 'voice'),
    'audio': ('sendAudio', 'audio'),
}


def _safe_id(raw: str) -> str:
    cleaned = re.sub(r'[^A-Za-z0-9._-]', '_', (raw or '').strip())[:64].strip('._')
    return cleaned or 'default'


def _build_inline_tree(links, inline_buttons, prefix, callbacks_out, parent_markup=None, depth=0):
    """Строит Telegram inline_keyboard с поддержкой вложенных подменю и регистрирует callback_data."""
    rows = []
    if links:
        rows.append([{'text': l['label'], 'url': l['url']} for l in links])
    if inline_buttons:
        if depth > 3:
            raise ValueError('Максимальная глубина вложенных подменю — 3 уровня.')
        grouped = {}
        for idx, btn in enumerate(inline_buttons):
            if not isinstance(btn, dict):
                raise ValueError('Некорректная inline-кнопка.')
            label = btn.get('label')
            if not isinstance(label, str) or not label.strip() or len(label) > 64:
                raise ValueError('Название inline-кнопки должно быть от 1 до 64 символов.')
            action = btn.get('action') or 'text'
            if action not in ('text', 'pack', 'dice', 'url', 'copy_text', 'submenu', 'back'):
                raise ValueError(f'Неизвестное действие inline-кнопки: {action}')
            row_idx = btn.get('row', idx)
            if not isinstance(row_idx, int) or row_idx < 0:
                row_idx = idx
            grouped.setdefault(row_idx, []).append((idx, btn))

        current_markup = {'inline_keyboard': []}
        for row_idx in sorted(grouped):
            tg_row = []
            for idx, btn in grouped[row_idx]:
                label = btn['label'].strip()
                act = btn.get('action') or 'text'
                if act == 'url':
                    url = btn.get('url', '')
                    parsed = urlsplit(url)
                    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password:
                        raise ValueError('URL inline-кнопки должен быть http(s) без логина/пароля.')
                    tg_row.append({'text': label, 'url': url})
                elif act == 'copy_text':
                    copy_txt = str(btn.get('text') or label)[:256]
                    tg_row.append({'text': label, 'copy_text': {'text': copy_txt}})
                else:
                    cb_data = f'{prefix}:{idx}'
                    if len(cb_data.encode('utf-8')) > 64:
                        cb_data = 'cb:' + hashlib.sha1(cb_data.encode()).hexdigest()[:16]
                    entry = {
                        'action': act,
                        'text': str(btn.get('text') or ''),
                        'toast': str(btn.get('toast') or '')[:200],
                        'parseMode': btn.get('parseMode') if btn.get('parseMode') in ('HTML', 'MarkdownV2') else '',
                        'diceEmoji': btn.get('diceEmoji') if btn.get('diceEmoji') in ALLOWED_DICE else '🎲',
                        'packReplies': list(btn.get('packReplies') or []),
                        'parentMarkup': parent_markup,
                    }
                    if act == 'submenu':
                        sub = btn.get('submenu') or []
                        if not isinstance(sub, list) or not sub:
                            raise ValueError('Подменю inline-кнопки не может быть пустым.')
                        entry['targetMarkup'] = _build_inline_tree(
                            [], sub, f'{cb_data}s', callbacks_out,
                            parent_markup=current_markup, depth=depth + 1,
                        )
                    callbacks_out[cb_data] = entry
                    tg_row.append({'text': label, 'callback_data': cb_data})
            if tg_row:
                current_markup['inline_keyboard'].append(tg_row)
        rows.extend(current_markup['inline_keyboard'])
    return {'inline_keyboard': rows} if rows else None


def compile_bot_full(bot):
    if not isinstance(bot, dict) or not isinstance(bot.get('buttons'), list) or not isinstance(bot.get('scenarios'), list):
        raise ValueError('Некорректные данные бота.')

    fsm_flows = {}
    for flow in (bot.get('fsmFlows') or []):
        if not isinstance(flow, dict) or not isinstance(flow.get('id'), str) or not flow['id']:
            raise ValueError('Некорректный FSM-поток.')
        steps = flow.get('steps') or []
        if not isinstance(steps, list) or not steps:
            raise ValueError('FSM-поток должен содержать минимум 1 шаг.')
        norm_steps = []
        for st in steps:
            if not isinstance(st, dict) or not isinstance(st.get('prompt'), str) or not st['prompt'].strip():
                raise ValueError('Каждый шаг анкеты должен иметь вопрос.')
            st_type = st.get('type') or 'text'
            if st_type not in ('text', 'number', 'phone', 'button', 'any'):
                st_type = 'text'
            norm_steps.append({
                'id': str(st.get('id') or len(norm_steps)),
                'prompt': st['prompt'].strip(),
                'type': st_type,
                'varName': str(st.get('varName') or f'step{len(norm_steps) + 1}').strip(),
                'options': [str(o) for o in (st.get('options') or []) if str(o).strip()],
            })
        fsm_flows[flow['id']] = {
            'id': flow['id'],
            'name': str(flow.get('name') or 'Анкета'),
            'steps': norm_steps,
            'summaryTemplate': str(flow.get('summaryTemplate') or 'Спасибо! Заявка сохранена.'),
        }

    scenarios = {}
    triggers = []
    callbacks = {}
    seen_commands = set()
    for sc_idx, s in enumerate(bot['scenarios']):
        if not isinstance(s, dict) or not isinstance(s.get('id'), str):
            raise ValueError('Некорректный сценарий.')
        if s.get('animation') is not None:
            validate_animation(s['animation'])
            sc_obj = {'animation': s['animation']}
        elif s.get('replies') is not None:
            replies = s['replies']
            if not isinstance(replies, list) or not replies or len(replies) > 500 or any(not isinstance(x, str) or not x.strip() or len(x) > 4096 for x in replies):
                raise ValueError('Набор должен содержать от 1 до 500 непустых ответов до 4096 символов.')
            sc_obj = {'replies': replies}
        elif s.get('diceEmoji') is not None:
            emoji = s.get('diceEmoji') or '🎲'
            if emoji not in ALLOWED_DICE:
                raise ValueError('Допустимые эмодзи кубика: 🎲 🎯 🏀 ⚽ 🎳 🎰.')
            sc_obj = {'diceEmoji': emoji}
        elif s.get('media') is not None:
            media = s['media']
            if not isinstance(media, dict) or media.get('type') not in ALLOWED_MEDIA or not isinstance(media.get('ref'), str) or not media['ref'].strip() or len(media['ref']) > 2048:
                raise ValueError('Укажите корректный тип и file_id/URL медиа.')
            sc_obj = {'media': {'type': media['type'], 'ref': media['ref'].strip()}, 'text': str(s.get('text') or '')}
        elif s.get('fsmFlowId'):
            fid = str(s['fsmFlowId'])
            if fid not in fsm_flows:
                raise ValueError(f'Указана несуществующая анкета FSM: {fid}')
            sc_obj = {'fsmFlowId': fid, 'text': str(s.get('text') or '')}
        else:
            text = s.get('text')
            if not isinstance(text, str):
                raise ValueError('Некорректный ответ.')
            sc_obj = {'text': text}

        sc_obj['name'] = s.get('name') or ''
        pm = s.get('parseMode') or ''
        if pm in ('HTML', 'MarkdownV2'):
            sc_obj['parseMode'] = pm

        links = s.get('links', [])
        if not isinstance(links, list) or len(links) > 3:
            raise ValueError('Можно добавить не более трёх ссылок под сообщением.')
        valid_links = []
        for link in links:
            if not isinstance(link, dict) or not isinstance(link.get('label'), str) or not link['label'].strip() or len(link['label']) > 100 or not isinstance(link.get('url'), str) or len(link['url']) > 2048:
                raise ValueError('Заполните название и URL кнопки-ссылки.')
            try:
                parsed = urlsplit(link['url'])
            except ValueError:
                raise ValueError('Неверный URL кнопки.') from None
            if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password:
                raise ValueError('Кнопка-ссылка должна содержать URL http или https без учётных данных.')
            valid_links.append({'label': link['label'], 'url': link['url']})

        inline_buttons = s.get('inlineButtons') or []
        if not isinstance(inline_buttons, list) or len(inline_buttons) > 30:
            raise ValueError('Не более 30 inline-кнопок на сценарий.')

        inline_markup = _build_inline_tree(valid_links, inline_buttons, f'cb:{sc_idx}', callbacks)
        if inline_markup:
            sc_obj['inline'] = inline_markup

        scenarios[s['id']] = sc_obj

        trigger = s.get('trigger')
        if trigger is not None:
            if not isinstance(trigger, dict) or trigger.get('type') not in ('command', 'contains', 'regex') or not isinstance(trigger.get('value'), str):
                raise ValueError('Некорректное условие сценария.')
            kind, value = trigger['type'], trigger['value'].strip()
            if not value or len(value) > 200:
                raise ValueError('Укажите команду, фразу или regex длиной до 200 символов.')
            compiled_regex = None
            if kind == 'command':
                if not re.fullmatch(r'/[a-zA-Z0-9_]{1,32}', value):
                    raise ValueError('Команда должна начинаться с / и содержать до 32 латинских букв, цифр или _.')
                if value.lower() in seen_commands:
                    raise ValueError('Команды не должны повторяться.')
                seen_commands.add(value.lower())
            elif kind == 'regex':
                try:
                    compiled_regex = re.compile(value, re.IGNORECASE)
                except re.error as exc:
                    raise ValueError(f'Некорректное регулярное выражение: {exc}') from None

            if 'text' in sc_obj and 'media' not in sc_obj and 'fsmFlowId' not in sc_obj:
                if not sc_obj['text'].strip() or len(sc_obj['text']) > 4096:
                    raise ValueError('Заполните ответ для сценария с условием (до 4096 символов).')
            triggers.append((kind, compiled_regex if kind == 'regex' else (value.casefold() if kind == 'command' else value), sc_obj))

    buttons = [(b.get('label'), scenarios.get(b.get('scenarioId'))) for b in bot['buttons'] if isinstance(b, dict)]
    if any(
        not isinstance(label, str) or not label.strip() or len(label) > 100 or not isinstance(action, dict)
        or ('text' in action and 'media' not in action and 'fsmFlowId' not in action and (not action['text'].strip() or len(action['text']) > 4096))
        for label, action in buttons
    ):
        raise ValueError('Добавьте кнопки с непустыми названиями и сценариями (ответ до 4096 символов).')

    schedule = bot.get('schedule', [])
    if not isinstance(schedule, list) or len(schedule) > 100:
        raise ValueError('Недопустимое расписание.')
    events = []
    for item in schedule:
        if not isinstance(item, dict) or not isinstance(item.get('time'), str) or not re.fullmatch(r'(?:[01][0-9]|2[0-3]):[0-5][0-9]', item['time']):
            raise ValueError('Укажите время напоминания в формате ЧЧ:ММ.')
        chat = item.get('chatId')
        if not isinstance(chat, str) or not re.fullmatch(r'-?[0-9]{1,20}', chat) or int(chat) == 0:
            raise ValueError('Укажите числовой chat ID для напоминания.')
        days = item.get('days')
        if not isinstance(days, list) or not days or any(type(d) is not int or d < 0 or d > 6 for d in days):
            raise ValueError('Укажите дни напоминания.')
        action = scenarios.get(item.get('scenarioId'))
        if not action or ('text' in action and 'media' not in action and 'fsmFlowId' not in action and (not action['text'].strip() or len(action['text']) > 4096)):
            raise ValueError('Укажите непустой сценарий для напоминания.')
        events.append((item['time'], int(chat), tuple(days), action))

    if not buttons and not triggers and not events:
        raise ValueError('Добавьте кнопку, условие сценария или напоминание.')
    if len({label.casefold() for label, _ in buttons}) != len(buttons):
        raise ValueError('Названия кнопок должны быть уникальными.')
    if len(buttons) > 100:
        raise ValueError('Допускается не более 100 кнопок.')

    return buttons, triggers, events, callbacks, fsm_flows


def compile_bot(bot):
    """Совместимость 0.6.0: возвращает кортеж (buttons, triggers, events)."""
    buttons, triggers, events, _, _ = compile_bot_full(bot)
    return buttons, triggers, events


def phrase_hits(value, text):
    """Фраза-триггер срабатывает по целым словам (без учёта регистра)."""
    if any(ch.isalnum() for ch in value):
        return re.search(r"(?<!\w)" + re.escape(value) + r"(?!\w)", text, re.IGNORECASE) is not None
    return value.casefold() in text.casefold()


def match_action(text, buttons, triggers):
    first = text.split()[0].split("@")[0].casefold() if text.split() else ""
    action = next((a for kind, value, a in triggers if kind == "command" and value == first), None)
    if action is None:
        action = next((a for label, a in buttons if label.casefold() == text.casefold()), None)
    if action is None:
        action = next((a for kind, value, a in triggers if kind == "contains" and phrase_hits(value, text)), None)
    if action is None:
        action = next((a for kind, pattern, a in triggers if kind == "regex" and pattern.search(text)), None)
    return first == "/start", action


def action_text(action):
    if "replies" in action:
        return random.choice(action["replies"])
    if "diceEmoji" in action:
        return f"Кубик {action['diceEmoji']}"
    return action.get("text", "")


def format_reply(template: str, user_name='', username='', user_id='', chat_id='', text='', vars_map=None) -> str:
    if not template:
        return ''
    parts = text.split()
    args = ' '.join(parts[1:]) if len(parts) > 1 and parts[0].startswith('/') else ''
    arg_list = args.split() if args else []
    replacements = {
        '{user}': user_name or 'Друг',
        '{name}': user_name or 'Друг',
        '{username}': f'@{username}' if username else (user_name or 'Друг'),
        '{user_id}': str(user_id),
        '{chat_id}': str(chat_id),
        '{args}': args,
        '{arg1}': arg_list[0] if len(arg_list) > 0 else '',
        '{arg2}': arg_list[1] if len(arg_list) > 1 else '',
        '{arg3}': arg_list[2] if len(arg_list) > 2 else '',
    }
    out = template
    for k, v in replacements.items():
        if k in out:
            out = out.replace(k, v)
    if vars_map:
        for k, v in vars_map.items():
            ph = '{' + str(k) + '}'
            if ph in out:
                out = out.replace(ph, str(v))
    return out


class TgError(RuntimeError):
    """Ошибка Telegram API с понятной причиной (токен в текст не попадает)."""
    def __init__(self, message, kind='api', code=None, retry_after=0):
        super().__init__(message)
        self.kind = kind
        self.code = code
        self.retry_after = max(0, int(retry_after or 0))


def parse_retry_after(body=None, headers=None, description=''):
    candidates = []
    if isinstance(body, dict):
        params = body.get('parameters')
        if isinstance(params, dict) and params.get('retry_after') is not None:
            candidates.append(params.get('retry_after'))
    if headers is not None:
        hdr = headers.get('Retry-After') if hasattr(headers, 'get') else None
        if hdr is not None:
            candidates.append(hdr)
    for raw in candidates:
        try:
            val = int(str(raw).strip())
            if val > 0:
                return min(val, 3600)
        except (TypeError, ValueError):
            pass
    for text in (description, str(body.get('description', '') if isinstance(body, dict) else '')):
        m = re.search(r'retry\s*after\D{0,4}(\d+)', text or '', re.IGNORECASE)
        if m:
            val = int(m.group(1))
            if val > 0:
                return min(val, 3600)
    return 0


def rate_limit_wait_sec(exc, streak=1):
    retry_after = getattr(exc, 'retry_after', 0) or 0
    if retry_after <= 0:
        retry_after = parse_retry_after(description=str(exc))
    if retry_after > 0:
        return min(3600, max(1, int(retry_after) + 1))
    return min(300, 15 * (2 ** min(max(0, streak - 1), 4)))


def is_rate_limit_error(exc):
    return isinstance(exc, TgError) and (exc.kind == 'rate_limit' or exc.code == 429)


def reason(exc):
    if isinstance(exc, TgError):
        return str(exc) if str(exc) else exc.kind
    return exc.__class__.__name__


def connect_error(exc):
    if isinstance(exc, TgError):
        if exc.kind == 'token':
            return 'Токен не принят Telegram. Проверьте токен в @BotFather.'
        if exc.kind == 'offline':
            return 'Нет связи с api.telegram.org. Проверьте интернет.'
        if is_rate_limit_error(exc):
            wait = f'{exc.retry_after} с' if exc.retry_after > 0 else 'несколько секунд'
            return f'Telegram ограничил запросы (HTTP 429: подождите {wait}). Это лимит на токен бота, смена VPN/Wi-Fi не поможет.'
        return f'Не удалось подключить бота: {exc}'
    return 'Не удалось подключить бота. Проверьте токен и интернет.'


def poll_error(exc, wait_sec=5):
    if isinstance(exc, TgError):
        if exc.kind == 'offline':
            return 'Пропала связь с Telegram. Повторная попытка через 5 секунд.'
        if is_rate_limit_error(exc):
            return f'Telegram ограничил запросы (HTTP 429). Пауза {wait_sec} с (смена VPN/Wi-Fi не поможет).'
        return f'Ошибка Telegram: {exc}. Повторная попытка через {wait_sec} секунд.'
    return 'Ошибка связи с Telegram. Повторная попытка через 5 секунд. Проверьте сеть и убедитесь, что бот не запущен в другом месте.'


def schedule_due(now, events, sent, window=10):
    due = []
    now_min = now.hour * 60 + now.minute
    today = now.strftime('%Y-%m-%d')
    for index, (time_label, chat, days, action) in enumerate(events):
        hh, _, mm = time_label.partition(':')
        sched = int(hh) * 60 + int(mm)
        if now.weekday() not in days or not 0 <= now_min - sched < window:
            continue
        if sent.get(index) == today:
            continue
        due.append(index)
    return due


class Runtime:
    DEFAULT_API_BASE = 'https://api.telegram.org'

    def __init__(self, request=None, api_base=None, bot_id='default'):
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.thread = None
        self.bot_id = bot_id
        self.state = 'stopped'
        self.error = ''
        self.api_base = (api_base or self.DEFAULT_API_BASE).rstrip('/')
        self.request = request or self._default_request
        self.animation_slots = threading.BoundedSemaphore(5)
        self.logs = deque(maxlen=150)
        self.users = {}
        self.fsm_sessions = {}
        self._last = None

    def _default_request(self, token, method, payload):
        return self.telegram(token, method, payload, api_base=self.api_base)

    @staticmethod
    def telegram(token, method, payload, api_base='https://api.telegram.org'):
        url = f"{api_base.rstrip('/')}/bot{token}/{method}"
        request = urllib.request.Request(
            url, data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'}
        )
        try:
            with urllib.request.urlopen(request, timeout=35) as response:
                result = json.load(response)
        except urllib.error.HTTPError as exc:
            body = None
            description = ''
            try:
                body = json.load(exc)
                description = str(body.get('description') or '')
            except Exception:
                pass
            retry_after = parse_retry_after(body, getattr(exc, 'headers', None), description)
            if exc.code == 429:
                kind = 'rate_limit'
            elif exc.code in (401, 404):
                kind = 'token'
            else:
                kind = 'api'
            raise TgError(description or f'HTTP {exc.code}', kind, exc.code, retry_after=retry_after) from None
        except (urllib.error.URLError, OSError, TimeoutError):
            raise TgError('нет сети', 'offline') from None
        if not result.get('ok'):
            err_code = result.get('error_code')
            description = str(result.get('description') or 'Telegram API error')
            retry_after = parse_retry_after(result, description=description)
            kind = 'rate_limit' if err_code == 429 else 'api'
            raise TgError(description, kind, err_code, retry_after=retry_after)
        return result['result']

    def status(self):
        with self.lock:
            return {
                'botId': self.bot_id,
                'state': self.state,
                'error': self.error,
                'logs': list(self.logs),
                'usersCount': len(self.users),
            }

    def log(self, message):
        with self.lock:
            self.logs.append(datetime.now().strftime('%H:%M:%S') + ' ' + message)

    def start(self, token, bot):
        if not isinstance(token, str) or not token or len(token) > 200 or any(c not in '0123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-' for c in token):
            raise ValueError('Укажите корректный токен бота.')
        buttons, triggers, events, callbacks, fsm_flows = compile_bot_full(bot)
        with self.lock:
            if self.thread and self.thread.is_alive():
                raise ValueError('Бот уже запущен или останавливается.')
            self.stop_event = threading.Event()
            self.state, self.error = 'starting', ''
            self._last = (token, bot)
            self.logs.append(datetime.now().strftime('%H:%M:%S') + ' Запуск бота')
            self.thread = threading.Thread(
                target=self.run,
                args=(token, buttons, triggers, events, callbacks, fsm_flows, self.stop_event),
                daemon=True,
            )
            self.thread.start()

    def stop(self):
        with self.lock:
            if self.thread and self.thread.is_alive():
                self.stop_event.set()
                self.state = 'stopping'
                self.logs.append(datetime.now().strftime('%H:%M:%S') + ' Остановка бота')

    def restart(self, token=None, bot=None):
        self.stop()
        deadline = time.time() + 10
        while time.time() < deadline:
            with self.lock:
                if not (self.thread and self.thread.is_alive()):
                    break
            time.sleep(0.2)
        with self.lock:
            if self.thread and self.thread.is_alive():
                raise ValueError('Бот ещё останавливается — попробуйте через несколько секунд.')
        if not token and self._last:
            token, bot = self._last
        self.start(token, bot)

    def _send_message_safe(self, token, payload):
        try:
            return self.request(token, 'sendMessage', payload)
        except Exception as exc:
            if payload.get('parse_mode') and ('parse' in str(exc).lower() or isinstance(exc, TgError) and exc.code == 400):
                fallback = dict(payload)
                fallback.pop('parse_mode', None)
                return self.request(token, 'sendMessage', fallback)
            raise

    def run(self, token, buttons, triggers, events, *extra):
        # Поддерживает как 5-аргументный вызов (совместимость), так и полный Stage 5 вызов
        if len(extra) == 1:
            callbacks, fsm_flows, stop = {}, {}, extra[0]
        else:
            callbacks, fsm_flows, stop = extra[0], extra[1], extra[2]
        try:
            me_streak = 0
            while not stop.is_set():
                try:
                    self.request(token, 'getMe', {})
                    break
                except Exception as exc:
                    if not is_rate_limit_error(exc):
                        raise
                    me_streak += 1
                    wait_sec = rate_limit_wait_sec(exc, me_streak)
                    with self.lock:
                        self.error = connect_error(exc)
                    self.log(f'HTTP 429 при проверке токена: пауза {wait_sec} с')
                    if stop.wait(wait_sec):
                        return
            if stop.is_set():
                return
            with self.lock:
                if not stop.is_set():
                    self.state = 'running'
                    self.error = ''
            if events:
                threading.Thread(target=self.schedule_loop, args=(token, events, stop), daemon=True).start()
            offset = None
            rl_streak = 0
            keyboard = {'keyboard': [[{'text': label}] for label, _ in buttons], 'resize_keyboard': True}
            while not stop.is_set():
                try:
                    payload = {'timeout': 5, 'allowed_updates': ['message', 'callback_query']}
                    if offset is not None:
                        payload['offset'] = offset
                    updates = self.request(token, 'getUpdates', payload)
                    rl_streak = 0
                    for update in updates:
                        try:
                            self.handle_update(token, update, buttons, triggers, keyboard, stop, callbacks, fsm_flows)
                        except Exception as exc:
                            chat_id = (
                                update.get('message', {}).get('chat', {}).get('id')
                                or update.get('callback_query', {}).get('message', {}).get('chat', {}).get('id')
                                or '?'
                            )
                            self.log(f'Не удалось ответить · chat ID {chat_id}: {reason(exc)}')
                            if is_rate_limit_error(exc):
                                wait_sec = rate_limit_wait_sec(exc, 1)
                                self.log(f'HTTP 429 при ответе: пауза {wait_sec} с')
                                if stop.wait(wait_sec):
                                    break
                    if updates:
                        offset = max(u['update_id'] for u in updates) + 1
                    with self.lock:
                        self.error = ''
                except Exception as exc:
                    if isinstance(exc, TgError) and exc.kind == 'token':
                        with self.lock:
                            self.error = connect_error(exc)
                        break
                    if is_rate_limit_error(exc):
                        rl_streak += 1
                        wait_sec = rate_limit_wait_sec(exc, rl_streak)
                        with self.lock:
                            self.error = poll_error(exc, wait_sec)
                        self.log(f'HTTP 429 на getUpdates: пауза {wait_sec} с')
                        stop.wait(wait_sec)
                    else:
                        rl_streak = 0
                        with self.lock:
                            self.error = poll_error(exc)
                        stop.wait(5)
        except Exception as exc:
            with self.lock:
                self.error = connect_error(exc)
        finally:
            with self.lock:
                self.state = 'stopped'

    def _record_user(self, chat_id, from_obj):
        with self.lock:
            u = self.users.setdefault(str(chat_id), {'vars': {}, 'msgCount': 0})
            u['msgCount'] += 1
            u['firstName'] = (from_obj or {}).get('first_name') or u.get('firstName') or ''
            u['username'] = (from_obj or {}).get('username') or u.get('username') or ''
            u['lastSeen'] = datetime.now().isoformat(timespec='seconds')
            return u

    def _handle_callback(self, token, cb, callbacks):
        cb_id = cb.get('id')
        data = cb.get('data', '')
        msg = cb.get('message') or {}
        chat_id = msg.get('chat', {}).get('id')
        msg_id = msg.get('message_id')
        entry = callbacks.get(data)
        toast = (entry or {}).get('toast') or ''
        if cb_id:
            try:
                self.request(token, 'answerCallbackQuery', {'callback_query_id': cb_id, 'text': toast})
            except Exception:
                pass
        if not entry or not chat_id:
            return
        act = entry.get('action', 'text')
        from_obj = cb.get('from') or {}
        u = self._record_user(chat_id, from_obj)
        if act == 'submenu' and entry.get('targetMarkup') and msg_id:
            if entry.get('text'):
                p = {'chat_id': chat_id, 'message_id': msg_id, 'text': entry['text'], 'reply_markup': entry['targetMarkup']}
                if entry.get('parseMode'):
                    p['parse_mode'] = entry['parseMode']
                self.request(token, 'editMessageText', p)
            else:
                self.request(token, 'editMessageReplyMarkup', {'chat_id': chat_id, 'message_id': msg_id, 'reply_markup': entry['targetMarkup']})
            return
        if act == 'back' and entry.get('parentMarkup') and msg_id:
            self.request(token, 'editMessageReplyMarkup', {'chat_id': chat_id, 'message_id': msg_id, 'reply_markup': entry['parentMarkup']})
            return
        if act == 'dice':
            self.request(token, 'sendDice', {'chat_id': chat_id, 'emoji': entry.get('diceEmoji', '🎲')})
            return
        raw_txt = random.choice(entry['packReplies']) if (act == 'pack' and entry.get('packReplies')) else entry.get('text', '')
        rendered = format_reply(
            raw_txt, user_name=from_obj.get('first_name', ''), username=from_obj.get('username', ''),
            user_id=from_obj.get('id', ''), chat_id=chat_id, vars_map=u.get('vars'),
        )
        if rendered:
            body = {'chat_id': chat_id, 'text': rendered}
            if entry.get('parseMode'):
                body['parse_mode'] = entry['parseMode']
            self._send_message_safe(token, body)

    def _handle_fsm_input(self, token, chat_id, text, from_obj, fsm_flows, u):
        sess = self.fsm_sessions.get(str(chat_id))
        if not sess:
            return False
        flow = fsm_flows.get(sess['flowId'])
        if not flow:
            self.fsm_sessions.pop(str(chat_id), None)
            return False
        cmd = text.strip().lower()
        if cmd == '/cancel':
            self.fsm_sessions.pop(str(chat_id), None)
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': 'Анкета отменена.'})
            return True
        if cmd == '/back':
            idx = max(0, sess['step'] - 1)
            sess['step'] = idx
            st = flow['steps'][idx]
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': st['prompt']})
            return True

        step = flow['steps'][sess['step']]
        val = text.strip()
        if step['type'] == 'number' and not re.fullmatch(r'-?\d+(?:[.,]\d+)?', val):
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': 'Введите число (или /cancel для отмены).'})
            return True
        if step['type'] == 'phone' and len(re.sub(r'\D', '', val)) < 7:
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': 'Введите корректный номер телефона (или /cancel).'})
            return True

        u['vars'][step['varName']] = val
        sess['data'][step['varName']] = val
        sess['step'] += 1
        if sess['step'] < len(flow['steps']):
            nxt = flow['steps'][sess['step']]
            body = {'chat_id': chat_id, 'text': nxt['prompt']}
            if nxt['options']:
                body['reply_markup'] = {'keyboard': [[{'text': o}] for o in nxt['options']], 'resize_keyboard': True, 'one_time_keyboard': True}
            self.request(token, 'sendMessage', body)
        else:
            merged = dict(u['vars'])
            merged.update(sess['data'])
            summary = format_reply(
                flow['summaryTemplate'],
                user_name=from_obj.get('first_name', ''),
                username=from_obj.get('username', ''),
                user_id=from_obj.get('id', ''),
                chat_id=chat_id,
                vars_map=merged,
            )
            self.fsm_sessions.pop(str(chat_id), None)
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': summary})
        return True

    def handle_update(self, token, update, buttons, triggers, keyboard, stop, callbacks=None, fsm_flows=None):
        """Одно входящее обновление: callback_query, /chatid, FSM, команды, кнопки, фразы, regex."""
        if 'callback_query' in update:
            self._handle_callback(token, update['callback_query'], callbacks or {})
            return

        message = update.get('message', {})
        chat = message.get('chat', {})
        text = message.get('text', '')
        if not chat.get('id') or not isinstance(text, str):
            return
        chat_id = chat['id']
        from_obj = message.get('from') or {}
        u = self._record_user(chat_id, from_obj)
        self.log(f"Получено сообщение · chat ID {chat_id}")
        stripped = text.strip()
        if stripped and stripped.split()[0].split("@")[0].casefold() == '/chatid':
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': f'Ваш chat ID: {chat_id}'})
            self.log(f'Команда /chatid · chat ID {chat_id}')
            return

        if fsm_flows and self._handle_fsm_input(token, chat_id, text, from_obj, fsm_flows, u):
            return

        is_start, action = match_action(text, buttons, triggers)
        if is_start and action is None and buttons:
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': 'Выберите кнопку:', 'reply_markup': keyboard})
            return
        if action is None:
            self.log('Подходящий сценарий не найден')
            return
        name = action.get('name') or ''
        self.log(f'Найден сценарий «{name}»' if name else 'Найден сценарий')
        inline = action.get('inline')
        if is_start and buttons and inline:
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': 'Выберите кнопку:', 'reply_markup': keyboard})
        markup = inline or (keyboard if is_start and buttons else None)

        if 'fsmFlowId' in action and fsm_flows and action['fsmFlowId'] in fsm_flows:
            flow = fsm_flows[action['fsmFlowId']]
            self.fsm_sessions[str(chat_id)] = {'flowId': flow['id'], 'step': 0, 'data': {}}
            first_step = flow['steps'][0]
            body = {'chat_id': chat_id, 'text': first_step['prompt']}
            if first_step['options']:
                body['reply_markup'] = {'keyboard': [[{'text': o}] for o in first_step['options']], 'resize_keyboard': True, 'one_time_keyboard': True}
            self.request(token, 'sendMessage', body)
            return

        if 'animation' in action:
            if self.animation_slots.acquire(blocking=False):
                user = from_obj.get('first_name', '')
                threading.Thread(target=self.animate, args=(token, chat_id, action['animation'], user, stop, markup), daemon=True).start()
            else:
                self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': 'Слишком много анимаций, попробуйте позже.'})
            return

        if 'diceEmoji' in action:
            body = {'chat_id': chat_id, 'emoji': action['diceEmoji']}
            if markup:
                body['reply_markup'] = markup
            self.request(token, 'sendDice', body)
            return

        if 'media' in action:
            method_name, field_name = ALLOWED_MEDIA[action['media']['type']]
            body = {'chat_id': chat_id, field_name: action['media']['ref']}
            cap = format_reply(
                action.get('text', ''), user_name=from_obj.get('first_name', ''), username=from_obj.get('username', ''),
                user_id=from_obj.get('id', ''), chat_id=chat_id, text=text, vars_map=u.get('vars'),
            )
            if cap and field_name != 'sticker':
                body['caption'] = cap
            if action.get('parseMode') and field_name != 'sticker':
                body['parse_mode'] = action['parseMode']
            if markup:
                body['reply_markup'] = markup
            self.request(token, method_name, body)
            return

        rendered = format_reply(
            action_text(action),
            user_name=from_obj.get('first_name', ''),
            username=from_obj.get('username', ''),
            user_id=from_obj.get('id', ''),
            chat_id=chat_id,
            text=text,
            vars_map=u.get('vars'),
        )
        body = {'chat_id': chat_id, 'text': rendered}
        if action.get('parseMode'):
            body['parse_mode'] = action['parseMode']
        if markup:
            body['reply_markup'] = markup
        self._send_message_safe(token, body)

    def schedule_loop(self, token, events, stop):
        sent = {}
        while not stop.is_set():
            now = datetime.now()
            for index in schedule_due(now, events, sent):
                time_label, chat, days, action = events[index]
                try:
                    if 'animation' in action:
                        if self.animation_slots.acquire(blocking=False):
                            threading.Thread(target=self.animate, args=(token, chat, action['animation'], '', stop, action.get('inline')), daemon=True).start()
                        else:
                            self.log('Напоминание пропущено: много анимаций')
                    elif 'diceEmoji' in action:
                        body = {'chat_id': chat, 'emoji': action['diceEmoji']}
                        if action.get('inline'):
                            body['reply_markup'] = action['inline']
                        self.request(token, 'sendDice', body)
                    else:
                        body = {'chat_id': chat, 'text': action_text(action)}
                        if action.get('parseMode'):
                            body['parse_mode'] = action['parseMode']
                        if action.get('inline'):
                            body['reply_markup'] = action['inline']
                        self._send_message_safe(token, body)
                    sent[index] = now.strftime('%Y-%m-%d')
                    self.log('Выполнено напоминание')
                except Exception as exc:
                    if is_rate_limit_error(exc):
                        wait_sec = rate_limit_wait_sec(exc, 1)
                        self.log(f'HTTP 429 в расписании: пауза {wait_sec} с; повтор в окне допуска')
                        if stop.wait(wait_sec):
                            return
                    else:
                        self.log(f'Ошибка отправки напоминания ({reason(exc)}); повтор в окне допуска')
            stop.wait(5)

    def _animate_request(self, token, method, payload, stop):
        for attempt in range(2):
            try:
                return self.request(token, method, payload)
            except Exception as exc:
                if 'not modified' in str(exc).lower():
                    return {}
                if attempt == 0 and is_rate_limit_error(exc):
                    wait_sec = min(60, rate_limit_wait_sec(exc, 1))
                    self.log(f'HTTP 429 в анимации ({method}): пауза {wait_sec} с')
                    if stop.wait(wait_sec):
                        return None
                    continue
                raise

    def animate(self, token, chat_id, spec, user, stop, markup=None):
        try:
            sequence = frames(spec, user if isinstance(user, str) else '')
            final = spec.get('final', '').replace('{user}', user if isinstance(user, str) else '')
            if not final and spec['preset'] == 'countdown':
                final = '🚀'
            if final.strip() and final != sequence[-1]:
                sequence.append(final[:3500])
            if stop.is_set():
                return
            body = {'chat_id': chat_id, 'text': sequence[0]}
            if markup and ('inline_keyboard' not in markup or len(sequence) == 1):
                body['reply_markup'] = markup
            sent = self._animate_request(token, 'sendMessage', body, stop)
            if not sent:
                return
            for index, frame in enumerate(sequence[1:], start=1):
                if stop.wait(spec.get('interval', 700) / 1000):
                    break
                edit = {'chat_id': chat_id, 'message_id': sent['message_id'], 'text': frame}
                if markup and 'inline_keyboard' in markup and index == len(sequence) - 1:
                    edit['reply_markup'] = markup
                if self._animate_request(token, 'editMessageText', edit, stop) is None:
                    break
        except Exception as exc:
            with self.lock:
                self.error = (
                    poll_error(exc, rate_limit_wait_sec(exc, 1))
                    if is_rate_limit_error(exc)
                    else 'Не удалось воспроизвести анимацию. Проверьте связь и ограничения Telegram.'
                )
        finally:
            self.animation_slots.release()


class BotDiskStore:
    """Хранение ботов в файлах .bc на диске с ротацией последних 10 бэкапов (без токенов)."""
    MAX_BACKUPS = 10

    def __init__(self, base_dir: Path):
        self.base_dir = Path(base_dir)
        self.bots_dir = self.base_dir / 'bots'
        self.backups_dir = self.bots_dir / 'backups'
        self.lock = threading.Lock()

    def _ensure_dirs(self):
        self.bots_dir.mkdir(parents=True, exist_ok=True)
        self.backups_dir.mkdir(parents=True, exist_ok=True)

    def save_bot(self, bot_id: str, bot: dict) -> dict:
        compile_bot_full(bot)
        sid = _safe_id(bot_id)
        clean = {k: v for k, v in bot.items() if k != 'token'}
        with self.lock:
            self._ensure_dirs()
            target = self.bots_dir / f'{sid}.bc'
            if target.exists():
                ts = datetime.now().strftime('%Y%m%d_%H%M%S_%f')
                bak = self.backups_dir / f'{sid}_{ts}.bc'
                bak.write_bytes(target.read_bytes())
                existing_baks = sorted(self.backups_dir.glob(f'{sid}_*.bc'))
                for old in existing_baks[:-self.MAX_BACKUPS]:
                    old.unlink(missing_ok=True)
            target.write_text(json.dumps(clean, ensure_ascii=False, indent=2), encoding='utf-8')
        return {'botId': sid, 'name': clean.get('name', sid)}

    def load_bot(self, bot_id: str) -> dict | None:
        sid = _safe_id(bot_id)
        target = self.bots_dir / f'{sid}.bc'
        if not target.exists():
            return None
        return json.loads(target.read_text(encoding='utf-8'))

    def list_bots(self) -> list[dict]:
        with self.lock:
            if not self.bots_dir.exists():
                return []
            items = []
            for p in sorted(self.bots_dir.glob('*.bc')):
                try:
                    data = json.loads(p.read_text(encoding='utf-8'))
                    items.append({
                        'botId': p.stem,
                        'name': data.get('name') or p.stem,
                        'scenariosCount': len(data.get('scenarios') or []),
                        'buttonsCount': len(data.get('buttons') or []),
                        'bot': data,
                    })
                except Exception:
                    continue
            return items

    def delete_bot(self, bot_id: str) -> bool:
        sid = _safe_id(bot_id)
        target = self.bots_dir / f'{sid}.bc'
        if target.exists():
            target.unlink()
            return True
        return False


class MultiBotManager:
    """Управление несколькими одновременно запущенными ботами."""
    def __init__(self, primary_runtime: Runtime, request=None, api_base=None):
        self.lock = threading.Lock()
        self.primary = primary_runtime
        self.runtimes = {'default': primary_runtime}
        self.request = request
        self.api_base = api_base

    def get(self, bot_id: str) -> Runtime:
        sid = _safe_id(bot_id)
        with self.lock:
            if sid not in self.runtimes:
                self.runtimes[sid] = Runtime(request=self.request, api_base=self.api_base, bot_id=sid)
            return self.runtimes[sid]

    def start(self, bot_id: str, token: str, bot: dict):
        rt = self.get(bot_id)
        rt.start(token, bot)
        return rt.status()

    def stop(self, bot_id: str):
        rt = self.get(bot_id)
        rt.stop()
        return rt.status()

    def restart(self, bot_id: str, token=None, bot=None):
        rt = self.get(bot_id)
        rt.restart(token, bot)
        return rt.status()

    def stop_all(self):
        with self.lock:
            rts = list(self.runtimes.values())
        for rt in rts:
            rt.stop()

    def statuses(self) -> dict[str, dict]:
        with self.lock:
            items = list(self.runtimes.items())
        return {bid: rt.status() for bid, rt in items}


runtime = Runtime()
disk_store = BotDiskStore(Path.home() / '.botcontrol-desktop')
multi_manager = MultiBotManager(runtime)
SERVER_PIN = None


def build_diagnostics() -> dict:
    statuses = multi_manager.statuses()
    bots = disk_store.list_bots()
    logs = []
    for bid, st in statuses.items():
        for line in st.get('logs') or []:
            logs.append(f'[{bid}] {line}')
    return {
        'app': 'BotControl Desktop',
        'version': APP_VERSION,
        'python': platform.python_version(),
        'os': f'{platform.system()} {platform.release()}',
        'savedBotsCount': len(bots),
        'runtimes': {k: {'state': v['state'], 'error': v['error'], 'usersCount': v['usersCount']} for k, v in statuses.items()},
        'logsTail': logs[-60:],
    }


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def valid_host(self):
        host = self.headers.get('Host', '')
        try:
            parsed = urlsplit('//' + host)
            name = (parsed.hostname or '').lower()
            port = parsed.port
        except ValueError:
            return False
        if port is not None and port != self.server.server_port:
            return False
        allowed = {'127.0.0.1', 'localhost', '::1'}
        bind = str(self.server.server_address[0])
        if bind and bind != '0.0.0.0':
            allowed.add(bind)
        return name in allowed

    def valid_origin(self):
        origin = self.headers.get('Origin', '')
        if not origin:
            return False
        port = self.server.server_port
        allowed = {
            f'http://127.0.0.1:{port}',
            f'http://localhost:{port}',
            f'http://[::1]:{port}',
        }
        bind = str(self.server.server_address[0])
        if bind and bind != '0.0.0.0':
            allowed.add(f'http://{bind}:{port}')
        return origin in allowed

    def _pin_ok(self):
        if not SERVER_PIN:
            return True
        if self.headers.get('X-BotControl-Pin') == SERVER_PIN:
            return True
        qs = urllib.parse.parse_qs(urlsplit(self.path).query)
        if qs.get('pin', [''])[0] == SERVER_PIN:
            return True
        cookie = self.headers.get('Cookie', '')
        for part in cookie.split(';'):
            k, _, v = part.strip().partition('=')
            if k == 'bc_pin' and v == SERVER_PIN:
                return True
        return False

    def send_error(self, code, message=None, explain=None):
        if code == 403:
            extra = (
                'BotControl — локальное приложение. Откройте '
                'http://127.0.0.1:%d/ напрямую.' % self.server.server_port
            )
            explain = (explain + ' ' if explain else '') + extra
        super().send_error(code, message, explain)

    def send_json(self, code, data):
        raw = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if not self.valid_host():
            return self.send_error(403)
        if not self._pin_ok():
            return self.send_error(403, 'PIN required')
        path_only = self.path.split('?', 1)[0]
        if path_only == '/api/status':
            return self.send_json(200, runtime.status())
        if path_only == '/api/bots':
            return self.send_json(200, {'bots': disk_store.list_bots(), 'runtimes': multi_manager.statuses()})
        if path_only == '/api/diagnostics':
            return self.send_json(200, build_diagnostics())
        if path_only not in ('/', '/index.html', '/style.css', '/app.js', '/generated.js'):
            return self.send_error(404)
        self.path = path_only
        return super().do_GET()

    def do_POST(self):
        if not self.valid_host() or not self.valid_origin() or self.headers.get('Content-Type') != 'application/json':
            return self.send_error(403)
        if not self._pin_ok():
            return self.send_error(403, 'PIN required')
        path_only = self.path.split('?', 1)[0]
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 2 * 1024 * 1024:
                raise ValueError('Размер запроса недопустим.')
            data = json.loads(self.rfile.read(length))
            if not isinstance(data, dict):
                raise ValueError('Некорректный запрос.')

            if path_only == '/api/start':
                runtime.start(data.get('token'), data.get('bot'))
            elif path_only == '/api/stop':
                runtime.stop()
            elif path_only == '/api/restart':
                runtime.restart(data.get('token'), data.get('bot'))
            elif path_only == '/api/bots/save':
                saved = disk_store.save_bot(str(data.get('botId') or 'default'), data.get('bot'))
                return self.send_json(200, {'ok': True, 'saved': saved, 'bots': disk_store.list_bots()})
            elif path_only == '/api/bots/delete':
                deleted = disk_store.delete_bot(str(data.get('botId') or ''))
                return self.send_json(200, {'ok': deleted, 'bots': disk_store.list_bots()})
            elif path_only == '/api/bots/start':
                st = multi_manager.start(str(data.get('botId') or 'default'), data.get('token'), data.get('bot'))
                return self.send_json(200, st)
            elif path_only == '/api/bots/stop':
                st = multi_manager.stop(str(data.get('botId') or 'default'))
                return self.send_json(200, st)
            elif path_only == '/api/bots/restart':
                st = multi_manager.restart(str(data.get('botId') or 'default'), data.get('token'), data.get('bot'))
                return self.send_json(200, st)
            elif path_only == '/api/validate-bot':
                compile_bot_full(data.get('bot'))
                return self.send_json(200, {'ok': True})
            elif path_only == '/api/import-python':
                return self.send_json(200, {'packs': extract_lists(data.get('source'))})
            elif path_only == '/api/import-android-py':
                src = data.get('source')
                if not isinstance(src, str):
                    raise ValueError('Нужен текст Python-файла.')
                res = import_android_py(src)
                compile_bot_full(res['bot'])
                return self.send_json(200, res)
            elif path_only == '/api/export-python':
                bot = data.get('bot')
                compile_bot_full(bot)
                return self.send_json(200, {'source': export_android_py(bot)})
            elif path_only == '/api/simulate':
                text = data.get('text')
                if not isinstance(text, str) or len(text) > 4096:
                    raise ValueError('Введите сообщение до 4096 символов.')
                buttons, triggers, _, _, _ = compile_bot_full(data.get('bot'))
                is_start, selected = match_action(text, buttons, triggers)
                if selected is None:
                    return self.send_json(200, {'result': 'Показать клавиатуру' if is_start and buttons else 'Подходящий сценарий не найден.'})
                if 'animation' in selected:
                    result = 'Анимация: ' + selected['animation']['preset'] + '\nПервый кадр: ' + frames(selected['animation'], 'Пользователь')[0]
                elif 'diceEmoji' in selected:
                    result = 'Бросок кубика: ' + selected['diceEmoji']
                elif 'media' in selected:
                    result = f"Медиа ({selected['media']['type']}): {selected['media']['ref']}"
                else:
                    result = format_reply(action_text(selected), user_name='Пользователь', username='demo', user_id='1', chat_id='1', text=text)
                return self.send_json(200, {'result': result})
            else:
                return self.send_error(404)
            self.send_json(200, runtime.status())
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            self.send_json(400, {'error': str(exc)})


def main():
    global disk_store, SERVER_PIN
    parser = argparse.ArgumentParser(description='Local BotControl')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--host', default='127.0.0.1', help='Local IPv4 address; use a private Wi-Fi address for LAN access')
    parser.add_argument('--data-dir', default=str(Path.home() / '.botcontrol-desktop'), help='Directory for saved .bc bots and backups')
    parser.add_argument('--pin', default='', help='Optional access PIN (auto-generated in LAN mode unless --no-pin)')
    parser.add_argument('--no-pin', action='store_true')
    parser.add_argument('--no-browser', action='store_true')
    args = parser.parse_args()
    try:
        address = ipaddress.IPv4Address(args.host)
    except ipaddress.AddressValueError:
        parser.error('--host must be an IPv4 address')
    allowed = [ipaddress.IPv4Network(net) for net in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')]
    if address != ipaddress.IPv4Address('127.0.0.1') and not any(address in net for net in allowed):
        parser.error('--host must be 127.0.0.1 or a private Wi-Fi IPv4 address')

    disk_store = BotDiskStore(Path(args.data_dir))
    if args.pin:
        SERVER_PIN = args.pin
    elif address != ipaddress.IPv4Address('127.0.0.1') and not args.no_pin:
        SERVER_PIN = f'{secrets.randbelow(900000) + 100000}'

    try:
        server = ThreadingHTTPServer((str(address), args.port), Handler)
    except OSError as exc:
        parser.exit(1, f'Не удалось запустить сервер: {exc}\n'
            f'Порт {args.port} занят? Скорее всего, BotControl уже запущен — '
            f'закройте старый экземпляр (консоль или окно программы).\n')
    url = f'http://{address}:{server.server_port}/'
    if SERVER_PIN:
        url_with_pin = f'{url}?pin={SERVER_PIN}'
        print(f'PIN защиты доступа: {SERVER_PIN}  ({url_with_pin})', flush=True)
    else:
        url_with_pin = url
    if address != ipaddress.IPv4Address('127.0.0.1') and not SERVER_PIN:
        print('WARNING: LAN mode has no authentication or encryption. Use only a trusted network.', flush=True)
    print(f'BotControl {APP_VERSION}: {url_with_pin} (Ctrl+C для остановки)', flush=True)
    if not args.no_browser:
        webbrowser.open(url_with_pin)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        multi_manager.stop_all()
        server.server_close()


if __name__ == '__main__':
    main()
