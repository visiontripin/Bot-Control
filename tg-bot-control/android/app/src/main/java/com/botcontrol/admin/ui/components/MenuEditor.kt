package com.botcontrol.admin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.Anim
import com.botcontrol.admin.data.AnimSpec
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.ReplyPack
import java.util.UUID

/** Сколько кнопок можно добавить под одно сообщение (Telegram допускает больше). */
const val MAX_MENU_BUTTONS = 12
/** Сколько рядов предлагать в выборе «Ряд кнопок». */
const val MAX_MENU_ROWS = 8
/** Максимальная глубина вложенного подменю (I-4). */
private const val MAX_SUBMENU_DEPTH = 3

/**
 * Редактор inline-меню сообщения (до [MAX_MENU_BUTTONS] кнопок): надпись, всплывашка,
 * действие (текст / набор / подменю I-4 / вкл-выкл напоминаний / скрипт /
 * анимация / кубик / ИИ / медиа / url / web_app / copy_text / switch_inline).
 */
@Composable
fun MenuEditor(
    menu: List<InlineBtn>,
    packs: List<ReplyPack>,
    onChange: (List<InlineBtn>) -> Unit,
    depth: Int = 0,
) {
    var editing by remember { mutableStateOf<InlineBtn?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<InlineBtn?>(null) }

    Column {
        Text(
            if (depth == 0) "Кнопки под этим сообщением (до $MAX_MENU_BUTTONS):"
            else "Кнопки подменю (уровень $depth, до $MAX_MENU_BUTTONS):",
            style = MaterialTheme.typography.bodySmall,
        )
        menu.forEach { btn ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🔘 ${btn.label}", style = MaterialTheme.typography.bodyMedium)
                        Text(actionLabel(btn, packs), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { editing = btn }) { Text("✎") }
                    TextButton(onClick = { deleting = btn }) { Text("✕") }
                }
            }
        }
        if (menu.size < MAX_MENU_BUTTONS) {
            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (depth == 0) "+ Добавить кнопку" else "+ Кнопка в подменю")
            }
        }
    }

    deleting?.let { btn ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить кнопку?") },
            text = { Text("«${btn.label}» исчезнет из меню этого сообщения.") },
            confirmButton = {
                TextButton(onClick = { onChange(menu - btn); deleting = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }

    if (showAdd || editing != null) {
        val initial = editing ?: InlineBtn()
        MenuButtonDialog(
            initial = initial,
            packs = packs,
            depth = depth,
            onDismiss = { showAdd = false; editing = null },
            onSave = { saved ->
                val next = if (editing == null) {
                    menu + saved.copy(id = "cb" + UUID.randomUUID().toString().substring(0, 8))
                } else {
                    menu.map { if (it.id == editing!!.id) saved.copy(id = it.id) else it }
                }
                onChange(next)
                showAdd = false
                editing = null
            },
        )
    }
}

private fun actionLabel(btn: InlineBtn, packs: List<ReplyPack>): String {
    val base = when (btn.action) {
        "pack" -> "→ случайное из «${packs.firstOrNull { it.id == btn.packId }?.name ?: btn.packId}»"
        "reminders_on" -> "→ включить напоминания"
        "reminders_off" -> "→ выключить напоминания"
        "script" -> "→ скрипт JS"
        "anim" -> "→ 🎞 " + Anim.describe(Anim.decode(btn.script))
        "dice" -> "→ кубик ${btn.text.ifBlank { "🎲" }}"
        "url" -> "→ ссылка: ${btn.url.take(30)}"
        "web_app" -> "→ Mini App: ${btn.url.take(30)}"
        "copy_text" -> "→ копировать: ${btn.text.take(25)}"
        "switch_inline" -> "→ запрос в чат: ${btn.text.take(25)}"
        "llm" -> "→ ИИ: ${btn.text.ifBlank { btn.label }.take(25)}"
        "media" -> "→ медиа (${btn.mediaType.ifBlank { "photo" }})"
        "fsm" -> "→ анкета FSM (${btn.menuTarget.ifBlank { btn.text.ifBlank { btn.label } }.take(20)})"
        "submenu" -> "→ подменю (${btn.submenu.size} кн.${if (btn.menuTarget.isNotBlank()) ", цель ${btn.menuTarget}" else ""})"
        "back" -> "→ ⬅️ назад в меню"
        else -> "→ текст: ${btn.text.take(30)}"
    }
    val flags = buildList {
        if (btn.edit) add("правит сообщение")
        if (btn.parseMode.isNotBlank()) add(btn.parseMode)
        if (btn.row > 0) add("ряд ${btn.row}")
    }
    return base + if (flags.isEmpty()) "" else " (${flags.joinToString(", ")})"
}

@Composable
private fun MenuButtonDialog(
    initial: InlineBtn,
    packs: List<ReplyPack>,
    depth: Int,
    onDismiss: () -> Unit,
    onSave: (InlineBtn) -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var toast by remember { mutableStateOf(initial.toast) }
    var action by remember { mutableStateOf(initial.action) }
    var packId by remember { mutableStateOf(initial.packId) }
    var text by remember { mutableStateOf(initial.text) }
    var script by remember { mutableStateOf(if (initial.action == "anim") "" else initial.script) }
    var url by remember { mutableStateOf(initial.url) }
    var edit by remember { mutableStateOf(initial.edit) }
    var row by remember { mutableStateOf(if (initial.row > 0) initial.row else 1) }
    var toastSame by remember { mutableStateOf(initial.toastNoChange) }
    var parseMode by remember { mutableStateOf(initial.parseMode) }
    var mediaType by remember { mutableStateOf(initial.mediaType.ifBlank { "photo" }) }
    var mediaRef by remember { mutableStateOf(initial.mediaRef) }
    var menuTarget by remember { mutableStateOf(initial.menuTarget) }
    var submenu by remember { mutableStateOf(initial.submenu) }
    var animSpec by remember {
        mutableStateOf(if (initial.action == "anim") Anim.decode(initial.script)
            else AnimSpec(preset = "spinner", text = "Загрузка", intervalMs = 600))
    }
    var dice by remember { mutableStateOf(if (initial.action == "dice") initial.text.ifBlank { "🎲" } else "🎲") }

    val actionOptions = buildList {
        addAll(listOf("text", "pack", "anim", "dice", "url", "web_app", "copy_text", "switch_inline", "llm", "media", "fsm"))
        if (depth < MAX_SUBMENU_DEPTH) add("submenu")
        if (depth > 0 || initial.action == "back") add("back")
        addAll(listOf("reminders_on", "reminders_off", "script"))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кнопка") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(label, { label = it },
                    label = { Text("Надпись на кнопке") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(toast, { toast = it },
                    label = { Text("Всплывашка при нажатии (необязательно)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    value = action,
                    options = actionOptions,
                    label = "Что сделать",
                    display = {
                        when (it) {
                            "pack" -> "Случайное из набора"
                            "url" -> "Открыть ссылку"
                            "web_app" -> "🌐 Mini App (Web App URL)"
                            "copy_text" -> "📋 Копировать текст"
                            "switch_inline" -> "🔎 Подставить запрос в чат"
                            "llm" -> "🤖 Спросить ИИ"
                            "media" -> "🖼 Отправить медиа (file_id/URL)"
                            "fsm" -> "📋 Запустить анкету (FSM)"
                            "submenu" -> "📂 Открыть подменю (вложенное)"
                            "back" -> "⬅️ Назад на уровень выше"
                            "reminders_on" -> "Включить напоминания"
                            "reminders_off" -> "Выключить напоминания"
                            "script" -> "Скрипт JS"
                            "anim" -> "🎞 Анимация (правка сообщения)"
                            "dice" -> "🎲 Кубик / дартс / слот"
                            else -> "Ответить текстом"
                        }
                    },
                    onSelect = {
                        action = it
                        if (it == "pack" && packId.isBlank()) {
                            packId = packs.firstOrNull()?.id.orEmpty()
                        }
                        if (it == "back" && label.isBlank()) label = "⬅️ Назад"
                    },
                )
                Spacer(Modifier.height(8.dp))
                when (action) {
                    "pack" -> DropdownField(
                        value = packId.ifBlank { packs.firstOrNull()?.id.orEmpty() },
                        options = packs.map { it.id },
                        label = "Набор ответов",
                        display = { id -> packs.firstOrNull { it.id == id }?.name ?: id },
                        onSelect = { packId = it },
                    )
                    "script" -> OutlinedTextField(script, { script = it },
                        label = { Text("JS: function handle(e) { … return \"ответ\" }") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().height(190.dp))
                    "anim" -> AnimEditor(spec = animSpec, packs = packs, onChange = { animSpec = it })
                    "dice" -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Anim.DICE.forEach { d ->
                            FilterChip(selected = dice == d, onClick = { dice = d }, label = { Text(d) })
                        }
                    }
                    "url", "web_app" -> {
                        OutlinedTextField(url, { url = it },
                            label = { Text(if (action == "web_app") "URL Mini App (https://…)" else "Ссылка") },
                            placeholder = { Text(if (action == "web_app") "https://…" else "https://… или tg://user?id=12345") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(
                            if (action == "web_app") "Telegram откроет страницу внутри встроенного Web App."
                            else "Кнопка-ссылка ничего не шлёт боту — Telegram просто открывает адрес.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    "copy_text" -> OutlinedTextField(text, { text = it },
                        label = { Text("Текст для копирования (до 256 симв.)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    "switch_inline" -> OutlinedTextField(text, { text = it },
                        label = { Text("Текст подстановки после @бота") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    "llm" -> OutlinedTextField(text, { text = it },
                        label = { Text("Промт для ИИ (пусто = надпись кнопки)") },
                        modifier = Modifier.fillMaxWidth().height(90.dp))
                    "media" -> {
                        DropdownField(
                            value = mediaType,
                            options = listOf("photo", "sticker", "animation", "document", "video", "voice", "audio"),
                            label = "Тип медиа",
                            display = {
                                when (it) {
                                    "sticker" -> "Стикер (sendSticker)"
                                    "animation" -> "GIF / анимация"
                                    "document" -> "Файл / документ"
                                    "video" -> "Видео"
                                    "voice" -> "Голосовое"
                                    "audio" -> "Аудио"
                                    else -> "Фото"
                                }
                            },
                            onSelect = { mediaType = it },
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(mediaRef, { mediaRef = it },
                            label = { Text("file_id, https:// URL или путь к файлу") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(text, { text = it },
                            label = { Text("Подпись к медиа (необязательно)") },
                            modifier = Modifier.fillMaxWidth().height(80.dp))
                    }
                    "fsm" -> OutlinedTextField(menuTarget, { menuTarget = it },
                        label = { Text("Название, ID или команда запуска анкеты (напр. /form)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    "submenu" -> {
                        OutlinedTextField(text, { text = it },
                            label = { Text("Текст заголовка при открытии подменю (необязательно)") },
                            modifier = Modifier.fillMaxWidth().height(72.dp))
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(menuTarget, { menuTarget = it },
                            label = { Text("Либо имя команды/правила с меню (если вложенных кнопок нет)") },
                            placeholder = { Text("/catalog") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        if (depth < MAX_SUBMENU_DEPTH) {
                            MenuEditor(menu = submenu, packs = packs, onChange = { submenu = it }, depth = depth + 1)
                        }
                    }
                    "back" -> OutlinedTextField(text, { text = it },
                        label = { Text("Текст при возврате (пусто = оставить сообщение)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    "reminders_on", "reminders_off" -> {
                        OutlinedTextField(text, { text = it },
                            label = { Text("Текст после переключения") },
                            modifier = Modifier.fillMaxWidth().height(90.dp))
                        OutlinedTextField(toastSame, { toastSame = it },
                            label = { Text("Всплывашка, если уже так (необязательно)") },
                            placeholder = { Text("Уже работает") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    else -> OutlinedTextField(text, { text = it },
                        label = { Text("Ответ бота") },
                        modifier = Modifier.fillMaxWidth().height(90.dp))
                }
                if (action in setOf("text", "pack", "anim", "script", "llm")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = edit, onCheckedChange = { edit = it })
                        Text(if (action == "anim") "Анимировать это же сообщение (кнопки останутся)"
                            else "Править это сообщение (не присылать новое)",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (action in setOf("text", "pack", "script", "llm", "media", "submenu", "back", "reminders_on", "reminders_off")) {
                    Spacer(Modifier.height(4.dp))
                    Text("Разметка текста (parse_mode):", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to "Обычный", "HTML" to "HTML", "MarkdownV2" to "MarkdownV2").forEach { (pm, lbl) ->
                            FilterChip(selected = parseMode == pm, onClick = { parseMode = pm }, label = { Text(lbl) })
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                DropdownField(
                    value = row,
                    options = (1..maxOf(MAX_MENU_ROWS, row)).toList(),
                    label = "Ряд кнопок",
                    display = { "Ряд $it" },
                    onSelect = { row = it },
                )
            }
        },
        confirmButton = {
            val resolvedPack = packId.ifBlank { packs.firstOrNull()?.id.orEmpty() }
            TextButton(
                onClick = {
                    onSave(InlineBtn(
                        action = action,
                        label = label.trim(),
                        toast = toast,
                        packId = if (action == "pack") resolvedPack else "",
                        text = if (action == "dice") dice else text,
                        script = if (action == "anim") Anim.encode(animSpec) else script,
                        url = if (action == "url" || action == "web_app") url.trim() else "",
                        edit = if (action == "submenu") true else edit,
                        row = row,
                        toastNoChange = if (action.startsWith("reminders")) toastSame.trim() else "",
                        submenu = if (action == "submenu") submenu else emptyList(),
                        menuTarget = if (action == "submenu" || action == "fsm") menuTarget.trim() else "",
                        mediaType = if (action == "media") mediaType else "",
                        mediaRef = if (action == "media") mediaRef.trim() else "",
                        parseMode = parseMode,
                    ))
                },
                enabled = label.isNotBlank() && when (action) {
                    "pack" -> resolvedPack.isNotBlank()
                    "text", "copy_text" -> text.isNotBlank()
                    "script" -> script.isNotBlank()
                    "url" -> url.trim().startsWith("http") || url.trim().startsWith("tg://")
                    "web_app" -> url.trim().startsWith("https://")
                    "media" -> mediaRef.isNotBlank()
                    "submenu" -> submenu.isNotEmpty() || menuTarget.isNotBlank()
                    "anim" -> animSpec.preset != "custom" || animSpec.frames.isNotEmpty()
                    else -> true
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
