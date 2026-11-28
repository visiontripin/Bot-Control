"""Импорт и экспорт между форматом Android BotControl (.py) и Desktop (.bc).

Разбор исходника выполняется только через ast.parse() без выполнения кода!
"""
from __future__ import annotations

import ast
import re
import uuid

SERVICE_LIST_NAMES = {'ANIMATIONS', 'FRAMES', 'SCHEDULE', 'STOP_WORDS', 'CLARIFY_QUESTIONS'}
SERVICE_PREFIXES = ('ANIM_', 'FRAMES_', 'PHOTO_')
SERVICE_SUFFIXES = ('_FRAMES', '_PHOTO', '_SCHEDULE')


def _is_service_list(name: str, items: list[str]) -> bool:
    upper = name.upper()
    if upper in SERVICE_LIST_NAMES:
        return True
    if upper.startswith(SERVICE_PREFIXES) or upper.endswith(SERVICE_SUFFIXES):
        return True
    return all(item.lower().startswith(('photo:', 'sticker:', 'http://', 'https://')) for item in items)


def extract_lists(source: str) -> list[dict]:
    """Сохраняет базовый импорт буквальных списков ответов из .py (совместимость 0.6.0)."""
    if len(source.encode('utf-8')) > 512 * 1024:
        raise ValueError('Файл больше 512 КБ.')
    try:
        tree = ast.parse(source)
    except SyntaxError as exc:
        raise ValueError('Не удалось разобрать Python-файл.') from exc
    packs = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            name = node.targets[0].id
            if isinstance(node.value, (ast.List, ast.Tuple)) and 1 <= len(node.value.elts) <= 500:
                items = [
                    el.value.strip()
                    for el in node.value.elts
                    if isinstance(el, ast.Constant) and isinstance(el.value, str) and el.value.strip()
                ]
                if len(items) == len(node.value.elts) and not _is_service_list(name, items):
                    packs.append({'name': name, 'items': items})
    return packs


def _str_val(node: ast.AST | None, constants: dict[str, str] | None = None) -> str | None:
    if node is None:
        return None
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.Name) and constants and node.id in constants:
        return constants[node.id]
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        left = _str_val(node.left, constants)
        right = _str_val(node.right, constants)
        if left is not None and right is not None:
            return left + right
    if isinstance(node, ast.JoinedStr):
        parts = []
        for v in node.values:
            if isinstance(v, ast.Constant) and isinstance(v.value, str):
                parts.append(v.value)
            else:
                parts.append('{user}')
        return ''.join(parts)
    return None


def _kw(call: ast.Call, name: str) -> ast.AST | None:
    for kw in call.keywords:
        if kw.arg == name:
            return kw.value
    return None


def _call_name(node: ast.AST | None) -> str:
    if not isinstance(node, ast.Call):
        return ''
    fn = node.func
    if isinstance(fn, ast.Name):
        return fn.id
    if isinstance(fn, ast.Attribute):
        return fn.attr
    return ''


def _parse_inline_keyboards(tree: ast.Module, constants: dict[str, str]) -> dict[str, list[list[dict]]]:
    """Извлекает функции def foo(): markup = InlineKeyboardMarkup(); markup.row(...)"""
    keyboards: dict[str, list[list[dict]]] = {}
    for node in tree.body:
        if not isinstance(node, ast.FunctionDef):
            continue
        is_inline = False
        rows: list[list[dict]] = []
        for stmt in ast.walk(node):
            if isinstance(stmt, ast.Call) and _call_name(stmt) == 'InlineKeyboardMarkup':
                is_inline = True
        if not is_inline:
            continue
        for stmt in node.body:
            if isinstance(stmt, ast.Expr) and isinstance(stmt.value, ast.Call):
                cname = _call_name(stmt.value)
                if cname in ('row', 'add'):
                    row_btns = []
                    for arg in stmt.value.args:
                        if isinstance(arg, ast.Call) and _call_name(arg) == 'InlineKeyboardButton':
                            label = _str_val(arg.args[0] if arg.args else _kw(arg, 'text'), constants) or ''
                            url = _str_val(_kw(arg, 'url'), constants)
                            cb = _str_val(_kw(arg, 'callback_data'), constants)
                            copy_t = _kw(arg, 'copy_text')
                            if label:
                                if url:
                                    row_btns.append({'label': label, 'action': 'url', 'url': url})
                                elif cb:
                                    row_btns.append({'label': label, 'action': 'cb', 'callbackData': cb})
                                elif copy_t is not None:
                                    row_btns.append({'label': label, 'action': 'copy_text', 'text': _str_val(copy_t, constants) or label})
                    if row_btns:
                        rows.append(row_btns)
        if rows:
            keyboards[node.name] = rows
    return keyboards


