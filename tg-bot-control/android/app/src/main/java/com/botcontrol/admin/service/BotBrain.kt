package com.botcontrol.admin.service

import android.content.Context
import com.botcontrol.admin.BotControlApp
import com.botcontrol.admin.data.AccessPolicy
import com.botcontrol.admin.data.Anim
import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.BotMatcher
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.MessageCtx
import com.botcontrol.admin.data.ModerationPolicy
import com.botcontrol.admin.data.ReplyPack
import com.botcontrol.admin.data.RuleOptions
import com.botcontrol.admin.data.ShuffleBag
import com.botcontrol.admin.data.withIds
import com.botcontrol.admin.llm.BotScript
import com.botcontrol.admin.llm.ChatMemory
import com.botcontrol.admin.llm.DeviceLlm
import com.botcontrol.admin.llm.DialogPrompt
import com.botcontrol.admin.llm.ScriptDbSession

/** Результат «размышлений» бота над сообщением. */
data class BotDecision(
    val source: String,   // правило / скрипт / набор / ИИ / уточняющий вопрос / запасной ответ / кулдаун
    val reply: String,    // текст ответа или подпись (пусто = молчать)
    val menu: List<InlineBtn> = emptyList(),
    /** Анимация правкой сообщения (правило с действием «Анимация»). */
    val anim: com.botcontrol.admin.data.AnimSpec? = null,
    /** Кубик Telegram (sendDice): 🎲 🎯 🏀 ⚽ 🎳 🎰. */
    val dice: String = "",
    /** Опции доставки (parseMode, link preview, silent, protectContent, replyTo…). */
    val options: RuleOptions = RuleOptions(),
    val replyToMessageId: Int? = null,
    /** Для actionType == "media" (U-4): тип (sticker/animation/document/photo/video/voice/audio) и file_id/URL. */
    val mediaType: String = "",
    val mediaRef: String = "",
    /** Для actionType == "poll": вопрос и варианты. */
    val pollQuestion: String = "",
    val pollOptions: List<String> = emptyList(),
    val pollAnonymous: Boolean = true,
    val pollMultiple: Boolean = false,
)

/**
 * Единый «мозг» бота. Порядок:
 *  1) правила (текст / скрипт JS / набор / анимация / кубик / медиа / опрос — всегда КОД, без ИИ);
 *  2) сценарий ИИ — только явно: команда /chat (+ вопрос) или правило с
 *     действием «ИИ»; ИИ по умолчанию выключен и никогда не отвечает
 *     «на всё» — функции бота выполняются исполнением кода;
 *  3) антифлуд;
 *  4) кодовый запасной ответ (уточняющий вопрос / default).
 * Используется и сервисом (реальный Telegram), и вкладкой имитации —
 * поведение в тесте и в бою гарантированно одинаковое.
 */
object BotBrain {

    // «botId:chatId» -> epoch sec. В личке chatId = id пользователя и совпадает
    // у всех ботов — без botId кулдаун одного бота глушил другие.
    private val lastAnswerAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Проблема ИИ висит в статусе бота до первого успешного ответа. */
    private fun addLlmProblem(botId: Long, text: String) {
        val cur = LocalBotService.states.value[botId] ?: return
        if (cur.problems["llm"] != text) {
            LocalBotService.states.value = LocalBotService.states.value +
                (botId to cur.copy(problems = cur.problems + ("llm" to text)))
        }
    }

    private fun resolveLlmProblem(botId: Long) {
        val cur = LocalBotService.states.value[botId] ?: return
        if ("llm" in cur.problems) {
            LocalBotService.states.value = LocalBotService.states.value +
                (botId to cur.copy(problems = cur.problems - "llm"))
        }
    }

    /** Параметры генерации из настроек конкретного бота. */
    private suspend fun engineParams(store: LocalBotStore, botId: Long): com.botcontrol.admin.llm.EngineParams =
        com.botcontrol.admin.llm.EngineParams(
            temperature = store.aiTemperature(botId),
            topK = store.aiTopK(botId),
            maxTokens = store.aiMaxTokens(botId),
        )

    fun parseMenu(json: String): List<InlineBtn> =
        runCatching { BotJson.menu(json) }.getOrDefault(emptyList()).withIds()

    fun randomFrom(packs: List<ReplyPack>, packId: String, botId: Long = 0L): String {
        val pack = packs.firstOrNull { it.id == packId }
            ?: packs.firstOrNull { it.name == packId }
            ?: packs.firstOrNull { it.id.contains(packId) || packId.contains(it.id, ignoreCase = true) }
            ?: packs.firstOrNull { p ->
                p.items.isNotEmpty() && packId.isNotBlank() && p.name.contains(packId, ignoreCase = true)
            }
        if (pack == null || pack.items.isEmpty()) {
            return "Набор пуст — добавь ответы в «🎲 Наборы ответов»"
        }
        return if (pack.shuffleBag) ShuffleBag.next(botId, pack.id, pack.items)
        else pack.items.random()
    }

