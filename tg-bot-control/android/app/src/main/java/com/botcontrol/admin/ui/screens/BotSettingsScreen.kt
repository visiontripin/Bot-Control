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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.MenuCommand
import com.botcontrol.admin.data.local.BotRuleEntity
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.data.telegram.TelegramRateLimit
import com.botcontrol.admin.llm.DeviceLlm
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch

/**
 * Настройки бота: приветствие после /start, главное меню Telegram
 * (кнопка «Меню» с командами), подсказка по командам-правилам.
 */
@Composable
fun BotSettingsScreen(localStore: LocalBotStore, repository: com.botcontrol.admin.data.BotRepository,
                      onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var greeting by remember { mutableStateOf("") }
    var greetingLoaded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<List<MenuCommand>>(emptyList()) }
    var commandRulesCount by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("") }
    var listingsOn by remember { mutableStateOf(false) }
    var listingMaxPerUser by remember { mutableStateOf(0) }
    var listingUseAlbum by remember { mutableStateOf(true) }
    var adminIdsRaw by remember { mutableStateOf("") }
    var reqChannel by remember { mutableStateOf("") }
    var reqChannelLink by remember { mutableStateOf("") }
    var reqChannelPrompt by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val id = localStore.activeBotId()
        channel = localStore.channelId(id)
        listingsOn = localStore.listingsOn(id)
        listingMaxPerUser = localStore.listingMaxPerUser(id)
        listingUseAlbum = localStore.listingUseAlbum(id)
        adminIdsRaw = localStore.adminIdsRaw(id)
        reqChannel = localStore.requiredChannel(id)
        reqChannelLink = localStore.requiredChannelLink(id)
        reqChannelPrompt = localStore.requiredChannelPrompt(id)
        menu = localStore.menuCommands(id)
        val rules = repository.botRules(id)
        commandRulesCount = rules.count { it.type == "command" }
        rules.firstOrNull {
            it.type == "command" && it.pattern.removePrefix("/").equals("start", true)
        }?.let { greeting = it.responseText }
        greetingLoaded = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Настройки бота", style = MaterialTheme.typography.titleLarge)
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)

        // ---------- канал и режим объявлений ----------
        SectionTitle("Канал публикаций и объявления")
        Text("Куда бот публикует посты режима «Объявления» и события с флажком «в канал» (например, @daromvpl).",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            channel, { channel = it },
            label = { Text("Канал, например @daromvpl") },
            placeholder = { Text("@daromvpl") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                scope.launch {
                    localStore.setChannelId(channel, localStore.activeBotId())
                    message = "✅ Канал сохранён: ${channel.trim()}"
                }
            }) { Text("💾 Сохранить канал") }
            Button(onClick = {
                scope.launch {
                    val nv = !listingsOn
                    localStore.setListingsOn(nv, localStore.activeBotId())
                    listingsOn = nv
                    message = if (nv)
                        "✅ Режим объявлений включён: «📝 Разместить объявление», «📋 Мои объявления», «❌ Отмена»"
                    else "⏹ Режим объявлений выключен"
                }
            }) { Text(if (listingsOn) "✅ Объявления: вкл" else "⏹ Объявления: выкл") }
        }
        Text("В режиме объявлений бот сам ведёт визард: описание → контакт → фото (до 3) → предпросмотр → публикация в канал с кнопкой «Написать автору». Совет: добавь в «Клавиатуру» кнопки «📝 Разместить объявление» и «📋 Мои объявления».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (listingsOn) {
            Spacer(Modifier.height(6.dp))
            Text("Лимит активных объявлений на человека:", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Без лимита", 1 to "1", 2 to "2", 3 to "3", 5 to "5").forEach { (lim, lbl) ->
                    FilterChip(
                        selected = listingMaxPerUser == lim,
                        onClick = {
                            listingMaxPerUser = lim
                            scope.launch { localStore.setListingMaxPerUser(lim, localStore.activeBotId()) }
                        },
                        label = { Text(lbl) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = listingUseAlbum,
                    onCheckedChange = { v ->
                        listingUseAlbum = v
                        scope.launch { localStore.setListingUseAlbum(v, localStore.activeBotId()) }
                    },
                )
                Text("Публиковать 2+ фото одним альбомом (sendMediaGroup)",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---------- администраторы и обязательная подписка ----------
        SectionTitle("Права администраторов и подписка на канал")
        Text("Telegram ID администраторов (через запятую или пробел). Админы могут вызывать админ-команды, удалять любые объявления и не проходят проверку подписки.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            adminIdsRaw, { adminIdsRaw = it },
            label = { Text("ID администраторов (напр. 123456789, 987654321)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            reqChannel, { reqChannel = it },
            label = { Text("Обязательный канал для подписки (пусто = выкл)") },
            placeholder = { Text("@mychannel или -1001234567890") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (reqChannel.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                reqChannelLink, { reqChannelLink = it },
                label = { Text("Ссылка приглашения (если канал закрытый, https://t.me/+…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                reqChannelPrompt, { reqChannelPrompt = it },
                label = { Text("Текст требования подписки (пусто = стандартный)") },
                modifier = Modifier.fillMaxWidth().height(84.dp),
            )
            Text("Бот должен быть администратором канала (право видеть участников), иначе getChatMember не сможет проверить подписку.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            scope.launch {
                val id = localStore.activeBotId()
                localStore.setAdminIdsRaw(adminIdsRaw, id)
                localStore.setRequiredChannel(reqChannel, id)
                localStore.setRequiredChannelLink(reqChannelLink, id)
                localStore.setRequiredChannelPrompt(reqChannelPrompt, id)
                message = "✅ Права и обязательная подписка сохранены"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("💾 Сохранить права и подписку") }
        Spacer(Modifier.height(10.dp))

        // ---------- приветствие /start ----------
        SectionTitle("Приветствие после /start")
        Text("Что бот ответит на первую команду /start. {user} заменится на имя пользователя.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            greeting, { greeting = it },
            label = { Text("Текст приветствия") },
            placeholder = { Text("Симуляция активирована, {user}. 👋") },
            modifier = Modifier.fillMaxWidth().height(110.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                scope.launch {
                    val botId = localStore.activeBotId()
                    val rules = repository.botRules(botId)
                    val existing = rules.firstOrNull {
                        it.type == "command" && it.pattern.removePrefix("/").equals("start", true)
                    }
                    if (existing != null) {
                        repository.saveBotRule(existing.copy(responseText = greeting))
                    } else {
                        repository.saveBotRule(
                            BotRuleEntity(
                                botId = localStore.activeBotId(),
                                type = "command", pattern = "/start",
                                responseText = greeting,
                                menu = "",
                            ))
                    }
                    message = "Приветствие сохранено (правило /start)"
                }
            }, enabled = greeting.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Сохранить") }
            OutlinedButton(onClick = { greeting = "Симуляция активирована, {user}. 👋" }) {
                Text("Пример")
            }
        }

        // ---------- главное меню ----------
        SectionTitle("Главное меню Telegram (кнопка «Меню»)")
        Text("Список команд появится в Telegram слева от поля ввода. Команда — только латиница, без «/».",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        menu.forEachIndexed { index, cmd ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Команда ${index + 1}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { menu = menu - cmd }) { Text("✕") }
                    }
                    OutlinedTextField(
                        cmd.command, { menu = menu.mapIndexed { i, m ->
                            if (i == index) m.copy(command = it) else m } },
                        label = { Text("Команда: латиница, цифры, _ (например start)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        cmd.description, { menu = menu.mapIndexed { i, m ->
                            if (i == index) m.copy(description = it) else m } },
                        label = { Text("Описание (видно в меню)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "default" to "Все чаты",
                            "all_private_chats" to "Личка",
                            "all_group_chats" to "Группы",
                            "all_chat_administrators" to "Админы",
                        ).forEach { (sc, lbl) ->
                            FilterChip(
                                selected = (cmd.scope.ifBlank { "default" }) == sc,
                                onClick = {
                                    menu = menu.mapIndexed { i, m ->
                                        if (i == index) m.copy(scope = sc) else m
                                    }
                                },
                                label = { Text(lbl) },
                            )
                        }
                    }
                    OutlinedTextField(
                        cmd.languageCode, { v -> menu = menu.mapIndexed { i, m ->
                            if (i == index) m.copy(languageCode = v.lowercase().take(5)) else m } },
                        label = { Text("Язык ISO (пусто = любой, напр. ru / en)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        OutlinedButton(onClick = {
            menu = menu + MenuCommand("", "")
        }, modifier = Modifier.fillMaxWidth()) { Text("+ Команда меню") }
        Text("Подсказка: обычно это start — «Запустить бота» и help — «Что умеет бот». " +
            "Встроенных примеров нет — список составляй под своего бота.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            scope.launch {
                val botId = localStore.activeBotId()
                val cleanMenu = menu.filter { it.command.isNotBlank() }
                localStore.setMenuCommands(cleanMenu, botId)
                val token = localStore.botToken(botId)
                if (token.isNullOrBlank()) {
                    error = "Токен не задан — сначала добавь бота"
                    return@launch
                }
                TelegramApi(token)
                    .setMyCommandsScoped(cleanMenu)
                    .onSuccess {
                        error = ""
                        localStore.setMenuSignature(
                            botId,
                            LocalBotStore.computeMenuSignature(TelegramRateLimit.tokenKey(token), cleanMenu),
                        )
                        message = "Меню применено в Telegram — проверь кнопку «Меню» у бота"
                        scope.launch {
                            com.botcontrol.admin.service.BotLog.log(localStore, botId,
                                "📜 Главное меню применено вручную (${cleanMenu.size} команд)")
                        }
                    }
                    .onFailure { error = it.message ?: "Не удалось применить меню" }
            }
        }, enabled = menu.any { it.command.isNotBlank() }, modifier = Modifier.fillMaxWidth()) {
            Text("📣 Применить в Telegram")
        }
        Text("Также применяется автоматически при каждом запуске бота.",
            style = MaterialTheme.typography.bodySmall)

        // ---------- команды-правила ----------
        SectionTitle("Команды бота (что они выполняют)")
        Text("Команд — $commandRulesCount. Что делает каждая команда (/start, /help, /joke…), задаётся правилами на главном экране бота: «+ Добавить команду» → тип «Команда /…», действие — текст, набор, скрипт или ИИ.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.height(18.dp))

        Spacer(Modifier.height(24.dp))
    }
}