def _parse_reply_keyboards(tree: ast.Module, constants: dict[str, str]) -> list[str]:
    labels: list[str] = []
    seen = set()
    for node in tree.body:
        if not isinstance(node, ast.FunctionDef):
            continue
        is_reply = any(isinstance(s, ast.Call) and _call_name(s) == 'ReplyKeyboardMarkup' for s in ast.walk(node))
        if not is_reply:
            continue
        for stmt in ast.walk(node):
            if isinstance(stmt, ast.Call) and _call_name(stmt) == 'KeyboardButton':
                lbl = _str_val(stmt.args[0] if stmt.args else _kw(stmt, 'text'), constants)
                if lbl and lbl.lower() not in seen:
                    seen.add(lbl.lower())
                    labels.append(lbl)
    return labels


def _parse_callbacks(tree: ast.Module, lists_map: dict[str, list[str]], constants: dict[str, str]) -> dict[str, dict]:
    """Разбирает @bot.callback_query_handler: callback_data -> действие."""
    callbacks: dict[str, dict] = {}
    for node in tree.body:
        if not isinstance(node, ast.FunctionDef):
            continue
        is_cb = any(
            isinstance(d, ast.Call) and _call_name(d) == 'callback_query_handler'
            for d in node.decorator_list
        )
        if not is_cb:
            continue

        def inspect_stmts(stmts: list[ast.stmt], current_key: str | None = None):
            for st in stmts:
                if isinstance(st, ast.If):
                    key = current_key
                    t = st.test
                    if isinstance(t, ast.Compare) and len(t.comparators) == 1:
                        right = _str_val(t.comparators[0], constants)
                        left_src = ast.unparse(t.left) if hasattr(ast, 'unparse') else ''
                        if right and ('data' in left_src):
                            key = right
                    if key:
                        inspect_stmts(st.body, key)
                    if st.orelse:
                        inspect_stmts(st.orelse, current_key)
                elif current_key and isinstance(st, ast.Expr) and isinstance(st.value, ast.Call):
                    c = st.value
                    cname = _call_name(c)
                    entry = callbacks.setdefault(current_key, {'action': 'text', 'text': '', 'toast': ''})
                    if cname == 'answer_callback_query':
                        toast = _str_val(c.args[1] if len(c.args) > 1 else _kw(c, 'text'), constants)
                        if toast:
                            entry['toast'] = toast
                    elif cname in ('send_message', 'edit_message_text'):
                        arg_idx = 1 if cname == 'send_message' else 0
                        arg_node = c.args[arg_idx] if len(c.args) > arg_idx else _kw(c, 'text')
                        if isinstance(arg_node, ast.Call) and _call_name(arg_node) == 'choice' and arg_node.args:
                            lst_name = getattr(arg_node.args[0], 'id', '')
                            if lst_name in lists_map:
                                entry['action'] = 'pack'
                                entry['packReplies'] = lists_map[lst_name]
                        else:
                            txt = _str_val(arg_node, constants)
                            if txt is not None:
                                entry['text'] = txt
                        pm = _str_val(_kw(c, 'parse_mode'), constants)
                        if pm in ('HTML', 'MarkdownV2'):
                            entry['parseMode'] = pm
                    elif cname == 'send_dice':
                        em = _str_val(_kw(c, 'emoji'), constants) or '🎲'
                        entry['action'] = 'dice'
                        entry['diceEmoji'] = em

        inspect_stmts(node.body)
    return callbacks


