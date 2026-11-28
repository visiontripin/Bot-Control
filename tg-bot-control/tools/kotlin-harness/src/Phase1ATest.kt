import com.botcontrol.admin.data.*
import com.botcontrol.admin.data.local.BotRuleEntity
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

private var pfailed = 0
private fun pcheck(name: String, ok: Boolean) {
    println((if (ok) "ok   " else "FAIL ") + name)
    if (!ok) pfailed++
}

private fun <T> runBlockingStub(block: suspend () -> T): T {
    var res: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { res = it })
    return res!!.getOrThrow()
}

fun main() {
    // 1. I-4: рекурсивные ID и поиск вложенных подменю
    val nested = listOf(
        InlineBtn(
            label = "Каталог",
            action = "submenu",
            text = "Выберите категорию",
            submenu = listOf(
                InlineBtn(
                    label = "Игры",
                    action = "submenu",
                    submenu = listOf(
                        InlineBtn(label = "RPG", action = "text", text = "Список RPG"),
                    ),
                ),
                InlineBtn(label = "О нас", action = "text", text = "Инфо"),
            ),
        ),
        InlineBtn(label = "Сайт", action = "web_app", url = "https://example.com/app"),
    ).withIds()
    val catBtn = nested[0]
    val gamesBtn = catBtn.submenu[0]
    val rpgBtn = gamesBtn.submenu[0]
    pcheck("I-4: id присвоены на всех 3 уровнях",
        catBtn.id.isNotBlank() && gamesBtn.id.isNotBlank() && rpgBtn.id.isNotBlank())
    pcheck("I-4: id уникальны между уровнями",
        setOf(catBtn.id, gamesBtn.id, rpgBtn.id, nested[1].id).size == 4)
    val foundRpg = nested.findButtonRecursive(rpgBtn.id)
    pcheck("I-4: findButtonRecursive находит кнопку 3-го уровня и её меню",
        foundRpg?.first?.label == "RPG" && foundRpg.second.size == 1 && foundRpg.second[0].label == "RPG")

    // 2. U-2: ShuffleBag (все элементы без повтора до конца круга + изоляция по botId)
    val bagItems = listOf("А", "Б", "В", "Г")
    val round1 = (1..4).map { ShuffleBag.next(101L, "pack_1", bagItems) }
    val round2 = (1..4).map { ShuffleBag.next(101L, "pack_1", bagItems) }
    pcheck("U-2: за первый круг все 4 разных элемента", round1.toSet() == bagItems.toSet())
    pcheck("U-2: за второй круг снова все 4 элемента", round2.toSet() == bagItems.toSet())
    val otherBot = ShuffleBag.next(202L, "pack_1", bagItems)
    pcheck("U-2: отдельный botId имеет независимую корзину", otherBot in bagItems)

    // 3. U-3: ScheduleJitter (детерминирован, в пределах суток, 0 = точное время)
    val exact = ScheduleJitter.effectiveMinute(9, 30, "ev1", 2026, 100, 0)
    pcheck("U-3: jitter=0 возвращает точное время", exact == 9 * 60 + 30)
    val j1 = ScheduleJitter.effectiveMinute(0, 2, "ev_midnight", 2026, 10, 15)
    val j2 = ScheduleJitter.effectiveMinute(23, 58, "ev_late", 2026, 11, 15)
    pcheck("U-3: jitter не выходит за 0..1439", j1 in 0..1439 && j2 in 0..1439)
    val spread = (1..20).map { d -> ScheduleJitter.effectiveMinute(12, 0, "ev_spread", 2026, d, 10) }.toSet()
    pcheck("U-3: jitter ±10 меняет минуту по дням в пределах 11:50..12:10",
        spread.size > 1 && spread.all { it in (12 * 60 - 10)..(12 * 60 + 10) })

    // 4. BotMatcher: regex, wholeWord, команды, аргументы и подстановки
    pcheck("Matcher regex валидный",
        BotMatcher.matchesRule("regex", """^заказ\s+#?\d+$""", "Заказ #42") &&
            !BotMatcher.matchesRule("regex", """^заказ\s+#?\d+$""", "мой заказ без номера"))
    pcheck("Matcher regex невалидный не падает",
        !BotMatcher.matchesRule("regex", "[незакрыто", "тест"))
    pcheck("Matcher wholeWord=true отделяет целые слова",
        BotMatcher.matchesRule("contains", "кот", "Где мой кот?", wholeWord = true) &&
            !BotMatcher.matchesRule("contains", "кот", "Это котлета", wholeWord = true) &&
            !BotMatcher.matchesRule("contains", "кот", "Огромный скот", wholeWord = true))
    pcheck("Matcher command с @username и аргументами",
        BotMatcher.matchesRule("command", "/ask", "/ask@MyBot что нового") &&
            BotMatcher.extractCommandArgs("/ask@MyBot раз два") == listOf("раз", "два") &&
            BotMatcher.extractCommandArgsRaw("/ask привет мир") == "привет мир")
    val ctx = MessageCtx(
        chatId = 555L, userId = 777L, firstName = "Анна", username = "anna_dev",
        chatType = "private", text = "/order 42 VIP",
    )
    val expanded = BotMatcher.expandPlaceholders(
        "{user} ({username}, id={user_id}/{userId}, чат={chat_id}): {arg1}/{arg2} / бот={bot}", ctx, "ShopBot")
    pcheck("Matcher expandPlaceholders подставляет весь контекст",
        expanded == "Анна (@anna_dev, id=777/777, чат=555): 42/VIP / бот=@ShopBot")

    // 5. RuleOptions JSON + ScriptImporter + SettingsExporter round-trip всех фич Phase 1A
    val samplePy = """
        @bot.message_handler(commands=["start"])
        def cmd_start(msg):
            bot.send_message(
                msg.chat.id,
                "<b>Добро пожаловать</b>, {user}!",
                parse_mode="HTML",
                disable_web_page_preview=True,
                disable_notification=True,
                protect_content=True,
                reply_to_message_id=msg.message_id,
                reply_markup=kb_main(),
            )

        @bot.message_handler(regexp=r"^заказ\s+#?\d+${'$'}")
        def rx_order(msg):
            bot.send_message(msg.chat.id, "Ищу заказ", parse_mode="MarkdownV2")

        @bot.message_handler(commands=["sticker"])
        def cmd_sticker(msg):
            bot.send_sticker(msg.chat.id, "CAACAgIAAxkBAAI123")

        @bot.message_handler(commands=["poll"])
        def cmd_poll(msg):
            bot.send_poll(msg.chat.id, "Какой язык лучше?", ["Kotlin", "Python", "JS"],
                          is_anonymous=False, allows_multiple_answers=True)

        @bot.message_handler(commands=["hidekb"])
        def cmd_hidekb(msg):
            bot.send_message(msg.chat.id, "Клавиатура скрыта", reply_markup=ReplyKeyboardRemove())

        def chat_kb():
            kb = ReplyKeyboardMarkup(resize_keyboard=True, one_time_keyboard=True,
                                     input_field_placeholder="Выбери кнопку…")
            kb.row(KeyboardButton("Кнопка 1"), KeyboardButton("Кнопка 2"), KeyboardButton("Кнопка 3"))
            kb.row(KeyboardButton("Поделиться телефоном", request_contact=True),
                   KeyboardButton("Геопозиция", request_location=True))
            return kb

        @bot.message_handler(commands=["kb"])
        def cmd_kb(msg):
            bot.send_message(msg.chat.id, "Клавиатура:", reply_markup=chat_kb())

        def kb_main():
            m = InlineKeyboardMarkup()
            m.row(
                InlineKeyboardButton("Раздел", callback_data="sub_open"),
                InlineKeyboardButton("Mini App", web_app=WebAppInfo(url="https://example.org/app")),
            )
            m.row(
                InlineKeyboardButton("Копировать код", copy_text=CopyTextButton(text="PROMO2026")),
                InlineKeyboardButton("Поиск", switch_inline_query_current_chat="cat "),
                InlineKeyboardButton("Спросить ИИ", callback_data="ask_btn"),
            )
            return m

        def kb_sub():
            m = InlineKeyboardMarkup()
            m.row(InlineKeyboardButton("Внутри", callback_data="inside_ok"))
            return m

        @bot.callback_query_handler(func=lambda call: True)
        def on_cb(call):
            if call.data == "sub_open":
                bot.edit_message_text("<b>Раздел</b>", call.message.chat.id, call.message.message_id,
                                      parse_mode="HTML", reply_markup=kb_sub())
            elif call.data == "inside_ok":
                bot.answer_callback_query(call.id, "Ок")
                bot.send_message(call.message.chat.id, "Готово!")
            elif call.data == "ask_btn":
                bot.send_message(call.message.chat.id, ask_llm(call.message.chat.id, call.message.text))

        bot.set_my_commands([BotCommand("start", "Старт"), BotCommand("poll", "Опрос")],
                            scope=BotCommandScopeAllGroupChats(), language_code="ru")
    """.trimIndent()

    val p1 = ScriptImporter.parse(samplePy)
    pcheck("Phase 1A import: без warnings", p1.warnings.isEmpty())
    val rStart = p1.rules.firstOrNull { it.pattern == "/start" }
    pcheck("Phase 1A import: /start parseMode/flags",
        rStart != null && rStart.options.parseMode == "HTML" &&
            rStart.options.disablePreview && rStart.options.silent &&
            rStart.options.protectContent && rStart.options.replyTo)
    val rRx = p1.rules.firstOrNull { it.type == "regex" }
    pcheck("Phase 1A import: regex правило и MarkdownV2",
        rRx != null && rRx.pattern == """^заказ\s+#?\d+$""" && rRx.options.parseMode == "MarkdownV2")
    val rSticker = p1.rules.firstOrNull { it.pattern == "/sticker" }
    pcheck("Phase 1A import: media sticker",
        rSticker?.actionType == "media" && rSticker.options.mediaType == "sticker" &&
            rSticker.options.mediaRef == "CAACAgIAAxkBAAI123")
    val rPoll = p1.rules.firstOrNull { it.pattern == "/poll" }
    pcheck("Phase 1A import: poll + options",
        rPoll?.actionType == "poll" && rPoll.options.pollQuestion == "Какой язык лучше?" &&
            rPoll.options.pollOptions == listOf("Kotlin", "Python", "JS") &&
            !rPoll.options.pollAnonymous && rPoll.options.pollMultiple)
    val startMenu = rStart?.menu.orEmpty()
    val subBtn = startMenu.firstOrNull { it.id == "sub_open" }
    pcheck("Phase 1A import: I-4 вложенное подменю",
        subBtn?.action == "submenu" && subBtn.parseMode == "HTML" &&
            subBtn.submenu.singleOrNull()?.id == "inside_ok")
    pcheck("Phase 1A import: web_app / copy_text / switch_inline / llm кнопки",
        startMenu.any { it.action == "web_app" && it.url == "https://example.org/app" } &&
            startMenu.any { it.action == "copy_text" && it.text == "PROMO2026" } &&
            startMenu.any { it.action == "switch_inline" && it.text == "cat " } &&
            startMenu.any { it.id == "ask_btn" && it.action == "llm" })
    pcheck("Phase 1A import: scoped menuCommands",
        p1.menuCommands.size == 2 && p1.menuCommands.all {
            it.scope == "all_group_chats" && it.languageCode == "ru"
        })
    pcheck("Phase 1A import: KeyboardOptions + ReplyKeyboardRemove",
        p1.keyboardRows.flatten() == listOf("Кнопка 1", "Кнопка 2", "Кнопка 3") &&
            p1.keyboardOptions.columns == 3 && p1.keyboardOptions.oneTime &&
            p1.keyboardOptions.placeholder == "Выбери кнопку…" &&
            p1.keyboardOptions.contactButton == "Поделиться телефоном" &&
            p1.keyboardOptions.locationButton == "Геопозиция" &&
            p1.rules.firstOrNull { it.pattern == "/hidekb" }?.options?.removeKeyboard == true)

    // Round-trip через SettingsExporter -> ScriptImporter
    val store = LocalBotStore()
    store.keyboardList = p1.keyboardRows.flatten()
    store.kbOptions = p1.keyboardOptions
    val repo = BotRepository()
    repo.rules = p1.rules.mapIndexed { idx, r ->
        BotRuleEntity(
            id = idx + 1,
            type = r.type,
            pattern = r.pattern,
            actionType = r.actionType,
            responseText = r.text,
            packId = r.packId,
            script = r.script,
            menu = BotJson.save(r.menu.withIds()),
            options = BotJson.save(r.options),
            botId = 3L,
        )
    }
    val exported = runBlockingStub { SettingsExporter.build(store, repo, 3L) }
    val p2 = ScriptImporter.parse(exported)
    pcheck("Phase 1A round-trip: все правила сохранились", p2.rules.size == p1.rules.size)
    val rtStart = p2.rules.firstOrNull { it.pattern == "/start" }
    pcheck("Phase 1A round-trip: parseMode/flags/подменю/web_app/copy_text/llm сохранились",
        rtStart != null && rtStart.options.parseMode == "HTML" &&
            rtStart.options.disablePreview && rtStart.options.silent &&
            rtStart.options.protectContent && rtStart.options.replyTo &&
            rtStart.menu.firstOrNull { it.id == "sub_open" }?.let {
                it.action == "submenu" && it.submenu.singleOrNull()?.id == "inside_ok"
            } == true &&
            rtStart.menu.any { it.action == "web_app" && it.url == "https://example.org/app" } &&
            rtStart.menu.any { it.action == "copy_text" && it.text == "PROMO2026" } &&
            rtStart.menu.any { it.action == "switch_inline" && it.text == "cat " } &&
            rtStart.menu.any { it.id == "ask_btn" && it.action == "llm" })
    pcheck("Phase 1A round-trip: regex, sticker и poll сохранились",
        p2.rules.any { it.type == "regex" && it.options.parseMode == "MarkdownV2" } &&
            p2.rules.any { it.actionType == "media" && it.options.mediaType == "sticker" } &&
            p2.rules.any { it.actionType == "poll" && it.options.pollOptions.size == 3 && !it.options.pollAnonymous && it.options.pollMultiple })
    pcheck("Phase 1A round-trip: KeyboardOptions + ReplyKeyboardRemove сохранились",
        p2.keyboardRows.flatten() == listOf("Кнопка 1", "Кнопка 2", "Кнопка 3") &&
            p2.keyboardOptions.columns == 3 && p2.keyboardOptions.oneTime &&
            p2.keyboardOptions.placeholder == "Выбери кнопку…" &&
            p2.keyboardOptions.contactButton == "Поделиться телефоном" &&
            p2.keyboardOptions.locationButton == "Геопозиция" &&
            p2.rules.firstOrNull { it.pattern == "/hidekb" }?.options?.removeKeyboard == true)

    println(if (pfailed == 0) "PHASE1A ALL OK" else "PHASE1A FAILED: $pfailed")
    if (pfailed != 0) kotlin.system.exitProcess(1)
}
