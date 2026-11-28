package com.botcontrol.admin.service

import com.botcontrol.admin.data.Anim
import com.botcontrol.admin.data.AnimSpec
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.data.telegram.TelegramApiException
import com.botcontrol.admin.data.telegram.TelegramRateLimit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Проигрывает [AnimSpec] в Telegram: первый кадр — новым сообщением (или
 * правкой сообщения под кнопкой), дальше editMessageText каждые N мс,
 * в конце — итоговый текст и кнопки.
 *
 * Фото-кадры («photo: …», см. [Anim.photoFrames]): первый — sendPhoto,
 * дальше editMessageMedia (новая картинка) или editMessageCaption (та же
 * картинка, новая подпись). Загруженные с телефона картинки запоминаются
 * по file_id — повторный показ не грузит файл заново.
 *
 * • Работает в фоне (scope сервиса) — не блокирует ответы на другие сообщения.
 * • Одна анимация на чат: новая останавливает предыдущую.
 * • 429 Too Many Requests → ждём retry_after и повторяем кадр один раз;
 *   «message is not modified» — пропускаем; прочие ошибки — стоп + журнал.
 */
object AnimPlayer {

    private val jobs = ConcurrentHashMap<String, Job>()
    /** «botId|источник» → file_id Telegram (картинка уже загружена). */
    private val fileIds = ConcurrentHashMap<String, String>()

    fun play(
        scope: CoroutineScope,
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        spec: AnimSpec,
        user: String,
        packItems: List<String>,
        menu: List<InlineBtn>,
        editMessageId: Int? = null,
        messageThreadId: Int? = null,
    ) {
        val key = "$botId:$chatId"
        jobs.remove(key)?.cancel()
        val job = scope.launch {
            try {
                run(api, botId, chatId, spec, user, packItems, menu, editMessageId, messageThreadId)
            } finally {
                jobs.remove(key, coroutineContext[Job])
            }
        }
        jobs[key] = job
    }

    private suspend fun run(
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        spec: AnimSpec,
        user: String,
        packItems: List<String>,
        menu: List<InlineBtn>,
        editMessageId: Int?,
        messageThreadId: Int?,
    ) {
        val finalText = Anim.final(spec, user, packItems)
        if (Anim.isPhotoAnim(spec)) {
            runPhoto(api, botId, chatId, spec, user, finalText, menu, editMessageId)
            return
        }
        val frames = Anim.frames(spec, user)
        val step = Anim.interval(spec)
        if (frames.isEmpty() && finalText.isBlank()) return
        val mode = if (spec.mono) "HTML" else null
        fun fmt(raw: String): String {
            val s = Anim.keepLayout(raw)
            return if (spec.mono) "<pre>${Anim.html(s)}</pre>" else s
        }

        BotLog.log(botId, "🎞 Анимация «${Anim.preset(spec.preset).title}»: кадров ${frames.size}, шаг ${step} мс")

        suspend fun sendNew(text: String, withMenu: Boolean): Int? {
            val res = callWithRateLimitRetry(botId) {
                api.sendMessageForId(
                    chatId = chatId,
                    text = fmt(text),
                    parseMode = mode,
                    inlineMenu = if (withMenu) menu else emptyList(),
                    messageThreadId = messageThreadId,
                )
            }
            res.onFailure { BotLog.log(botId, "❌ Анимация: первый кадр не отправлен: ${it.message?.take(160)}") }
            return res.getOrNull()?.takeIf { it > 0 }
        }

        var mid: Int
        var start = 0
        if (editMessageId == null) {
            mid = sendNew(frames.firstOrNull() ?: finalText, frames.isEmpty()) ?: return
            start = 1
            if (frames.isEmpty()) return
        } else {
            mid = editMessageId
        }

        for (i in start until frames.size) {
            if (i > 0 || editMessageId == null) delay(step)
            val err = attempt(botId, "кадр #${i + 1}") { api.editMessageText(chatId, mid, fmt(frames[i]), emptyList(), mode) }
            if (err != null) {
                // Под кнопкой фото (в нём нет текста) — анимируем новым сообщением.
                if (i == 0 && editMessageId != null) {
                    BotLog.log(botId, "ℹ️ Анимация: сообщение под кнопкой не текстовое — показываю новым сообщением")
                    mid = sendNew(frames[0], false) ?: return
                    continue
                }
                BotLog.log(botId, "❌ Анимация остановлена: ${err.take(160)}")
                return
            }
        }
        // Итог: обычный текст (не моноширинный) + кнопки меню.
        val last = if (finalText.isNotBlank()) finalText else null
        if (last != null || menu.isNotEmpty()) {
            delay(step)
            val err = if (last != null) attempt(botId, "итог") { api.editMessageText(chatId, mid, last, menu, null) }
            else attempt(botId, "итог") { api.editMessageText(chatId, mid, fmt(frames.last()), menu, mode) }
            err?.let { BotLog.log(botId, "❌ Анимация: итог не показан: ${it.take(160)}") }
        }
    }

