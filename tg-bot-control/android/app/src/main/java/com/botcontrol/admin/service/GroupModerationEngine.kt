package com.botcontrol.admin.service

import com.botcontrol.admin.BotControlApp
import com.botcontrol.admin.data.BotMatcher
import com.botcontrol.admin.data.CaptchaPolicy
import com.botcontrol.admin.data.FloodTracker
import com.botcontrol.admin.data.GroupModerationConfig
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.MessageCtx
import com.botcontrol.admin.data.ModerationPolicy
import com.botcontrol.admin.data.ModerationVerdict
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.data.telegram.TelegramUpdatesParser
import com.botcontrol.admin.data.telegram.TgCallback
import com.botcontrol.admin.data.telegram.TgChatMemberUpdate
import com.botcontrol.admin.data.telegram.TgChatParticipant
import com.botcontrol.admin.data.telegram.TgJoinRequest
import com.botcontrol.admin.data.telegram.TgMessage
import com.botcontrol.admin.data.telegram.TgMessageReaction
import com.botcontrol.admin.data.telegram.TgPollAnswer
import com.botcontrol.admin.data.telegram.TgPollUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Движок групп, каналов, форумов, реакций, опросов и авто-модерации (Stage 3).
 * Все методы принимают botId явно (строгая изоляция ботов).
 */
object GroupModerationEngine {

    private data class AdminCacheEntry(val ids: Set<Long>, val expiresAtMs: Long)
    private data class PendingCaptcha(
        val botId: Long,
        val chatId: Long,
        val userId: Long,
        val messageId: Int,
        val firstName: String,
    )

    private val groupAdminCache = ConcurrentHashMap<String, AdminCacheEntry>()
    private val recentJoins = ConcurrentHashMap<String, Long>()
    private val pendingCaptchas = ConcurrentHashMap<String, PendingCaptcha>()

    fun clearBotCaches(botId: Long) {
        val prefix = "$botId:"
        groupAdminCache.keys.filter { it.startsWith(prefix) }.forEach { groupAdminCache.remove(it) }
        recentJoins.keys.filter { it.startsWith(prefix) }.forEach { recentJoins.remove(it) }
        pendingCaptchas.keys.filter { it.startsWith(prefix) }.forEach { pendingCaptchas.remove(it) }
        FloodTracker.reset(botId)
    }

    /** Проверка, является ли пользователь админом группы (локально + кэш getChatAdministrators на 5 мин). */
    suspend fun isGroupAdmin(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        userId: Long,
    ): Boolean {
        if (userId <= 0L) return false
        val localAdmins = store.adminIds(botId)
        if (userId in localAdmins) return true
        val dbUser = app.repository.botUser(botId, userId)
        if (dbUser?.role.equals("admin", ignoreCase = true)) return true
        if (chatId >= 0L) return false

        val cacheKey = "$botId:$chatId"
        val now = System.currentTimeMillis()
        val cached = groupAdminCache[cacheKey]
        if (cached != null && cached.expiresAtMs > now) {
            return userId in cached.ids
        }
        val fetched = api.getChatAdministrators(chatId.toString()).getOrNull()
        if (fetched != null) {
            val ids = fetched.map { it.userId }.filter { it > 0L }.toSet()
            if (groupAdminCache.size > 500) groupAdminCache.clear()
            groupAdminCache[cacheKey] = AdminCacheEntry(ids, now + 300_000L)
            return userId in ids
        }
        return false
    }

