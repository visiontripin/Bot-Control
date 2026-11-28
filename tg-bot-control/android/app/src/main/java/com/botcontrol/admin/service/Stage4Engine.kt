package com.botcontrol.admin.service

import com.botcontrol.admin.BotControlApp
import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.BotMatcher
import com.botcontrol.admin.data.DigitalProduct
import com.botcontrol.admin.data.InlineCatalogMatcher
import com.botcontrol.admin.data.InlineRuleEntry
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.MessageCtx
import com.botcontrol.admin.data.PaymentPolicy
import com.botcontrol.admin.data.StarOrder
import com.botcontrol.admin.data.telegram.MiniJson
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.data.telegram.TgChosenInlineResult
import com.botcontrol.admin.data.telegram.TgInlineQuery
import com.botcontrol.admin.data.telegram.TgMessage
import com.botcontrol.admin.data.telegram.TgPreCheckoutQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Движок Этапа 4 (v2.1.0):
 *  - Inline-режим (@bot запрос): inline_query / chosen_inline_result;
 *  - Оплаты и Telegram Stars (XTR): sendInvoice / pre_checkout_query / successful_payment / авто-выдача;
 *  - Telegram Mini Apps (web_app_data) и отправка лидов/оплат на внешние HTTP-вебхуки.
 */
object Stage4Engine {

    suspend fun handleInlineQuery(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        iq: TgInlineQuery,
    ) {
        val cfg = store.inlineConfig(botId)
        if (!cfg.enabled) {
            api.answerInlineQuery(
                inlineQueryId = iq.id,
                articles = emptyList(),
                cacheTimeSec = 5,
                switchPmText = cfg.switchPmText.ifBlank { "Открыть бота" },
                switchPmParameter = "inline",
            )
            return
        }
        val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()
        val rules = app.repository.botRules(botId).map { r ->
            val opts = BotJson.ruleOptions(r.options)
            InlineRuleEntry(
                id = r.id,
                pattern = r.pattern,
                responseText = r.responseText,
                actionType = r.actionType,
                packId = r.packId,
                parseMode = opts.parseMode,
                adminOnly = opts.adminOnly,
                enabled = r.enabled,
            )
        }
        val packs = store.packs()
        val products = store.digitalProducts(botId)
        val ctx = MessageCtx(
            chatId = iq.userId,
            userId = iq.userId,
            firstName = iq.firstName,
            username = iq.username,
            chatType = iq.chatType,
            text = iq.query,
        )
        val articles = InlineCatalogMatcher.buildArticles(
            query = iq.query,
            config = cfg,
            rules = rules,
            packs = packs,
            products = products,
            botId = botId,
            ctx = ctx,
            botUsername = botName,
        )
        BotLog.log(botId, "🔎 Inline-запрос от @${iq.username.ifBlank { iq.userId.toString() }}: '${iq.query.take(50)}' → ${articles.size} карточ.")
        api.answerInlineQuery(
            inlineQueryId = iq.id,
            articles = articles,
            cacheTimeSec = cfg.cacheTimeSec,
            isPersonal = true,
            switchPmText = cfg.switchPmText,
            switchPmParameter = "inline",
        ).onFailure {
            BotLog.log(botId, "⚠️ answerInlineQuery ошибка: ${it.message?.take(120)}")
        }
    }

    fun handleChosenInlineResult(botId: Long, cir: TgChosenInlineResult) {
        BotLog.log(
            botId,
            "✅ Выбран inline-результат '${cir.resultId}' (запрос '${cir.query.take(40)}', user=${cir.userId})",
        )
    }

    suspend fun handlePreCheckoutQuery(
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        pcq: TgPreCheckoutQuery,
    ) {
        val products = store.digitalProducts(botId)
        val (ok, err) = PaymentPolicy.verifyPreCheckout(
            currency = pcq.currency,
            totalAmount = pcq.totalAmount,
            payload = pcq.invoicePayload,
            products = products,
        )
        BotLog.log(
            botId,
            "💳 PreCheckout user=${pcq.userId} ${pcq.totalAmount} ${pcq.currency} payload='${pcq.invoicePayload}' → ${if (ok) "OK" else "отказ: $err"}",
        )
        api.answerPreCheckoutQuery(pcq.id, ok = ok, errorMessage = err).onFailure {
            BotLog.log(botId, "⚠️ answerPreCheckoutQuery ошибка: ${it.message?.take(120)}")
        }
    }