    /** Запуск JS-скрипта с загрузкой и сохранением персистентного `db` в Room. */
    suspend fun runScriptWithDb(
        app: BotControlApp,
        botId: Long,
        source: String,
        user: String,
        text: String,
        chatId: Long,
        ctx: MessageCtx,
    ): Result<String> {
        val uid = if (ctx.userId != 0L) ctx.userId else chatId
        val userScope = "u:$uid"
        val userInit = app.repository.kvList(botId, userScope)
        val globalInit = app.repository.kvList(botId, "global")
        val dbSession = ScriptDbSession(userInit, globalInit)
        val res = BotScript.run(source, user, text, chatId, ctx, dbSession)
        dbSession.userWrites.forEach { (k, v) -> app.repository.kvSet(botId, userScope, k, v) }
        dbSession.userDeletes.forEach { k -> app.repository.kvDelete(botId, userScope, k) }
        dbSession.globalWrites.forEach { (k, v) -> app.repository.kvSet(botId, "global", k, v) }
        dbSession.globalDeletes.forEach { k -> app.repository.kvDelete(botId, "global", k) }
        return res
    }

    suspend fun decide(
        context: Context,
        store: LocalBotStore,
        botId: Long,
        chatId: Long,
        firstName: String,
        text: String,
        respectCooldown: Boolean = true,
        onLlmStart: (suspend () -> Unit)? = null,
        trace: ((String) -> Unit)? = null,
        ctx: MessageCtx? = null,
    ): BotDecision {
        val app = context.applicationContext as BotControlApp
        val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()
        val msgCtx = ctx ?: MessageCtx(chatId = chatId, firstName = firstName, text = text)
        val dbUser = app.repository.botUser(botId, chatId)
        val adminIds = store.adminIds(botId)
        val userRole = dbUser?.role ?: "user"
        val isAdmin = AccessPolicy.isAdmin(msgCtx.userId, chatId, userRole, adminIds)
        val userVars = BotJson.stringMap(dbUser?.varsJson.orEmpty())

        if (dbUser?.isBanned == true && !isAdmin) {
            trace?.invoke("⛔ Пользователь в бане (ban=true) — ответ заблокирован")
            return BotDecision("бан", "")
        }

        // 1) декларативные правила
        val rules = app.repository.botRules(botId).filter { it.enabled }
        trace?.invoke("Проверяю правила (${rules.size})…")
        val packs = store.packs()
        var deniedCommandRule: RuleOptions? = null
        for (rule in rules) {
            val opts = BotJson.ruleOptions(rule.options)
            if (!ModerationPolicy.matchesChatScope(opts.chatScope, msgCtx.chatType)) {
                trace?.invoke("Пропускаю правило «${rule.pattern}» (область: ${opts.chatScope}, чат: ${msgCtx.chatType.ifBlank { "private" }})")
                continue
            }
            val matched = BotMatcher.matchesRule(rule.type, rule.pattern, text, opts.wholeWord)
            if (!matched) continue
            if (!AccessPolicy.canUseRule(opts, isAdmin, userRole)) {
                trace?.invoke("⛔ Правило «${rule.pattern}» пропущено (нет прав: adminOnly=${opts.adminOnly}, роль=${opts.requiredRole.ifBlank { "—" }})")
                if (rule.type == "command" && deniedCommandRule == null) deniedCommandRule = opts
                continue
            }
            val replyToId = if (opts.replyTo && msgCtx.messageId > 0) msgCtx.messageId else null
            trace?.invoke("✓ Сработало правило «${rule.pattern}» (тип: ${rule.type})")
            return when (rule.actionType) {
                "llm" -> {
                    trace?.invoke("Действие правила: отвечает ИИ")
                    val q = if (rule.type == "command") {
                        BotMatcher.extractCommandArgsRaw(text).ifBlank { text }
                    } else text
                    llmReply(context, store, botId, chatId, firstName, q, onLlmStart, trace)
                        .copy(options = opts, replyToMessageId = replyToId)
                }
                "script" -> {
                    trace?.invoke("Действие правила: скрипт JS")
                    runScriptWithDb(app, botId, rule.script, firstName, text, chatId, msgCtx).fold(
                        onSuccess = {
                            trace?.invoke("Скрипт выполнен")
                            BotDecision(
                                source = "скрипт «${rule.pattern}»",
                                reply = it.ifBlank { "…" },
                                menu = parseMenu(rule.menu),
                                options = opts,
                                replyToMessageId = replyToId,
                            )
                        },
                        onFailure = {
                            trace?.invoke("Ошибка скрипта: ${it.message?.take(100)}")
                            BotDecision(
                                source = "скрипт «${rule.pattern}» (ошибка)",
                                reply = "Ошибка скрипта: ${it.message?.take(140)}",
                                menu = parseMenu(rule.menu),
                                options = opts,
                                replyToMessageId = replyToId,
                            )
                        },
                    )
                }
                "anim" -> {
                    val spec = Anim.decode(rule.script)
                    trace?.invoke("Действие правила: анимация «${Anim.preset(spec.preset).title}»")
                    val items = packs.firstOrNull { it.id == spec.packId }?.items.orEmpty()
                    val final = Anim.final(spec, firstName, items)
                    BotDecision(
                        source = "анимация «${rule.pattern}»",
                        reply = final.ifBlank { Anim.frames(spec, firstName).lastOrNull().orEmpty() }.ifBlank { "🎞" },
                        menu = parseMenu(rule.menu),
                        anim = spec,
                        options = opts,
                        replyToMessageId = replyToId,
                    )
                }
                "dice" -> {
                    val emoji = rule.responseText.trim().ifBlank { "🎲" }
                    trace?.invoke("Действие правила: кубик Telegram $emoji")
                    BotDecision("кубик «${rule.pattern}»", emoji, parseMenu(rule.menu), dice = emoji,
                        options = opts, replyToMessageId = replyToId)
                }
                "media" -> {
                    val mType = opts.mediaType.ifBlank { "photo" }
                    val mRef = opts.mediaRef.ifBlank { rule.script.trim() }
                    val caption = BotMatcher.expandPlaceholders(rule.responseText, msgCtx, botName, vars = userVars)
                    trace?.invoke("Действие правила: медиа ($mType)")
                    BotDecision(
                        source = "медиа ($mType) «${rule.pattern}»",
                        reply = caption,
                        menu = parseMenu(rule.menu),
                        options = opts,
                        replyToMessageId = replyToId,
                        mediaType = mType,
                        mediaRef = mRef,
                    )
                }
                "poll" -> {
                    val q = BotMatcher.expandPlaceholders(
                        opts.pollQuestion.ifBlank { rule.responseText }, msgCtx, botName, vars = userVars)
                    trace?.invoke("Действие правила: опрос «${q.take(40)}»")
                    BotDecision(
                        source = "опрос «${rule.pattern}»",
                        reply = q,
                        menu = parseMenu(rule.menu),
                        options = opts,
                        replyToMessageId = replyToId,
                        pollQuestion = q,
                        pollOptions = opts.pollOptions,
                        pollAnonymous = opts.pollAnonymous,
                        pollMultiple = opts.pollMultiple,
                    )
                }
                "pack" -> {
                    val raw = randomFrom(packs, rule.packId, botId)
                    val reply = BotMatcher.expandPlaceholders(raw, msgCtx, botName, vars = userVars)
                    trace?.invoke("Действие правила: случайное из набора")
                    BotDecision(
                        source = "набор «${packs.firstOrNull { it.id == rule.packId }?.name ?: rule.packId}»",
                        reply = reply,
                        menu = parseMenu(rule.menu),
                        options = opts,
                        replyToMessageId = replyToId,
                    )
                }
                else -> BotDecision(
                    source = "правило «${rule.pattern}»",
                    reply = BotMatcher.expandPlaceholders(rule.responseText, msgCtx, botName, vars = userVars),
                    menu = parseMenu(rule.menu),
                    options = opts,
                    replyToMessageId = replyToId,
                )
            }
        }
        if (deniedCommandRule != null) {
            val req = deniedCommandRule.requiredRole
            val why = if (deniedCommandRule.adminOnly) "только для администраторов"
            else "требуется роль «$req»"
            return BotDecision("доступ запрещён", "⛔ Недостаточно прав ($why).")
        }
        trace?.invoke("Ни одно правило не подошло")

        // 1.1) Нажата кнопка клавиатуры бота, а правила для неё нет.
        val kbHit = store.keyboard(botId)
            .firstOrNull { it.trim().equals(text.trim(), ignoreCase = true) }
        if (kbHit != null) {
            trace?.invoke("🔘 Это кнопка клавиатуры «$kbHit», но правила для неё нет")
            return BotDecision(
                "кнопка без действия",
                "🔘 У кнопки «$kbHit» пока нет действия.\n\n" +
                    "Задай его: «Сценарии → Правила ответов» → «+ Правило» → " +
                    "тип «Кнопка», образец «$kbHit», действие — текст, набор ответов или скрипт.",
            )
        }

        // 2) Явная команда «ИИ-сценарий»: /chat (или /ai) + вопрос.
        val aiCommand = Regex("^/(chat|ai)(@\\w+)?\\s*(.*)$", RegexOption.IGNORE_CASE).find(text)
        if (aiCommand != null) {
            val question = aiCommand.groupValues[3].trim()
            return if (question.isBlank()) {
                BotDecision("ИИ-сценарий",
                    "Спроси так: /chat + вопрос. ИИ отвечает только в этом сценарии " +
                        "и по правилам с действием «ИИ» — всё остальное бот делает кодом.")
            } else if (!store.llmEnabled(botId)) {
                BotDecision("ИИ-сценарий",
                    "🤖 ИИ выключен: включи в «Сценарии → Правила ответов» (переключатель «ИИ»). " +
                        "Остальные функции работают без ИИ.")
            } else if (store.aiModel(botId).isBlank()) {
                BotDecision("ИИ-сценарий",
                    "🤖 ИИ не настроен: «ИИ → Модели ИИ» → скачай модель и выбери её.")
            } else {
                trace?.invoke("🤖 Сценарий /chat — спрашиваю ИИ…")
                llmReply(context, store, botId, chatId, firstName, question, onLlmStart, trace)
            }
        }

        // 3) Антифлуд на «незнакомых» сообщениях
        val cd = store.cooldownSec(botId).toLong()
        if (respectCooldown && cd > 0) {
            val now = System.currentTimeMillis() / 1000
            val last = lastAnswerAt["$botId:$chatId"] ?: 0L
            if (now - last < cd) {
                trace?.invoke("⏳ Кулдаун ${cd} с — молчу")
                return BotDecision("кулдаун", "")
            }
            lastAnswerAt["$botId:$chatId"] = now
        }

        // 4) Кодовый запасной ответ (уточняющий вопрос или default).
        return fallback(store, botId)
    }

