package com.botcontrol.admin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.botcontrol.admin.BotControlApp
import com.botcontrol.admin.R
import com.botcontrol.admin.data.AccessPolicy
import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.BotMatcher
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.MessageCtx
import com.botcontrol.admin.data.ModerationPolicy
import com.botcontrol.admin.data.ScheduleJitter
import com.botcontrol.admin.data.findButtonRecursive
import com.botcontrol.admin.data.withIds
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.data.telegram.TelegramApiException
import com.botcontrol.admin.data.telegram.TelegramRateLimit
import com.botcontrol.admin.data.telegram.TgCallback
import com.botcontrol.admin.data.telegram.TgMessage
import com.botcontrol.admin.data.telegram.TgUpdatesBatch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalBotState(
    val botId: Long = 0L,
    val running: Boolean = false,
    val botUsername: String = "",
    val processed: Int = 0,
    val lastError: String = "",
    val lastPollAt: Long = 0L, // epoch seconds of the last SUCCESSFUL getUpdates
    val remindersOn: Boolean = false,
    /** Нерешённые проблемы бота: тег -> текст. Висят до устранения причины. */
    val problems: Map<String, String> = emptyMap(),
)

/**
 * The WHOLE bot on the phone: long-polls Telegram, answers with rules,
 * scheduled reminders (with inline menus), scripts and the on-device LLM
 * with dialog context. No external server, no login, no Termux.
 *
 * Reliability (v1.0.3, keep): fresh scope per start, auto deleteWebhook,
 * silent backlog confirm, clear Russian errors, lastPollAt diagnostics.
 */
class LocalBotService : Service() {

