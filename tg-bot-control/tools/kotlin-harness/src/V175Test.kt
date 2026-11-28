import com.botcontrol.admin.data.*
import java.io.File

private var vfailed = 0
private fun vcheck(name: String, ok: Boolean) {
    println((if (ok) "ok   " else "FAIL ") + name)
    if (!ok) vfailed++
}

fun main() {
    // 1. I-1 + D-10: oracle-v5.py без def play_animation(...) разбирается чисто и без warnings
    val oracleV5 = ScriptImporter.parse(File("../import-fixtures/oracle-v5.py").readText())
    vcheck("oracle-v5: warnings пусты", oracleV5.warnings.isEmpty())
    vcheck("oracle-v5: 9 правил", oracleV5.rules.size == 9)
    val slotRule = oracleV5.rules.firstOrNull { it.pattern == "🎰 Испытать удачу" }
    vcheck("oracle-v5: слот rule anim", slotRule?.actionType == "anim" && Anim.decode(slotRule.script).preset == "slot")
    val waveRule = oracleV5.rules.firstOrNull { it.pattern == "/wave" }
    vcheck("oracle-v5: /wave anim", waveRule?.actionType == "anim" && Anim.decode(waveRule.script).mono)
    val slideBtn = oracleV5.allButtons.firstOrNull { it.id == "slide_1" }
    vcheck("oracle-v5: slide_1 anim", slideBtn?.action == "anim" && slideBtn.edit && Anim.isPhotoAnim(Anim.decode(slideBtn.script)))

    // 2. I-1 + I-3 + D-10: негативная фикстура oracle-broken-nohelper.py
    val broken = ScriptImporter.parse(File("../import-fixtures/oracle-broken-nohelper.py").readText())
    vcheck("broken: /wave всё равно распознан как anim",
        broken.rules.firstOrNull { it.pattern == "/wave" }?.actionType == "anim")
    val brokenCmd = broken.rules.firstOrNull { it.pattern == "/broken" }
    vcheck("broken: /broken остался пустым text (пойман ImportScreen как skipped)",
        brokenCmd?.actionType == "text" && brokenCmd.text.isBlank())
    vcheck("broken: warning про неизвестный helper у /broken",
        broken.warnings.any { it.contains("/broken") && it.contains("не распознан") })
    vcheck("broken: warning про неизвестный helper у кнопки broken_cb",
        broken.warnings.any { it.contains("broken_cb") && it.contains("не распознано") })

    // 3. I-2: резолв random.choice(PACK) и pack=PACK против библиотеки без определения списка в файле
    val existing = listOf(
        ReplyPack(id = "p_jokes", name = "😂 Шутки", items = listOf("раз", "два"), ownerBotId = 1L),
        ReplyPack(id = "pack_slot_prizes", name = "Призы", items = listOf("💎"), ownerBotId = 0L),
    )
    val snippet = """
        @bot.message_handler(commands=["start"])
        def cmd_start(msg):
            bot.send_message(msg.chat.id, "Привет", reply_markup=kb())

        def kb():
            m = InlineKeyboardMarkup()
            m.row(InlineKeyboardButton("Шутка", callback_data="j"))
            return m

        @bot.message_handler(commands=["joke"])
        def cmd_joke(msg):
            bot.send_message(msg.chat.id, random.choice(JOKES))

        @bot.message_handler(commands=["slot"])
        def cmd_slot(msg):
            play_animation(msg.chat.id, "slot", delay=0.5, pack=SLOT_PRIZES)

        @bot.message_handler(commands=["leak"])
        def cmd_leak(msg):
            bot.send_message(msg.chat.id, random.choice(FOREIGN_SECRET))

        @bot.callback_query_handler(func=lambda c: True)
        def on_cb(c):
            if c.data == "j":
                bot.edit_message_text(random.choice(JOKES), c.message.chat.id, c.message.message_id)
    """.trimIndent()
    val libParsed = ScriptImporter.parse(snippet, existing)
    vcheck("I-2: /joke резолвится в lib:p_jokes",
        libParsed.rules.firstOrNull { it.pattern == "/joke" }?.let {
            it.actionType == "pack" && it.packId == "lib:p_jokes"
        } == true)
    vcheck("I-2: slot pack=SLOT_PRIZES резолвится в lib:pack_slot_prizes",
        libParsed.rules.firstOrNull { it.pattern == "/slot" }?.let {
            Anim.decode(it.script).packId == "lib:pack_slot_prizes"
        } == true)
    vcheck("I-2: inline кнопка j резолвится в lib:p_jokes",
        libParsed.allButtons.firstOrNull { it.id == "j" }?.let {
            it.action == "pack" && it.packId == "lib:p_jokes"
        } == true)
    vcheck("I-2: библиотечное замечание ℹ️ есть",
        libParsed.warnings.any { it.contains("взят из библиотеки") })
    vcheck("I-2: чужой/отсутствующий FOREIGN_SECRET не подхватывается",
        libParsed.rules.firstOrNull { it.pattern == "/leak" }?.actionType != "pack" &&
            libParsed.warnings.any { it.contains("cmd_leak") })

    // 4. I-5: явные предупреждения при обрезках и выходе за лимиты
    val clarify55 = (1..55).joinToString(",\n") { "    \"q$it\"" }
    val pack505 = (1..505).joinToString(",\n") { "    \"item$it\"" }
    val kb13 = (1..13).joinToString(", ") { i ->
        val lbl = if (i == 1) "Очень длинная кнопка клавиатуры сверх 32 символов!!" else "B$i"
        "KeyboardButton(\"$lbl\")"
    }
    val inline13 = (1..13).joinToString("\n") { i ->
        val lbl = if (i == 1) "Очень длинная inline-кнопка ".repeat(3) else "I$i"
        "    m.row(InlineKeyboardButton(\"$lbl\", callback_data=\"c$i\"))"
    }
    val cmds22 = (1..22).joinToString(",\n") { "    BotCommand(\"c$it\", \"d$it\")" }
    val bigPrompt = "А".repeat(16050)
    val truncCode = listOf(
        "SYSTEM_PROMPT = \"$bigPrompt\"",
        "CLARIFY_QUESTIONS = [",
        clarify55,
        "]",
        "BIG_PACK = [",
        pack505,
        "]",
        "ANIM_TEN = [",
        "    \"f1\", \"f2\", \"f3\", \"f4\", \"f5\", \"f6\", \"f7\", \"f8\", \"f9\", \"f10\"",
        "]",
        "def chat_keyboard():",
        "    m = ReplyKeyboardMarkup()",
        "    m.row($kb13)",
        "    return m",
        "def big_kb():",
        "    m = InlineKeyboardMarkup()",
        inline13,
        "    return m",
        "@bot.message_handler(commands=[\"start\"])",
        "def cmd_start(msg):",
        "    bot.send_message(msg.chat.id, \"hi\", reply_markup=big_kb())",
        "@bot.message_handler(commands=[\"anim\"])",
        "def cmd_anim(msg):",
        "    play_animation(msg.chat.id, ANIM_TEN, delay=0.1, loops=15)",
        "bot.set_my_commands([",
        cmds22,
        "])",
    ).joinToString("\n")
    val tp = ScriptImporter.parse(truncCode)
    vcheck("I-5: SYSTEM_PROMPT warning", tp.warnings.any { it.contains("SYSTEM_PROMPT") && it.contains("16000") })
    vcheck("I-5: CLARIFY обрезан до 50 + warning", tp.clarify.size == 50 && tp.warnings.any { it.contains("CLARIFY_QUESTIONS") })
    vcheck("I-5: pack обрезан до 500 + warning", tp.packs.firstOrNull()?.items?.size == 500 && tp.warnings.any { it.contains("BIG_PACK") })
    vcheck("I-5: keyboard warning (кол-во и длина)", tp.warnings.any { it.contains("Клавиатура") && it.contains("12") } &&
        tp.warnings.any { it.contains("Кнопка клавиатуры") && it.contains("32") })
    vcheck("I-5: inline label > 64 и menu > 12", tp.warnings.any { it.contains("64") } &&
        tp.warnings.any { it.contains("кнопок 13") })
    vcheck("I-5: анимация delay / loops / MAX_FRAMES", tp.warnings.any { it.contains("0.5–3.0") } &&
        tp.warnings.any { it.contains("1–10") } && tp.warnings.any { it.contains("60") })
    vcheck("I-5: set_my_commands обрезан до 20 + warning", tp.menuCommands.size == 20 &&
        tp.warnings.any { it.contains("20") })

    // 5. U-1: ScheduleEvent.pickVariant(dayOfYear) для личных напоминаний и каналов
    val single = ScheduleEvent(hour = 9, minute = 0, text = "Один вариант", toChannel = false)
    val rotated = ScheduleEvent(hour = 9, minute = 0, text = "А | Б |  | В", toChannel = false)
    vcheck("U-1: одиночный текст", single.pickVariant(0) == "Один вариант" && single.pickVariant(42) == "Один вариант")
    vcheck("U-1: ротация по dayOfYear", rotated.pickVariant(0) == "А" && rotated.pickVariant(1) == "Б" &&
        rotated.pickVariant(2) == "В" && rotated.pickVariant(3) == "А")

    println(if (vfailed == 0) "V175 ALL OK" else "V175 FAILED: $vfailed")
    if (vfailed != 0) kotlin.system.exitProcess(1)
}
