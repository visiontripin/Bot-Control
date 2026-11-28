package com.botcontrol.admin.ui.screens

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
import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.local.BotUserEntity
import com.botcontrol.admin.ui.components.ConfirmHost
import com.botcontrol.admin.ui.components.ConfirmRequest
import com.botcontrol.admin.ui.components.SectionTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * База подписчиков бота (per-botId): роли (user/vip/admin), теги, бан,
 * отметки о блокировке 403 и переменные {var} из FSM.
 */
@Composable
fun UsersScreen(
    localStore: LocalBotStore,
    repository: BotRepository,
    onOpenBroadcast: () -> Unit,
    onBack: () -> Unit,
) {
    val confirm = remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmHost(confirm)
    val scope = rememberCoroutineScope()
    var botId by remember { mutableStateOf(0L) }
    var users by remember { mutableStateOf<List<BotUserEntity>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("all") } // all | vip | admin | banned | blocked
    var editing by remember { mutableStateOf<BotUserEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            val id = localStore.activeBotId()
            botId = id
            users = repository.botUsers(id)
        }
    }
    LaunchedEffect(Unit) { load() }

    val activeCount = users.count { !it.isBlocked && !it.isBanned }
    val vipCount = users.count { it.role.equals("vip", ignoreCase = true) }
    val adminCount = users.count { it.role.equals("admin", ignoreCase = true) }
    val blockedCount = users.count { it.isBlocked }
    val bannedCount = users.count { it.isBanned }

    val q = search.trim().lowercase()
    val filtered = users.filter { u ->
        val matchesMode = when (filterMode) {
            "vip" -> u.role.equals("vip", ignoreCase = true)
            "admin" -> u.role.equals("admin", ignoreCase = true)
            "banned" -> u.isBanned
            "blocked" -> u.isBlocked
            else -> true
        }
        val matchesQ = q.isBlank() ||
            u.firstName.lowercase().contains(q) ||
            u.username.lowercase().contains(q) ||
            u.chatId.toString().contains(q) ||
            u.tags.lowercase().contains(q)
        matchesMode && matchesQ
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Подписчики", style = MaterialTheme.typography.titleLarge)
        }

        if (info.isNotBlank()) {
            Text(info, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Всего в базе: ${users.size} • Активных: $activeCount",
                    style = MaterialTheme.typography.titleSmall)
                Text("VIP: $vipCount • Админов: $adminCount • Бан: $bannedCount • Блок (403): $blockedCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onOpenBroadcast, modifier = Modifier.weight(1f)) {
                        Text("📣 Рассылка")
                    }
                    OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) {
                        Text("+ Добавить ID")
                    }
                }
            }
        }

        SectionTitle("Фильтр и поиск")
        OutlinedTextField(
            search, { search = it },
            label = { Text("Поиск: имя, @username, ID или тег") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "all" to "Все",
                "vip" to "VIP",
                "admin" to "Админ",
                "banned" to "Бан",
                "blocked" to "403",
            ).forEach { (key, label) ->
                FilterChip(
                    selected = filterMode == key,
                    onClick = { filterMode = key },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Text("Подписчиков пока нет — они добавляются автоматически при любом сообщении боту.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        filtered.forEach { u ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clickable { editing = u },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val badge = buildList {
                            if (u.role != "user" && u.role.isNotBlank()) add(u.role.uppercase())
                            if (u.isBanned) add("БАН")
                            if (u.isBlocked) add("403")
                        }.joinToString(" · ")
                        val name = u.firstName.ifBlank { "Чат ${u.chatId}" }
                        val handle = if (u.username.isNotBlank()) " (@${u.username})" else ""
                        Text("$name$handle" + if (badge.isNotBlank()) " [$badge]" else "",
                            style = MaterialTheme.typography.titleSmall)
                        val seen = if (u.lastSeenAt > 0L)
                            SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(u.lastSeenAt * 1000L))
                        else "—"
                        val varsCount = BotJson.stringMap(u.varsJson).size
                        Text(
                            "ID: ${u.chatId} · сообщ.: ${u.msgCount} · был: $seen" +
                                (if (u.tags.isNotBlank()) " · теги: ${u.tags}" else "") +
                                (if (varsCount > 0) " · перем.: $varsCount" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("✎", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    editing?.let { u ->
        UserEditDialog(
            user = u,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch {
                    repository.saveBotUser(updated)
                    editing = null
                    info = "✅ Профиль ${updated.chatId} сохранён"
                    load()
                }
            },
            onDelete = {
                confirm.value = ConfirmRequest(
                    "Удалить подписчика?",
                    "Чат ${u.chatId} (${u.firstName}) будет удалён из базы этого бота.",
                ) {
                    scope.launch {
                        repository.deleteBotUser(botId, u.chatId)
                        editing = null
                        load()
                    }
                }
            },
        )
    }

    if (showAdd) {
        AddUserDialog(
            botId = botId,
            onDismiss = { showAdd = false },
            onAdd = { added ->
                scope.launch {
                    repository.saveBotUser(added)
                    showAdd = false
                    info = "✅ Добавлен чат ${added.chatId}"
                    load()
                }
            },
        )
    }
}

@Composable
private fun UserEditDialog(
    user: BotUserEntity,
    onDismiss: () -> Unit,
    onSave: (BotUserEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var role by remember { mutableStateOf(user.role.ifBlank { "user" }) }
    var tags by remember { mutableStateOf(user.tags) }
    var isBanned by remember { mutableStateOf(user.isBanned) }
    var isBlocked by remember { mutableStateOf(user.isBlocked) }
    var varsJson by remember { mutableStateOf(user.varsJson) }
    val varsMap = BotJson.stringMap(varsJson)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(user.firstName.ifBlank { "Чат ${user.chatId}" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("chatId: ${user.chatId}" +
                    if (user.username.isNotBlank()) " · @${user.username}" else "",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("Роль пользователя:", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("user" to "Обычный", "vip" to "VIP", "admin" to "Админ").forEach { (r, lbl) ->
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(lbl) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    tags, { tags = it },
                    label = { Text("Теги через запятую") },
                    placeholder = { Text("клиент, москва, тест") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Бан (игнорировать сообщения)", style = MaterialTheme.typography.bodyMedium)
                        Text("Забаненный пользователь не получает ответы и не может слать заявки",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(isBanned, onCheckedChange = { isBanned = it })
                }
                if (isBlocked) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Заблокировал бота (403)", style = MaterialTheme.typography.bodyMedium)
                            Text("Снять флаг, если пользователь вернулся",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(isBlocked, onCheckedChange = { isBlocked = it })
                    }
                }
                if (varsMap.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Переменные анкет ({переменная}):", style = MaterialTheme.typography.bodySmall)
                    varsMap.forEach { (k, v) ->
                        Text("• $k = ${v.take(60)}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { varsJson = "{}" }) { Text("Очистить переменные") }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("🗑 Удалить из базы")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(user.copy(
                    role = role,
                    tags = tags.split(',').map { it.trim() }.filter { it.isNotBlank() }.joinToString(", "),
                    isBanned = isBanned,
                    isBlocked = isBlocked,
                    varsJson = varsJson,
                ))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun AddUserDialog(
    botId: Long,
    onDismiss: () -> Unit,
    onAdd: (BotUserEntity) -> Unit,
) {
    var chatIdText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("vip") }
    var tags by remember { mutableStateOf("") }
    val chatId = chatIdText.trim().toLongOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить подписчика вручную") },
        text = {
            Column {
                OutlinedTextField(
                    chatIdText, { chatIdText = it.filter { c -> c.isDigit() || c == '-' }.take(20) },
                    label = { Text("Telegram ID / chat_id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    nameText, { nameText = it },
                    label = { Text("Имя (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("user" to "user", "vip" to "vip", "admin" to "admin").forEach { (r, lbl) ->
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(lbl) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    tags, { tags = it },
                    label = { Text("Теги через запятую") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cid = chatId ?: return@TextButton
                    val now = System.currentTimeMillis() / 1000L
                    onAdd(
                        BotUserEntity(
                            botId = botId,
                            chatId = cid,
                            userId = if (cid > 0) cid else 0L,
                            firstName = nameText.trim(),
                            role = role,
                            tags = tags.trim(),
                            firstSeenAt = now,
                            lastSeenAt = now,
                        )
                    )
                },
                enabled = chatId != null && chatId != 0L,
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