def _extract_handler_trigger(dec: ast.Call, constants: dict[str, str]) -> list[dict]:
    triggers: list[dict] = []
    cmds = _kw(dec, 'commands')
    if isinstance(cmds, (ast.List, ast.Tuple)):
        for el in cmds.elts:
            v = _str_val(el, constants)
            if v:
                cmd = '/' + v.lstrip('/')
                triggers.append({'kind': 'command', 'value': cmd})
        return triggers

    rgx = _kw(dec, 'regexp')
    rgx_val = _str_val(rgx, constants)
    if rgx_val:
        triggers.append({'kind': 'regex', 'value': rgx_val})
        return triggers

    func = _kw(dec, 'func')
    if isinstance(func, ast.Lambda):
        body = func.body
        if isinstance(body, ast.Compare) and len(body.ops) == 1 and len(body.comparators) == 1:
            op = body.ops[0]
            if isinstance(op, ast.Eq):
                val = _str_val(body.comparators[0], constants)
                if val:
                    triggers.append({'kind': 'button_or_exact', 'value': val})
            elif isinstance(op, ast.In):
                val = _str_val(body.left, constants)
                if val:
                    triggers.append({'kind': 'contains', 'value': val})
    return triggers


def _resolve_reply_markup_name(call: ast.Call) -> str | None:
    rm = _kw(call, 'reply_markup')
    if isinstance(rm, ast.Call):
        return _call_name(rm)
    if isinstance(rm, ast.Name):
        return rm.id
    return None


def _parse_animation_call(call: ast.Call, constants: dict[str, str]) -> dict:
    preset = _str_val(_kw(call, 'preset'), constants) or 'custom'
    text = _str_val(_kw(call, 'text'), constants) or ''
    final_t = _str_val(call.args[3] if len(call.args) > 3 else _kw(call, 'final'), constants) or ''
    interval_sec = 0.7
    if len(call.args) > 2 and isinstance(call.args[2], ast.Constant) and isinstance(call.args[2].value, (int, float)):
        interval_sec = float(call.args[2].value)
    interval = max(500, min(3000, int(round(interval_sec * 1000))))
    loops = 1
    loops_node = _kw(call, 'loops')
    if isinstance(loops_node, ast.Constant) and isinstance(loops_node.value, int):
        loops = max(1, min(10, loops_node.value))
    frames_str = ''
    if len(call.args) > 1 and isinstance(call.args[1], (ast.List, ast.Tuple)):
        raw_frames = [_str_val(e, constants) or '' for e in call.args[1].elts]
        raw_frames = [f for f in raw_frames if f.strip()]
        if raw_frames:
            frames_str = '\n---\n'.join(raw_frames)
    if preset == 'custom' and not frames_str:
        frames_str = '⏳\n---\n✅'
    return {
        'preset': preset if preset in (
            'spinner', 'progress', 'dots', 'countdown', 'typewriter',
            'matrix', 'slot', 'moon', 'clock', 'heart', 'custom'
        ) else 'custom',
        'text': text,
        'interval': interval,
        'loops': loops,
        'count': 5,
        'final': final_t,
        'frames': frames_str,
    }