    /** Приветствие или кнопка-капча для новых участников группы. */
    suspend fun handleNewMembers(
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        chatTitle: String,
        threadId: Int?,
        members: List<TgChatParticipant>,
        botScope: CoroutineScope,
    ) {
        if (members.isEmpty()) return
        val mod = store.groupModeration(botId)
        if (!mod.welcomeEnabled && !mod.captchaEnabled) return
        val now = System.currentTimeMillis()
        val resolvedThread = ModerationPolicy.resolveThreadId(null, threadId, mod.defaultThreadId, isGroupChat = true)
        val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()

        for (member in members) {
            if (member.isBot || member.userId <= 0L) continue
            val joinKey = "$botId:$chatId:${member.userId}"
            val prevJoin = recentJoins[joinKey] ?: 0L
            if (now - prevJoin < 15_000L) continue
            if (recentJoins.size > 1000) recentJoins.clear()
            recentJoins[joinKey] = now

            val ctx = MessageCtx(
                chatId = chatId,
                userId = member.userId,
                firstName = member.firstName.ifBlank { "Участник" },
                username = member.username,
                chatType = "supergroup",
                threadId = resolvedThread,
            )
            if (mod.captchaEnabled) {
                api.restrictChatMember(chatId.toString(), member.userId, canSendMessages = false)
                    .onFailure { BotLog.log(botId, "⚠️ Капча: не удалось ограничить ${member.userId}: ${it.message?.take(100)}") }
                val timeoutMin = mod.captchaTimeoutMin.coerceIn(1, 60)
                val prompt = BotMatcher.expandPlaceholders(
                    mod.captchaPrompt.ifBlank { "👋 {user}, нажмите кнопку ниже в течение {timeout} мин." },
                    ctx, botName, vars = mapOf("timeout" to timeoutMin.toString(), "chat" to chatTitle),
                )
                val btn = InlineBtn(
                    id = CaptchaPolicy.callbackData(chatId, member.userId),
                    label = mod.captchaButtonText.ifBlank { "✅ Я не робот" },
                    action = "captcha",
                    row = 1,
                )
                val msgId = api.sendMessageForId(
                    chatId = chatId,
                    text = prompt,
                    inlineMenu = listOf(btn),
                    messageThreadId = resolvedThread,
                ).getOrDefault(0)
                val capKey = "$botId:$chatId:${member.userId}"
                pendingCaptchas[capKey] = PendingCaptcha(botId, chatId, member.userId, msgId, ctx.firstName)
                BotLog.log(botId, "🛡 Капча выдана ${ctx.firstName} (id=${member.userId}) в чате $chatId")

                val actionOnTimeout = mod.captchaActionOnTimeout
                botScope.launch {
                    delay(timeoutMin * 60_000L)
                    val pending = pendingCaptchas.remove(capKey) ?: return@launch
                    if (pending.messageId > 0) api.deleteMessage(chatId.toString(), pending.messageId)
                    if (actionOnTimeout == "kick") {
                        val until = System.currentTimeMillis() / 1000L + 45L
                        api.banChatMember(chatId.toString(), pending.userId, untilDateSec = until)
                        api.unbanChatMember(chatId.toString(), pending.userId)
                        BotLog.log(botId, "👢 Капча не пройдена за $timeoutMin мин — кик ${pending.firstName} (${pending.userId})")
                        sendModLog(api, mod, "👢 Капча истекла: ${pending.firstName} (${pending.userId}) удалён из чата $chatId")
                    } else {
                        BotLog.log(botId, "🔇 Капча не пройдена за $timeoutMin мин — оставлен в муте ${pending.firstName} (${pending.userId})")
                    }
                }
            } else if (mod.welcomeEnabled && mod.welcomeText.isNotBlank()) {
                val msg = BotMatcher.expandPlaceholders(
                    mod.welcomeText, ctx, botName, vars = mapOf("chat" to chatTitle),
                )
                val sentId = api.sendMessageForId(chatId, msg, messageThreadId = resolvedThread).getOrDefault(0)
                BotLog.log(botId, "👋 Приветствие для ${ctx.firstName} в чате $chatId")
                if (mod.welcomeDeleteAfterSec > 0 && sentId > 0) {
                    val delDelay = mod.welcomeDeleteAfterSec.coerceIn(5, 600) * 1000L
                    botScope.launch {
                        delay(delDelay)
                        api.deleteMessage(chatId.toString(), sentId)
                    }
                }
            }
        }
    }

