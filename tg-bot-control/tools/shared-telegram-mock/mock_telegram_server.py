#!/usr/bin/env python3
"""Локальный HTTP-мок Telegram Bot API для автотестов desktop-web и Android.

Поддерживает базовые методы Bot API (`getMe`, `getUpdates`, `sendMessage`,
`editMessageText`, `editMessageReplyMarkup`, `answerCallbackQuery`, `sendDice`,
`sendPhoto`, `sendDocument`, `sendChatAction`, `setMyCommands`, `answerInlineQuery`,
`answerPreCheckoutQuery`, `createInvoiceLink`, `banChatMember`, `deleteMessage`)
и инъекцию HTTP-ошибок (429 retry_after, 403, 400).
"""
from __future__ import annotations

import argparse
import collections
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import threading
import urllib.parse


class MockTelegramState:
    def __init__(self, expected_token: str | None = None, bot_username: str = 'BotControlMockBot'):
        self.lock = threading.Lock()
        self.expected_token = expected_token
        self.bot_username = bot_username
        self.updates: collections.deque[dict] = collections.deque()
        self.calls: list[dict] = []
        self.injected_errors: dict[str, collections.deque[tuple[int, dict]]] = {}
        self.next_message_id = 100

    def enqueue_updates(self, items: list[dict]) -> None:
        with self.lock:
            for item in items:
                self.updates.append(item)

    def inject_error(self, method: str, status: int, body: dict) -> None:
        with self.lock:
            self.injected_errors.setdefault(method, collections.deque()).append((status, body))

    def handle_call(self, token: str, method: str, payload: dict) -> tuple[int, dict]:
        with self.lock:
            if self.expected_token and token != self.expected_token:
                return 401, {'ok': False, 'error_code': 401, 'description': 'Unauthorized'}

            self.calls.append({'token': token, 'method': method, 'payload': payload})

            q = self.injected_errors.get(method)
            if q:
                status, err_body = q.popleft()
                return status, err_body

            if method == 'getMe':
                return 200, {
                    'ok': True,
                    'result': {
                        'id': 999000,
                        'is_bot': True,
                        'first_name': 'BotControl Mock',
                        'username': self.bot_username,
                    },
                }

            if method == 'getUpdates':
                offset = int(payload.get('offset') or 0)
                batch = []
                while self.updates:
                    upd = self.updates.popleft()
                    if int(upd.get('update_id', 0)) >= offset:
                        batch.append(upd)
                return 200, {'ok': True, 'result': batch}

            if method in ('sendMessage', 'sendDice', 'sendPhoto', 'sendDocument', 'sendAnimation',
                          'sendSticker', 'sendVideo', 'sendVoice', 'sendAudio', 'sendPoll'):
                self.next_message_id += 1
                return 200, {
                    'ok': True,
                    'result': {
                        'message_id': self.next_message_id,
                        'chat': {'id': payload.get('chat_id', 0)},
                        'text': payload.get('text', ''),
                    },
                }

            if method in ('editMessageText', 'editMessageReplyMarkup'):
                return 200, {
                    'ok': True,
                    'result': {
                        'message_id': payload.get('message_id', 1),
                        'chat': {'id': payload.get('chat_id', 0)},
                        'text': payload.get('text', ''),
                    },
                }

            if method in ('answerCallbackQuery', 'answerInlineQuery', 'answerPreCheckoutQuery',
                          'sendChatAction', 'setMyCommands', 'deleteWebhook', 'setWebhook',
                          'banChatMember', 'restrictChatMember', 'deleteMessage'):
                return 200, {'ok': True, 'result': True}

            if method == 'createInvoiceLink':
                return 200, {'ok': True, 'result': 'https://t.me/$mock_invoice_link'}

            return 200, {'ok': True, 'result': True}


def make_handler(state: MockTelegramState):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            m = re.match(r'^/bot([^/]+)/([A-Za-z0-9_]+)$', self.path.split('?', 1)[0])
            if not m:
                self._send(404, {'ok': False, 'error_code': 404, 'description': 'Not Found'})
                return
            token, method = urllib.parse.unquote(m.group(1)), m.group(2)
            length = int(self.headers.get('Content-Length') or 0)
            raw = self.rfile.read(length) if length > 0 else b'{}'
            try:
                payload = json.loads(raw.decode('utf-8') or '{}')
            except Exception:
                payload = {}
            status, body = state.handle_call(token, method, payload)
            self._send(status, body)

        def do_GET(self):
            if self.path.startswith('/__health'):
                self._send(200, {'ok': True, 'calls': len(state.calls)})
                return
            self.do_POST()

        def _send(self, status: int, body: dict):
            raw = json.dumps(body, ensure_ascii=False).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            if status == 429:
                ra = (body.get('parameters') or {}).get('retry_after')
                if ra:
                    self.send_header('Retry-After', str(ra))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, format, *args):
            pass

    return Handler


def start_mock_server(state: MockTelegramState | None = None, host: str = '127.0.0.1', port: int = 0):
    state = state or MockTelegramState()
    srv = ThreadingHTTPServer((host, port), make_handler(state))
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    return srv, state, thread


def main():
    parser = argparse.ArgumentParser(description='Mock Telegram Bot API server')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=18081)
    parser.add_argument('--token', default=None)
    parser.add_argument('--updates', default=None, help='Path to contract_updates.json')
    args = parser.parse_args()

    state = MockTelegramState(expected_token=args.token)
    if args.updates:
        with open(args.updates, encoding='utf-8') as f:
            data = json.load(f)
        updates = (data.get('getUpdatesResponse') or {}).get('result') or []
        state.enqueue_updates(updates)

    srv = ThreadingHTTPServer((args.host, args.port), make_handler(state))
    print(f'Mock Telegram Bot API listening on http://{args.host}:{srv.server_port}')
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


if __name__ == '__main__':
    main()