def import_android_py(source: str) -> dict:
    """Полный импорт Android .py (правила, команды, наборы, кнопки, inline-меню, анимации, расписание) в .bc."""
    if len(source.encode('utf-8')) > 512 * 1024:
        raise ValueError('Файл больше 512 КБ.')
    try:
        tree = ast.parse(source)
    except SyntaxError as exc:
        raise ValueError('Не удалось разобрать Python-файл.') from exc

    warnings: list[str] = []
    name = 'Импортированный бот'
    for line in source.splitlines()[:10]:
        cleaned = line.lstrip('# ').strip()
        if cleaned and not set(cleaned) <= {'=', '-', '*'}:
            if cleaned.startswith('BotControl — '):
                cleaned = cleaned[len('BotControl — '):]
            name = cleaned[:100]
            break

    constants: dict[str, str] = {}
    lists_map: dict[str, list[str]] = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            var = node.targets[0].id
            if var == 'BOT_TOKEN':
                continue
            sval = _str_val(node.value, constants)
            if sval is not None:
                constants[var] = sval
            elif isinstance(node.value, (ast.List, ast.Tuple)) and 1 <= len(node.value.elts) <= 500:
                items = [
                    el.value.strip()
                    for el in node.value.elts
                    if isinstance(el, ast.Constant) and isinstance(el.value, str) and el.value.strip()
                ]
                if len(items) == len(node.value.elts):
                    lists_map[var] = items

    inline_kbds = _parse_inline_keyboards(tree, constants)
    reply_buttons = _parse_reply_keyboards(tree, constants)
    callbacks = _parse_callbacks(tree, lists_map, constants)

    scenarios: list[dict] = []
    buttons: list[dict] = []
    seen_commands: set[str] = set()
    seen_buttons: set[str] = set()
    exact_button_scenarios: dict[str, str] = {}

    def build_inline_payload(kbd_name: str | None) -> tuple[list[dict], list[dict]]:
        links: list[dict] = []
        inline_btns: list[dict] = []
        if not kbd_name or kbd_name not in inline_kbds:
            return links, inline_btns
        for row_idx, row in enumerate(inline_kbds[kbd_name]):
            for btn in row:
                if btn['action'] == 'url':
                    if len(links) < 3 and btn['url'].startswith(('http://', 'https://')):
                        links.append({'label': btn['label'], 'url': btn['url']})
                elif btn['action'] == 'copy_text':
                    inline_btns.append({
                        'id': uuid.uuid4().hex[:8],
                        'label': btn['label'],
                        'action': 'copy_text',
                        'text': btn.get('text', btn['label']),
                        'row': row_idx,
                    })
                elif btn['action'] == 'cb':
                    cb_key = btn['callbackData']
                    cb_def = callbacks.get(cb_key)
                    if cb_def:
                        item = {
                            'id': uuid.uuid4().hex[:8],
                            'label': btn['label'],
                            'action': cb_def.get('action', 'text'),
                            'text': cb_def.get('text', ''),
                            'toast': cb_def.get('toast', ''),
                            'row': row_idx,
                        }
                        if cb_def.get('packReplies'):
                            item['packReplies'] = cb_def['packReplies']
                        if cb_def.get('diceEmoji'):
                            item['diceEmoji'] = cb_def['diceEmoji']
                        if cb_def.get('parseMode'):
                            item['parseMode'] = cb_def['parseMode']
                        inline_btns.append(item)
                    else:
                        inline_btns.append({
                            'id': uuid.uuid4().hex[:8],
                            'label': btn['label'],
                            'action': 'text',
                            'text': f"Нажата кнопка «{btn['label']}»",
                            'toast': 'OK',
                            'row': row_idx,
                        })
        return links, inline_btns

    for node in tree.body:
        if not isinstance(node, ast.FunctionDef):
            continue
        triggers: list[dict] = []
        for dec in node.decorator_list:
            if isinstance(dec, ast.Call) and _call_name(dec) == 'message_handler':
                triggers.extend(_extract_handler_trigger(dec, constants))
        if not triggers:
            continue

        sc_text = ''
        sc_replies: list[str] | None = None
        sc_anim: dict | None = None
        sc_dice: str | None = None
        parse_mode = ''
        kbd_name: str | None = None

        for st in ast.walk(node):
            if not isinstance(st, ast.Call):
                continue
            cname = _call_name(st)
            if cname == 'send_message' and not sc_text and sc_replies is None:
                arg_node = st.args[1] if len(st.args) > 1 else _kw(st, 'text')
                if isinstance(arg_node, ast.Call) and _call_name(arg_node) == 'choice' and arg_node.args:
                    lst_id = getattr(arg_node.args[0], 'id', '')
                    if lst_id in lists_map:
                        sc_replies = lists_map[lst_id]
                else:
                    raw_t = _str_val(arg_node, constants)
                    if raw_t is not None:
                        sc_text = raw_t.replace('{name}', '{user}')
                pm = _str_val(_kw(st, 'parse_mode'), constants)
                if pm in ('HTML', 'MarkdownV2'):
                    parse_mode = pm
                kbd_name = kbd_name or _resolve_reply_markup_name(st)
            elif cname == 'send_dice' and sc_dice is None:
                sc_dice = _str_val(_kw(st, 'emoji'), constants) or '🎲'
                kbd_name = kbd_name or _resolve_reply_markup_name(st)
            elif cname == 'play_animation' and sc_anim is None:
                sc_anim = _parse_animation_call(st, constants)
                kbd_name = kbd_name or _resolve_reply_markup_name(st)

        links, inline_btns = build_inline_payload(kbd_name)
        if not sc_text and not sc_replies and not sc_anim and not sc_dice:
            sc_text = 'Готово.'

        for trig in triggers:
            sc_id = uuid.uuid4().hex[:12]
            sc: dict = {
                'id': sc_id,
                'name': f"{node.name} ({trig['value']})"[:60],
                'text': sc_text if (not sc_replies and not sc_anim and not sc_dice) else '',
            }
            if parse_mode:
                sc['parseMode'] = parse_mode
            if sc_replies:
                sc['replies'] = sc_replies
            elif sc_anim:
                sc['animation'] = sc_anim
            elif sc_dice:
                sc['diceEmoji'] = sc_dice
            if links:
                sc['links'] = links
            if inline_btns:
                sc['inlineButtons'] = inline_btns

            if trig['kind'] == 'command':
                cmd_val = trig['value']
                if not re.fullmatch(r'/[a-zA-Z0-9_]{1,32}', cmd_val):
                    warnings.append(f"Команда «{cmd_val}» пропущена (недопустимый формат).")
                    continue
                if cmd_val.lower() in seen_commands:
                    warnings.append(f"Дублирующая команда «{cmd_val}» пропущена.")
                    continue
                seen_commands.add(cmd_val.lower())
                sc['trigger'] = {'type': 'command', 'value': cmd_val}
                scenarios.append(sc)
            elif trig['kind'] == 'contains':
                sc['trigger'] = {'type': 'contains', 'value': trig['value'][:200]}
                scenarios.append(sc)
            elif trig['kind'] == 'regex':
                try:
                    re.compile(trig['value'])
                    sc['trigger'] = {'type': 'regex', 'value': trig['value'][:200]}
                    scenarios.append(sc)
                except re.error:
                    warnings.append(f"Некорректное регулярное выражение «{trig['value']}» пропущено.")
            elif trig['kind'] == 'button_or_exact':
                lbl = trig['value'].strip()
                scenarios.append(sc)
                if lbl.lower() not in seen_buttons:
                    seen_buttons.add(lbl.lower())
                    buttons.append({'id': uuid.uuid4().hex[:10], 'label': lbl, 'scenarioId': sc_id})
                    exact_button_scenarios[lbl.lower()] = sc_id

    for lbl in reply_buttons:
        if lbl.lower() not in seen_buttons:
            target = exact_button_scenarios.get(lbl.lower(), '')
            if not target:
                sc_id = uuid.uuid4().hex[:12]
                scenarios.append({'id': sc_id, 'name': lbl[:60], 'text': f'Раздел «{lbl}»'})
                target = sc_id
                warnings.append(f"Для кнопки клавиатуры «{lbl}» не найден обработчик — создан шаблонный ответ.")
            seen_buttons.add(lbl.lower())
            buttons.append({'id': uuid.uuid4().hex[:10], 'label': lbl, 'scenarioId': target})

    # Расписание (get_today_schedule): создаём готовые сценарии, а в schedule добавляем
    # только если известен chatId (иначе compile_bot потребует числовой chatId перед запуском).
    schedule: list[dict] = []
    imported_times: list[str] = []
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == 'get_today_schedule':
            for st in ast.walk(node):
                if isinstance(st, ast.Tuple) and len(st.elts) >= 3:
                    h, m = st.elts[0], st.elts[1]
                    txt = _str_val(st.elts[2], constants)
                    if (
                        isinstance(h, ast.Constant) and isinstance(h.value, int)
                        and isinstance(m, ast.Constant) and isinstance(m.value, int)
                        and txt and 0 <= h.value <= 23 and 0 <= m.value <= 59
                    ):
                        t_label = f'{h.value:02d}:{m.value:02d}'
                        imported_times.append(t_label)
                        sc_id = uuid.uuid4().hex[:12]
                        scenarios.append({'id': sc_id, 'name': f'Расписание {t_label}', 'text': txt})
            if imported_times:
                warnings.append(
                    f'Из get_today_schedule импортированы сценарии ({", ".join(imported_times)}); '
                    'чтобы включить рассылку по времени, выберите их в разделе «Напоминания» и укажите ваш /chatid.'
                )

    if 'SYSTEM_PROMPT' in constants:
        warnings.append('Локальный ИИ (SYSTEM_PROMPT) в Windows-версии не запускается — перенесены правила, кнопки и наборы.')

    if not scenarios:
        # Запасной вариант — только буквальные списки, если в файле нет декораторов
        for p in extract_lists(source):
            scenarios.append({'id': uuid.uuid4().hex[:12], 'name': p['name'], 'text': '', 'replies': p['items']})

    return {
        'bot': {
            'format': 'botcontrol',
            'version': 1,
            'name': name,
            'scenarios': scenarios,
            'buttons': buttons,
            'schedule': schedule,
        },
        'warnings': warnings,
    }


