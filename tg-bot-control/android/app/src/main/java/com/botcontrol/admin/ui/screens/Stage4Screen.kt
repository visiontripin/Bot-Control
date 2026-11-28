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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.DigitalProduct
import com.botcontrol.admin.data.HttpAllowlistPolicy
import com.botcontrol.admin.data.InlineArticleItem
import com.botcontrol.admin.data.InlineModeConfig
import com.botcontrol.admin.data.IntegrationsConfig
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.PaymentPolicy
import com.botcontrol.admin.data.StarOrder
import com.botcontrol.admin.data.WebAppConfig
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.service.FileServer
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Экран Stage 4 (v2.1.0):
 *  - Inline-режим (@bot запрос);
 *  - Telegram Stars (XTR): каталог товаров, ссылки оплаты и возврат Stars;
 *  - Telegram Mini Apps (web_app): локальная витрина и внешний URL;
 *  - Внешний HTTP API для JS-скриптов (allowlist) и обработка входящих медиа.
 * Все настройки изолированы строго per-botId.
 */
@Composable
fun Stage4Screen(
    localStore: LocalBotStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var botId by remember { mutableStateOf(0L) }
    var info by remember { mutableStateOf("") }

    // 1) Inline Mode
    var inlineCfg by remember { mutableStateOf(InlineModeConfig()) }
    var newArtTitle by remember { mutableStateOf("") }
    var newArtDesc by remember { mutableStateOf("") }
    var newArtReply by remember { mutableStateOf("") }
    var newArtKeywords by remember { mutableStateOf("") }

    // 2) Telegram Stars (XTR)
    var products by remember { mutableStateOf<List<DigitalProduct>>(emptyList()) }
    var orders by remember { mutableStateOf<List<StarOrder>>(emptyList()) }
    var newProdTitle by remember { mutableStateOf("") }
    var newProdDesc by remember { mutableStateOf("") }
    var newProdStars by remember { mutableStateOf("25") }
    var newProdDeliver by remember { mutableStateOf("✅ Спасибо за оплату {title}! Ваш доступ активирован.") }
    var newProdRole by remember { mutableStateOf("vip") }
    var newProdTag by remember { mutableStateOf("buyer") }
    var lastInvoiceLink by remember { mutableStateOf("") }

    // 3) Mini App
    var webAppCfg by remember { mutableStateOf(WebAppConfig()) }

    // 4) Integrations & incoming media
    var integCfg by remember { mutableStateOf(IntegrationsConfig()) }
    var domainsText by remember { mutableStateOf("") }
    var timeoutText by remember { mutableStateOf("8") }

    LaunchedEffect(Unit) {
        val id = localStore.activeBotId()
        botId = id
        inlineCfg = localStore.inlineConfig(id)
        products = localStore.digitalProducts(id)
        orders = localStore.starOrders(id)
        webAppCfg = localStore.webAppConfig(id)
        val integ = localStore.integrationsConfig(id)
        integCfg = integ
        domainsText = integ.httpAllowedDomains.joinToString(", ")
        timeoutText = integ.httpTimeoutSec.toString()
    }

    suspend fun apiForBot(): TelegramApi? {
        val id = if (botId > 0L) botId else localStore.activeBotId()
        val token = localStore.botToken(id)
        if (token.isNullOrBlank()) {
            info = "⚠️ Токен бота не задан"
            return null
        }
        return TelegramApi(token)
    }

    val lanIp = remember { FileServer.lanAddress() ?: "127.0.0.1" }
    val localMiniAppUrl = "http://$lanIp:${FileServer.PORT}/miniapp/${if (botId > 0L) botId else 1L}"

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Инлайн, Stars, Mini App", style = MaterialTheme.typography.titleMedium)
        }
        if (info.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(info, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        // ============================================================
        // 1. INLINE MODE (@bot запрос)
        // ============================================================
        SectionTitle("🔎 Inline-режим (@bot запрос)")
        Text(
            "Включите Inline Mode в @BotFather (/setinline), затем включите переключатель ниже — бот будет отдавать карточки при вводе @бот в любом чате.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Включить ответы на inline_query", Modifier.weight(1f))
                    Switch(inlineCfg.enabled, onCheckedChange = { inlineCfg = inlineCfg.copy(enabled = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Подставлять текстовые правила", Modifier.weight(1f))
                    Switch(inlineCfg.includeRules, onCheckedChange = { inlineCfg = inlineCfg.copy(includeRules = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Подставлять наборы ответов", Modifier.weight(1f))
                    Switch(inlineCfg.includePacks, onCheckedChange = { inlineCfg = inlineCfg.copy(includePacks = it) })
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = inlineCfg.switchPmText,
                    onValueChange = { inlineCfg = inlineCfg.copy(switchPmText = it.take(64)) },
                    label = { Text("Кнопка перехода в ЛС бота (необязательно)") },
                    placeholder = { Text("Открыть каталог") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Свои карточки (${inlineCfg.customItems.size}):", style = MaterialTheme.typography.titleSmall)
                inlineCfg.customItems.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium)
                            Text(item.replyText.take(80), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            inlineCfg = inlineCfg.copy(customItems = inlineCfg.customItems - item)
                        }) { Text("✕") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(newArtTitle, { newArtTitle = it }, label = { Text("Заголовок карточки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(newArtDesc, { newArtDesc = it }, label = { Text("Краткое описание") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(newArtKeywords, { newArtKeywords = it }, label = { Text("Ключевые слова поиска") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(newArtReply, { newArtReply = it }, label = { Text("Текст, который отправится в чат") }, modifier = Modifier.fillMaxWidth().height(72.dp))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (newArtTitle.isBlank() || newArtReply.isBlank()) {
                                info = "Заполните заголовок и текст карточки"
                                return@OutlinedButton
                            }
                            val next = inlineCfg.customItems + InlineArticleItem(
                                id = UUID.randomUUID().toString().substring(0, 8),
                                title = newArtTitle.trim(),
                                description = newArtDesc.trim(),
                                replyText = newArtReply.trim(),
                                keywords = newArtKeywords.trim(),
                            )
                            inlineCfg = inlineCfg.copy(customItems = next)
                            newArtTitle = ""
                            newArtDesc = ""
                            newArtReply = ""
                            newArtKeywords = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("+ Карточка") }
                    Button(
                        onClick = {
                            scope.launch {
                                localStore.setInlineConfig(inlineCfg, botId)
                                info = "✅ Настройки Inline-режима сохранены"
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Сохранить") }
                }
            }
        }

        // ============================================================
        // 2. TELEGRAM STARS (XTR)
        // ============================================================
        SectionTitle("⭐ Оплаты Telegram Stars (XTR)")
        Text(
            "Цифровые товары за звёзды Telegram (currency = XTR, без внешнего провайдера). После оплаты бот выдаёт текст/медиа и назначает роль/тег.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                if (products.isEmpty()) {
                    Text("Товаров пока нет.", style = MaterialTheme.typography.bodySmall)
                }
                products.forEach { prod ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("⭐ ${prod.title} — ${prod.priceStars} XTR (id: ${prod.id})", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${prod.description.take(70)} · роль: ${prod.grantRole.ifBlank { "—" }} · тег: ${prod.grantTag.ifBlank { "—" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        val next = products - prod
                                        products = next
                                        localStore.setDigitalProducts(next, botId)
                                    }
                                }) { Text("✕") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val api = apiForBot() ?: return@launch
                                            api.createInvoiceLink(
                                                title = prod.title,
                                                description = prod.description,
                                                payloadId = PaymentPolicy.productPayload(prod.id),
                                                amount = prod.priceStars,
                                                currency = "XTR",
                                            ).onSuccess { link ->
                                                lastInvoiceLink = link
                                                clipboard.setText(AnnotatedString(link))
                                                info = "✅ Ссылка Stars скопирована: $link"
                                            }.onFailure { info = "❌ ${it.message?.take(120)}" }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("🔗 Ссылка XTR") }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val api = apiForBot() ?: return@launch
                                            val chatId = localStore.lastChatId(botId)
                                            if (chatId == 0L) {
                                                info = "⚠️ Сначала напишите боту в Telegram, чтобы появился lastChatId"
                                                return@launch
                                            }
                                            api.sendInvoice(
                                                chatId = chatId,
                                                title = prod.title,
                                                description = prod.description,
                                                payloadId = PaymentPolicy.productPayload(prod.id),
                                                amount = prod.priceStars,
                                                currency = "XTR",
                                            ).onSuccess { info = "✅ Счёт отправлен в чат $chatId" }
                                                .onFailure { info = "❌ ${it.message?.take(120)}" }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("📩 Счёт в чат") }
                            }
                        }
                    }
                }
                if (lastInvoiceLink.isNotBlank()) {
                    Text("Последняя ссылка: $lastInvoiceLink", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                Text("Добавить товар за Stars:", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(newProdTitle, { newProdTitle = it }, label = { Text("Название (до 32 симв.)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(newProdDesc, { newProdDesc = it }, label = { Text("Описание товара") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newProdStars, { newProdStars = it.filter(Char::isDigit) }, label = { Text("Цена (XTR)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(newProdTag, { newProdTag = it }, label = { Text("Выдать тег") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Text("Роль после оплаты:", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "не менять", "vip" to "vip", "admin" to "admin").forEach { (r, lbl) ->
                        FilterChip(selected = newProdRole == r, onClick = { newProdRole = r }, label = { Text(lbl) })
                    }
                }
                OutlinedTextField(newProdDeliver, { newProdDeliver = it }, label = { Text("Авто-сообщение после оплаты") }, modifier = Modifier.fillMaxWidth().height(72.dp))
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        val stars = newProdStars.toIntOrNull()?.coerceAtLeast(1) ?: 0
                        if (newProdTitle.isBlank() || stars <= 0) {
                            info = "Укажите название и цену в Stars (>= 1)"
                            return@Button
                        }
                        val prod = DigitalProduct(
                            id = UUID.randomUUID().toString().substring(0, 6),
                            title = newProdTitle.trim().take(32),
                            description = newProdDesc.trim().ifBlank { "Доступ: ${newProdTitle.trim()}" },
                            priceStars = stars,
                            deliverText = newProdDeliver.trim(),
                            grantRole = newProdRole,
                            grantTag = newProdTag.trim(),
                            active = true,
                        )
                        val next = products + prod
                        products = next
                        newProdTitle = ""
                        newProdDesc = ""
                        scope.launch {
                            localStore.setDigitalProducts(next, botId)
                            info = "✅ Товар «${prod.title}» (${prod.priceStars} XTR) добавлен"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("+ Добавить товар Stars") }

                if (orders.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Последние оплаты (${orders.size}):", style = MaterialTheme.typography.titleSmall)
                    orders.take(15).forEach { ord ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("⭐ ${ord.title} (${ord.amountStars} XTR) — user ${ord.userId}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (ord.refunded) "↩️ Возвращён (${ord.chargeId.take(18)}…)"
                                    else "charge: ${ord.chargeId.take(22)}…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!ord.refunded && ord.chargeId.isNotBlank()) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val api = apiForBot() ?: return@launch
                                        api.refundStarPayment(ord.userId, ord.chargeId)
                                            .onSuccess {
                                                localStore.markOrderRefunded(ord.chargeId, botId)
                                                orders = localStore.starOrders(botId)
                                                info = "✅ Stars возвращены пользователю ${ord.userId}"
                                            }
                                            .onFailure { info = "❌ Возврат: ${it.message?.take(120)}" }
                                    }
                                }) { Text("Возврат") }
                            }
                        }
                    }
                }
            }
        }

        // ============================================================
        // 3. TELEGRAM MINI APPS (web_app)
        // ============================================================
        SectionTitle("🌐 Telegram Mini Apps (web_app)")
        Text(
            "Встроенная HTML5-витрина и форма заявки доступны по адресу $localMiniAppUrl (через HTTPS-туннель/домен её можно поставить прямо в кнопку меню Telegram).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Локальная Mini App витрина (/miniapp)", Modifier.weight(1f))
                    Switch(webAppCfg.localMiniAppEnabled, onCheckedChange = { webAppCfg = webAppCfg.copy(localMiniAppEnabled = it) })
                }
                OutlinedTextField(webAppCfg.title, { webAppCfg = webAppCfg.copy(title = it) }, label = { Text("Заголовок витрины") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(webAppCfg.introText, { webAppCfg = webAppCfg.copy(introText = it) }, label = { Text("Приветственный текст") }, modifier = Modifier.fillMaxWidth().height(72.dp))
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(webAppCfg.submitReplyTemplate, { webAppCfg = webAppCfg.copy(submitReplyTemplate = it) }, label = { Text("Ответ на web_app_data ({data})") }, modifier = Modifier.fillMaxWidth().height(72.dp))
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(webAppCfg.externalUrl, { webAppCfg = webAppCfg.copy(externalUrl = it) }, label = { Text("HTTPS URL для кнопки меню Mini App") }, placeholder = { Text("https://my-tunnel.example/miniapp/$botId") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Дублировать заявки админам", Modifier.weight(1f))
                    Switch(webAppCfg.sendSubmitToAdmin, onCheckedChange = { webAppCfg = webAppCfg.copy(sendSubmitToAdmin = it) })
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                localStore.setWebAppConfig(webAppCfg, botId)
                                info = "✅ Настройки Mini App сохранены"
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Сохранить") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                localStore.setWebAppConfig(webAppCfg, botId)
                                val api = apiForBot() ?: return@launch
                                val u = webAppCfg.externalUrl.trim()
                                if (!u.startsWith("https://")) {
                                    info = "⚠️ Telegram требует https:// URL для кнопки меню Mini App"
                                    return@launch
                                }
                                api.setChatMenuButtonWebApp(text = webAppCfg.title.ifBlank { "Открыть" }, webAppUrl = u)
                                    .onSuccess { info = "✅ Кнопка меню Mini App установлена в Telegram" }
                                    .onFailure { info = "❌ setChatMenuButton: ${it.message?.take(120)}" }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("📌 В меню чата") }
                }
                TextButton(onClick = {
                    scope.launch {
                        val api = apiForBot() ?: return@launch
                        api.setChatMenuButtonWebApp(text = "", webAppUrl = "")
                            .onSuccess { info = "✅ Кнопка меню возвращена к списку команд" }
                            .onFailure { info = "❌ ${it.message?.take(120)}" }
                    }
                }) { Text("Сбросить кнопку меню к командам") }
            }
        }

        // ============================================================
        // 4. HTTP API ALLOWLIST & INCOMING MEDIA
        // ============================================================
        SectionTitle("🔌 Внешний HTTP API и входящие медиа")
        Text(
            "Скрипты JS могут вызывать http.get(url) и http.post(url, data) только для доменов из белого списка. Входящие фото/файлы/голосовые скачиваются в папку бота и передаются в e.localPath и промт ИИ.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Скачивать входящие медиа в сценарии и ИИ", Modifier.weight(1f))
                    Switch(integCfg.passIncomingMediaToLlm, onCheckedChange = { integCfg = integCfg.copy(passIncomingMediaToLlm = it) })
                }
                OutlinedTextField(
                    value = domainsText,
                    onValueChange = { domainsText = it },
                    label = { Text("Белый список доменов (через запятую)") },
                    placeholder = { Text("api.example.com, hooks.make.com") },
                    modifier = Modifier.fillMaxWidth().height(76.dp),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter(Char::isDigit) },
                    label = { Text("Таймаут HTTP (сек, 2–30)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = integCfg.webhookOnPaymentUrl,
                    onValueChange = { integCfg = integCfg.copy(webhookOnPaymentUrl = it) },
                    label = { Text("Вебхук при оплате Stars (POST JSON)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = integCfg.webhookOnLeadUrl,
                    onValueChange = { integCfg = integCfg.copy(webhookOnLeadUrl = it) },
                    label = { Text("Вебхук при заявке Mini App (POST JSON)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val next = integCfg.copy(
                            httpAllowedDomains = HttpAllowlistPolicy.parseDomains(domainsText),
                            httpTimeoutSec = timeoutText.toIntOrNull()?.coerceIn(2, 30) ?: 8,
                            webhookOnPaymentUrl = integCfg.webhookOnPaymentUrl.trim(),
                            webhookOnLeadUrl = integCfg.webhookOnLeadUrl.trim(),
                        )
                        integCfg = next
                        scope.launch {
                            localStore.setIntegrationsConfig(next, botId)
                            info = "✅ Интеграции и политика входящих медиа сохранены"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сохранить интеграции") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
