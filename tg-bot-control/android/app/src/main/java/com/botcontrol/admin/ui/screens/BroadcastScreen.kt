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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.BotControlApp
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.ReplyPack
import com.botcontrol.admin.data.local.BotUserEntity
import com.botcontrol.admin.service.BroadcastEngine
import com.botcontrol.admin.service.BroadcastRequest
import com.botcontrol.admin.service.BroadcastSegment
import com.botcontrol.admin.ui.components.ConfirmHost
import com.botcontrol.admin.ui.components.ConfirmRequest
import com.botcontrol.admin.ui.components.DropdownField
import com.botcontrol.admin.ui.components.MenuEditor
import com.botcontrol.admin.ui.components.SectionTitle

/**
 * Экран массовой рассылки по сегментам базы подписчиков.
 * Показывает число получателей, прогресс отправки и автоматически помечает
 * пользователей с ошибкой 403 как заблокировавших бота.
 */
@Composable
fun BroadcastScreen(
    localStore: LocalBotStore,
    repository: BotRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BotControlApp
    val confirm = remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmHost(confirm)

    val statuses by BroadcastEngine.statuses.collectAsState()
    var botId by remember { mutableStateOf(0L) }
    var users by remember { mutableStateOf<List<BotUserEntity>>(emptyList()) }
    var packs by remember { mutableStateOf<List<ReplyPack>>(emptyList()) }

    var segment by remember { mutableStateOf(BroadcastSegment.ALL) }
    var tagFilter by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var parseMode by remember { mutableStateOf("") }
    var disablePreview by remember { mutableStateOf(false) }
    var silent by remember { mutableStateOf(false) }
    var useMedia by remember { mutableStateOf(false) }
    var mediaType by remember { mutableStateOf("photo") }
    var mediaRef by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<List<InlineBtn>>(emptyList()) }

    LaunchedEffect(Unit) {
        val id = localStore.activeBotId()
        botId = id
        users = repository.botUsers(id)
        packs = localStore.packs()
    }

    val targetsCount = BroadcastEngine.filterAudience(users, segment, tagFilter).size
    val status = statuses[botId]

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Рассылка", style = MaterialTheme.typography.titleLarge)
        }

        if (status != null) {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (status.running) "📣 Идёт рассылка…" else "📣 Итог рассылки",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (status.total > 0) {
                        val done = status.sent + status.failed + status.blocked403
                        LinearProgressIndicator(
                            progress = { (done.toFloat() / status.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                    Text(
                        "Отправлено: ${status.sent} из ${status.total} · " +
                            "блок 403: ${status.blocked403} · ошибок: ${status.failed}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (status.lastError.isNotBlank()) {
                        Text("⚠️ ${status.lastError}", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (status.running) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { BroadcastEngine.cancel(botId) }) {
                            Text("⏹ Остановить рассылку")
                        }
                    }
                }
            }
        }

        SectionTitle("1. Кому отправить")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                BroadcastSegment.ALL,
                BroadcastSegment.ROLE_VIP,
                BroadcastSegment.ROLE_ADMIN,
                BroadcastSegment.ROLE_USER,
                BroadcastSegment.TAG,
            ).forEach { seg ->
                FilterChip(
                    selected = segment == seg,
                    onClick = { segment = seg },
                    label = { Text(seg.title) },
                )
            }
        }
        if (segment == BroadcastSegment.TAG) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                tagFilter, { tagFilter = it },
                label = { Text("Тег для фильтра (например: клиент)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("Получателей по фильтру: $targetsCount (исключены 403 и бан)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)

        SectionTitle("2. Сообщение")
        OutlinedTextField(
            text, { text = it },
            label = { Text("Текст рассылки") },
            placeholder = { Text("Привет, {user}! У нас новости…") },
            modifier = Modifier.fillMaxWidth().height(130.dp),
        )
        Text("Подстановки: {user}, {username}, {user_id}, {chat_id}, {переменная_из_анкеты}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text("Разметка текста:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("" to "Обычный", "HTML" to "HTML", "MarkdownV2" to "MarkdownV2").forEach { (pm, lbl) ->
                FilterChip(selected = parseMode == pm, onClick = { parseMode = pm }, label = { Text(lbl) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = silent, onCheckedChange = { silent = it })
            Text("Тихое уведомление (без звука)", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = disablePreview, onCheckedChange = { disablePreview = it })
            Text("Без превью ссылок", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useMedia, onCheckedChange = { useMedia = it })
            Text("Прикрепить медиа (фото/видео/документ)", style = MaterialTheme.typography.bodySmall)
        }
        if (useMedia) {
            DropdownField(
                value = mediaType,
                options = listOf("photo", "video", "document", "animation", "audio"),
                label = "Тип вложения",
                display = {
                    when (it) {
                        "video" -> "Видео"
                        "document" -> "Файл"
                        "animation" -> "GIF"
                        "audio" -> "Аудио"
                        else -> "Фото"
                    }
                },
                onSelect = { mediaType = it },
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                mediaRef, { mediaRef = it },
                label = { Text("file_id или https:// URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionTitle("3. Inline-кнопки под рассылкой (необязательно)")
        MenuEditor(menu = menu, packs = packs, onChange = { menu = it })

        Spacer(Modifier.height(14.dp))
        val canSend = targetsCount > 0 && status?.running != true &&
            (text.isNotBlank() || (useMedia && mediaRef.isNotBlank()))
        Button(
            onClick = {
                confirm.value = ConfirmRequest(
                    "Запустить рассылку?",
                    "Сообщение будет отправлено $targetsCount получателям (${segment.title}).",
                ) {
                    BroadcastEngine.start(
                        app,
                        BroadcastRequest(
                            botId = botId,
                            text = text.trim(),
                            parseMode = parseMode,
                            segment = segment,
                            tagFilter = tagFilter.trim(),
                            menu = menu,
                            mediaType = if (useMedia) mediaType else "",
                            mediaRef = if (useMedia) mediaRef.trim() else "",
                            disablePreview = disablePreview,
                            silent = silent,
                        ),
                    )
                }
            },
            enabled = canSend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("🚀 Запустить рассылку ($targetsCount)")
        }
        Spacer(Modifier.height(24.dp))
    }
}