def _py_str(s: str) -> str:
    escaped = s.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
    return f'"{escaped}"'


def export_android_py(bot: dict) -> str:
    """Экспортирует .bc-конфиг в Android-совместимый Python-файл (для ScriptImporter)."""
    name = (bot.get('name') or 'BotControl').replace('\n', ' ').strip()
    scenarios = bot.get('scenarios') or []
    buttons = bot.get('buttons') or []
    schedule = bot.get('schedule') or []

    sc_by_id = {s.get('id'): s for s in scenarios if isinstance(s, dict) and s.get('id')}
    lines = [
        '# ============================================================',
        f'# BotControl — {name}',
        '# ============================================================',
        '',
        'BOT_TOKEN = "ТОКЕН_НЕ_ИМПОРТИРУЕТСЯ"',
        '',
        'import random',
        'from telebot import types',
        '',
    ]

    # Наборы ответов (replies)
    pack_vars: dict[str, str] = {}
    used_vars: set[str] = set()
    for idx, sc in enumerate(scenarios):
        if sc.get('replies'):
            base = re.sub(r'[^A-Za-z0-9]+', '_', sc.get('name') or '').strip('_').upper()
            if not base or not base[0].isalpha():
                base = f'PACK_{idx + 1}'
            var = base
            n = 2
            while var in used_vars:
                var = f'{base}_{n}'
                n += 1
            used_vars.add(var)
            pack_vars[sc['id']] = var
            lines.append(f'{var} = [')
            for item in sc['replies']:
                lines.append(f'    {_py_str(str(item))},')
            lines.append(']')
            lines.append('')

    # Reply-клавиатура
    if buttons:
        lines.append('def chat_keyboard():')
        lines.append('    markup = types.ReplyKeyboardMarkup(resize_keyboard=True)')
        row_items = [f'types.KeyboardButton({_py_str(str(b["label"]))})' for b in buttons if b.get('label')]
        for i in range(0, len(row_items), 2):
            lines.append(f'    markup.row({", ".join(row_items[i:i + 2])})')
        lines.append('    return markup')
        lines.append('')

    # Inline-меню и callbacks
    callback_defs: list[tuple[str, dict]] = []
    inline_fns: dict[str, str] = {}
    for idx, sc in enumerate(scenarios):
        links = sc.get('links') or []
        ib = sc.get('inlineButtons') or []
        if not links and not ib:
            continue
        fn = f'menu_sc_{idx}'
        inline_fns[sc['id']] = fn
        lines.append(f'def {fn}():')
        lines.append('    markup = types.InlineKeyboardMarkup()')
        for lk in links:
            lines.append(f'    markup.row(types.InlineKeyboardButton({_py_str(str(lk["label"]))}, url={_py_str(str(lk["url"]))}))')
        for j, btn in enumerate(ib):
            if btn.get('action') == 'url' and btn.get('url'):
                lines.append(f'    markup.row(types.InlineKeyboardButton({_py_str(str(btn["label"]))}, url={_py_str(str(btn["url"]))}))')
            else:
                cb_id = f'cb_{idx}_{j}'
                callback_defs.append((cb_id, btn))
                lines.append(f'    markup.row(types.InlineKeyboardButton({_py_str(str(btn["label"]))}, callback_data={_py_str(cb_id)}))')
        lines.append('    return markup')
        lines.append('')

    def reply_call(sc: dict, var_name: str = 'msg') -> list[str]:
        rm = f', reply_markup={inline_fns[sc["id"]]}()' if sc.get('id') in inline_fns else (
            ', reply_markup=chat_keyboard()' if buttons else ''
        )
        pm = f', parse_mode={_py_str(sc["parseMode"])}' if sc.get('parseMode') in ('HTML', 'MarkdownV2') else ''
        if sc.get('diceEmoji'):
            return [f'    bot.send_dice({var_name}.chat.id, emoji={_py_str(sc["diceEmoji"])}{rm})']
        if sc.get('replies') and sc.get('id') in pack_vars:
            return [f'    bot.send_message({var_name}.chat.id, random.choice({pack_vars[sc["id"]]}){pm}{rm})']
        if sc.get('animation'):
            a = sc['animation']
            return [
                f'    play_animation({var_name}.chat.id, [], {a.get("interval", 700) / 1000:.1f}, '
                f'{_py_str(a.get("final", ""))}, preset={_py_str(a.get("preset", "spinner"))}, '
                f'text={_py_str(a.get("text", ""))}, loops={int(a.get("loops", 1))})'
            ]
        return [f'    bot.send_message({var_name}.chat.id, {_py_str(str(sc.get("text", "")))}{pm}{rm})']

    # Хендлеры команд и триггеров
    button_by_sc = {b.get('scenarioId'): b.get('label') for b in buttons if b.get('scenarioId')}
    for idx, sc in enumerate(scenarios):
        trig = sc.get('trigger') or {}
        ttype = trig.get('type')
        tval = trig.get('value') or ''
        if ttype == 'command' and tval:
            cmd = tval.lstrip('/')
            lines.append(f'@bot.message_handler(commands=[{_py_str(cmd)}])')
            lines.append(f'def cmd_{idx}(msg):')
            lines.extend(reply_call(sc))
            lines.append('')
        elif ttype == 'contains' and tval:
            lines.append(f'@bot.message_handler(func=lambda m: {_py_str(tval.lower())} in (m.text or "").lower())')
            lines.append(f'def phrase_{idx}(msg):')
            lines.extend(reply_call(sc))
            lines.append('')
        elif ttype == 'regex' and tval:
            lines.append(f'@bot.message_handler(regexp={_py_str(tval)})')
            lines.append(f'def regex_{idx}(msg):')
            lines.extend(reply_call(sc))
            lines.append('')
        if sc.get('id') in button_by_sc and button_by_sc[sc['id']]:
            lbl = button_by_sc[sc['id']]
            lines.append(f'@bot.message_handler(func=lambda m: m.text == {_py_str(lbl)})')
            lines.append(f'def btn_{idx}(msg):')
            lines.extend(reply_call(sc))
            lines.append('')

    if callback_defs:
        lines.append('@bot.callback_query_handler(func=lambda call: True)')
        lines.append('def on_callback(call):')
        for idx, (cb_id, btn) in enumerate(callback_defs):
            kw = 'if' if idx == 0 else 'elif'
            lines.append(f'    {kw} call.data == {_py_str(cb_id)}:')
            lines.append(f'        bot.answer_callback_query(call.id, {_py_str(str(btn.get("toast") or "OK"))})')
            if btn.get('action') == 'dice':
                lines.append(f'        bot.send_dice(call.message.chat.id, emoji={_py_str(str(btn.get("diceEmoji") or "🎲"))})')
            elif btn.get('action') == 'pack' and btn.get('packReplies'):
                lines.append(f'        bot.send_message(call.message.chat.id, random.choice({btn["packReplies"]!r}))')
            else:
                lines.append(f'        bot.send_message(call.message.chat.id, {_py_str(str(btn.get("text") or btn.get("label") or ""))})')
        lines.append('')

    if schedule:
        lines.append('def get_today_schedule(now):')
        lines.append('    schedule = [')
        for ev in schedule:
            sc = sc_by_id.get(ev.get('scenarioId')) or {}
            hh, mm = (ev.get('time') or '09:00').split(':', 1)
            lines.append(f'        ({int(hh)}, {int(mm)}, {_py_str(str(sc.get("text") or sc.get("name") or "Напоминание"))}),')
        lines.append('    ]')
        lines.append('    return sorted(schedule, key=lambda item: (item[0], item[1]))')
        lines.append('')

    return '\n'.join(lines)
