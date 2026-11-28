import com.botcontrol.admin.data.*
import com.botcontrol.admin.data.local.BotUserEntity
import com.botcontrol.admin.data.telegram.TelegramApiException
import com.botcontrol.admin.data.telegram.TelegramRateLimit

private var s2failed = 0
private fun s2check(name: String, ok: Boolean) {
    println((if (ok) "ok   " else "FAIL ") + name)
    if (!ok) s2failed++
}

fun main() {
    // 1. FsmValidator: все типы шагов + ошибки + ветвление + сводка
    val stepText = FsmStep(id = "s1", prompt = "Имя?", inputType = "text", varName = "name")
    val stepNum = FsmStep(id = "s2", prompt = "Возраст?", inputType = "number", varName = "age")
    val stepPhone = FsmStep(id = "s3", prompt = "Телефон?", inputType = "phone", varName = "phone")
    val stepPhoto = FsmStep(id = "s4", prompt = "Фото?", inputType = "photo", varName = "pic")
    val stepLoc = FsmStep(id = "s5", prompt = "Гео?", inputType = "location", varName = "geo")
    val stepBtn = FsmStep(
        id = "s6",
        prompt = "Доставка?",
        inputType = "button",
        varName = "delivery",
        choices = listOf("Курьер", "Самовывоз"),
        branchMap = mapOf("Самовывоз" to "DONE"),
    )
    val stepAddr = FsmStep(id = "s7", prompt = "Адрес?", inputType = "text", varName = "address")

    s2check("FSM text ok & blank rejected",
        FsmValidator.validate(stepText, "Иван").let { it.ok && it.value == "Иван" } &&
            !FsmValidator.validate(stepText, "   ").ok)
    s2check("FSM number comma & rejection",
        FsmValidator.validate(stepNum, "12,5").let { it.ok && it.value == "12.5" } &&
            !FsmValidator.validate(stepNum, "abc").ok)
    s2check("FSM phone text & contact",
        FsmValidator.validate(stepPhone, "+7 (999) 123-45-67").ok &&
            FsmValidator.validate(stepPhone, "", contactPhone = "+79990001122").let { it.ok && it.value == "+79990001122" } &&
            !FsmValidator.validate(stepPhone, "123").ok)
    s2check("FSM photo accepts file_id & rejects plain text",
        FsmValidator.validate(stepPhoto, "[фото]", kind = "фото", photoId = "AgAC123").let { it.ok && it.value == "AgAC123" } &&
            !FsmValidator.validate(stepPhoto, "нет фото").ok)
    s2check("FSM location lat/lon",
        FsmValidator.validate(stepLoc, "[геолокация]", locationLat = 55.75, locationLon = 37.61).let {
            it.ok && it.value == "55.75,37.61"
        } && !FsmValidator.validate(stepLoc, "Москва").ok)
    s2check("FSM button case-insensitive match",
        FsmValidator.validate(stepBtn, "курьер").let { it.ok && it.value == "Курьер" } &&
            !FsmValidator.validate(stepBtn, "Дрон").ok)

    val flow = FsmFlow(
        id = "f1",
        name = "Заказ",
        steps = listOf(stepText, stepBtn, stepAddr),
        summaryTemplate = "Заказ от {name} ({user}): {delivery}, адрес={address}",
    )
    s2check("FSM ветвление: Курьер -> шаг адреса (idx=2)",
        FsmValidator.nextStepIndex(flow, 1, "Курьер") == 2)
    s2check("FSM ветвление: Самовывоз -> DONE (null)",
        FsmValidator.nextStepIndex(flow, 1, "Самовывоз") == null)

    val ctx = MessageCtx(chatId = 100L, userId = 100L, firstName = "Мария", username = "masha")
    val summary = FsmValidator.buildSummary(
        flow,
        ctx,
        mapOf("name" to "Мария", "delivery" to "Курьер", "address" to "Ленина 10"),
    )
    s2check("FSM summaryTemplate подставляет пользовательские переменные",
        summary == "Заказ от Мария (Мария): Курьер, адрес=Ленина 10")

    // 2. BotMatcher.expandPlaceholders с vars из FSM / базы подписчиков
    val expanded = BotMatcher.expandPlaceholders(
        "Привет, {user}! Город: {city}, тел: {phone}",
        ctx,
        "ShopBot",
        vars = mapOf("city" to "Казань", "phone" to "+79001112233"),
    )
    s2check("BotMatcher.expandPlaceholders раскрывает vars",
        expanded == "Привет, Мария! Город: Казань, тел: +79001112233")

    // 3. AccessPolicy: adminIds, роли, правила, статусы подписки на канал
    val admins = AccessPolicy.parseAdminIds("111, 222\n333; abc")
    s2check("AccessPolicy.parseAdminIds", admins == setOf(111L, 222L, 333L))
    s2check("AccessPolicy.isAdmin по списку и по роли",
        AccessPolicy.isAdmin(111L, 111L, "user", admins) &&
            AccessPolicy.isAdmin(999L, 999L, "admin", emptySet()) &&
            !AccessPolicy.isAdmin(999L, 999L, "vip", admins))

    val rPublic = RuleOptions()
    val rVip = RuleOptions(requiredRole = "vip")
    val rAdmin = RuleOptions(adminOnly = true)
    s2check("AccessPolicy.canUseRule матрица прав",
        AccessPolicy.canUseRule(rPublic, isAdmin = false, userRole = "user") &&
            !AccessPolicy.canUseRule(rVip, isAdmin = false, userRole = "user") &&
            AccessPolicy.canUseRule(rVip, isAdmin = false, userRole = "vip") &&
            AccessPolicy.canUseRule(rVip, isAdmin = true, userRole = "user") &&
            !AccessPolicy.canUseRule(rAdmin, isAdmin = false, userRole = "vip") &&
            AccessPolicy.canUseRule(rAdmin, isAdmin = true, userRole = "user"))

    s2check("AccessPolicy.isMemberSubscribed",
        AccessPolicy.isMemberSubscribed("member") &&
            AccessPolicy.isMemberSubscribed("creator") &&
            AccessPolicy.isMemberSubscribed("administrator") &&
            AccessPolicy.isMemberSubscribed("restricted") &&
            !AccessPolicy.isMemberSubscribed("left") &&
            !AccessPolicy.isMemberSubscribed("kicked") &&
            !AccessPolicy.isMemberSubscribed(""))

    // 4. ScheduleEvent.onceDate + BotJson round-trip
    val ev = ScheduleEvent(id = "e1", hour = 14, minute = 30, text = "Старт!", onceDate = "2026-12-31")
    s2check("ScheduleEvent.onceDate daysLabel", ev.daysLabel() == "разово 2026-12-31")
    val flowsJson = BotJson.save(listOf(flow))
    val flowsBack = BotJson.fsmList(flowsJson)
    s2check("BotJson.fsmList round-trip", flowsBack.size == 1 && flowsBack[0].steps.size == 3)
    val optsBack = BotJson.ruleOptions(BotJson.save(RuleOptions(adminOnly = true, requiredRole = "vip", skipSubCheck = true)))
    s2check("RuleOptions access flags round-trip",
        optsBack.adminOnly && optsBack.requiredRole == "vip" && optsBack.skipSubCheck)

    // 5. TelegramApiException.isBlockedOrForbidden для 403 / 429
    val err403 = TelegramRateLimit.buildHttpError(
        "sendMessage",
        403,
        """{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}""",
    )
    val err429 = TelegramRateLimit.buildHttpError(
        "sendMessage",
        429,
        """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 12"}""",
    )
    s2check("TelegramApiException 403 blocked flag",
        err403.isBlockedOrForbidden && !err403.isRateLimit)
    s2check("TelegramApiException 429 rate-limit flag",
        err429.isRateLimit && err429.retryAfterSec == 12L && !err429.isBlockedOrForbidden)

    // 6. BotUserEntity model sanity (multi-bot isolation by botId)
    val u1 = BotUserEntity(botId = 1L, chatId = 500L, role = "vip", tags = "moscow, client")
    val u2 = BotUserEntity(botId = 2L, chatId = 500L, role = "user", isBanned = true)
    s2check("BotUserEntity изоляция по botId", u1.botId != u2.botId && !u1.isBanned && u2.isBanned)

    println(if (s2failed == 0) "STAGE2 ALL OK" else "STAGE2 FAILED: $s2failed")
    if (s2failed != 0) kotlin.system.exitProcess(1)
}