    suspend fun sendProductInvoice(
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        productKey: String,
        messageThreadId: Int? = null,
    ): Boolean {
        val products = store.digitalProducts(botId).filter { it.active }
        val clean = productKey.trim()
        val product = products.firstOrNull { it.id == clean }
            ?: products.firstOrNull { it.title.equals(clean, ignoreCase = true) }
            ?: products.firstOrNull { it.title.contains(clean, ignoreCase = true) }
            ?: products.firstOrNull()
        if (product == null) {
            api.sendMessage(chatId, "🛒 Каталог товаров для оплаты Stars пока пуст.")
            return false
        }
        val payload = PaymentPolicy.productPayload(product.id)
        val res = api.sendInvoice(
            chatId = chatId,
            title = product.title,
            description = product.description.ifBlank { "Оплата цифрового доступа в Telegram Stars" },
            payloadId = payload,
            amount = product.priceStars.coerceAtLeast(1),
            currency = "XTR",
            messageThreadId = messageThreadId,
        )
        res.onSuccess {
            BotLog.log(botId, "⭐ Отправлен счёт «${product.title}» (${product.priceStars} XTR) в чат $chatId")
        }.onFailure {
            BotLog.log(botId, "❌ sendInvoice ошибка: ${it.message?.take(140)}")
            api.sendMessage(chatId, "Не удалось выставить счёт: ${it.message?.take(120)}")
        }
        return res.isSuccess
    }

    suspend fun handleSuccessfulPayment(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        msg: TgMessage,
        botScope: CoroutineScope,
    ) {
        val sp = msg.successfulPayment ?: return
        val prodId = PaymentPolicy.parseProductPayload(sp.invoicePayload)
        val products = store.digitalProducts(botId)
        val product = prodId?.let { id -> products.firstOrNull { it.id == id } }
        val uid = if (msg.userId != 0L) msg.userId else msg.chatId
        val order = StarOrder(
            id = "ord_" + UUID.randomUUID().toString().substring(0, 8),
            userId = uid,
            username = msg.username,
            productId = product?.id.orEmpty().ifBlank { sp.invoicePayload },
            title = product?.title.orEmpty().ifBlank { sp.invoicePayload },
            amountStars = sp.totalAmount,
            chargeId = sp.telegramPaymentChargeId,
            createdAtSec = System.currentTimeMillis() / 1000L,
        )
        store.addStarOrder(order, botId)
        BotLog.log(
            botId,
            "⭐ Успешная оплата! user=$uid (${msg.firstName}) · ${sp.totalAmount} ${sp.currency} · «${order.title}» · charge=${sp.telegramPaymentChargeId}",
        )

        // Авто-выдача роли и тега в Room-базе пользователей
        val user = app.repository.botUser(botId, msg.chatId)
        if (user != null && product != null) {
            val nextRole = if (product.grantRole in setOf("vip", "admin")) product.grantRole else user.role
            val nextTags = if (product.grantTag.isNotBlank()) {
                (user.tags.split(",").map { it.trim() }.filter { it.isNotBlank() } + product.grantTag.trim())
                    .distinct().joinToString(", ")
            } else user.tags
            if (nextRole != user.role || nextTags != user.tags) {
                app.repository.saveBotUser(user.copy(role = nextRole, tags = nextTags))
            }
        }
        app.repository.mergeUserVars(
            botId,
            msg.chatId,
            mapOf(
                "last_order_id" to order.id,
                "last_order_title" to order.title,
                "last_order_stars" to sp.totalAmount.toString(),
            ),
        )

        val ctx = MessageCtx(
            chatId = msg.chatId,
            userId = uid,
            firstName = msg.firstName,
            username = msg.username,
            chatType = msg.chatType,
            text = order.title,
        )
        val rawDeliver = product?.deliverText?.takeIf { it.isNotBlank() }
            ?: "✅ Оплата ${sp.totalAmount} ${sp.currency} получена! Спасибо, {first_name}."
        val deliverMsg = BotMatcher.expandPlaceholders(
            rawDeliver.replace("{title}", order.title)
                .replace("{amount}", sp.totalAmount.toString())
                .replace("{charge_id}", sp.telegramPaymentChargeId),
            ctx,
            LocalBotService.states.value[botId]?.botUsername.orEmpty(),
        )
        if (product != null && product.deliverMediaType.isNotBlank() && product.deliverMediaRef.isNotBlank()) {
            api.sendMediaByType(
                chatId = msg.chatId,
                mediaType = product.deliverMediaType,
                mediaRef = product.deliverMediaRef,
                caption = deliverMsg,
                messageThreadId = msg.messageThreadId,
            ).onFailure { api.sendMessage(msg.chatId, deliverMsg, messageThreadId = msg.messageThreadId) }
        } else {
            api.sendMessage(msg.chatId, deliverMsg, messageThreadId = msg.messageThreadId)
        }

        // Уведомляем администраторов
        val admins = store.adminIds(botId)
        val note = "⭐ Новый платёж (${sp.totalAmount} ${sp.currency}): «${order.title}» от ${msg.firstName} (@${msg.username.ifBlank { uid.toString() }})\ncharge_id: ${sp.telegramPaymentChargeId}"
        admins.filter { it != msg.chatId }.forEach { adminId ->
            api.sendMessage(adminId, note)
        }

        // Внешний вебхук оплаты (если настроен и в allowlist)
        val integ = store.integrationsConfig(botId)
        if (integ.webhookOnPaymentUrl.isNotBlank()) {
            val payloadJson = BotJson.save(
                mapOf(
                    "event" to "successful_payment",
                    "botId" to botId,
                    "userId" to uid,
                    "username" to msg.username,
                    "firstName" to msg.firstName,
                    "productId" to order.productId,
                    "title" to order.title,
                    "currency" to sp.currency,
                    "amount" to sp.totalAmount,
                    "chargeId" to sp.telegramPaymentChargeId,
                ),
            )
            botScope.launch(Dispatchers.IO) {
                val res = SafeHttpClient.execute("POST", integ.webhookOnPaymentUrl, integ, payloadJson)
                if (!res.ok) BotLog.log(botId, "⚠️ Вебхук оплаты: ${res.error}")
            }
        }
    }

