import com.botcontrol.admin.data.BotMatcher
import com.botcontrol.admin.data.DigitalProduct
import com.botcontrol.admin.data.HttpAllowlistPolicy
import com.botcontrol.admin.data.MessageCtx
import com.botcontrol.admin.data.PaymentPolicy
import com.botcontrol.admin.data.ScriptImporter
import com.botcontrol.admin.data.telegram.TelegramUpdatesParser
import java.io.File

private var checks = 0

private fun assertTrue(cond: Boolean, msg: String) {
    checks++
    if (!cond) error("FAIL: $msg")
}

private fun <T> assertEq(expected: T, actual: T, msg: String) {
    checks++
    if (expected != actual) error("FAIL ($msg): expected=$expected, actual=$actual")
}

fun main(args: Array<String>) {
    val repoRoot = File(args.firstOrNull() ?: "../..").canonicalFile
    val contractFile = File(repoRoot, "tools/shared-telegram-mock/contract_updates.json")
    assertTrue(contractFile.exists(), "contract_updates.json exists at ${contractFile.path}")

    val rawContract = contractFile.readText(Charsets.UTF_8)
    val startIdx = rawContract.indexOf("\"getUpdatesResponse\"")
    assertTrue(startIdx >= 0, "getUpdatesResponse key exists")
    val braceStart = rawContract.indexOf('{', startIdx)
    val updatesJson = rawContract.substring(braceStart, rawContract.lastIndexOf('}'))

    // 1. Общие контрактные фикстуры парсятся тем же TelegramUpdatesParser, что в Android
    val batch = TelegramUpdatesParser.parseGetUpdatesResponse(updatesJson)
    assertEq(8, batch.totalCount, "contract total updates")
    assertEq(5, batch.messages.size, "contract text/web_app messages")
    assertEq(1, batch.callbacks.size, "contract callbacks")
    assertEq(1, batch.inlineQueries.size, "contract inlineQueries")
    assertEq(1, batch.preCheckoutQueries.size, "contract preCheckouts")

    // 2. Сквозной паритет матчинга между desktop-web и Android BotMatcher
    val texts = batch.messages.map { it.text }
    assertTrue(BotMatcher.matchesRule("command", "/start", texts[0]), "/start command match")
    assertTrue(BotMatcher.matchesRule("command", "/help", texts[1]), "/help@BotControlDemoBot match")
    assertTrue(!BotMatcher.matchesRule("contains", "кот", texts[2], wholeWord = true), "wholeWord skips substring 'скот'")
    assertTrue(BotMatcher.matchesRule("regex", "^заказ\\s+\\d+$", texts[3]), "regex matches 'заказ 42'")
    assertTrue(!BotMatcher.matchesRule("regex", "[unclosed", texts[3]), "invalid regex safe false")

    val expanded = BotMatcher.expandPlaceholders(
        "Привет, {user} ({username})! Аргумент: {arg1}",
        MessageCtx(
            chatId = 7001L,
            userId = 7001L,
            firstName = "Алиса",
            username = "alice_test",
            text = "/order coffee",
        ),
    )
    assertEq("Привет, Алиса (@alice_test)! Аргумент: coffee", expanded, "placeholder expansion")

    // 3. Проверка импорта демо-бота с кофе для паритета с desktop-web
    val coffeeFile = File(repoRoot, "tools/import-fixtures/demo-bot-coffee.py")
    assertTrue(coffeeFile.exists(), "demo-bot-coffee.py exists")
    val parsedCoffee = ScriptImporter.parse(coffeeFile.readText(Charsets.UTF_8))
    assertTrue(parsedCoffee.commands.size >= 2, "coffee commands imported")
    assertTrue(parsedCoffee.textRules.isNotEmpty(), "coffee text rules imported")
    assertTrue(parsedCoffee.schedule.isNotEmpty(), "coffee schedule imported")

    // 4. Политика безопасности исходящих HTTP-запросов и Telegram Stars
    val allowedDomains = listOf("api.example.com")
    val vipProduct = listOf(DigitalProduct(id = "vip", title = "VIP", priceStars = 50, active = true))
    assertTrue(HttpAllowlistPolicy.isUrlAllowed("https://api.example.com/v1", allowedDomains), "HTTPS allowlisted ok")
    assertTrue(!HttpAllowlistPolicy.isUrlAllowed("http://api.example.com/v1", allowedDomains), "cleartext remote HTTP blocked")
    assertTrue(!HttpAllowlistPolicy.isUrlAllowed("http://169.254.169.254/latest", allowedDomains), "metadata SSRF blocked")
    assertTrue(PaymentPolicy.verifyPreCheckout("XTR", 50, "prod_vip", vipProduct).first, "Stars XTR validation")

    // 5. Проверка network_security_config.xml и AndroidManifest.xml (A10)
    val manifest = File(repoRoot, "android/app/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
    val netCfg = File(repoRoot, "android/app/src/main/res/xml/network_security_config.xml")
    assertTrue(netCfg.exists(), "network_security_config.xml exists")
    assertTrue(!manifest.contains("usesCleartextTraffic=\"true\""), "global cleartext disabled")
    assertTrue(manifest.contains("networkSecurityConfig=\"@xml/network_security_config\""), "manifest wires security config")

    println("STAGE5 OK ($checks checks)")
}