    /** Фото-анимация: sendPhoto → editMessageMedia / editMessageCaption. */
    private suspend fun runPhoto(
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        spec: AnimSpec,
        user: String,
        finalText: String,
        menu: List<InlineBtn>,
        editMessageId: Int?,
    ) {
        val frames = Anim.photoFrames(spec, user)
        if (frames.isEmpty()) return
        val step = Anim.interval(spec)
        BotLog.log(botId, "🖼 Фото-анимация: кадров ${frames.size}, шаг ${step} мс")

        fun resolve(src: String) = fileIds["$botId|$src"] ?: src
        fun remember(src: String, fileId: String) {
            if (fileId.isNotBlank() && Anim.isLocalSrc(src)) fileIds["$botId|$src"] = fileId
        }
        val onlyOne = frames.size == 1 && finalText.isBlank()

        suspend fun sendFirst(f: Anim.PhotoFrame): Int? {
            val r = callWithRateLimitRetry(botId) {
                api.sendPhotoForId(chatId, resolve(f.src), f.caption, if (onlyOne) menu else emptyList())
            }
            r.onFailure { BotLog.log(botId, "❌ Фото-анимация: первый кадр не отправлен: ${it.message?.take(160)}") }
            val (id, fileId) = r.getOrNull() ?: return null
            remember(f.src, fileId)
            return id.takeIf { it > 0 }
        }

        var mid: Int? = editMessageId
        var curSrc: String? = null
        for ((i, f) in frames.withIndex()) {
            val m = mid
            if (m == null) {
                mid = sendFirst(f) ?: return
                curSrc = f.src
                continue
            }
            if (i > 0 || editMessageId == null) delay(step)
            // Без итогового текста меню вешаем на последний кадр: иначе
            // editMessageMedia/Caption снимет клавиатуру, и кнопки пропадут
            // (навигация по слайдам под одной картинкой).
            val tailMenu = if (i == frames.lastIndex && finalText.isBlank()) menu else emptyList()
            val err = if (f.src == curSrc) {
                attempt(botId, "фото-кадр #${i + 1}") { api.editMessageCaption(chatId, m, f.caption, tailMenu) }
            } else {
                attempt(botId, "фото-кадр #${i + 1}") {
                    api.editMessageMedia(chatId, m, resolve(f.src), f.caption, tailMenu).onSuccess { remember(f.src, it) }
                }
            }
            if (err != null) {
                if (i == 0 && editMessageId != null) {
                    // Под кнопкой текстовое сообщение — картинку в него не вставить.
                    BotLog.log(botId, "ℹ️ Фото-анимация: сообщение под кнопкой текстовое — показываю новым сообщением")
                    mid = sendFirst(f) ?: return
                    curSrc = f.src
                    continue
                }
                BotLog.log(botId, "❌ Фото-анимация остановлена: ${err.take(160)}")
                return
            }
            curSrc = f.src
        }
        val m = mid ?: return
        if (!onlyOne && finalText.isNotBlank()) {
            delay(step)
            attempt(botId, "фото-итог") { api.editMessageCaption(chatId, m, finalText, menu) }
                ?.let { BotLog.log(botId, "❌ Фото-анимация: итог не показан: ${it.take(160)}") }
        }
    }

    private suspend fun <T> callWithRateLimitRetry(
        botId: Long,
        block: suspend () -> Result<T>,
    ): Result<T> {
        val first = block()
        if (first.isSuccess) return first
        val err = first.exceptionOrNull()
        val apiErr = err as? TelegramApiException
        val msg = err?.message.orEmpty()
        if (apiErr?.isRateLimit == true || msg.contains("HTTP 429")) {
            val retrySec = apiErr?.retryAfterSec?.takeIf { it > 0L }
                ?: TelegramRateLimit.parseRetryAfterSec(msg).takeIf { it > 0L }
                ?: 3L
            if (retrySec > 60L) return first
            val waitSec = retrySec.coerceIn(1L, 60L)
            BotLog.log(botId, "⏳ Анимация (первый кадр): HTTP 429 → retry_after=${retrySec} с, пауза $waitSec с")
            delay((waitSec + 1L) * 1000L + 50L)
            return block()
        }
        return first
    }

    /**
     * Один вызов Bot API с повтором при 429 (ждём retry_after).
     * null — успех («message is not modified» тоже успех + запись в журнал), иначе текст ошибки.
     */
    private suspend fun attempt(botId: Long, label: String = "", block: suspend () -> Result<*>): String? {
        val tag = if (label.isNotBlank()) " ($label)" else ""
        repeat(2) { n ->
            val r = block()
            if (r.isSuccess) return null
            val err = r.exceptionOrNull()
            val apiErr = err as? TelegramApiException
            val msg = err?.message.orEmpty()
            when {
                msg.contains("not modified", ignoreCase = true) -> {
                    BotLog.log(botId, "ℹ️ Анимация$tag: кадр пропущен (message is not modified)")
                    return null
                }
                (apiErr?.isRateLimit == true || msg.contains("HTTP 429")) && n == 0 -> {
                    val retrySec = apiErr?.retryAfterSec?.takeIf { it > 0L }
                        ?: TelegramRateLimit.parseRetryAfterSec(msg).takeIf { it > 0L }
                        ?: 2L
                    if (retrySec > 60L) return msg.ifBlank { "HTTP 429: Telegram просит паузу $retrySec с" }
                    val waitSec = retrySec.coerceIn(1L, 60L)
                    BotLog.log(botId, "⏳ Анимация$tag: HTTP 429 → retry_after=${retrySec} с, ждём $waitSec с")
                    delay((waitSec + 1L) * 1000L + 50L)
                }
                else -> return msg.ifBlank { "ошибка" }
            }
        }
        return "HTTP 429: Telegram просит паузу"
    }
}