    suspend fun handleWebAppData(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        msg: TgMessage,
        botScope: CoroutineScope,
    ) {
        val wad = msg.webAppData ?: return
        val cfg = store.webAppConfig(botId)
        val uid = if (msg.userId != 0L) msg.userId else msg.chatId
        BotLog.log(botId, "🌐 Mini App данные от user=$uid (${msg.firstName}): ${wad.data.take(120)}")

        val parsed = MiniJson.parseObject(wad.data)
        val patch = linkedMapOf<String, String>("webapp_raw" to wad.data.take(1000))
        val prettyLines = if (parsed != null) {
            parsed.entries.take(20).map { (k, v) ->
                val valStr = v?.toString().orEmpty()
                patch["webapp_${k.take(32)}"] = valStr.take(500)
                "• $k: $valStr"
            }.joinToString("\n")
        } else {
            wad.data.take(1000)
        }
        app.repository.mergeUserVars(botId, msg.chatId, patch)

        // Если Mini App передал {"action":"buy","productId":"..."}, сразу выставляем счёт XTR
        val action = parsed?.get("action")?.toString().orEmpty()
        val prodId = parsed?.get("productId")?.toString().orEmpty()
        if (action == "buy" && prodId.isNotBlank()) {
            sendProductInvoice(store, api, botId, msg.chatId, prodId, msg.messageThreadId)
            return
        }

        val ctx = MessageCtx(
            chatId = msg.chatId,
            userId = uid,
            firstName = msg.firstName,
            username = msg.username,
            chatType = msg.chatType,
            text = wad.data,
        )
        val reply = BotMatcher.expandPlaceholders(
            cfg.submitReplyTemplate.ifBlank { "✅ Заявка из Mini App получена:\n{data}" }
                .replace("{data}", prettyLines),
            ctx,
            LocalBotService.states.value[botId]?.botUsername.orEmpty(),
            vars = app.repository.userVars(botId, msg.chatId),
        )
        api.sendMessage(msg.chatId, reply, messageThreadId = msg.messageThreadId)

        if (cfg.sendSubmitToAdmin) {
            val adminNote = "🌐 Заявка из Mini App от ${msg.firstName} (@${msg.username.ifBlank { uid.toString() }}):\n$prettyLines"
            store.adminIds(botId).filter { it != msg.chatId }.forEach { adminId ->
                api.sendMessage(adminId, adminNote)
            }
        }

        val integ = store.integrationsConfig(botId)
        if (integ.webhookOnLeadUrl.isNotBlank()) {
            val payloadJson = BotJson.save(
                mapOf(
                    "event" to "web_app_data",
                    "botId" to botId,
                    "userId" to uid,
                    "username" to msg.username,
                    "firstName" to msg.firstName,
                    "buttonText" to wad.buttonText,
                    "data" to (parsed ?: wad.data),
                ),
            )
            botScope.launch(Dispatchers.IO) {
                val res = SafeHttpClient.execute("POST", integ.webhookOnLeadUrl, integ, payloadJson)
                if (!res.ok) BotLog.log(botId, "⚠️ Вебхук Mini App: ${res.error}")
            }
        }
    }
}