    /** Обработка нажатия кнопки капчи `cap_<chatId>_<userId>`. */
    suspend fun handleCaptchaCallback(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        cb: TgCallback,
        botScope: CoroutineScope,
    ): Boolean {
        val parsed = CaptchaPolicy.parseCallback(cb.data) ?: return false
        val (targetChatId, targetUserId) = parsed
        val clickerId = if (cb.userId != 0L) cb.userId else cb.chatId
        val isAdmin = isGroupAdmin(app, store, api, botId, targetChatId, clickerId)
        if (clickerId != targetUserId && !isAdmin) {
            api.answerCallbackQuery(cb.callbackId, "Эта проверка предназначена для нового участника.")
            return true
        }
        val capKey = "$botId:$targetChatId:$targetUserId"
        pendingCaptchas.remove(capKey)
        val unmute = api.restrictChatMember(targetChatId.toString(), targetUserId, canSendMessages = true)
        if (unmute.isFailure) {
            api.answerCallbackQuery(cb.callbackId, "Не удалось снять ограничения (проверьте права бота)")
            BotLog.log(botId, "⚠️ Ошибка снятия мута капчи: ${unmute.exceptionOrNull()?.message?.take(120)}")
            return true
        }
        api.answerCallbackQuery(cb.callbackId, "✅ Добро пожаловать!")
        val mod = store.groupModeration(botId)
        if (mod.welcomeEnabled && mod.welcomeText.isNotBlank()) {
            val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()
            val ctx = MessageCtx(chatId = targetChatId, userId = targetUserId, firstName = cb.firstName, username = cb.username)
            val txt = BotMatcher.expandPlaceholders(mod.welcomeText, ctx, botName)
            api.editMessageText(targetChatId, cb.messageId, txt)
            if (mod.welcomeDeleteAfterSec > 0 && cb.messageId > 0) {
                val delDelay = mod.welcomeDeleteAfterSec.coerceIn(5, 600) * 1000L
                botScope.launch {
                    delay(delDelay)
                    api.deleteMessage(targetChatId.toString(), cb.messageId)
                }
            }
        } else if (cb.messageId > 0) {
            api.deleteMessage(targetChatId.toString(), cb.messageId)
        }
        BotLog.log(botId, "✅ Капча пройдена пользователем ${cb.firstName} ($targetUserId) в чате $targetChatId")
        return true
    }

    /** Антиспам (стоп-слова, ссылки, флуд) в группах и супергруппах. Возвращает true, если сообщение нарушило правила и обработано. */
    suspend fun moderateIncomingMessage(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        msg: TgMessage,
        mod: GroupModerationConfig,
        botScope: CoroutineScope,
    ): Boolean {
        if (!ModerationPolicy.isGroupChat(msg.chatType) || msg.senderIsBot) return false
        if (!mod.antispamEnabled) return false
        if (isGroupAdmin(app, store, api, botId, msg.chatId, msg.userId)) return false

        val bodyText = listOf(msg.text, msg.caption).filter { it.isNotBlank() }.joinToString("\n")
        var verdict = ModerationPolicy.checkMessage(mod, bodyText, isAdmin = false, chatType = msg.chatType)
        if (!verdict.violation && mod.floodLimitCount > 0) {
            val flooded = FloodTracker.recordAndCheck(
                botId = botId,
                chatId = msg.chatId,
                userId = msg.userId,
                limitCount = mod.floodLimitCount,
                windowSec = mod.floodWindowSec,
            )
            if (flooded) {
                verdict = ModerationVerdict(
                    violation = true,
                    reason = "flood",
                    matched = ">${mod.floodLimitCount}/${mod.floodWindowSec}с",
                    action = mod.antispamAction.ifBlank { "delete" },
                )
            }
        }
        if (!verdict.violation) return false

        if (msg.messageId > 0) {
            api.deleteMessage(msg.chatId.toString(), msg.messageId)
                .onFailure { BotLog.log(botId, "⚠️ Модерация: не удалось удалить msg=${msg.messageId}: ${it.message?.take(100)}") }
        }
        val uid = msg.userId
        when (verdict.action) {
            "mute" -> if (uid > 0L) {
                val until = System.currentTimeMillis() / 1000L + mod.muteDurationMin.coerceIn(1, 525_600) * 60L
                api.restrictChatMember(msg.chatId.toString(), uid, canSendMessages = false, untilDateSec = until)
            }
            "ban" -> if (uid > 0L) {
                api.banChatMember(msg.chatId.toString(), uid, revokeMessages = false)
            }
        }
        val reasonText = when (verdict.reason) {
            "link" -> "запрещённая ссылка"
            "flood" -> "флуд (${verdict.matched})"
            else -> "стоп-слово"
        }
        val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()
        val ctx = MessageCtx(chatId = msg.chatId, userId = msg.userId, firstName = msg.firstName, username = msg.username, chatType = msg.chatType)
        val resolvedThread = ModerationPolicy.resolveThreadId(null, msg.messageThreadId, mod.defaultThreadId, isGroupChat = true)
        if (mod.antispamWarnText.isNotBlank()) {
            val warn = BotMatcher.expandPlaceholders(
                mod.antispamWarnText, ctx, botName, vars = mapOf("reason" to reasonText),
            )
            val warnId = api.sendMessageForId(msg.chatId, warn, messageThreadId = resolvedThread).getOrDefault(0)
            if (warnId > 0) {
                botScope.launch {
                    delay(10_000L)
                    api.deleteMessage(msg.chatId.toString(), warnId)
                }
            }
        }
        val logMsg = "🛡 Модерация (${verdict.action}): $reasonText «${verdict.matched.take(40)}» от ${msg.firstName} ($uid) в чате ${msg.chatTitle.ifBlank { msg.chatId.toString() }}"
        BotLog.log(botId, logMsg)
        sendModLog(api, mod, logMsg)
        return true
    }

