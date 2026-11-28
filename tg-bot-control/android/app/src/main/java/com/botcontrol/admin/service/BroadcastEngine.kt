package com.botcontrol.admin.service

import com.botcontrol.admin.BotControlApp
import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.BotMatcher
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.MessageCtx
import com.botcontrol.admin.data.local.BotUserEntity
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.data.telegram.TelegramApiException
import com.botcontrol.admin.data.withIds
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class BroadcastSegment(val title: String) {
    ALL("Все активные"),
    ROLE_USER("Роль: user"),
    ROLE_VIP("Роль: vip"),
    ROLE_ADMIN("Роль: admin"),
    TAG("По тегу"),
}

data class BroadcastRequest(
    val botId: Long,
    val text: String,
    val parseMode: String = "",
    val segment: BroadcastSegment = BroadcastSegment.ALL,
    val tagFilter: String = "",
    val menu: List<InlineBtn> = emptyList(),
    val mediaType: String = "",
    val mediaRef: String = "",
    val disablePreview: Boolean = false,
    val silent: Boolean = false,
)

data class BroadcastStatus(
    val botId: Long = 0L,
    val running: Boolean = false,
    val total: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val blocked403: Int = 0,
    val lastError: String = "",
    val finishedAt: Long = 0L,
)

/**
 * Движок массовой рассылки подписчикам (Этап 2):
 *  - сегментация (все активные / роль / тег);
 *  - пауза ~45 мс между отправками (~22 msg/s < 30 msg/s лимита Telegram);
 *  - обработка HTTP 429 retry_after;
 *  - автоматическая отметка isBlocked = true при HTTP 403 (bot was blocked).
 */
object BroadcastEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _statuses = MutableStateFlow<Map<Long, BroadcastStatus>>(emptyMap())
    val statuses: StateFlow<Map<Long, BroadcastStatus>> = _statuses

    fun filterAudience(
        users: List<BotUserEntity>,
        segment: BroadcastSegment,
        tagFilter: String = "",
    ): List<BotUserEntity> {
        val cleanTag = tagFilter.trim().lowercase()
        return users.filter { u ->
            if (u.chatId == 0L || u.isBlocked || u.isBanned) return@filter false
            when (segment) {
                BroadcastSegment.ALL -> true
                BroadcastSegment.ROLE_USER -> u.role.equals("user", ignoreCase = true)
                BroadcastSegment.ROLE_VIP -> u.role.equals("vip", ignoreCase = true)
                BroadcastSegment.ROLE_ADMIN -> u.role.equals("admin", ignoreCase = true)
                BroadcastSegment.TAG -> {
                    if (cleanTag.isBlank()) true
                    else u.tags.split(',').any { it.trim().equals(cleanTag, ignoreCase = true) }
                }
            }
        }
    }

    fun start(app: BotControlApp, req: BroadcastRequest) {
        val botId = req.botId
        jobs.remove(botId)?.cancel()

        val job = scope.launch {
            val token = app.localStore.botToken(botId)
            if (token.isNullOrBlank()) {
                updateStatus(botId, BroadcastStatus(botId = botId, lastError = "Токен бота не задан"))
                return@launch
            }
            val allUsers = app.repository.botUsers(botId)
            val targets = filterAudience(allUsers, req.segment, req.tagFilter)
            if (targets.isEmpty()) {
                updateStatus(
                    botId,
                    BroadcastStatus(
                        botId = botId,
                        total = 0,
                        lastError = "Нет подходящих получателей в сегменте «${req.segment.title}»",
                        finishedAt = System.currentTimeMillis() / 1000L,
                    ),
                )
                return@launch
            }

            val api = TelegramApi(token)
            val botName = app.localStore.botUsername(botId)
            val pm = req.parseMode.ifBlank { null }
            val menu = req.menu.withIds()
            var sent = 0
            var failed = 0
            var blocked = 0
            var lastErr = ""

            updateStatus(botId, BroadcastStatus(botId = botId, running = true, total = targets.size))
            BotLog.log(botId, "📣 Старт рассылки: ${targets.size} получателей (${req.segment.title})")

            for (user in targets) {
                if (!isActive) break
                val vars = BotJson.stringMap(user.varsJson)
                val ctx = MessageCtx(
                    chatId = user.chatId,
                    userId = if (user.userId != 0L) user.userId else user.chatId,
                    firstName = user.firstName,
                    username = user.username,
                    chatType = user.chatType,
                )
                val body = BotMatcher.expandPlaceholders(req.text, ctx, botName, vars = vars)
                var res = sendOnce(api, user.chatId, body, pm, menu, req)
                val err = res.exceptionOrNull() as? TelegramApiException
                if (res.isFailure && err?.isRateLimit == true) {
                    val waitMs = ((err.retryAfterSec.coerceIn(1L, 60L)) + 1L) * 1000L
                    BotLog.log(botId, "⏳ Рассылка HTTP 429: пауза ${waitMs / 1000} с")
                    delay(waitMs)
                    res = sendOnce(api, user.chatId, body, pm, menu, req)
                }

                if (res.isSuccess) {
                    sent++
                } else {
                    val finalErr = res.exceptionOrNull()
                    val apiErr = finalErr as? TelegramApiException
                    if (apiErr?.isBlockedOrForbidden == true) {
                        blocked++
                        app.repository.markUserBlocked(botId, user.chatId, true)
                    } else {
                        failed++
                        lastErr = finalErr?.message?.take(120).orEmpty()
                    }
                }
                updateStatus(
                    botId,
                    BroadcastStatus(
                        botId = botId,
                        running = true,
                        total = targets.size,
                        sent = sent,
                        failed = failed,
                        blocked403 = blocked,
                        lastError = lastErr,
                    ),
                )
                delay(45L)
            }

            updateStatus(
                botId,
                BroadcastStatus(
                    botId = botId,
                    running = false,
                    total = targets.size,
                    sent = sent,
                    failed = failed,
                    blocked403 = blocked,
                    lastError = lastErr,
                    finishedAt = System.currentTimeMillis() / 1000L,
                ),
            )
            BotLog.log(
                botId,
                "📣 Рассылка завершена: доставлено $sent из ${targets.size}, 403/блок: $blocked, ошибок: $failed",
            )
        }
        jobs[botId] = job
    }

    fun cancel(botId: Long) {
        jobs.remove(botId)?.cancel()
        val cur = _statuses.value[botId] ?: return
        updateStatus(botId, cur.copy(running = false, finishedAt = System.currentTimeMillis() / 1000L))
        BotLog.log(botId, "⏹ Рассылка остановлена вручную")
    }

    private suspend fun sendOnce(
        api: TelegramApi,
        chatId: Long,
        body: String,
        pm: String?,
        menu: List<InlineBtn>,
        req: BroadcastRequest,
    ): Result<Unit> =
        if (req.mediaType.isNotBlank() && req.mediaRef.isNotBlank()) {
            api.sendMediaByType(
                chatId = chatId,
                mediaType = req.mediaType,
                mediaRef = req.mediaRef,
                caption = body,
                parseMode = pm,
                silent = req.silent,
                inlineMenu = menu,
            ).map { Unit }
        } else {
            api.sendMessage(
                chatId = chatId,
                text = body,
                inlineMenu = menu,
                parseMode = pm,
                disablePreview = req.disablePreview,
                silent = req.silent,
            )
        }

    private fun updateStatus(botId: Long, status: BroadcastStatus) {
        _statuses.value = _statuses.value + (botId to status)
    }
}
