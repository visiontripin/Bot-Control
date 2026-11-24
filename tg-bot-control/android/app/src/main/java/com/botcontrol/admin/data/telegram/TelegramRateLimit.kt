package com.botcontrol.admin.data.telegram

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Структурированная ошибка HTTP / API Telegram.
 * [retryAfterSec] > 0 означает, что Telegram (HTTP 429) явно попросил
 * подождать столько секунд перед повтором для данного токена/метода/чата.
 */
class TelegramApiException(
    val method: String,
    val httpCode: Int,
    val description: String = "",
    val retryAfterSec: Long = 0L,
    message: String,
) : IllegalStateException(message) {
    val isRateLimit: Boolean get() = httpCode == 429
    val isAuthError: Boolean get() = httpCode == 401 || httpCode == 404
}

/**
 * Разбор HTTP 429 (parameters.retry_after / Retry-After) и per-token защита
 * от повторных ударов по api.telegram.org внутри окна flood-wait.
 *
 * Чистый Kotlin (без Android/Gson/OkHttp) — компилируется и проверяется
 * напрямую в kotlin-harness.
 */
object TelegramRateLimit {

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()

    private val JSON_DESC_RE = Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val JSON_RETRY_RE = Regex(""""retry_after"\s*:\s*(\d+(?:\.\d+)?)""")
    private val TEXT_RETRY_RE = Regex("""retry[_\s]+after\D{0,6}(\d+)""", RegexOption.IGNORE_CASE)

    /** Короткий SHA-256 ключ токена: сам токен в памяти карт не храним. */
    fun tokenKey(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun bucketKey(token: String, bucket: String): String = "${tokenKey(token)}:$bucket"

    fun parseDescription(body: String): String {
        val m = JSON_DESC_RE.find(body) ?: return ""
        return m.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()
    }

    fun parseRetryAfterSec(body: String, retryAfterHeader: String? = null): Long {
        JSON_RETRY_RE.find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.let { v ->
            if (v > 0) return ceil(v).toLong()
        }
        retryAfterHeader?.trim()?.toDoubleOrNull()?.let { v ->
            if (v > 0) return ceil(v).toLong()
        }
        val desc = parseDescription(body).ifBlank { body }
        TEXT_RETRY_RE.find(desc)?.groupValues?.get(1)?.toLongOrNull()?.let { v ->
            if (v > 0) return v
        }
        return 0L
    }

    fun buildHttpError(
        method: String,
        httpCode: Int,
        body: String,
        retryAfterHeader: String? = null,
    ): TelegramApiException {
        val desc = parseDescription(body)
        val retryAfter = if (httpCode == 429) parseRetryAfterSec(body, retryAfterHeader) else 0L
        val msg = if (httpCode == 429) {
            val waitHint = if (retryAfter > 0L) ": подождите $retryAfter с" else ""
            val isTelegramBody = desc.isNotBlank() || JSON_RETRY_RE.containsMatchIn(body)
            val detail = desc.ifBlank { body.replace(Regex("\\s+"), " ").trim().take(160) }
            val extra = if (detail.isNotBlank()) " ($detail)" else ""
            if (method == "getMe") {
                if (isTelegramBody) {
                    "Telegram ограничил запросы (HTTP 429$waitHint)$extra — это лимит на токен бота, смена VPN/Wi-Fi не поможет"
                } else {
                    "Telegram или сеть вернули HTTP 429$waitHint$extra — подождите и попробуйте снова"
                }
            } else {
                "$method HTTP 429$waitHint$extra"
            }
        } else {
            val detail = desc.ifBlank { body.replace(Regex("\\s+"), " ").trim().take(200) }
            if (detail.isNotBlank()) "$method HTTP $httpCode: $detail"
            else "$method HTTP $httpCode"
        }
        return TelegramApiException(
            method = method,
            httpCode = httpCode,
            description = desc,
            retryAfterSec = retryAfter,
            message = msg,
        )
    }

    fun recordCooldown(
        token: String,
        bucket: String,
        retryAfterSec: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (retryAfterSec <= 0L) return
        val bounded = retryAfterSec.coerceAtMost(86_400L)
        val until = nowMs + (bounded + 1L) * 1000L
        val key = bucketKey(token, bucket)
        cooldownUntilMs.compute(key) { _, prev ->
            if (prev == null || until > prev) until else prev
        }
    }

    fun remainingCooldownSec(
        token: String,
        bucket: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val key = bucketKey(token, bucket)
        val until = cooldownUntilMs[key] ?: return 0L
        val diff = until - nowMs
        if (diff <= 0L) {
            cooldownUntilMs.remove(key, until)
            return 0L
        }
        return (diff + 999L) / 1000L
    }

    fun clearCooldowns(token: String) {
        val prefix = tokenKey(token) + ":"
        cooldownUntilMs.keys.removeIf { it.startsWith(prefix) }
    }

    /** Пауза перед повтором в фоновых циклах при HTTP 429: retry_after + 1 с или экспоненциальный backoff. */
    fun backoffSec(retryAfterSec: Long, streak: Int, minSec: Long = 15L, maxSec: Long = 300L): Long {
        if (retryAfterSec > 0L) return (retryAfterSec + 1L).coerceAtMost(86_400L)
        val exp = (streak - 1).coerceIn(0, 5)
        return minOf(maxSec, minSec * (1L shl exp))
    }
}