    /** Изменения статуса самого бота или участников (my_chat_member / chat_member). */
    suspend fun handleMemberUpdate(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        upd: TgChatMemberUpdate,
        botScope: CoroutineScope,
    ) {
        if (upd.chatId < 0L) {
            app.repository.touchBotUser(
                botId = botId,
                chatId = upd.chatId,
                userId = 0L,
                firstName = upd.chatTitle.ifBlank { "Чат ${upd.chatId}" },
                username = "",
                chatType = upd.chatType,
            )
        }
        groupAdminCache.remove("$botId:${upd.chatId}")
        if (upd.isMyChatMember) {
            BotLog.log(botId, "🤖 Статус бота в «${upd.chatTitle.ifBlank { upd.chatId.toString() }}»: ${upd.oldStatus} → ${upd.newStatus}")
            return
        }
        val wasOut = upd.oldStatus in setOf("left", "kicked", "")
        val nowIn = upd.newStatus in setOf("member", "restricted")
        if (wasOut && nowIn && upd.targetUserId > 0L) {
            handleNewMembers(
                store = store,
                api = api,
                botId = botId,
                chatId = upd.chatId,
                chatTitle = upd.chatTitle,
                threadId = null,
                members = listOf(
                    TgChatParticipant(
                        userId = upd.targetUserId,
                        firstName = upd.targetFirstName,
                        username = upd.targetUsername,
                        isBot = upd.targetIsBot,
                    )
                ),
                botScope = botScope,
            )
        }
    }

