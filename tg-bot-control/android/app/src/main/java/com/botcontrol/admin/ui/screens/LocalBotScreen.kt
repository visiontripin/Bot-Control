package com.botcontrol.admin.ui.screens

import com.botcontrol.admin.ui.components.ConfirmRequest
import com.botcontrol.admin.ui.components.ConfirmHost
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.local.BotRuleEntity
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.service.LocalBotService
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch

/**
 * Home of the on-phone bot: Start/Stop, «команда → ответ», кнопки
 * клавиатуры Telegram, скрипты и ИИ. Всё кнопками, без логина.
 */
@Composable
fun LocalBotScreen(
    repository: BotRepository,
    localStore: LocalBotStore,
    onManageModels: () -> Unit,
    onLlmChat: () -> Unit,
    onOpenScript: (Int) -> Unit,
    onReminders: () -> Unit,
    onButtons: () -> Unit,
    onPacks: () -> Unit,
    onAiSettings: () -> Unit,
    onBotSettings: () -> Unit,
    onSimulate: () -> Unit,
    onChangeMode: () -> Unit,
    onEditToken: () -> Unit,
) {
    val confirm = remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmHost(confirm)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by LocalBotService.states.collectAsState()
    var activeId by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { activeId = localStore.activeBotId() }
    val state = states[activeId]
        ?: com.botcontrol.admin.service.LocalBotState(botId = activeId)

    var rules by remember { mutableStateOf<List<BotRuleEntity>>(emptyList()) }
    var llmEnabled by remember { mutableStateOf(false) }
    var systemPrompt by remember { mutableStateOf("") }
    var defaultReply by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var showAddRule by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<BotRuleEntity?>(null) }
    var packs by remember { mutableStateOf<List<com.botcontrol.admin.data.ReplyPack>>(emptyList()) }

    fun load() {
        scope.launch {
            rules = repository.botRules(localStore.activeBotId())
            packs = localStore.packs()
            llmEnabled = localStore.llmEnabled()
            systemPrompt = localStore.systemPrompt()
            defaultReply = localStore.defaultReply()
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Мой бот", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onChangeMode) { Text("Сменить режим") }
        }
        if (error.isNotBlank()) ErrorText(error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)

        // ---- status ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (state.running) "🟢 Работает" else "⚪️ Остановлен",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state.running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.botUsername.isNotBlank()) {
                    Text("Бот: @${state.botUsername}")
                }
                Text("Обработано сообщений: ${state.processed}",
                    style = MaterialTheme.typography.bodySmall)
                val pollAgo = if (state.lastPollAt == 0L) -1L
                    else (System.currentTimeMillis() / 1000 - state.lastPollAt)
                Text(
                    if (pollAgo < 0) "Опрос Telegram: ещё не выполнялся"
                    else "Опрос Telegram: $pollAgo с назад",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pollAgo in 0..60) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.lastError.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Проблема: ${state.lastError}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                LocalBotService.startBot(context, activeId)
                scope.launch { delay800(); load() }
            }, enabled = !state.running && activeId > 0, modifier = Modifier.weight(1f)) {
                Text("▶ Запустить бота") }
            OutlinedButton(onClick = {
                LocalBotService.stopBot(context, activeId)
                scope.launch { delay300(); load() }
            }, enabled = state.running && activeId > 0, modifier = Modifier.weight(1f)) {
                Text("■ Остановить бота") }
        }

        // ---- rules ----
        SectionTitle("Команды и ответы")
        Text("Что напишут в Telegram — бот ответит сам. Тип действия: текст, скрипт или ИИ.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        if (rules.isEmpty()) {
            Text("Пока нет ни одной команды.", style = MaterialTheme.typography.bodySmall)
        }
        rules.forEach { rule ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .clickable { editingRule = rule }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val kindLabel = when (rule.type) {
                            "command" -> "Команда: ${rule.pattern}"
                            "button" -> "Кнопка: «${rule.pattern}»"
                            "regex" -> "Regex: /${rule.pattern}/"
                            else -> "Содержит: «${rule.pattern}»"
                        }
                        Text(kindLabel, style = MaterialTheme.typography.titleSmall)
                        val ruleOpts = com.botcontrol.admin.data.BotJson.ruleOptions(rule.options)
                        val actionLabel = when (rule.actionType) {
                            "script" -> "⚙️ скрипт JS"
                            "llm" -> "🤖 отвечает ИИ"
                            "pack" -> "🎲 случайное из набора"
                            "anim" -> "🎞 анимация: " + com.botcontrol.admin.data.Anim.describe(
                                com.botcontrol.admin.data.Anim.decode(rule.script))
                            "dice" -> "🎲 кубик ${rule.responseText.ifBlank { "🎲" }}"
                            "media" -> "🖼 медиа (${ruleOpts.mediaType.ifBlank { "photo" }})"
                            "poll" -> "📊 опрос: ${ruleOpts.pollQuestion.ifBlank { rule.responseText }.take(35)}"
                            else -> "Ответ: ${rule.responseText.take(50)}"
                        }
                        Text(actionLabel, style = MaterialTheme.typography.bodySmall)
                    }
                    if (rule.actionType == "script") {
                        TextButton(onClick = { onOpenScript(rule.id) }) { Text("✎ код") }
                    }
                    TextButton(onClick = { editingRule = rule }) { Text("Изменить") }
                    Switch(rule.enabled, onCheckedChange = { on ->
                        scope.launch {
                            repository.saveBotRule(rule.copy(enabled = on))
                            load()
                        }
                    })
                    TextButton(onClick = { confirm.value = ConfirmRequest("Удалить правило?", rule.pattern) {
                        scope.launch { repository.deleteBotRule(rule.id); load() }
                    } }) { Text("✕") }
                }
            }
        }
        OutlinedButton(onClick = { showAddRule = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Добавить команду")
        }

        // Кнопки настраиваются в одном месте, вместе с действием.
        OutlinedButton(onClick = onButtons, modifier = Modifier.fillMaxWidth()) {
            Text("🔘 Настроить кнопки бота")
        }

        // ---- AI ----
        SectionTitle("ИИ — фича, а не ответ «на всё»")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Включить ИИ")
                Text("ИИ отвечает ТОЛЬКО в сценариях: правило с действием «ИИ» и команда /chat. " +
                    "Команды, кнопки, наборы и расписание работают без ИИ — кодом.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(llmEnabled, onCheckedChange = {
                llmEnabled = it
                scope.launch { localStore.setLlmEnabled(it) }
            })
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            systemPrompt, { systemPrompt = it },
            label = { Text("Характер бота (системный промт)") },
            placeholder = { Text("Ты дружелюбный помощник…") },
            modifier = Modifier.fillMaxWidth().height(90.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                scope.launch { localStore.setSystemPrompt(systemPrompt); message = "Характер сохранён" }
            }, modifier = Modifier.weight(1f)) { Text("Сохранить характер") }
            OutlinedButton(onClick = onManageModels, modifier = Modifier.weight(1f)) {
                Text("Модели ИИ")
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onLlmChat, modifier = Modifier.fillMaxWidth()) {
            Text("💬 Чат с ИИ (и процесс раздумий)")
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onReminders, modifier = Modifier.fillMaxWidth()) {
            Text("⏰ Напоминания (расписание)")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onPacks, modifier = Modifier.weight(1f)) {
                Text("🎲 Наборы ответов")
            }
            OutlinedButton(onClick = onAiSettings, modifier = Modifier.weight(1f)) {
                Text("⚙️ Настройки ИИ")
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onSimulate, modifier = Modifier.weight(1f)) {
                Text("🧪 Имитация")
            }
            OutlinedButton(onClick = onBotSettings, modifier = Modifier.weight(1f)) {
                Text("⚙️ Настройки бота")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Подсказка: бота целиком (характер, команды, кнопки, наборы, расписание) можно "
            + "собрать кодом — «Скрипты → Импорт настроек из кода». Готовый промт, по которому "
            + "нейросеть напишет такой код, — в разделе «🧠 Промт для создания бота».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ---- default reply ----
        SectionTitle("Ответ, если ничего не подошло")
        OutlinedTextField(
            defaultReply, { defaultReply = it },
            label = { Text("Можно оставить пустым") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            scope.launch { localStore.setDefaultReply(defaultReply); message = "Сохранено" }
        }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить ответ") }

        // ---- token ----
        SectionTitle("Токен бота")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEditToken, modifier = Modifier.weight(1f)) {
                Text("+ Бот (новый токен)")
            }
        }
        Text("Токен хранится в зашифрованном хранилище телефона.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }

    // ---- add/edit rule dialog ----
    if (showAddRule || editingRule != null) {
        val initial = editingRule
        val initOpts = remember(initial?.id) {
            com.botcontrol.admin.data.BotJson.ruleOptions(initial?.options.orEmpty())
        }
        var type by remember { mutableStateOf(initial?.type ?: "command") }
        var action by remember { mutableStateOf(initial?.actionType ?: "text") }
        var pattern by remember { mutableStateOf(initial?.pattern ?: "") }
        var response by remember { mutableStateOf(initial?.responseText ?: "") }
        var packId by remember { mutableStateOf(initial?.packId ?: "") }
        var animSpec by remember {
            mutableStateOf(
                if (initial?.actionType == "anim") com.botcontrol.admin.data.Anim.decode(initial?.script.orEmpty())
                else com.botcontrol.admin.data.AnimSpec(preset = "progress", text = "Подготовка", intervalMs = 600)
            )
        }
        var dice by remember {
            mutableStateOf(if (initial?.actionType == "dice") initial?.responseText.orEmpty().ifBlank { "🎲" } else "🎲")
        }
        var ruleMenu by remember {
            mutableStateOf(com.botcontrol.admin.data.BotJson.menu(initial?.menu.orEmpty()))
        }
        var parseMode by remember { mutableStateOf(initOpts.parseMode) }
        var disablePreview by remember { mutableStateOf(initOpts.disablePreview) }
        var silent by remember { mutableStateOf(initOpts.silent) }
        var protectContent by remember { mutableStateOf(initOpts.protectContent) }
        var replyTo by remember { mutableStateOf(initOpts.replyTo) }
        var wholeWord by remember { mutableStateOf(initOpts.wholeWord) }
        var mediaType by remember { mutableStateOf(initOpts.mediaType.ifBlank { "photo" }) }
        var mediaRef by remember { mutableStateOf(initOpts.mediaRef) }
        var pollQuestion by remember { mutableStateOf(initOpts.pollQuestion.ifBlank { initial?.responseText.orEmpty() }) }
        var pollOptsText by remember { mutableStateOf(initOpts.pollOptions.joinToString("\n")) }
        var pollAnon by remember { mutableStateOf(initOpts.pollAnonymous) }
        var pollMulti by remember { mutableStateOf(initOpts.pollMultiple) }
        var showDeliveryOpts by remember {
            mutableStateOf(initOpts.parseMode.isNotBlank() || initOpts.disablePreview ||
                initOpts.silent || initOpts.protectContent || initOpts.replyTo || initOpts.wholeWord)
        }
        val regexValid = type != "regex" || runCatching { Regex(pattern) }.isSuccess
        LaunchedEffect(Unit) { packs = localStore.packs() }
        AlertDialog(
            onDismissRequest = { showAddRule = false; editingRule = null },
            title = { Text(if (initial == null) "Добавить ответ" else "Изменить ответ") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("1. Когда бот должен ответить?", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = type == "command",
                            onClick = { type = "command" }, label = { Text("Команда /…") })
                        FilterChip(selected = type == "contains",
                            onClick = { type = "contains" }, label = { Text("Содержит") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = type == "button",
                            onClick = { type = "button" }, label = { Text("Нажатие кнопки") })
                        FilterChip(selected = type == "regex",
                            onClick = { type = "regex" }, label = { Text("Regex") })
                    }
                    Text("Для кнопки удобнее выбрать надпись и действие в разделе «Кнопки бота».", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        pattern, { pattern = it },
                        label = { Text(when (type) {
                            "command" -> "Команда, например /start"
                            "button" -> "Текст кнопки (как на кнопке)"
                            "regex" -> "Регулярное выражение, напр. ^заказ\\s+#?\\d+"
                            else -> "Слово или фраза"
                        }) },
                        isError = !regexValid,
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    if (!regexValid) {
                        Text("Некорректный Regex", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (type == "contains") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = wholeWord, onCheckedChange = { wholeWord = it })
                            Text("Только целое слово (не подстрока внутри слова)",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("2. Что должен сделать бот?", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = action == "text",
                            onClick = { action = "text" }, label = { Text("Текст") })
                        FilterChip(selected = action == "pack",
                            onClick = { action = "pack" }, label = { Text("Набор") })
                        FilterChip(selected = action == "llm",
                            onClick = { action = "llm" }, label = { Text("ИИ") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = action == "script",
                            onClick = { action = "script" }, label = { Text("Скрипт JS") })
                        FilterChip(selected = action == "anim",
                            onClick = { action = "anim" }, label = { Text("🎞 Анимация") })
                        FilterChip(selected = action == "dice",
                            onClick = { action = "dice" }, label = { Text("🎲 Кубик") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = action == "media",
                            onClick = { action = "media" }, label = { Text("🖼 Медиа") })
                        FilterChip(selected = action == "poll",
                            onClick = { action = "poll" }, label = { Text("📊 Опрос") })
                    }
                    Spacer(Modifier.height(8.dp))
                    if (action == "text") {
                        OutlinedTextField(
                            response, { response = it },
                            label = { Text("Ответ бота") },
                            placeholder = { Text("Привет, {user}!") },
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                        )
                        Text("Подстановки: {user}, {username}, {user_id}, {chat_id}, {args}, {arg1}, {bot}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (action == "script") {
                        Text("Сохранишь правило — открой «✎ код» и напиши JS-логику (функция handle(e)).",
                            style = MaterialTheme.typography.bodySmall)
                    } else if (action == "pack") {
                        com.botcontrol.admin.ui.components.DropdownField(
                            value = packId.ifBlank { packs.firstOrNull()?.id.orEmpty() },
                            options = packs.map { it.id },
                            label = "Набор ответов",
                            display = { id ->
                                packs.firstOrNull { it.id == id }?.name ?: "(создай набор)"
                            },
                            onSelect = { packId = it },
                        )
                    } else if (action == "anim") {
                        Text("Бот пришлёт сообщение и будет править его кадр за кадром " +
                            "(0,5–3 с на кадр), в конце — итог и кнопки ниже.",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        com.botcontrol.admin.ui.components.AnimEditor(
                            spec = animSpec, packs = packs, onChange = { animSpec = it })
                    } else if (action == "dice") {
                        Text("Telegram покажет анимированный эмодзи со случайным результатом.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            com.botcontrol.admin.data.Anim.DICE.forEach { d ->
                                FilterChip(selected = dice == d, onClick = { dice = d }, label = { Text(d) })
                            }
                        }
                    } else if (action == "media") {
                        com.botcontrol.admin.ui.components.DropdownField(
                            value = mediaType,
                            options = listOf("photo", "sticker", "animation", "document", "video", "voice", "audio"),
                            label = "Тип медиа",
                            display = {
                                when (it) {
                                    "sticker" -> "Стикер"
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
                        OutlinedTextField(
                            mediaRef, { mediaRef = it },
                            label = { Text("file_id, https:// URL или путь к файлу") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            response, { response = it },
                            label = { Text("Подпись (необязательно)") },
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                        )
                    } else if (action == "poll") {
                        OutlinedTextField(
                            pollQuestion, { pollQuestion = it },
                            label = { Text("Вопрос опроса") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            pollOptsText, { pollOptsText = it },
                            label = { Text("Варианты (по одному на строке, от 2 до 10)") },
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = pollAnon, onCheckedChange = { pollAnon = it })
                            Text("Анонимный", style = MaterialTheme.typography.bodySmall)
                            Checkbox(checked = pollMulti, onCheckedChange = { pollMulti = it })
                            Text("Несколько ответов", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("Ответ сгенерирует локальный ИИ (вкладка «Модели ИИ»).",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { showDeliveryOpts = !showDeliveryOpts }) {
                        Text(if (showDeliveryOpts) "▲ Скрыть опции отправки" else "▼ Опции отправки (HTML, цитата, тихо…)")
                    }
                    if (showDeliveryOpts) {
                        Text("Разметка текста (parse_mode):", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("" to "Обычный", "HTML" to "HTML", "MarkdownV2" to "MarkdownV2").forEach { (pm, lbl) ->
                                FilterChip(selected = parseMode == pm, onClick = { parseMode = pm }, label = { Text(lbl) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = disablePreview, onCheckedChange = { disablePreview = it })
                            Text("Без превью ссылок", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = silent, onCheckedChange = { silent = it })
                            Text("Тихое уведомление (без звука)", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = protectContent, onCheckedChange = { protectContent = it })
                            Text("Запретить пересылку/копирование", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = replyTo, onCheckedChange = { replyTo = it })
                            Text("Отвечать с цитатой (reply_to)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (action != "llm") {
                        com.botcontrol.admin.ui.components.MenuEditor(
                            menu = ruleMenu, packs = packs,
                            onChange = { ruleMenu = it },
                        )
                    }
                }
            },
            confirmButton = {
                val pollOpts = pollOptsText.lines().map { it.trim() }.filter { it.isNotBlank() }.take(10)
                TextButton(
                    onClick = {
                        scope.launch {
                            val opts = com.botcontrol.admin.data.RuleOptions(
                                parseMode = parseMode,
                                disablePreview = disablePreview,
                                silent = silent,
                                protectContent = protectContent,
                                replyTo = replyTo,
                                wholeWord = wholeWord,
                                mediaType = if (action == "media") mediaType else "",
                                mediaRef = if (action == "media") mediaRef.trim() else "",
                                pollQuestion = if (action == "poll") pollQuestion.trim() else "",
                                pollOptions = if (action == "poll") pollOpts else emptyList(),
                                pollAnonymous = pollAnon,
                                pollMultiple = pollMulti,
                            )
                            val id = repository.saveBotRule(
                                BotRuleEntity(
                                    id = initial?.id ?: 0,
                                    botId = initial?.botId ?: localStore.activeBotId(),
                                    enabled = initial?.enabled ?: true,
                                    type = type, pattern = pattern.trim(),
                                    responseText = when (action) {
                                        "text", "media" -> response
                                        "poll" -> pollQuestion.trim()
                                        "dice" -> dice
                                        else -> ""
                                    },
                                    actionType = action,
                                    packId = if (action == "pack") packId.ifBlank { packs.firstOrNull()?.id.orEmpty() } else "",
                                    // JS-код скрипта не теряем при правке правила; анимация — спецификация
                                    script = if (action == "anim") com.botcontrol.admin.data.Anim.encode(animSpec)
                                        else if (initial?.actionType == "anim") "" else initial?.script.orEmpty(),
                                    menu = com.botcontrol.admin.data.BotJson.save(ruleMenu),
                                    options = com.botcontrol.admin.data.BotJson.save(opts),
                                )
                            )
                            showAddRule = false
                            editingRule = null
                            load()
                            if (action == "script" && initial == null) onOpenScript(id.toInt())
                        }
                    },
                    enabled = pattern.isNotBlank() && regexValid && when (action) {
                        "text" -> response.isNotBlank()
                        "media" -> mediaRef.isNotBlank()
                        "poll" -> pollQuestion.isNotBlank() && pollOpts.size >= 2
                        "anim" -> animSpec.preset != "custom" || animSpec.frames.isNotEmpty()
                        "pack" -> packId.isNotBlank() || packs.isNotEmpty()
                        else -> true
                    },
                ) { Text(if (initial == null) "Добавить" else "Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddRule = false; editingRule = null }) { Text("Отмена") }
            },
        )
    }


}

private suspend fun delay800() = kotlinx.coroutines.delay(800)
private suspend fun delay300() = kotlinx.coroutines.delay(300)