    private var scope: CoroutineScope = newScope()
    private val botJobs = ConcurrentHashMap<Long, Job>()
    private val activeTokens = ConcurrentHashMap<Long, Pair<Job, String>>()
    private val inlineMenuStacks = ConcurrentHashMap<String, MutableList<Pair<String?, List<InlineBtn>>>>()
    private val subCache = ConcurrentHashMap<String, Long>()
    private val startMutex = Mutex()


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_BOT) {
            // Остановить ОДНОГО бота: отменяем его корутину (и активный long-poll).
            val botId = intent.getLongExtra(EXTRA_BOT_ID, 0L)
            if (botId > 0) stopBotLoop(botId)
            refreshNotification()
            val anyLeft = states.value.values.any { it.running }
            if (!anyLeft) {
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_START_BOT) {
            // Запустить ОДНОГО конкретного бота.
            startInForeground()
            ensureScope()
            val botId = intent.getLongExtra(EXTRA_BOT_ID, 0L)
            if (botId > 0) scope.launch { startBotLoop(botId) }
            return START_STICKY
        }
        startInForeground()
        ensureScope()
        // Без действия: запускаем всех включённых ботов.
        scope.launch {
            val store = (application as BotControlApp).localStore
            val profiles = store.profiles().filter { it.enabled }
            if (profiles.isEmpty()) {
                notifyContent("Нет включённых ботов — добавь бота на главном экране")
            }
            profiles.forEach { profile ->
                scope.launch { startBotLoop(profile.id) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Версия в строке запуска: по логу сразу видно, какая сборка работает. */
    @Suppress("DEPRECATION")
    private val appVersion: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun shutdown() {
        states.value = states.value.mapValues { it.value.copy(running = false) }
        botJobs.values.forEach { it.cancel() }
        botJobs.clear()
        activeTokens.clear()
        scope.cancel()
    }

    private fun stopBotLoop(botId: Long) {
        setState(botId) { it.copy(running = false) }
        botJobs.remove(botId)?.cancel()
        activeTokens.remove(botId)
        GroupModerationEngine.clearBotCaches(botId)
        WebhookQueue.clear(botId)
    }

    /** Scope пересоздаём только если он был отменён (глобальный стоп). */
    private fun ensureScope() {
        if (!scope.isActive) scope = newScope()
    }

    /** Запуск одного бота: сериализация, отмена старого цикла и защита от дублей токена. */
    private suspend fun startBotLoop(botId: Long) = startMutex.withLock {
        val store = (application as BotControlApp).localStore
        val profile = store.profiles().firstOrNull { it.id == botId }
        if (profile == null || !profile.enabled) {
            setState(botId) { it.copy(running = false, lastError = "Бот выключен или удалён") }
            return@withLock
        }
        val existingJob = botJobs[botId]
        if (existingJob?.isActive == true && states.value[botId]?.running == true) {
            return@withLock // уже работает
        }
        existingJob?.cancel()
        val token = store.botToken(botId)
        if (token.isNullOrBlank()) {
            setState(botId) { it.copy(running = false, lastError = "Токен бота не задан") }
            return@withLock
        }
        val tKey = TelegramRateLimit.tokenKey(token)
        val duplicateOwner = activeTokens.entries.firstOrNull { (id, pair) ->
            id != botId && pair.second == tKey && pair.first.isActive
        }?.key
        if (duplicateOwner != null) {
            val msg = "Этот токен уже запущен в профиле #$duplicateOwner — дубль остановлен (защита от HTTP 429/409)"
            addProblem(botId, "conflict", msg)
            setState(botId) { it.copy(running = false, lastError = msg) }
            BotLog.log(botId, "⚠️ $msg")
            return@withLock
        }
        val existing = states.value[botId]
        states.value = states.value + (botId to
            (existing ?: LocalBotState(botId = botId)).copy(running = true, lastError = ""))
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                runBotLoop(botId, token)
            } finally {
                if (self != null) {
                    activeTokens.remove(botId, self to tKey)
                    if (botJobs.remove(botId, self)) {
                        setState(botId) { it.copy(running = false) }
                    }
                }
                refreshNotification()
            }
        }
        botJobs[botId] = job
        activeTokens[botId] = job to tKey
        job.start()
    }

    private suspend fun runBotLoop(botId: Long, token: String) {
        val app = application as BotControlApp
        val store = app.localStore
        val api = TelegramApi(token)
        var botName = ""
        var meStreak = 0
        while (currentCoroutineContext().isActive && states.value[botId]?.running == true && botName.isBlank()) {
            val res = api.validateToken()
            res.onSuccess { name ->
                botName = name
                meStreak = 0
                store.updateProfile(
                    (store.profile(botId) ?: com.botcontrol.admin.data.BotProfile(botId))
                        .copy(username = name))
                resolveProblem(botId, "token")
                resolveProblem(botId, "network")
                resolveProblem(botId, "conflict")
            }.onFailure { err ->
                val apiErr = err as? TelegramApiException
                val msg = err.message ?: "ошибка сети"
                if (apiErr?.isAuthError == true || (apiErr == null && msg.containsAny("HTTP 401", "HTTP 404", "(401)", "(404)"))) {
                    addProblem(botId, "token", msg)
                    setState(botId) { it.copy(running = false, lastError = msg) }
                    return
                }
                if (apiErr?.isRateLimit == true || msg.contains("HTTP 429")) {
                    meStreak++
                    val retrySec = apiErr?.retryAfterSec ?: TelegramRateLimit.parseRetryAfterSec(msg)
                    val waitSec = TelegramRateLimit.backoffSec(retrySec, meStreak, minSec = 15L, maxSec = 300L)
                    val info = "Telegram ограничил запросы (HTTP 429) — ждём $waitSec с"
                    addProblem(botId, "network", info)
                    setState(botId) { it.copy(lastError = info) }
                    BotLog.log(botId, "⏳ HTTP 429 при проверке токена: пауза $waitSec с" +
                        if (retrySec > 0L) " (retry_after=$retrySec)" else "")
                    delay(waitSec * 1000L)
                } else {
                    addProblem(botId, "network", msg)
                    setState(botId) { it.copy(lastError = "$msg — повторяем каждые 15 с") }
                    delay(15_000L)
                }
            }
        }
        if (botName.isBlank() || states.value[botId]?.running != true) return

        setState(botId) { it.copy(botUsername = botName, running = true, lastError = "") }
        BotLog.log(botId, "🚀 Бот запущен (правил: ${runCatching { app.repository.botRules(botId).size }.getOrDefault(-1)}) · BotControl v$appVersion")
        refreshNotification()

        // Главное меню Telegram: применяем только если команды изменились с прошлой успешной синхронизации.
        val menuCommands = store.menuCommands(botId)
        if (menuCommands.isNotEmpty()) {
            val menuSig = LocalBotStore.computeMenuSignature(TelegramRateLimit.tokenKey(token), menuCommands)
            if (store.menuSignature(botId) != menuSig) {
                api.setMyCommandsScoped(menuCommands)
                    .onSuccess {
                        store.setMenuSignature(botId, menuSig)
                        BotLog.log(botId, "📜 Главное меню применено (${menuCommands.size} команд)")
                        resolveProblem(botId, "menu")
                    }
                    .onFailure { e ->
                        val apiErr = e as? TelegramApiException
                        if (apiErr?.isRateLimit == true) {
                            val waitSec = TelegramRateLimit.backoffSec(apiErr.retryAfterSec, 1, minSec = 15L, maxSec = 300L)
                            BotLog.log(botId, "⏳ Меню Telegram: HTTP 429, пауза $waitSec с")
                            addProblem(botId, "menu", "Меню Telegram: пауза $waitSec с (HTTP 429)")
                        } else {
                            BotLog.log(botId, "⚠️ Меню не применено: ${e.message?.take(160)}")
                            addProblem(botId, "menu", "Меню Telegram: ${e.message?.take(140)}")
                        }
                    }
            } else {
                resolveProblem(botId, "menu")
            }
        }

        // Опрос и планировщик живут в одном дочернем coroutineScope бота:
        // при остановке отменяются оба одновременно.
        var backlogDropped = false
        var backlogPages = 0
        var offset = 0L
        var pollRateLimitStreak = 0
        var webhookCleared = false

        coroutineScope {
            val botScope = this
            val schedJob = launch { schedulerLoop(botId, store, api) }
            val webhookJob = launch {
                while (isActive && states.value[botId]?.running == true) {
                    val queued = WebhookQueue.drain(botId)
                    if (queued.isNotEmpty()) {
                        queued.forEach { batch ->
                            processBatch(app, store, api, botId, batch, botScope)
                        }
                        refreshNotification()
                    }
                    delay(500L)
                }
            }
            try {
                while (isActive && states.value[botId]?.running == true) {
                    try {
                        val timeoutSec = if (backlogDropped) 25 else 0
                        val batch = api.getUpdates(offset, timeoutSec)
                        offset = batch.nextOffset
                        pollRateLimitStreak = 0
                        setState(botId) {
                            it.copy(
                                lastPollAt = System.currentTimeMillis() / 1000, lastError = "",
                            )
                        }
                        resolveProblem(botId, "network")
                        resolveProblem(botId, "conflict")
                        if (!backlogDropped) {
                            // Confirm any backlog silently: no reply spam to messages sent before start.
                            backlogPages++
                            if (batch.totalCount < 100 || backlogPages >= 5) {
                                backlogDropped = true
                            }
                            continue
                        }
                        processBatch(app, store, api, botId, batch, botScope)
                        refreshNotification()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val apiErr = e as? TelegramApiException
                        val msg = e.message.orEmpty()
                        when {
                            apiErr?.isAuthError == true -> {
                                addProblem(botId, "token", msg)
                                setState(botId) { it.copy(running = false, lastError = msg) }
                                return@coroutineScope
                            }
                            !webhookCleared && msg.contains("webhook", true) -> {
                                webhookCleared = true
                                BotLog.log(botId, "🔧 Активен webhook — снимаю через deleteWebhook")
                                api.deleteWebhook().onFailure { wErr ->
                                    val wApi = wErr as? TelegramApiException
                                    if (wApi?.isRateLimit == true) {
                                        val waitSec = TelegramRateLimit.backoffSec(wApi.retryAfterSec, 1, minSec = 15L, maxSec = 300L)
                                        delay(waitSec * 1000L)
                                    } else {
                                        delay(POLL_ERROR_MS)
                                    }
                                }
                            }
                            apiErr?.isRateLimit == true || msg.contains("HTTP 429") -> {
                                pollRateLimitStreak++
                                val retrySec = apiErr?.retryAfterSec ?: TelegramRateLimit.parseRetryAfterSec(msg)
                                val waitSec = TelegramRateLimit.backoffSec(retrySec, pollRateLimitStreak, minSec = 15L, maxSec = 300L)
                                val reason2 = "Telegram ограничил запросы (HTTP 429) — ждём $waitSec с"
                                setState(botId) { it.copy(lastError = reason2) }
                                addProblem(botId, "network", reason2)
                                BotLog.log(botId, "⏳ HTTP 429 на getUpdates: ждём $waitSec с" +
                                    if (retrySec > 0L) " (retry_after=$retrySec)" else "")
                                delay(waitSec * 1000L)
                            }
                            msg.contains("409") || msg.contains("Conflict", true) ||
                                msg.contains("webhook", true) -> {
                                val reason2 = "конфликт: токен уже опрашивается другим приложением. Останови второй экземпляр"
                                setState(botId) { it.copy(lastError = reason2) }
                                addProblem(botId, "conflict", reason2)
                                delay(POLL_ERROR_MS)
                            }
                            else -> {
                                val reason2 = msg.ifBlank { "ошибка сети" }
                                setState(botId) { it.copy(lastError = reason2) }
                                addProblem(botId, "network", reason2)
                                delay(POLL_ERROR_MS)
                            }
                        }
                    }
                }
            } finally {
                webhookJob.cancel()
                schedJob.cancel()
            }
        }
    }

    private suspend fun processBatch(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        batch: TgUpdatesBatch,
        botScope: CoroutineScope,
    ) {
        batch.memberUpdates.forEach { upd ->
            GroupModerationEngine.handleMemberUpdate(app, store, api, botId, upd, botScope)
            setState(botId) { it.copy(processed = it.processed + 1) }
        }
        batch.joinRequests.forEach { req ->
            GroupModerationEngine.handleJoinRequest(app, store, api, botId, req)
            setState(botId) { it.copy(processed = it.processed + 1) }
        }
        batch.messages.forEach { msg ->
            handle(app, store, api, botId, msg, botScope)
            setState(botId) { it.copy(processed = it.processed + 1) }
        }
        batch.callbacks.forEach { cb ->
            handleCallback(app, store, api, botId, cb, botScope)
            setState(botId) { it.copy(processed = it.processed + 1) }
        }
        batch.reactions.forEach { mr ->
            GroupModerationEngine.handleMessageReaction(store, api, botId, mr)
            setState(botId) { it.copy(processed = it.processed + 1) }
        }
        batch.pollUpdates.forEach { p ->
            GroupModerationEngine.handlePollUpdate(botId, p)
            setState(botId) { it.copy(processed = it.processed + 1) }
        }
        batch.pollAnswers.forEach { pa ->
            GroupModerationEngine.handlePollAnswer(store, api, botId, pa)
            setState(botId) { it.copy(processed = it.processed + 1) }
        }
    }

    private fun setState(botId: Long, update: (LocalBotState) -> LocalBotState) {
        val current = states.value[botId] ?: LocalBotState(botId = botId)
        states.value = states.value + (botId to update(current))
    }

    /** Проблема висит, пока причина не устранена (снимается resolveProblem). */
    private fun addProblem(botId: Long, tag: String, text: String) {
        setState(botId) {
            if (it.problems[tag] == text) it
            else it.copy(problems = it.problems + (tag to text))
        }
    }

    private fun resolveProblem(botId: Long, tag: String) {
        setState(botId) {
            if (tag in it.problems) it.copy(problems = it.problems - tag) else it
        }
    }

    private fun refreshNotification() {
        val all = states.value.values
        val running = all.count { it.running }
        val processed = all.sumOf { it.processed }
        notifyContent(
            if (running > 1) "Ботов работает: $running • сообщений: $processed"
            else if (running == 1) {
                val bot = all.first { it.running }
                "Бот @${bot.botUsername} работает • сообщений: ${bot.processed}"
            } else "Боты остановлены"
        )
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }

    // ---------- входящее сообщение ----------

    private suspend fun handle(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        msg: TgMessage,
        botScope: CoroutineScope,
    ) {
        store.setLastChatId(msg.chatId, botId)
        val isGroup = ModerationPolicy.isGroupChat(msg.chatType)
        if (msg.chatId < 0L) {
            app.repository.touchBotUser(
                botId = botId,
                chatId = msg.chatId,
                userId = 0L,
                firstName = msg.chatTitle.ifBlank { "Чат ${msg.chatId}" },
                username = "",
                chatType = msg.chatType,
            )
        }
        val dbUser = app.repository.touchBotUser(
            botId = botId,
            chatId = if (msg.chatId < 0L && msg.userId > 0L) msg.userId else msg.chatId,
            userId = msg.userId,
            firstName = msg.firstName.ifBlank { msg.chatTitle },
            username = msg.username,
            chatType = if (msg.chatId < 0L && msg.userId > 0L) "private" else msg.chatType,
        )
        val adminIds = store.adminIds(botId)
        val isAdmin = AccessPolicy.isAdmin(msg.userId, msg.chatId, dbUser.role, adminIds)
        if (dbUser.isBanned && !isAdmin) {
            BotLog.log(botId, "⛔ Бан: сообщение от chat=${msg.chatId} проигнорировано")
            return
        }

        // Новые участники в группе/супергруппе (приветствие и кнопка-капча)
        if (msg.newMembers.isNotEmpty() && isGroup) {
            GroupModerationEngine.handleNewMembers(
                store = store,
                api = api,
                botId = botId,
                chatId = msg.chatId,
                chatTitle = msg.chatTitle,
                threadId = msg.messageThreadId,
                members = msg.newMembers,
                botScope = botScope,
            )
            return
        }

        val mod = store.groupModeration(botId)
        // Антиспам/стоп-слова/ссылки/флуд в группах (проверяет и edited_message!)
        if (isGroup && GroupModerationEngine.moderateIncomingMessage(app, store, api, botId, msg, mod, botScope)) {
            return
        }

        // Редактирование сообщений не перезапускает обычные правила (чтобы не дублировать ответы).
        if (msg.isEdited) {
            BotLog.log(botId, "✏️ Правка сообщения msg=${msg.messageId} в чате ${msg.chatId}: '${msg.text.take(60)}'")
            return
        }

        val text = if (msg.text.isBlank() && msg.kind.isNotBlank()) "[${msg.kind}]" else msg.text.trim()
        if (text.isEmpty() && msg.photoId.isBlank() && msg.locationLat == null && msg.contactPhone.isBlank()) return
        val keyboard = store.keyboard(botId)
        val msgCtx = MessageCtx(
            chatId = msg.chatId,
            userId = msg.userId,
            firstName = msg.firstName,
            username = msg.username,
            chatType = msg.chatType,
            text = text,
            kind = msg.kind,
            fileId = msg.fileId.ifBlank { msg.photoId },
            messageId = msg.messageId,
            threadId = msg.messageThreadId,
            replyToText = msg.replyToText,
        )

        // Обычные посты в каналах обрабатываем только при явном правиле с областью "channel" или автореакцией.
        if (msg.isChannelPost) {
            val channelRules = app.repository.botRules(botId).filter { r ->
                r.enabled && BotJson.ruleOptions(r.options).chatScope.equals("channel", ignoreCase = true)
            }
            if (channelRules.isEmpty()) {
                BotLog.log(botId, "📢 Пост в канале «${msg.chatTitle.ifBlank { msg.chatId.toString() }}»: '${text.take(60)}'")
                return
            }
        }

        // Обязательная подписка на канал (включается в «Настройках бота»)
        val reqChan = store.requiredChannel(botId)
        if (reqChan.isNotBlank() && !isAdmin && msg.chatType in listOf("", "private")) {
            val bypassRule = app.repository.botRules(botId).any { r ->
                r.enabled && BotJson.ruleOptions(r.options).skipSubCheck &&
                    BotMatcher.matchesRule(r.type, r.pattern, text, BotJson.ruleOptions(r.options).wholeWord)
            }
            if (!bypassRule) {
                val allowed = ensureChannelSubscription(store, api, botId, reqChan, msg.chatId, msg.userId)
                if (!allowed) return
            }
        }

        // Режим «Объявления»: визард/статистика перехватывают текст и фото.
        if (store.listingsOn(botId)) {
            val consumed = ListingEngine.handleText(
                context = this,
                store = store, api = api, botId = botId,
                chatId = msg.chatId, firstName = msg.firstName,
                text = text, photoId = msg.photoId, replyKeyboard = keyboard,
            )
            if (consumed) {
                BotLog.log(botId, "📩 Сообщение: '${text.take(80)}'")
                BotLog.log(botId, "   • Обработал режим «Объявления»")
                return
            }
        } else if (ListingEngine.looksLikeNew(text) || ListingEngine.looksLikeMy(text)) {
            val hasRule = app.repository.botRules(botId).any { r ->
                r.enabled && r.pattern.isNotBlank() && (
                    (r.type == "button" && r.pattern.equals(text, ignoreCase = true)) ||
                        text.contains(r.pattern, ignoreCase = true)
                    )
            }
            if (!hasRule) {
                BotLog.log(botId, "📩 Сообщение: '${text.take(80)}'")
                BotLog.log(botId, "   • Похоже на запрос объявлений, но режим «Объявления» выключен")
                api.sendMessage(
                    msg.chatId,
                    "📝 Режим «Объявления» выключен — пошаговое размещение не запущено.\n\n" +
                        "Включи: «Настройки бота» → «Объявления: вкл» и укажи канал " +
                        "(например @daromvpl). После этого «добавить объявление» запустит " +
                        "визард: описание → контакт → фото → предпросмотр → пост в канал.",
                    keyboard = keyboard,
                )
                return
            }
        }

        // Пошаговые сценарии (FSM: анкеты, заявки, заказы)
        if (FsmEngine.handleIncoming(app, store, api, botId, msg, msgCtx, keyboard)) {
            return
        }

        // Решение принимает общий «мозг» (тот же, что во вкладке имитации).
        BotLog.log(botId, "📩 Сообщение: '${text.take(80)}'")
        val decision = BotBrain.decide(
            context = this,
            store = store,
            botId = botId,
            chatId = msg.chatId,
            firstName = msg.firstName,
            text = text,
            respectCooldown = true,
            onLlmStart = { api.sendChatAction(msg.chatId) },
            trace = { BotLog.log(botId, "   • $it") },
            ctx = msgCtx,
        )
        BotLog.log(botId, "💬 Источник: ${decision.source}; ответ: '${decision.reply.take(60)}'")
        val opts = decision.options
        val pm = opts.parseMode.ifBlank { null }
        val resolvedThreadId = ModerationPolicy.resolveThreadId(
            ruleTargetThreadId = opts.targetThreadId,
            incomingThreadId = msg.messageThreadId,
            defaultModThreadId = mod.defaultThreadId,
            isGroupChat = isGroup,
        )
        if (opts.autoReactEmoji.isNotBlank() && msg.messageId > 0) {
            api.setMessageReaction(msg.chatId, msg.messageId, opts.autoReactEmoji)
                .onFailure { BotLog.log(botId, "⚠️ Автореакция не поставлена: ${it.message?.take(90)}") }
        }
        decision.anim?.let { spec ->
            val items = store.packs().firstOrNull { it.id == spec.packId }?.items.orEmpty()
            AnimPlayer.play(botScope, api, botId, msg.chatId, spec, msg.firstName, items, decision.menu, messageThreadId = resolvedThreadId)
            return
        }
        if (decision.dice.isNotBlank()) {
            api.sendDice(msg.chatId, decision.dice, messageThreadId = resolvedThreadId)
                .onFailure { BotLog.log(botId, "❌ Кубик не отправлен: ${it.message?.take(120)}") }
            return
        }
        if (decision.mediaType.isNotBlank() && decision.mediaRef.isNotBlank()) {
            withTyping(api, store, botId, msg.chatId)
            api.sendMediaByType(
                chatId = msg.chatId,
                mediaType = decision.mediaType,
                mediaRef = decision.mediaRef,
                caption = decision.reply,
                parseMode = pm,
                silent = opts.silent,
                protectContent = opts.protectContent,
                replyToMessageId = decision.replyToMessageId,
                messageThreadId = resolvedThreadId,
                inlineMenu = decision.menu,
            ).onFailure {
                BotLog.log(botId, "❌ Медиа (${decision.mediaType}) не отправлено: ${it.message?.take(140)}")
            }
            return
        }
        if (decision.pollQuestion.isNotBlank() && decision.pollOptions.size >= 2) {
            withTyping(api, store, botId, msg.chatId)
            api.sendPoll(
                chatId = msg.chatId,
                question = decision.pollQuestion,
                options = decision.pollOptions,
                isAnonymous = decision.pollAnonymous,
                allowsMultipleAnswers = decision.pollMultiple,
                silent = opts.silent,
                protectContent = opts.protectContent,
                replyToMessageId = decision.replyToMessageId,
                messageThreadId = resolvedThreadId,
                inlineMenu = decision.menu,
            ).onFailure {
                BotLog.log(botId, "❌ Опрос не отправлен: ${it.message?.take(140)}")
            }
            return
        }
        if (decision.reply.isBlank()) return
        // Для ответа ИИ «печатает…» уже показано во время генерации — не дублируем паузу.
        if (decision.source != "ИИ") withTyping(api, store, botId, msg.chatId)
        val kbOpts = store.keyboardOptions(botId)
        api.sendMessage(
            chatId = msg.chatId,
            text = decision.reply,
            keyboard = keyboard,
            inlineMenu = decision.menu,
            parseMode = pm,
            disablePreview = opts.disablePreview,
            silent = opts.silent,
            protectContent = opts.protectContent,
            replyToMessageId = decision.replyToMessageId,
            messageThreadId = resolvedThreadId,
            keyboardOptions = kbOpts,
            removeKeyboard = opts.removeKeyboard,
        ).onFailure { err ->
            val apiErr = err as? TelegramApiException
            if (apiErr?.isBlockedOrForbidden == true) {
                app.repository.markUserBlocked(botId, msg.chatId, true)
                BotLog.log(botId, "🚫 Пользователь ${msg.chatId} заблокировал бота (403)")
            } else {
                BotLog.log(botId, "❌ Не отправлено (${decision.source}): ${err.message ?: "?"}")
            }
        }
    }

    private suspend fun ensureChannelSubscription(
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        reqChannel: String,
        chatId: Long,
        userId: Long,
    ): Boolean {
        val uid = if (userId != 0L) userId else chatId
        val cacheKey = "$botId:$uid"
        val now = System.currentTimeMillis()
        val cachedUntil = subCache[cacheKey] ?: 0L
        if (cachedUntil > now) return true

        val statusRes = api.getChatMemberStatus(reqChannel, uid)
        if (statusRes.isFailure) {
            val errMsg = statusRes.exceptionOrNull()?.message.orEmpty()
            BotLog.log(botId, "⚠️ Проверка подписки $reqChannel: ${errMsg.take(120)}")
            addProblem(botId, "sub_channel", "Канал $reqChannel: ${errMsg.take(100)}")
            return true
        }
        resolveProblem(botId, "sub_channel")
        val status = statusRes.getOrDefault("")
        if (AccessPolicy.isMemberSubscribed(status)) {
            if (subCache.size > 1000) subCache.clear()
            subCache[cacheKey] = now + 60_000L
            return true
        }
        val link = store.requiredChannelLink(botId).ifBlank {
            if (reqChannel.startsWith("@")) "https://t.me/${reqChannel.removePrefix("@")}" else ""
        }
        val prompt = store.requiredChannelPrompt(botId).ifBlank {
            "📢 Для работы с ботом подпишитесь на канал $reqChannel и нажмите «Проверить подписку»."
        }
        val btns = buildList {
            if (link.isNotBlank()) add(InlineBtn(id = "sub_url", label = "📢 Подписаться", action = "url", url = link, row = 1))
            add(InlineBtn(id = "sub_check", label = "✅ Проверить подписку", action = "sub_check", row = 2))
        }
        api.sendMessage(chatId, prompt, inlineMenu = btns)
        return false
    }

    /** «Печатает…» + пауза-имитация набора (как typing_plugin, сек из настроек бота). */
    private suspend fun withTyping(api: TelegramApi, store: LocalBotStore, botId: Long, chatId: Long) {
        val secs = store.typingSeconds(botId)
        if (secs <= 0) return
        api.sendChatAction(chatId)
        delay(secs * 1000L)
    }

    // ---------- inline-кнопки (callback_query) ----------

    private suspend fun handleCallback(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        cb: TgCallback,
        botScope: CoroutineScope,
    ) {
        store.setLastChatId(cb.chatId, botId)
        val cbUid = if (cb.userId != 0L) cb.userId else cb.chatId
        val dbUser = app.repository.touchBotUser(
            botId = botId,
            chatId = cb.chatId,
            userId = cbUid,
            firstName = cb.firstName,
            username = cb.username,
            chatType = "",
        )
        val isAdmin = AccessPolicy.isAdmin(cbUid, cb.chatId, dbUser.role, store.adminIds(botId))
        if (dbUser.isBanned && !isAdmin) {
            api.answerCallbackQuery(cb.callbackId, "⛔ Доступ ограничен")
            return
        }

        if (cb.data == "sub_check") {
            val reqChan = store.requiredChannel(botId)
            subCache.remove("$botId:$cbUid")
            if (reqChan.isBlank()) {
                api.answerCallbackQuery(cb.callbackId, "Проверка не требуется")
                api.editMessageText(cb.chatId, cb.messageId, "✅ Можно пользоваться ботом.")
                return
            }
            val res = api.getChatMemberStatus(reqChan, cbUid)
            val status = res.getOrDefault("")
            if (res.isSuccess && AccessPolicy.isMemberSubscribed(status)) {
                subCache["$botId:$cbUid"] = System.currentTimeMillis() + 60_000L
                api.answerCallbackQuery(cb.callbackId, "✅ Подписка подтверждена!")
                api.editMessageText(cb.chatId, cb.messageId, "✅ Спасибо за подписку! Теперь можно пользоваться ботом.")
            } else {
                api.answerCallbackQuery(cb.callbackId, "Подпишитесь на $reqChan и нажмите снова")
            }
            return
        }

        if (GroupModerationEngine.handleCaptchaCallback(app, store, api, botId, cb, botScope)) {
            return
        }

        if (FsmEngine.handleCallback(app, store, api, botId, cb, store.keyboard(botId))) {
            return
        }

        // Кнопки режима объявлений (lst_*) обрабатывает ListingEngine.
        // legacyCallback — кнопки из сообщений v1.5.2–1.5.4 ("cb<хеш>").
        val lstData = if (cb.data.startsWith("lst_")) cb.data
        else if (store.listingsOn(botId)) ListingEngine.legacyCallback(cb.data) else null
        if (store.listingsOn(botId) && lstData != null) {
            ListingEngine.handleCallback(
                context = this, store = store, api = api, botId = botId,
                chatId = cb.chatId, messageId = cb.messageId,
                callbackId = cb.callbackId, data = lstData,
                firstName = cb.firstName, replyKeyboard = store.keyboard(botId),
            )
            return
        }

        // Ищем кнопку ТОЛЬКО среди меню этого бота (правила + события, включая вложенные подменю I-4).
        val stackKey = "$botId:${cb.chatId}:${cb.messageId}"
        val hit = findButton(app, store, botId, cb.data)
        val syntheticBack = if (hit == null && cb.data.startsWith("back_")) {
            val hasStack = inlineMenuStacks[stackKey]?.let { synchronized(it) { it.isNotEmpty() } } == true
            val hasParent = hasStack || findParentMenuOf(app, store, botId, cb.data) != null
            if (hasParent) InlineBtn(id = cb.data, label = "⬅️ Назад", action = "back") else null
        } else null
        val btn = hit?.first ?: syntheticBack
        // Всегда отвечаем на callback, чтобы в Telegram не висели «часики».
        if (btn == null) {
            // Диагностика: что бот знает сейчас — видно в «Журнал событий».
            val known = LinkedHashSet<String>()
            app.repository.botRules(botId).forEach { rule ->
                known.addAll(BotBrain.parseMenu(rule.menu).map { it.id })
            }
            store.schedule(botId).forEach { event ->
                known.addAll(event.menu.withIds().map { it.id })
            }
            BotLog.log(botId, "⚠️ Кнопка '${cb.data}' не найдена в меню этого бота. " +
                "Известно: ${known.ifEmpty { listOf("— (меню пусто — пересоздай правила/расписание)") }.joinToString(", ").take(300)}")
            api.answerCallbackQuery(
                cb.callbackId,
                "Кнопка устарела: пересыль /start или нажми кнопку в новом сообщении",
            )
            return
        }
        BotLog.log(botId, "🔘 Кнопка «${btn.label}» (${btn.action})")
        api.answerCallbackQuery(cb.callbackId, btn.toast)

        // Inline-кнопка с «объявительной» надписью (своя, из импорта или
        // созданная вручную) запускает визард объявлений, даже если её
        // callback_data не из движка (lst_*). Иначе такая кнопка отвечает
        // только своей надписью и выглядит «заготовленной».
        if (store.listingsOn(botId)) {
            val kb = store.keyboard(botId)
            when {
                ListingEngine.looksLikeMy(btn.label) -> {
                    ListingEngine.showMyListings(
                        context = this, store = store, api = api, botId = botId,
                        chatId = cb.chatId, replyKeyboard = kb,
                    )
                    return
                }
                ListingEngine.looksLikeCancel(btn.label) -> {
                    ListingEngine.cancelWizard(api = api, botId = botId, chatId = cb.chatId, replyKeyboard = kb)
                    return
                }
                ListingEngine.looksLikeNew(btn.label) -> {
                    ListingEngine.startWizard(
                        context = this, store = store, api = api, botId = botId,
                        chatId = cb.chatId, replyKeyboard = kb,
                    )
                    return
                }
            }
        }

        val rawMenu = hit?.second ?: listOf(btn)
        val inSubmenuNow = inlineMenuStacks[stackKey]?.let { synchronized(it) { it.isNotEmpty() } } == true
        val menu = if (inSubmenuNow && !rawMenu.any { it.action == "back" } && rawMenu.size < 12) {
            rawMenu + InlineBtn(id = "back_${cb.messageId}", label = "⬅️ Назад", action = "back")
        } else rawMenu
        val botName = states.value[botId]?.botUsername.orEmpty()
        val userVars = BotJson.stringMap(dbUser.varsJson)
        val cbCtx = MessageCtx(
            chatId = cb.chatId,
            userId = cbUid,
            firstName = cb.firstName,
            username = cb.username,
            text = btn.label,
            messageId = cb.messageId,
        )
        val pm = btn.parseMode.ifBlank { null }
        when (btn.action) {
            "fsm" -> {
                val started = FsmEngine.startFlowById(
                    app = app, store = store, api = api, botId = botId,
                    flowIdOrName = btn.menuTarget.ifBlank { btn.text.ifBlank { btn.label } },
                    msgCtx = cbCtx,
                )
                if (!started) {
                    api.sendMessage(cb.chatId, "Сценарий анкеты не найден или выключен.")
                }
            }
            "submenu" -> {
                val sub = if (btn.submenu.isNotEmpty()) btn.submenu.withIds()
                else findTargetMenu(app, store, botId, btn.menuTarget)
                if (sub.isEmpty()) {
                    api.sendMessage(cb.chatId, BotMatcher.expandPlaceholders(btn.text.ifBlank { "Подменю пусто." }, cbCtx, botName, vars = userVars), parseMode = pm)
                    return
                }
                if (inlineMenuStacks.size > 500) inlineMenuStacks.clear()
                val stack = inlineMenuStacks.getOrPut(stackKey) { mutableListOf() }
                synchronized(stack) {
                    if (stack.size >= 16) stack.removeAt(0)
                    stack.add(null to rawMenu)
                }
                val hasBack = sub.any { it.action == "back" }
                val finalSub = if (!hasBack && sub.size < 12) {
                    sub + InlineBtn(id = "back_${btn.id.take(28)}", label = "⬅️ Назад", action = "back")
                } else sub
                if (btn.text.isNotBlank()) {
                    api.editMessageText(
                        cb.chatId, cb.messageId,
                        BotMatcher.expandPlaceholders(btn.text, cbCtx, botName, vars = userVars),
                        finalSub, parseMode = pm,
                    )
                } else {
                    api.editMessageReplyMarkup(cb.chatId, cb.messageId, finalSub)
                }
            }
            "back" -> {
                val stack = inlineMenuStacks[stackKey]
                var hasMoreLevels = false
                val prev = stack?.let {
                    synchronized(it) {
                        val item = if (it.isNotEmpty()) it.removeAt(it.lastIndex) else null
                        hasMoreLevels = it.isNotEmpty()
                        item
                    }
                }
                if (prev != null) {
                    val (prevText, prevMenuRaw) = prev
                    val prevMenu = if (hasMoreLevels && !prevMenuRaw.any { b -> b.action == "back" } && prevMenuRaw.size < 12) {
                        prevMenuRaw + InlineBtn(id = "back_${cb.messageId}", label = "⬅️ Назад", action = "back")
                    } else prevMenuRaw
                    val textToSet = btn.text.ifBlank { prevText.orEmpty() }
                    if (textToSet.isNotBlank()) {
                        api.editMessageText(
                            cb.chatId, cb.messageId,
                            BotMatcher.expandPlaceholders(textToSet, cbCtx, botName, vars = userVars),
                            prevMenu, parseMode = pm,
                        )
                    } else {
                        api.editMessageReplyMarkup(cb.chatId, cb.messageId, prevMenu)
                    }
                } else {
                    val parentMenu = findParentMenuOf(app, store, botId, cb.data)
                    if (parentMenu != null) {
                        if (btn.text.isNotBlank()) {
                            api.editMessageText(
                                cb.chatId, cb.messageId,
                                BotMatcher.expandPlaceholders(btn.text, cbCtx, botName, vars = userVars),
                                parentMenu, parseMode = pm,
                            )
                        } else {
                            api.editMessageReplyMarkup(cb.chatId, cb.messageId, parentMenu)
                        }
                    } else {
                        api.answerCallbackQuery(cb.callbackId, btn.toast.ifBlank { "Это верхний уровень меню" })
                    }
                }
            }
            "pack" -> {
                val raw = BotBrain.randomFrom(store.packs(), btn.packId, botId)
                val reply = BotMatcher.expandPlaceholders(raw, cbCtx, botName, vars = userVars)
                withTyping(api, store, botId, cb.chatId)
                if (btn.edit) api.editMessageText(cb.chatId, cb.messageId, reply, menu, parseMode = pm)
                else api.sendMessage(cb.chatId, reply, parseMode = pm)
            }
            "script" -> {
                BotBrain.runScriptWithDb(app, botId, btn.script, cb.firstName, btn.label, cb.chatId, cbCtx)
                    .onSuccess { reply ->
                        val textOut = reply.ifBlank { "…" }
                        withTyping(api, store, botId, cb.chatId)
                        if (btn.edit) api.editMessageText(cb.chatId, cb.messageId, textOut, menu, parseMode = pm)
                        else api.sendMessage(cb.chatId, textOut, parseMode = pm)
                    }
                    .onFailure { e ->
                        api.sendMessage(cb.chatId, "Ошибка скрипта: ${e.message?.take(140)}")
                    }
            }
            "llm" -> {
                val prompt = btn.text.ifBlank { btn.label }
                val decision = BotBrain.askLlmFromButton(
                    context = this,
                    store = store,
                    botId = botId,
                    chatId = cb.chatId,
                    firstName = cb.firstName,
                    promptText = prompt,
                    onLlmStart = { api.sendChatAction(cb.chatId) },
                )
                val reply = decision.reply.ifBlank { "…" }
                if (btn.edit) api.editMessageText(cb.chatId, cb.messageId, reply, menu, parseMode = pm)
                else api.sendMessage(cb.chatId, reply, parseMode = pm)
            }
            "media" -> {
                val mType = btn.mediaType.ifBlank { "photo" }
                val mRef = btn.mediaRef.ifBlank { btn.url }
                if (mRef.isBlank()) {
                    api.sendMessage(cb.chatId, "Не задан file_id или URL медиа для кнопки «${btn.label}».")
                } else {
                    withTyping(api, store, botId, cb.chatId)
                    api.sendMediaByType(
                        chatId = cb.chatId,
                        mediaType = mType,
                        mediaRef = mRef,
                        caption = BotMatcher.expandPlaceholders(btn.text, cbCtx, botName, vars = userVars),
                        parseMode = pm,
                    ).onFailure { BotLog.log(botId, "❌ Медиа кнопки не отправлено: ${it.message?.take(140)}") }
                }
            }
            "reminders_on", "reminders_off" -> {
                val on = btn.action == "reminders_on"
                if (store.remindersOn(botId) == on) {
                    // Уже в этом состоянии — как в оригинале: только всплывашка.
                    api.answerCallbackQuery(
                        cb.callbackId,
                        btn.toastNoChange.ifBlank { "Уже ${if (on) "работает" else "остановлено"}" })
                    return
                }
                store.setRemindersOn(on, botId)
                setState(botId) { it.copy(remindersOn = on) }
                BotLog.log(botId,
                    if (on) "▶️ Напоминания включены (кнопкой в Telegram)"
                    else "⏹ Напоминания выключены (кнопкой в Telegram)")
                // Правим сообщение под кнопкой, как в оригинальном боте:
                // ВСЕ кнопки меню остаются — «Стоп»/«Запуск» работают дальше.
                api.editMessageText(
                    cb.chatId, cb.messageId,
                    btn.text.ifBlank {
                        if (on) "▶️ Напоминания включены." else "⏹ Напоминания выключены."
                    },
                    inlineMenu = menu,
                    parseMode = pm,
                )
            }
            "anim" -> {
                // «Править это сообщение» — анимация прямо в сообщении под
                // кнопкой (текстовые квесты, флипбук), иначе новым сообщением.
                val spec = com.botcontrol.admin.data.Anim.decode(btn.script)
                val items = store.packs().firstOrNull { it.id == spec.packId }?.items.orEmpty()
                AnimPlayer.play(botScope, api, botId, cb.chatId, spec, cb.firstName, items,
                    menu = if (btn.edit) menu else emptyList(),
                    editMessageId = if (btn.edit) cb.messageId else null)
            }
            "dice" -> {
                api.sendDice(cb.chatId, btn.text.trim().ifBlank { "🎲" })
                    .onFailure { BotLog.log(botId, "❌ Кубик не отправлен: ${it.message?.take(120)}") }
            }
            "url", "web_app", "copy_text", "switch_inline" -> {
                // Клиентские действия Telegram: открывает ссылку/Mini App, копирует текст или подставляет inline-запрос.
            }
            else -> {
                val reply = BotMatcher.expandPlaceholders(btn.text.ifBlank { "…" }, cbCtx, botName, vars = userVars)
                withTyping(api, store, botId, cb.chatId)
                if (btn.edit) api.editMessageText(cb.chatId, cb.messageId, reply, menu, parseMode = pm)
                else api.sendMessage(cb.chatId, reply, parseMode = pm)
            }
        }
    }

    /**
     * Кнопка по её callback_data: (сама кнопка, всё меню текущего уровня).
     * Ищем рекурсивно (поддерживая вложенные подменю I-4) и только среди меню ЭТОГО бота.
     */
    private suspend fun findButton(
        app: BotControlApp,
        store: LocalBotStore,
        botId: Long,
        data: String,
    ): Pair<InlineBtn, List<InlineBtn>>? {
        for (rule in app.repository.botRules(botId)) {
            val menu = BotBrain.parseMenu(rule.menu)
            menu.findButtonRecursive(data)?.let { return it }
        }
        for (event in store.schedule(botId)) {
            val menu = event.menu.withIds()
            menu.findButtonRecursive(data)?.let { return it }
        }
        return null
    }

    /** Найти именованное меню правила по menuTarget (для action == "submenu"). */
    private suspend fun findTargetMenu(
        app: BotControlApp,
        store: LocalBotStore,
        botId: Long,
        target: String,
    ): List<InlineBtn> {
        val clean = target.trim()
        if (clean.isBlank()) return emptyList()
        for (rule in app.repository.botRules(botId)) {
            if (rule.pattern.equals(clean, ignoreCase = true) ||
                rule.pattern.removePrefix("/").equals(clean.removePrefix("/"), ignoreCase = true)
            ) {
                val m = BotBrain.parseMenu(rule.menu)
                if (m.isNotEmpty()) return m
            }
        }
        for (event in store.schedule(botId)) {
            if (event.id == clean && event.menu.isNotEmpty()) return event.menu.withIds()
        }
        return emptyList()
    }

    /** Fallback для «⬅️ Назад», если стек в памяти сброшен: найти родительский уровень для id подменю. */
    private suspend fun findParentMenuOf(
        app: BotControlApp,
        store: LocalBotStore,
        botId: Long,
        backData: String,
    ): List<InlineBtn>? {
        val targetId = backData.removePrefix("back_")
        fun matchesTarget(id: String) = id == backData || id == targetId ||
            (targetId.length >= 8 && id.startsWith(targetId))
        fun searchParent(level: List<InlineBtn>): List<InlineBtn>? {
            for (b in level) {
                if (b.submenu.any { matchesTarget(it.id) } ||
                    (matchesTarget(b.id) && b.action == "submenu")
                ) {
                    return level
                }
                searchParent(b.submenu)?.let { return it }
            }
            return null
        }
        for (rule in app.repository.botRules(botId)) {
            searchParent(BotBrain.parseMenu(rule.menu))?.let { return it }
        }
        for (event in store.schedule(botId)) {
            searchParent(event.menu.withIds())?.let { return it }
        }
        return null
    }

    // ---------- планировщик напоминаний ----------

    private suspend fun schedulerLoop(botId: Long, store: LocalBotStore, api: TelegramApi) {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sentToday = HashSet<String>()
        var curDate = ""
        while (currentCoroutineContext().isActive && states.value[botId]?.running == true) {
            delay(20_000)
            try {
                if (!store.remindersOn(botId)) continue
                val chatId = store.lastChatId(botId)
                val allEvents = store.schedule(botId)
                if (chatId == 0L && allEvents.none { it.enabled && it.toChannel }) continue

                val cal = Calendar.getInstance()
                val today = dayFormat.format(cal.time)
                if (today != curDate) {
                    sentToday.clear()
                    curDate = today
                }
                // Calendar: SUNDAY=1 … SATURDAY=7 → Пн=1 … Вс=7
                val rawDow = cal.get(Calendar.DAY_OF_WEEK)
                val dow = if (rawDow == Calendar.SUNDAY) 7 else rawDow - 1

                val year = cal.get(Calendar.YEAR)
                val doy = cal.get(Calendar.DAY_OF_YEAR)
                val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val modDefaultThread = store.groupModeration(botId).defaultThreadId
                val events = allEvents
                    .filter { e ->
                        e.enabled && (if (e.onceDate.isNotBlank()) e.onceDate == today else e.days.contains(dow))
                    }
                    .sortedBy { ScheduleJitter.effectiveMinute(it.hour, it.minute, it.id, year, doy, it.jitterMin) }
                val onceDoneIds = mutableListOf<String>()
                for (e in events) {
                    val key = "${e.id}@$today"
                    if (key in sentToday) continue
                    val targetMin = ScheduleJitter.effectiveMinute(e.hour, e.minute, e.id, year, doy, e.jitterMin)
                    val ageMin = nowMin - targetMin
                    if (ageMin < 0) continue          // ещё впереди
                    if (ageMin > CATCH_UP_MINUTES) {  // слишком старое — тихо пропускаем
                        sentToday.add(key)
                        continue
                    }
                    var markDone = true
                    val postText = e.pickVariant(doy)
                    val pm = e.parseMode.ifBlank { null }
                    val targetThread = e.threadId ?: if (e.toChannel || chatId < 0L) modDefaultThread else null
                    if (e.toChannel) {
                        // Пост в канал публикаций (например, дайджест 10:20/20:40).
                        val channel = store.channelId(botId)
                        if (channel.isBlank()) {
                            BotLog.log(botId, "⚠️ Событие ${e.timeLabel()} в канал, но канал не задан")
                        } else {
                            val res = api.sendTo(channel, postText, e.menu.withIds(), parseMode = pm, messageThreadId = targetThread)
                            if (res.isSuccess) {
                                BotLog.log(botId, "📢 Пост в канал $channel: ${postText.take(60)}")
                            } else {
                                val err = res.exceptionOrNull()
                                val apiErr = err as? TelegramApiException
                                BotLog.log(botId, "❌ Пост в канал не отправлен: ${err?.message ?: "?"}")
                                if (apiErr?.isRateLimit == true) {
                                    markDone = false
                                    val waitSec = TelegramRateLimit.backoffSec(apiErr.retryAfterSec, 1, minSec = 15L, maxSec = 300L)
                                    delay(waitSec * 1000L)
                                } else if (apiErr == null || apiErr.httpCode >= 500) {
                                    markDone = false
                                }
                            }
                        }
                    } else if (chatId != 0L) {
                        withTyping(api, store, botId, chatId)
                        val res = api.sendMessage(chatId, postText, inlineMenu = e.menu.withIds(), parseMode = pm, messageThreadId = targetThread)
                        if (res.isSuccess) {
                            BotLog.log(botId, "⏰ Напоминание ${e.timeLabel()}: ${postText.take(60)}")
                        } else {
                            val err = res.exceptionOrNull()
                            val apiErr = err as? TelegramApiException
                            BotLog.log(botId, "❌ Напоминание не отправлено: ${err?.message ?: "?"}")
                            if (apiErr?.isRateLimit == true) {
                                markDone = false
                                val waitSec = TelegramRateLimit.backoffSec(apiErr.retryAfterSec, 1, minSec = 15L, maxSec = 300L)
                                delay(waitSec * 1000L)
                            } else if (apiErr == null || apiErr.httpCode >= 500) {
                                markDone = false
                            }
                        }
                    } else {
                        markDone = false
                    }
                    if (markDone) {
                        sentToday.add(key)
                        if (e.onceDate.isNotBlank()) onceDoneIds.add(e.id)
                    }
                    delay(400)
                }
                if (onceDoneIds.isNotEmpty()) {
                    val refreshed = store.schedule(botId).map { ev ->
                        if (ev.id in onceDoneIds) ev.copy(enabled = false) else ev
                    }
                    store.setSchedule(refreshed, botId)
                }
                val remOn = store.remindersOn(botId)
                setState(botId) { it.copy(remindersOn = remOn) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BotLog.log(botId, "⚠️ Планировщик: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ---------- notification ----------

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Мой бот", NotificationManager.IMPORTANCE_LOW)
            )
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Запуск бота…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun notifyContent(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "local_bot"
        private const val NOTIFICATION_ID = 2001
        private const val POLL_ERROR_MS = 10_000L
        private const val CATCH_UP_MINUTES = 5L
        const val ACTION_STOP = "com.botcontrol.admin.STOP_LOCAL_BOT"
        const val ACTION_START_BOT = "com.botcontrol.admin.START_ONE_BOT"
        const val ACTION_STOP_BOT = "com.botcontrol.admin.STOP_ONE_BOT"
        const val EXTRA_BOT_ID = "bot_id"

        val states = MutableStateFlow<Map<Long, LocalBotState>>(emptyMap())

        /** Суммарный статус для уведомлений/виджетов. */
        fun anyRunning(): Boolean = states.value.values.any { it.running }

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, LocalBotService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocalBotService::class.java).setAction(ACTION_STOP))
        }

        /** Запустить одного бота. */
        fun startBot(context: Context, botId: Long) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, LocalBotService::class.java)
                    .setAction(ACTION_START_BOT).putExtra(EXTRA_BOT_ID, botId))
        }

        /** Остановить одного бота (остальные продолжают). */
        fun stopBot(context: Context, botId: Long) {
            context.startService(
                Intent(context, LocalBotService::class.java)
                    .setAction(ACTION_STOP_BOT).putExtra(EXTRA_BOT_ID, botId))
        }
    }
}