    /** Заявка на вступление в закрытый канал/группу (chat_join_request). */
    suspend fun handleJoinRequest(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        req: TgJoinRequest,
    ) {
        app.repository.touchBotUser(
            botId = botId,
            chatId = req.chatId,
            userId = 0L,
            firstName = req.chatTitle.ifBlank { "Чат ${req.chatId}" },
            username = "",
            chatType = req.chatType,
        )
        val mod = store.groupModeration(botId)
        val who = "${req.firstName.ifBlank { "Пользователь" }} (id=${req.userId})"
        if (mod.autoApproveJoinRequests) {
            api.approveChatJoinRequest(req.chatId.toString(), req.userId)
                .onSuccess {
                    val info = "✅ Заявка одобрена автоматически: $who → ${req.chatTitle.ifBlank { req.chatId.toString() }}"
                    BotLog.log(botId, info)
                    sendModLog(api, mod, info)
                    if (mod.joinRequestWelcome.isNotBlank()) {
                        val ctx = MessageCtx(chatId = req.userId, userId = req.userId, firstName = req.firstName, username = req.username)
                        val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()
                        api.sendMessage(
                            req.userId,
                            BotMatcher.expandPlaceholders(mod.joinRequestWelcome, ctx, botName, vars = mapOf("chat" to req.chatTitle)),
                        )
                    }
                }
                .onFailure {
                    BotLog.log(botId, "⚠️ Ошибка приёма заявки $who: ${it.message?.take(120)}")
                }
        } else {
            val info = "🚪 Новая заявка на вступление от $who в «${req.chatTitle.ifBlank { req.chatId.toString() }}»"
            BotLog.log(botId, info)
            sendModLog(api, mod, info)
        }
    }

    /** Реакции пользователей на сообщения (message_reaction). */
    suspend fun handleMessageReaction(
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        mr: TgMessageReaction,
    ) {
        val mod = store.groupModeration(botId)
        val emojis = mr.newEmojis.joinToString(" ")
        if (emojis.isNotBlank()) {
            BotLog.log(botId, "✨ Реакция $emojis от ${mr.firstName.ifBlank { mr.userId.toString() }} на msg=${mr.messageId} в чате ${mr.chatId}")
        }
        val want = mod.reactionTriggerEmoji.trim()
        if (want.isNotBlank() && mod.reactionTriggerReply.isNotBlank() && mr.newEmojis.isNotEmpty()) {
            val hit = want == "*" || want in mr.newEmojis
            if (hit) {
                val ctx = MessageCtx(chatId = mr.chatId, userId = mr.userId, firstName = mr.firstName, username = mr.username, messageId = mr.messageId)
                val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()
                val reply = BotMatcher.expandPlaceholders(
                    mod.reactionTriggerReply, ctx, botName,
                    vars = mapOf("emoji" to emojis, "message_id" to mr.messageId.toString()),
                )
                api.sendMessage(mr.chatId, reply, replyToMessageId = mr.messageId.takeIf { it > 0 })
            }
        }
    }

    /** Итоги опросов и ответы пользователей (poll / poll_answer). */
    suspend fun handlePollUpdate(botId: Long, p: TgPollUpdate) {
        val summary = p.options.joinToString(" | ") { "${it.first}:${it.second}" }
        BotLog.log(botId, "📊 Опрос «${p.question.take(40)}» (голосов: ${p.totalVoterCount}): $summary")
    }

    suspend fun handlePollAnswer(
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        pa: TgPollAnswer,
    ) {
        val opts = pa.optionIds.joinToString(",")
        val who = pa.firstName.ifBlank { pa.userId.toString() }
        BotLog.log(botId, "🗳 Голос в опросе ${pa.pollId}: $who → [$opts]")
        val mod = store.groupModeration(botId)
        if (mod.pollAnswerReply.isNotBlank()) {
            val target = mod.logChatId.trim().toLongOrNull() ?: store.lastChatId(botId)
            if (target != 0L) {
                val ctx = MessageCtx(chatId = target, userId = pa.userId, firstName = pa.firstName, username = pa.username)
                val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()
                val text = BotMatcher.expandPlaceholders(
                    mod.pollAnswerReply, ctx, botName,
                    vars = mapOf("poll_id" to pa.pollId, "option_ids" to opts),
                )
                api.sendMessage(target, text)
            }
        }
    }

    private suspend fun sendModLog(api: TelegramApi, mod: GroupModerationConfig, text: String) {
        val target = mod.logChatId.trim()
        if (target.isBlank()) return
        api.sendTo(target, text)
    }

    /** Локальный тестовый helper (вызов с UI-экрана или webhook-проверки). */
    fun parseWebhookSample(json: String) = TelegramUpdatesParser.parseSingleUpdate(json)
}