    /** Вызов локальной ИИ по нажатию inline-кнопки с action == "llm". */
    suspend fun askLlmFromButton(
        context: Context,
        store: LocalBotStore,
        botId: Long,
        chatId: Long,
        firstName: String,
        promptText: String,
        onLlmStart: (suspend () -> Unit)? = null,
    ): BotDecision {
        if (!store.llmEnabled(botId)) {
            return BotDecision("ИИ (выключен)", "🤖 ИИ выключен в настройках бота.")
        }
        if (store.aiModel(botId).isBlank()) {
            return BotDecision("ИИ (нет модели)", "🤖 Модель ИИ не выбрана в «ИИ → Модели ИИ».")
        }
        return llmReply(context, store, botId, chatId, firstName, promptText, onLlmStart, null)
    }

    private suspend fun llmReply(
        context: Context,
        store: LocalBotStore,
        botId: Long,
        chatId: Long,
        firstName: String,
        text: String,
        onLlmStart: (suspend () -> Unit)?,
        trace: ((String) -> Unit)?,
    ): BotDecision {
        val model = store.aiModel(botId)
        if (!store.llmEnabled(botId) || model.isBlank()) {
            trace?.invoke("ИИ выключен или модель не выбрана («Модели ИИ» → выбрать)")
            return fallback(store, botId)
        }
        trace?.invoke("🤖 Спрашиваю ИИ (модель: $model)…")
        onLlmStart?.invoke()
        val history = ChatMemory.history(botId, chatId)
        if (history.isNotEmpty()) trace?.invoke("Контекст: ${history.size} прошлых реплик")
        val prompt = DialogPrompt.build(store.systemPrompt(botId), history, text)
        val params = engineParams(store, botId)
        return DeviceLlm.ensureLoaded(context, model, params)
            .mapCatching { DeviceLlm.generate("", prompt, params).getOrThrow() }
            .fold(
                onSuccess = { reply ->
                    ChatMemory.remember(botId, chatId, text, reply, store.historyLimit(botId))
                    trace?.invoke("ИИ ответил (${reply.length} симв.)")
                    resolveLlmProblem(botId)
                    BotDecision("ИИ", reply)
                },
                onFailure = { e ->
                    trace?.invoke("❌ Ошибка ИИ: ${e.message?.take(120)}")
                    addLlmProblem(botId, "ИИ: ${e.message?.take(120)}")
                    fallback(store, botId)
                },
            )
    }

    private suspend fun fallback(store: LocalBotStore, botId: Long): BotDecision {
        val clarify = if (store.clarifyEnabled(botId)) {
            store.clarifyQuestions(botId).randomOrNull().orEmpty()
        } else ""
        val reply = clarify.ifBlank { store.defaultReply(botId) }
        return BotDecision(
            if (clarify.isNotBlank()) "уточняющий вопрос" else "запасной ответ",
            reply,
        )
    }
}
