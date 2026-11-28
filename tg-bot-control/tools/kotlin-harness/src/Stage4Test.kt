import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.BotMatcher
import com.botcontrol.admin.data.DigitalProduct
import com.botcontrol.admin.data.HttpAllowlistPolicy
import com.botcontrol.admin.data.InlineArticleItem
import com.botcontrol.admin.data.InlineCatalogMatcher
import com.botcontrol.admin.data.InlineModeConfig
import com.botcontrol.admin.data.InlineRuleEntry
import com.botcontrol.admin.data.IntegrationsConfig
import com.botcontrol.admin.data.MessageCtx
import com.botcontrol.admin.data.PaymentPolicy
import com.botcontrol.admin.data.ReplyPack
import com.botcontrol.admin.data.WebAppConfig
import com.botcontrol.admin.data.telegram.TelegramUpdatesParser

private var checks = 0

private fun assertTrue(cond: Boolean, msg: String) {
    checks++
    if (!cond) error("FAIL: $msg")
}

private fun <T> assertEq(expected: T, actual: T, msg: String) {
    checks++
    if (expected != actual) error("FAIL ($msg): expected=$expected, actual=$actual")
}

fun main() {
    // 1. DEFAULT_ALLOWED_UPDATES (14 типов включая inline и pre_checkout_query)
    val allowed = TelegramUpdatesParser.DEFAULT_ALLOWED_UPDATES
    assertEq(14, allowed.size, "allowed_updates count")
    assertTrue("inline_query" in allowed, "inline_query in allowed_updates")
    assertTrue("chosen_inline_result" in allowed, "chosen_inline_result in allowed_updates")
    assertTrue("pre_checkout_query" in allowed, "pre_checkout_query in allowed_updates")

    // 2. Разбор inline_query, chosen_inline_result, pre_checkout_query, successful_payment, web_app_data, attachment metadata
    val rawUpdates = """
    {
      "ok": true,
      "result": [
        {
          "update_id": 1001,
          "inline_query": {
            "id": "iq_1",
            "from": {"id": 77, "first_name": "Алиса", "username": "alice"},
            "query": "тариф",
            "chat_type": "private",
            "offset": ""
          }
        },
        {
          "update_id": 1002,
          "chosen_inline_result": {
            "result_id": "prod_vip",
            "from": {"id": 77, "first_name": "Алиса", "username": "alice"},
            "inline_message_id": "im_99",
            "query": "тариф"
          }
        },
        {
          "update_id": 1003,
          "pre_checkout_query": {
            "id": "pcq_1",
            "from": {"id": 77, "first_name": "Алиса", "username": "alice"},
            "currency": "XTR",
            "total_amount": 50,
            "invoice_payload": "prod_vip"
          }
        },
        {
          "update_id": 1004,
          "message": {
            "message_id": 501,
            "chat": {"id": 77, "type": "private"},
            "from": {"id": 77, "first_name": "Алиса", "username": "alice"},
            "successful_payment": {
              "currency": "XTR",
              "total_amount": 50,
              "invoice_payload": "prod_vip",
              "telegram_payment_charge_id": "chg_abc_123",
              "provider_payment_charge_id": ""
            }
          }
        },
        {
          "update_id": 1005,
          "message": {
            "message_id": 502,
            "chat": {"id": 77, "type": "private"},
            "from": {"id": 77, "first_name": "Алиса", "username": "alice"},
            "web_app_data": {
              "data": "{\"action\":\"lead\",\"name\":\"Алиса\"}",
              "button_text": "Открыть витрину"
            }
          }
        },
        {
          "update_id": 1006,
          "message": {
            "message_id": 503,
            "chat": {"id": 77, "type": "private"},
            "from": {"id": 77, "first_name": "Алиса", "username": "alice"},
            "caption": "Вот чек",
            "document": {
              "file_id": "doc_xyz",
              "file_name": "receipt.pdf",
              "mime_type": "application/pdf",
              "file_size": 4096
            }
          }
        }
      ]
    }
    """.trimIndent()

    val batch = TelegramUpdatesParser.parseGetUpdatesResponse(rawUpdates)
    assertEq(6, batch.totalCount, "totalCount")
    assertEq(1, batch.inlineQueries.size, "inlineQueries")
    assertEq("iq_1", batch.inlineQueries[0].id, "iq id")
    assertEq("тариф", batch.inlineQueries[0].query, "iq query")
    assertEq(1, batch.chosenInlineResults.size, "chosenInlineResults")
    assertEq("prod_vip", batch.chosenInlineResults[0].resultId, "cir resultId")
    assertEq(1, batch.preCheckoutQueries.size, "preCheckoutQueries")
    assertEq(50, batch.preCheckoutQueries[0].totalAmount, "pcq amount")
    assertEq("XTR", batch.preCheckoutQueries[0].currency, "pcq currency")
    assertEq(3, batch.messages.size, "messages count (payment + webapp + doc)")
    assertEq("оплата", batch.messages[0].kind, "payment kind")
    assertEq("chg_abc_123", batch.messages[0].successfulPayment?.telegramPaymentChargeId, "chargeId")
    assertEq("mini_app", batch.messages[1].kind, "webapp kind")
    assertTrue(batch.messages[1].webAppData?.data?.contains("lead") == true, "webapp payload")
    assertEq("файл", batch.messages[2].kind, "doc kind")
    assertEq("receipt.pdf", batch.messages[2].fileName, "doc fileName")
    assertEq("application/pdf", batch.messages[2].mimeType, "doc mimeType")
    assertEq(4096L, batch.messages[2].fileSize, "doc fileSize")

    // 3. InlineCatalogMatcher + изоляция наборов по ownerBotId
    val products = listOf(
        DigitalProduct(id = "vip", title = "VIP тариф", description = "Доступ на 30 дней", priceStars = 50, grantRole = "vip", active = true),
        DigitalProduct(id = "arch", title = "Архив", description = "Скрыт", priceStars = 10, active = false),
    )
    val packs = listOf(
        ReplyPack(id = "p_shared", name = "Тарифы", items = listOf("Базовый тариф бесплатный"), ownerBotId = 0L),
        ReplyPack(id = "p_foreign", name = "Чужой тариф", items = listOf("Секрет другого бота"), ownerBotId = 99L),
    )
    val rules = listOf(
        InlineRuleEntry(id = 1, pattern = "/tariffs", responseText = "Тариф для {user}: VIP", actionType = "text", packId = ""),
        InlineRuleEntry(id = 2, pattern = "/admin_secret", responseText = "Админ-тариф", actionType = "text", packId = "", adminOnly = true),
    )
    val inlineCfg = InlineModeConfig(
        enabled = true,
        includePacks = true,
        includeRules = true,
        customItems = listOf(
            InlineArticleItem(id = "c1", title = "Прайс", replyText = "Привет, {user}! Тариф 50 Stars", keywords = "тариф цена"),
        ),
    )
    val ctx = MessageCtx(chatId = 77L, userId = 77L, firstName = "Алиса")
    val builtOff = InlineCatalogMatcher.buildArticles("тариф", inlineCfg.copy(enabled = false), rules, packs, products, 1L, ctx, "demo_bot")
    assertEq(0, builtOff.size, "disabled inline returns 0")

    val built = InlineCatalogMatcher.buildArticles("тариф", inlineCfg, rules, packs, products, 1L, ctx, "demo_bot")
    assertTrue(built.any { it.id == "item_c1" && it.messageText.contains("Алиса") }, "custom item matched & expanded")
    assertTrue(built.any { it.id == "rule_1" }, "public rule matched")
    assertTrue(built.none { it.id == "rule_2" }, "adminOnly rule excluded")
    assertTrue(built.none { it.messageText.contains("Секрет другого бота") }, "foreign bot pack excluded")
    assertTrue(built.any { it.id == "prod_vip" && it.messageText.contains("start=buy_vip") }, "active product included")
    assertTrue(built.none { it.id == "prod_arch" }, "inactive product excluded")

    // 4. HttpAllowlistPolicy (безопасность http.get/post и вебхуков)
    val domains = HttpAllowlistPolicy.parseDomains("api.example.com, https://hooks.make.com/path")
    assertEq(listOf("api.example.com", "hooks.make.com"), domains, "parsed allowlist domains")
    assertTrue(!HttpAllowlistPolicy.isUrlAllowed("https://api.example.com/v1", emptyList()), "empty allowlist blocks")
    assertTrue(HttpAllowlistPolicy.isUrlAllowed("https://api.example.com/v1/orders", domains), "exact https domain allowed")
    assertTrue(HttpAllowlistPolicy.isUrlAllowed("https://eu.hooks.make.com/abc", domains), "subdomain allowed")
    assertTrue(!HttpAllowlistPolicy.isUrlAllowed("http://api.example.com/v1", domains), "non-https remote blocked")
    assertTrue(!HttpAllowlistPolicy.isUrlAllowed("https://evil.com/steal", domains), "unlisted domain blocked")
    assertTrue(!HttpAllowlistPolicy.isUrlAllowed("https://user:pass@api.example.com/v1", domains), "userinfo blocked")
    assertTrue(!HttpAllowlistPolicy.isUrlAllowed("file:///etc/passwd", domains), "file scheme blocked")
    assertTrue(HttpAllowlistPolicy.isUrlAllowed("http://127.0.0.1:8080/hook", listOf("127.0.0.1")), "explicit localhost allowed")

    // 5. PaymentPolicy.verifyPreCheckout
    assertEq(true to "", PaymentPolicy.verifyPreCheckout("XTR", 50, "prod_vip", products), "valid XTR payment")
    assertTrue(!PaymentPolicy.verifyPreCheckout("XTR", 10, "prod_vip", products).first, "wrong XTR amount rejected")
    assertTrue(!PaymentPolicy.verifyPreCheckout("XTR", 10, "prod_arch", products).first, "inactive product rejected")
    assertTrue(!PaymentPolicy.verifyPreCheckout("XTR", 0, "prod_vip", products).first, "zero amount rejected")

    // 6. Media placeholders & JSON round-trip
    val mediaCtx = MessageCtx(
        chatId = 77L,
        firstName = "Алиса",
        kind = "фото",
        fileId = "ph_1",
        caption = "Сломался экран",
        fileName = "screen.jpg",
        localMediaPath = "/tmp/bot_1/media/in_503.jpg",
    )
    val expanded = BotMatcher.expandPlaceholders(
        "{kind}: {caption} ({file_name}) -> {local_path} [{file_id}]",
        mediaCtx,
        "demo_bot",
    )
    assertEq("фото: Сломался экран (screen.jpg) -> /tmp/bot_1/media/in_503.jpg [ph_1]", expanded, "media placeholders")

    val roundWeb = BotJson.webAppConfig(BotJson.save(WebAppConfig(localMiniAppEnabled = true, title = "Shop")))
    assertTrue(roundWeb.localMiniAppEnabled && roundWeb.title == "Shop", "WebAppConfig JSON")
    val roundInteg = BotJson.integrationsConfig(BotJson.save(IntegrationsConfig(httpAllowedDomains = listOf("api.example.com"), httpTimeoutSec = 12)))
    assertEq(listOf("api.example.com"), roundInteg.httpAllowedDomains, "IntegrationsConfig JSON")

    println("STAGE4 ALL OK ($checks checks)")
}
