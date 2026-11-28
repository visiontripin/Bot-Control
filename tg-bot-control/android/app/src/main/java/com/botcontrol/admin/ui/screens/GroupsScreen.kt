package com.botcontrol.admin.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.GroupModerationConfig
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.local.BotUserEntity
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch

/**
 * Экран Stage 3 (v2.0.0): группы, каналы, форумы, модерация (капча/антиспам/заявки),
 * реакции, опросы и опциональный Webhook-адаптер (строго per-botId).
 */
@Composable
fun GroupsScreen(
    localStore: LocalBotStore,
    repository: BotRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var botId by remember { mutableStateOf(0L) }
    var cfg by remember { mutableStateOf(GroupModerationConfig()) }
    var knownChats by remember { mutableStateOf<List<BotUserEntity>>(emptyList()) }
    var info by remember { mutableStateOf("") }

    // Быстрые инструменты администрирования
    var targetChat by remember { mutableStateOf("") }
    var topicName by remember { mutableStateOf("") }
    var targetUserId by remember { mutableStateOf("") }
    var chatDetails by remember { mutableStateOf("") }

    // Опциональный Webhook
    var webhookUrl by remember { mutableStateOf("") }
    var webhookSecret by remember { mutableStateOf("") }
    var webhookStatus by remember { mutableStateOf("") }

    // Удобные текстовые поля для списков и чисел
    var stopWordsText by remember { mutableStateOf("") }
    var allowedDomainsText by remember { mutableStateOf("") }
    var welcomeDelText by remember { mutableStateOf("0") }
    var captchaTimeoutText by remember { mutableStateOf("5") }
    var floodCountText by remember { mutableStateOf("6") }
    var floodSecText by remember { mutableStateOf("10") }
    var muteMinText by remember { mutableStateOf("60") }
    var defaultThreadText by remember { mutableStateOf("") }

    fun syncFromCfg(loaded: GroupModerationConfig) {
        cfg = loaded
        stopWordsText = loaded.stopWords.joinToString(", ")
        allowedDomainsText = loaded.allowedLinkDomains.joinToString(", ")
        welcomeDelText = loaded.welcomeDeleteAfterSec.toString()
        captchaTimeoutText = loaded.captchaTimeoutMin.toString()
        floodCountText = loaded.floodLimitCount.toString()
        floodSecText = loaded.floodWindowSec.toString()
        muteMinText = loaded.muteDurationMin.toString()
        defaultThreadText = loaded.defaultThreadId?.toString().orEmpty()
    }

    LaunchedEffect(Unit) {
        val id = localStore.activeBotId()
        botId = id
        syncFromCfg(localStore.groupModeration(id))
        webhookSecret = localStore.webhookSecret(id)
        val allUsers = repository.botUsers(id)
        knownChats = allUsers.filter {
            it.chatId < 0L || it.chatType in listOf("group", "supergroup", "channel")
        }
        val firstChat = knownChats.firstOrNull()?.chatId?.toString()
            ?: localStore.channelId(id)
        targetChat = firstChat
    }

    fun buildConfig(): GroupModerationConfig = cfg.copy(
        stopWords = stopWordsText.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        allowedLinkDomains = allowedDomainsText.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        welcomeDeleteAfterSec = welcomeDelText.toIntOrNull()?.coerceIn(0, 600) ?: 0,
        captchaTimeoutMin = captchaTimeoutText.toIntOrNull()?.coerceIn(1, 60) ?: 5,
        floodLimitCount = floodCountText.toIntOrNull()?.coerceIn(0, 100) ?: 0,
        floodWindowSec = floodSecText.toIntOrNull()?.coerceIn(2, 120) ?: 10,
        muteDurationMin = muteMinText.toIntOrNull()?.coerceIn(1, 525_600) ?: 60,
        defaultThreadId = defaultThreadText.trim().toIntOrNull()?.takeIf { it > 0 },
    )

    suspend fun apiForBot(): TelegramApi? {
        val id = if (botId > 0L) botId else localStore.activeBotId()
        val token = localStore.botToken(id)
        if (token.isNullOrBlank()) {
            info = "⚠️ Токен бота не задан"
            return null
        }
        return TelegramApi(token)
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Группы, каналы и форумы", style = MaterialTheme.typography.titleLarge)
        }

        if (info.isNotBlank()) {
            Text(info, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
        }

        SectionTitle("Приветствие и кнопка-капча")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Приветствовать новых участников", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cfg.welcomeEnabled, onCheckedChange = { cfg = cfg.copy(welcomeEnabled = it) })
                }
                if (cfg.welcomeEnabled) {
                    OutlinedTextField(
                        cfg.welcomeText, { cfg = cfg.copy(welcomeText = it) },
                        label = { Text("Текст приветствия ({user}, {chat})") },
                        modifier = Modifier.fillMaxWidth().height(84.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        welcomeDelText, { s -> welcomeDelText = s.filter { it.isDigit() }.take(3) },
                        label = { Text("Удалять приветствие через N сек (0 = оставить)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡 Капча кнопкой при входе в группу", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cfg.captchaEnabled, onCheckedChange = { cfg = cfg.copy(captchaEnabled = it) })
                }
                if (cfg.captchaEnabled) {
                    OutlinedTextField(
                        cfg.captchaPrompt, { cfg = cfg.copy(captchaPrompt = it) },
                        label = { Text("Текст капчи ({user}, {timeout})") },
                        modifier = Modifier.fillMaxWidth().height(78.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            cfg.captchaButtonText, { cfg = cfg.copy(captchaButtonText = it) },
                            label = { Text("Кнопка") },
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            captchaTimeoutText, { s -> captchaTimeoutText = s.filter { it.isDigit() }.take(2) },
                            label = { Text("Таймаут (мин)") },
                            singleLine = true, modifier = Modifier.weight(0.6f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Если участник не нажал кнопку вовремя:", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = cfg.captchaActionOnTimeout == "mute",
                            onClick = { cfg = cfg.copy(captchaActionOnTimeout = "mute") },
                            label = { Text("Оставить в муте") },
                        )
                        FilterChip(
                            selected = cfg.captchaActionOnTimeout == "kick",
                            onClick = { cfg = cfg.copy(captchaActionOnTimeout = "kick") },
                            label = { Text("Кикнуть из чата") },
                        )
                    }
                }
            }
        }

        SectionTitle("Антиспам, стоп-слова и ссылки")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡 Включить автомодерацию групп", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cfg.antispamEnabled, onCheckedChange = { cfg = cfg.copy(antispamEnabled = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = cfg.blockLinks, onCheckedChange = { cfg = cfg.copy(blockLinks = it) })
                    Text("Удалять ссылки (http/t.me/@username)", style = MaterialTheme.typography.bodySmall)
                }
                if (cfg.blockLinks) {
                    OutlinedTextField(
                        allowedDomainsText, { allowedDomainsText = it },
                        label = { Text("Белый список доменов (через запятую)") },
                        placeholder = { Text("example.com, github.com") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    stopWordsText, { stopWordsText = it },
                    label = { Text("Стоп-слова (через запятую)") },
                    placeholder = { Text("казино, криптосигнал, спам") },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        floodCountText, { s -> floodCountText = s.filter { it.isDigit() }.take(3) },
                        label = { Text("Флуд: сообщ. (0=выкл)") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        floodSecText, { s -> floodSecText = s.filter { it.isDigit() }.take(3) },
                        label = { Text("За окно (сек)") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("Наказание нарушителю (админы не проверяются):", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("delete" to "Только удалить", "mute" to "Удалить + мут", "ban" to "Удалить + бан").forEach { (a, lbl) ->
                        FilterChip(
                            selected = cfg.antispamAction == a,
                            onClick = { cfg = cfg.copy(antispamAction = a) },
                            label = { Text(lbl) },
                        )
                    }
                }
                if (cfg.antispamAction == "mute") {
                    OutlinedTextField(
                        muteMinText, { s -> muteMinText = s.filter { it.isDigit() }.take(6) },
                        label = { Text("Длительность мута (минут)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    cfg.antispamWarnText, { cfg = cfg.copy(antispamWarnText = it) },
                    label = { Text("Предупреждение в чат ({user}, {reason}; пусто = тихо)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    cfg.logChatId, { cfg = cfg.copy(logChatId = it) },
                    label = { Text("ID/канал для лога модерации (необязательно)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionTitle("Заявки, темы форума, реакции и опросы")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Авто-одобрение заявок (chat_join_request)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cfg.autoApproveJoinRequests, onCheckedChange = { cfg = cfg.copy(autoApproveJoinRequests = it) })
                }
                if (cfg.autoApproveJoinRequests) {
                    OutlinedTextField(
                        cfg.joinRequestWelcome, { cfg = cfg.copy(joinRequestWelcome = it) },
                        label = { Text("ЛС после приёма заявки (необязательно)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    defaultThreadText, { s -> defaultThreadText = s.filter { it.isDigit() }.take(9) },
                    label = { Text("Тема форума по умолчанию (message_thread_id)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        cfg.reactionTriggerEmoji, { cfg = cfg.copy(reactionTriggerEmoji = it) },
                        label = { Text("Реакция-триггер (🔥 или *)") },
                        singleLine = true, modifier = Modifier.weight(0.55f),
                    )
                    OutlinedTextField(
                        cfg.reactionTriggerReply, { cfg = cfg.copy(reactionTriggerReply = it) },
                        label = { Text("Ответ на реакцию ({user}, {emoji})") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    cfg.pollAnswerReply, { cfg = cfg.copy(pollAnswerReply = it) },
                    label = { Text("Реакция на голос в опросе ({user}, {option_ids})") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val id = if (botId > 0L) botId else localStore.activeBotId()
                            val built = buildConfig()
                            localStore.setGroupModeration(built, id)
                            syncFromCfg(built)
                            info = "✅ Настройки групп и модерации сохранены"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сохранить настройки модерации") }
            }
        }

        SectionTitle("Управление группой / форумом / участником")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (knownChats.isNotEmpty()) {
                    Text("Известные группы/каналы бота:", style = MaterialTheme.typography.bodySmall)
                    knownChats.take(6).forEach { ch ->
                        FilterChip(
                            selected = targetChat == ch.chatId.toString(),
                            onClick = { targetChat = ch.chatId.toString() },
                            label = { Text("${ch.firstName.take(24)} (${ch.chatId})") },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    targetChat, { targetChat = it },
                    label = { Text("chat_id группы/супергруппы или @channel") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val api = apiForBot() ?: return@launch
                                val c = targetChat.trim()
                                val chatRes = api.getChat(c)
                                val cntRes = api.getChatMemberCount(c)
                                val admRes = api.getChatAdministrators(c)
                                chatDetails = buildString {
                                    chatRes.onSuccess {
                                        appendLine("${it.title.ifBlank { c }} • тип: ${it.type}${if (it.isForum) " (форум)" else ""}")
                                    }.onFailure { appendLine("getChat: ${it.message}") }
                                    cntRes.onSuccess { appendLine("Участников: $it") }
                                    admRes.onSuccess { list ->
                                        append("Админы (${list.size}): ")
                                        append(list.take(10).joinToString(", ") { a ->
                                            "${a.firstName}${if (a.username.isNotBlank()) " @${a.username}" else ""} [${a.userId}]"
                                        })
                                    }
                                }
                            }
                        },
                        enabled = targetChat.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Инфо и админы") }
                }
                if (chatDetails.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(chatDetails, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        topicName, { topicName = it },
                        label = { Text("Название новой темы форума") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val api = apiForBot() ?: return@launch
                                api.createForumTopic(targetChat.trim(), topicName.trim())
                                    .onSuccess { tid ->
                                        info = "✅ Создана тема форума: message_thread_id=$tid"
                                        topicName = ""
                                    }
                                    .onFailure { info = "⚠️ Ошибка createForumTopic: ${it.message}" }
                            }
                        },
                        enabled = targetChat.isNotBlank() && topicName.isNotBlank(),
                    ) { Text("+ Тема") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    targetUserId, { s -> targetUserId = s.filter { it.isDigit() } },
                    label = { Text("user_id участника для ручной модерации") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val uid = targetUserId.toLongOrNull() ?: return@OutlinedButton
                            scope.launch {
                                val api = apiForBot() ?: return@launch
                                val until = System.currentTimeMillis() / 1000L + 3600L
                                api.restrictChatMember(targetChat.trim(), uid, canSendMessages = false, untilDateSec = until)
                                    .onSuccess { info = "🔇 Участник $uid замьючен на 1 час" }
                                    .onFailure { info = "⚠️ ${it.message}" }
                            }
                        },
                        enabled = targetChat.isNotBlank() && targetUserId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Мут 1ч") }
                    OutlinedButton(
                        onClick = {
                            val uid = targetUserId.toLongOrNull() ?: return@OutlinedButton
                            scope.launch {
                                val api = apiForBot() ?: return@launch
                                api.restrictChatMember(targetChat.trim(), uid, canSendMessages = true)
                                    .onSuccess { info = "🔊 Ограничения с $uid сняты" }
                                    .onFailure { info = "⚠️ ${it.message}" }
                            }
                        },
                        enabled = targetChat.isNotBlank() && targetUserId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Снять мут") }
                    OutlinedButton(
                        onClick = {
                            val uid = targetUserId.toLongOrNull() ?: return@OutlinedButton
                            scope.launch {
                                val api = apiForBot() ?: return@launch
                                api.banChatMember(targetChat.trim(), uid)
                                    .onSuccess { info = "⛔ Участник $uid забанен" }
                                    .onFailure { info = "⚠️ ${it.message}" }
                            }
                        },
                        enabled = targetChat.isNotBlank() && targetUserId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Бан") }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val uid = targetUserId.toLongOrNull() ?: return@OutlinedButton
                            scope.launch {
                                val api = apiForBot() ?: return@launch
                                api.unbanChatMember(targetChat.trim(), uid)
                                    .onSuccess { info = "✅ Разбан $uid выполнен" }
                                    .onFailure { info = "⚠️ ${it.message}" }
                            }
                        },
                        enabled = targetChat.isNotBlank() && targetUserId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Разбан") }
                    OutlinedButton(
                        onClick = {
                            val uid = targetUserId.toLongOrNull() ?: return@OutlinedButton
                            scope.launch {
                                val api = apiForBot() ?: return@launch
                                api.approveChatJoinRequest(targetChat.trim(), uid)
                                    .onSuccess { info = "✅ Заявка $uid одобрена" }
                                    .onFailure { info = "⚠️ ${it.message}" }
                            }
                        },
                        enabled = targetChat.isNotBlank() && targetUserId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Принять заявку") }
                    OutlinedButton(
                        onClick = {
                            val uid = targetUserId.toLongOrNull() ?: return@OutlinedButton
                            scope.launch {
                                val api = apiForBot() ?: return@launch
                                api.declineChatJoinRequest(targetChat.trim(), uid)
                                    .onSuccess { info = "❌ Заявка $uid отклонена" }
                                    .onFailure { info = "⚠️ ${it.message}" }
                            }
                        },
                        enabled = targetChat.isNotBlank() && targetUserId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Отклонить") }
                }
            }
        }

        SectionTitle("Webhook-адаптер (опционально)")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "По умолчанию бот работает через long polling (getUpdates). " +
                        "Если есть внешний HTTPS-туннель на этот телефон (порт 8090), локальный сервер " +
                        "принимает обновления по адресу POST /telegram-webhook/$botId.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    webhookUrl, { webhookUrl = it },
                    label = { Text("HTTPS URL для setWebhook") },
                    placeholder = { Text("https://example.com/telegram-webhook/$botId") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    webhookSecret, { webhookSecret = it },
                    label = { Text("Secret Token (X-Telegram-Bot-Api-Secret-Token)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            scope.launch {
                                val id = if (botId > 0L) botId else localStore.activeBotId()
                                localStore.setWebhookSecret(webhookSecret.trim(), id)
                                val api = apiForBot() ?: return@launch
                                api.setWebhook(webhookUrl.trim(), webhookSecret.trim())
                                    .onSuccess { webhookStatus = "✅ Webhook установлен" }
                                    .onFailure { webhookStatus = "⚠️ ${it.message}" }
                            }
                        },
                        enabled = webhookUrl.startsWith("https://"),
                        modifier = Modifier.weight(1f),
                    ) { Text("Поставить") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val id = if (botId > 0L) botId else localStore.activeBotId()
                                localStore.setWebhookSecret(webhookSecret.trim(), id)
                                val api = apiForBot() ?: return@launch
                                api.deleteWebhook()
                                    .onSuccess { webhookStatus = "✅ Webhook снят (polling активен)" }
                                    .onFailure { webhookStatus = "⚠️ ${it.message}" }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Снять") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val api = apiForBot() ?: return@launch
                                api.getWebhookInfo()
                                    .onSuccess { webhookStatus = it }
                                    .onFailure { webhookStatus = "⚠️ ${it.message}" }
                            }
                        },
                        modifier = Modifier.weight(0.9f),
                    ) { Text("Статус") }
                }
                if (webhookStatus.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(webhookStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
