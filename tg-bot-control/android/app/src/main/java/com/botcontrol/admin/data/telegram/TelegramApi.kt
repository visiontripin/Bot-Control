package com.botcontrol.admin.data.telegram

import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.MenuCommand
import com.botcontrol.admin.data.layoutRows
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One incoming Telegram message (with metadata for rules, scripts, media and forums). */
data class TgMessage(
    val updateId: Long,
    val chatId: Long,
    val text: String,
    val firstName: String,
    /** Пусто для текста; иначе человекочитаемый тип: «фото», «стикер», «голосовое»… */
    val kind: String = "",
    /** file_id наибольшего фото (kind == "фото"). */
    val photoId: String = "",
    val username: String = "",
    val userId: Long = 0L,
    val chatType: String = "",
    val messageId: Int = 0,
    val messageThreadId: Int? = null,
    val caption: String = "",
    /** file_id любого входящего вложения (стикер, гифка, файл, видео, голосовое, аудио, фото). */
    val fileId: String = "",
    val replyToText: String = "",
    val locationLat: Double? = null,
    val locationLon: Double? = null,
    val contactPhone: String = "",
)

/** Нажатие inline-кнопки (callback_query). */
data class TgCallback(
    val updateId: Long,
    val callbackId: String,
    val chatId: Long,
    val messageId: Int,
    val data: String,
    val firstName: String,
)

/**
 * Minimal Telegram Bot API client (long polling) for the on-phone bot mode.
 * Endpoints: getMe, getUpdates, sendMessage, sendChatAction,
 * answerCallbackQuery, editMessageText, deleteWebhook — plain HTTPS + JSON.
 */
class TelegramApi(private val token: String) {

    private val json = "application/json; charset=utf-8".toMediaType()

    private val fastClient: OkHttpClient = sharedClient

    // Long polling holds the connection open -> generous read timeout, shared pool.
    private val pollClient: OkHttpClient = sharedClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun url(method: String) = "https://api.telegram.org/bot$token/$method"

    private suspend fun <T> executeCall(
        client: OkHttpClient,
        request: Request,
        block: (Response) -> T,
    ): T = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use(block)
                    if (cont.isActive) cont.resume(value)
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }
        })
    }

    private fun httpFailure(
        method: String,
        bucket: String,
        resp: Response,
        body: String,
    ): TelegramApiException {
        val err = TelegramRateLimit.buildHttpError(method, resp.code, body, resp.header("Retry-After"))
        if (err.isRateLimit && err.retryAfterSec > 0L) {
            TelegramRateLimit.recordCooldown(token, bucket, err.retryAfterSec)
        }
        return err
    }

    private inline fun <T> parse(method: String, body: String, block: (root: com.google.gson.JsonObject) -> T): T {
        val root = JsonParser.parseString(body).asJsonObject
        if (!root.get("ok").asBoolean) {
            val code = root.get("error_code")?.asInt ?: 0
            if (code == 429) {
                throw TelegramRateLimit.buildHttpError(method, 429, body)
            }
            val desc = root.get("description")?.asString ?: "unknown error"
            error("Telegram API: $desc")
        }
        return block(root)
    }

    private suspend fun <T> withShortRateLimitRetry(
        block: suspend () -> Result<T>,
    ): Result<T> {
        val first = block()
        if (first.isSuccess) return first
        val err = first.exceptionOrNull() as? TelegramApiException ?: return first
        if (!err.isRateLimit || err.retryAfterSec !in 1L..15L) return first
        delay((err.retryAfterSec + 1L) * 1000L + 50L)
        return block()
    }

    /**
     * Returns @username of the bot. MUST be suspend: performs an async
     * network call on OkHttp's dispatcher — calling it from the main thread
     * is safe and never throws NetworkOnMainThreadException.
     */
    suspend fun validateToken(): Result<String> {
        // одна повторная попытка только при обрыве сети (IOException);
        // HTTP 429 / 401 / 404 НЕ повторяем через 700 мс — это только усиливает лимит.
        var last: Result<String> = Result.failure(IllegalStateException("не запускалось"))
        for (attempt in 1..2) {
            last = validateOnce()
            if (last.isSuccess) return last
            val err = last.exceptionOrNull()
            if (err is TelegramApiException) return last
            if (attempt == 1) delay(700)
        }
        return last
    }

    private suspend fun validateOnce(): Result<String> {
        val rem = TelegramRateLimit.remainingCooldownSec(token, "me")
        if (rem > 0L) {
            return Result.failure(
                TelegramApiException(
                    method = "getMe",
                    httpCode = 429,
                    description = "Too Many Requests: retry after $rem",
                    retryAfterSec = rem,
                    message = "Telegram ограничил запросы (HTTP 429: подождите ещё $rem с) — это лимит на токен бота, смена VPN/Wi-Fi не поможет",
                )
            )
        }
        return try {
            executeCall(fastClient, Request.Builder().url(url("getMe")).build()) { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 404 -> Result.failure(
                        TelegramApiException(
                            method = "getMe",
                            httpCode = 404,
                            description = TelegramRateLimit.parseDescription(text),
                            message = "Токен не похож на правильный (404). Формат: 123456789:AAHfqT… — проверь у @BotFather",
                        )
                    )
                    resp.code == 401 -> Result.failure(
                        TelegramApiException(
                            method = "getMe",
                            httpCode = 401,
                            description = TelegramRateLimit.parseDescription(text),
                            message = "Токен неверный (401): Telegram его не принял. Проверь, что скопирован целиком, или создай новый через /newbot",
                        )
                    )
                    !resp.isSuccessful -> Result.failure(httpFailure("getMe", "me", resp, text))
                    else -> parse("getMe", text) { root ->
                        Result.success(root.getAsJsonObject("result").get("username").asString)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.failure(IllegalStateException(
                "Нет связи с api.telegram.org (${e.javaClass.simpleName}) — проверь интернет; если в сети провайдера Telegram API блокируется, попробуйте мобильный интернет или VPN"))
        } catch (e: Throwable) {
            Result.failure(
                e as? TelegramApiException ?: IllegalStateException(
                    "Не удалось проверить токен: ${e.message ?: e.javaClass.simpleName}"
                )
            )
        }
    }

    /** Long poll. [timeoutSec] up to 50; returns messages, callbacks and the next offset. */
    suspend fun getUpdates(
        offset: Long,
        timeoutSec: Int = 25,
    ): Triple<List<TgMessage>, List<TgCallback>, Long> {
        val rem = TelegramRateLimit.remainingCooldownSec(token, "poll")
        if (rem > 0L) {
            throw TelegramApiException(
                method = "getUpdates",
                httpCode = 429,
                description = "Too Many Requests: retry after $rem",
                retryAfterSec = rem,
                message = "getUpdates HTTP 429: подождите ещё $rem с",
            )
        }
        val req = Request.Builder()
            .url("${url("getUpdates")}?timeout=$timeoutSec&offset=$offset" +
                "&allowed_updates=%5B%22message%22,%22callback_query%22%5D")
            .build()
        return executeCall(pollClient, req) { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw httpFailure("getUpdates", "poll", resp, text)
            parse("getUpdates", text) { root ->
                val messages = mutableListOf<TgMessage>()
                val callbacks = mutableListOf<TgCallback>()
                var maxId = if (offset > 0) offset - 1 else 0L
                root.getAsJsonArray("result")?.forEach { element ->
                    val update = element.asJsonObject
                    val updateId = update.get("update_id").asLong
                    if (updateId > maxId) maxId = updateId

                    update.getAsJsonObject("message")?.let { message ->
                        val chat = message.getAsJsonObject("chat") ?: return@forEach
                        val kind = when {
                            message.get("text")?.isJsonNull == false -> ""
                            message.get("photo")?.isJsonNull == false -> "фото"
                            message.get("sticker")?.isJsonNull == false -> "стикер"
                            message.get("voice")?.isJsonNull == false -> "голосовое"
                            message.get("video_note")?.isJsonNull == false -> "видеосообщение"
                            message.get("video")?.isJsonNull == false -> "видео"
                            message.get("animation")?.isJsonNull == false -> "гифка"
                            message.get("document")?.isJsonNull == false -> "файл"
                            message.get("audio")?.isJsonNull == false -> "аудио"
                            message.get("location")?.isJsonNull == false -> "геолокация"
                            message.get("contact")?.isJsonNull == false -> "контакт"
                            else -> "вложение"
                        }
                        val photoId = if (kind == "фото")
                            message.getAsJsonArray("photo")?.lastOrNull()
                                ?.asJsonObject?.get("file_id")?.asString ?: ""
                        else ""
                        val attachFileId = when (kind) {
                            "фото" -> photoId
                            "стикер" -> message.getAsJsonObject("sticker")?.get("file_id")?.asString ?: ""
                            "гифка" -> message.getAsJsonObject("animation")?.get("file_id")?.asString ?: ""
                            "файл" -> message.getAsJsonObject("document")?.get("file_id")?.asString ?: ""
                            "видео" -> message.getAsJsonObject("video")?.get("file_id")?.asString ?: ""
                            "голосовое" -> message.getAsJsonObject("voice")?.get("file_id")?.asString ?: ""
                            "аудио" -> message.getAsJsonObject("audio")?.get("file_id")?.asString ?: ""
                            "видеосообщение" -> message.getAsJsonObject("video_note")?.get("file_id")?.asString ?: ""
                            else -> ""
                        }
                        val from = message.getAsJsonObject("from")
                        val replyMsg = message.getAsJsonObject("reply_to_message")
                        val replyText = replyMsg?.get("text")?.takeIf { !it.isJsonNull }?.asString
                            ?: replyMsg?.get("caption")?.takeIf { !it.isJsonNull }?.asString
                            ?: ""
                        val loc = message.getAsJsonObject("location")
                        val contact = message.getAsJsonObject("contact")
                        messages.add(
                            TgMessage(
                                updateId = updateId,
                                chatId = chat.get("id").asLong,
                                text = message.get("text")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                firstName = from?.get("first_name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                kind = kind,
                                photoId = photoId,
                                username = from?.get("username")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                userId = from?.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                                chatType = chat.get("type")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                messageId = message.get("message_id")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                                messageThreadId = message.get("message_thread_id")?.takeIf { !it.isJsonNull }?.asInt,
                                caption = message.get("caption")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                fileId = attachFileId,
                                replyToText = replyText,
                                locationLat = loc?.get("latitude")?.takeIf { !it.isJsonNull }?.asDouble,
                                locationLon = loc?.get("longitude")?.takeIf { !it.isJsonNull }?.asDouble,
                                contactPhone = contact?.get("phone_number")?.takeIf { !it.isJsonNull }?.asString ?: "",
                            )
                        )
                        return@forEach
                    }

                    update.getAsJsonObject("callback_query")?.let { cb ->
                        val message = cb.getAsJsonObject("message")
                        val chat = message?.getAsJsonObject("chat")
                        if (chat != null) {
                            callbacks.add(
                                TgCallback(
                                    updateId = updateId,
                                    callbackId = cb.get("id").asString,
                                    chatId = chat.get("id").asLong,
                                    messageId = message?.get("message_id")?.asInt ?: 0,
                                    data = cb.get("data")?.asString ?: "",
                                    firstName = cb.getAsJsonObject("from")
                                        ?.get("first_name")?.asString ?: "",
                                )
                            )
                        }
                    }
                }
                Triple(messages, callbacks, maxId + 1)
            }
        }
    }

    private fun applyMessageOpts(
        map: MutableMap<String, Any>,
        parseMode: String? = null,
        disablePreview: Boolean = false,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
    ) {
        if (!parseMode.isNullOrBlank()) map["parse_mode"] = parseMode
        if (disablePreview) map["link_preview_options"] = mapOf("is_disabled" to true)
        if (silent) map["disable_notification"] = true
        if (protectContent) map["protect_content"] = true
        if (replyToMessageId != null && replyToMessageId > 0) {
            map["reply_parameters"] = mapOf(
                "message_id" to replyToMessageId,
                "allow_sending_without_reply" to true,
            )
        }
        if (messageThreadId != null && messageThreadId > 0) {
            map["message_thread_id"] = messageThreadId
        }
    }

    private fun isParseEntityError(err: Throwable?): Boolean {
        val apiErr = err as? TelegramApiException ?: return false
        return apiErr.httpCode == 400 && !apiErr.description.contains("not modified", true) &&
            (apiErr.description.contains("parse", true) || apiErr.description.contains("entity", true))
    }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        keyboard: List<String> = emptyList(),
        inlineMenu: List<InlineBtn> = emptyList(),
        parseMode: String? = null,
        disablePreview: Boolean = false,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
    ): Result<Unit> = withShortRateLimitRetry {
        suspend fun attempt(pm: String?): Result<Unit> {
            val bucket = "chat:$chatId"
            val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
            if (rem > 0L) {
                return Result.failure(
                    TelegramApiException("sendMessage", 429, "Too Many Requests", rem, "sendMessage HTTP 429: подождите ещё $rem с")
                )
            }
            return try {
                val payloadMap = mutableMapOf<String, Any>(
                    "chat_id" to chatId, "text" to text.take(4000))
                applyMessageOpts(payloadMap, pm, disablePreview, silent, protectContent, replyToMessageId, messageThreadId)
                if (keyboard.isNotEmpty()) {
                    val rows = keyboard.chunked(2).map { row -> row.toList() }
                    payloadMap["reply_markup"] = Gson().toJson(
                        mapOf("keyboard" to rows, "resize_keyboard" to true))
                }
                if (inlineMenu.isNotEmpty()) {
                    payloadMap["reply_markup"] = Gson().toJson(
                        mapOf("inline_keyboard" to inlineMenu.layoutRows().map { r -> r.map { it.toApi() } }))
                }
                val payload = Gson().toJson(payloadMap).toRequestBody(json)
                executeCall(fastClient, Request.Builder().url(url("sendMessage")).post(payload).build()) { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(httpFailure("sendMessage", bucket, resp, body))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        val res = attempt(parseMode?.takeIf { it.isNotBlank() })
        if (res.isFailure && !parseMode.isNullOrBlank() && isParseEntityError(res.exceptionOrNull())) {
            attempt(null)
        } else res
    }

    /**
     * Сообщение в чат с возвратом message_id — первый кадр анимации, который
     * потом правится editMessageText. [parseMode] = "HTML" для <pre>-кадров.
     */
    suspend fun sendMessageForId(
        chatId: Long,
        text: String,
        parseMode: String? = null,
        inlineMenu: List<InlineBtn> = emptyList(),
        disablePreview: Boolean = false,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
    ): Result<Int> {
        val bucket = "chat:$chatId"
        val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
        if (rem > 0L) {
            return Result.failure(
                TelegramApiException("sendMessage", 429, "Too Many Requests", rem, "sendMessage HTTP 429: подождите ещё $rem с")
            )
        }
        return try {
            val payloadMap = mutableMapOf<String, Any>("chat_id" to chatId, "text" to text.take(4000))
            applyMessageOpts(payloadMap, parseMode, disablePreview, silent, protectContent, replyToMessageId, messageThreadId)
            if (inlineMenu.isNotEmpty()) {
                payloadMap["reply_markup"] = Gson().toJson(
                    mapOf("inline_keyboard" to inlineMenu.layoutRows().map { r -> r.map { it.toApi() } }))
            }
            val payload = Gson().toJson(payloadMap).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("sendMessage")).post(payload).build()) { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) Result.failure(httpFailure("sendMessage", bucket, resp, body))
                else Result.success(parse("sendMessage", body) { it.getAsJsonObject("result")?.get("message_id")?.asInt ?: 0 })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Анимированный эмодзи Telegram со случайным значением (sendDice):
     * 🎲 🎯 🏀 ⚽ 🎳 🎰. Значение выбирает сам Telegram.
     */
    suspend fun sendDice(chatId: Long, emoji: String = "🎲"): Result<Unit> = withShortRateLimitRetry {
        val bucket = "chat:$chatId"
        val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
        if (rem > 0L) {
            return@withShortRateLimitRetry Result.failure(
                TelegramApiException("sendDice", 429, "Too Many Requests", rem, "sendDice HTTP 429: подождите ещё $rem с")
            )
        }
        try {
            val payload = Gson().toJson(mapOf("chat_id" to chatId, "emoji" to emoji)).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("sendDice")).post(payload).build()) { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(httpFailure("sendDice", bucket, resp, body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** file_path файла по его file_id (для скачивания). */
    suspend fun getFile(fileId: String): Result<String> {
        return try {
            val payload = Gson().toJson(mapOf("file_id" to fileId)).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("getFile")).post(payload).build()) { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) Result.failure(httpFailure("getFile", "file", resp, text))
                else Result.success(
                    parse("getFile", text) { it.get("result")?.asJsonObject?.get("file_path")?.asString ?: "" }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Скачивает файл Telegram в указанную директорию. */
    suspend fun downloadFile(filePath: String, targetDir: java.io.File, name: String): Result<java.io.File> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.telegram.org/file/bot$token/$filePath")
                    .build()
                executeCall(fastClient, req) { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        throw httpFailure("download", "file", resp, body)
                    }
                    val out = java.io.File(targetDir, name)
                    val stream = resp.body?.byteStream() ?: error("пустой ответ при скачивании")
                    stream.use { ins -> out.outputStream().use { ins.copyTo(it) } }
                    Result.success(out)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** Фото в чат/канал (файл с диска). Возвращает message_id для удаления. */
    suspend fun sendPhoto(
        chatId: Long,
        image: java.io.File,
        caption: String = "",
        urlButton: InlineBtn? = null,
    ): Result<Int> = withShortRateLimitRetry {
        val bucket = "chat:$chatId"
        val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
        if (rem > 0L) {
            return@withShortRateLimitRetry Result.failure(
                TelegramApiException("sendPhoto", 429, "Too Many Requests", rem, "sendPhoto HTTP 429: подождите ещё $rem с")
            )
        }
        try {
            val mime = when (image.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }.toMediaType()
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("caption", caption.take(1000))
                .addFormDataPart("photo", image.name, image.asRequestBody(mime))
            if (urlButton != null) {
                val kb = Gson().toJson(
                    mapOf("inline_keyboard" to listOf(listOf(mapOf(
                        "text" to urlButton.label, "url" to urlButton.url)))))
                builder.addFormDataPart("reply_markup", kb)
            }
            executeCall(fastClient, Request.Builder().url(url("sendPhoto")).post(builder.build()).build()) { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) Result.failure(httpFailure("sendPhoto", bucket, resp, text))
                else Result.success(
                    parse("sendPhoto", text) {
                        it.getAsJsonObject("result")?.get("message_id")?.asInt ?: 0
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Сообщение в канал по имени «@channel» (chat_id строкой). */
    suspend fun sendTo(
        channel: String,
        text: String,
        inlineMenu: List<InlineBtn> = emptyList(),
        parseMode: String? = null,
        disablePreview: Boolean = false,
        silent: Boolean = false,
        protectContent: Boolean = false,
        messageThreadId: Int? = null,
    ): Result<Int> = withShortRateLimitRetry {
        suspend fun attempt(pm: String?): Result<Int> {
            val bucket = "chat:$channel"
            val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
            if (rem > 0L) {
                return Result.failure(
                    TelegramApiException("sendMessage", 429, "Too Many Requests", rem, "sendMessage HTTP 429: подождите ещё $rem с")
                )
            }
            return try {
                val payloadMap = mutableMapOf<String, Any>(
                    "chat_id" to channel, "text" to text.take(4000))
                applyMessageOpts(payloadMap, pm, disablePreview, silent, protectContent, null, messageThreadId)
                if (inlineMenu.isNotEmpty()) {
                    payloadMap["reply_markup"] = Gson().toJson(
                        mapOf("inline_keyboard" to inlineMenu.layoutRows().map { r -> r.map { it.toApi() } }))
                }
                val payload = Gson().toJson(payloadMap).toRequestBody(json)
                executeCall(fastClient, Request.Builder().url(url("sendMessage")).post(payload).build()) { resp ->
                    // message_id поста нужен, чтобы потом удалить его из канала.
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) Result.success(
                        parse("sendMessage", body) { it.getAsJsonObject("result")?.get("message_id")?.asInt ?: 0 })
                    else Result.failure(httpFailure("sendMessage", bucket, resp, body))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        val res = attempt(parseMode?.takeIf { it.isNotBlank() })
        if (res.isFailure && !parseMode.isNullOrBlank() && isParseEntityError(res.exceptionOrNull())) {
            attempt(null)
        } else res
    }

    /** Фото в канал по имени «@channel» (chat_id строкой). */
    suspend fun sendPhotoTo(
        channel: String,
        image: java.io.File,
        caption: String = "",
        urlButton: InlineBtn? = null,
    ): Result<Int> = withShortRateLimitRetry {
        val bucket = "chat:$channel"
        val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
        if (rem > 0L) {
            return@withShortRateLimitRetry Result.failure(
                TelegramApiException("sendPhoto", 429, "Too Many Requests", rem, "sendPhoto HTTP 429: подождите ещё $rem с")
            )
        }
        try {
            val mime = when (image.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }.toMediaType()
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", channel)
                .addFormDataPart("caption", caption.take(1000))
                .addFormDataPart("photo", image.name, image.asRequestBody(mime))
            if (urlButton != null) {
                val kb = Gson().toJson(
                    mapOf("inline_keyboard" to listOf(listOf(mapOf(
                        "text" to urlButton.label, "url" to urlButton.url)))))
                builder.addFormDataPart("reply_markup", kb)
            }
            executeCall(fastClient, Request.Builder().url(url("sendPhoto")).post(builder.build()).build()) { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) Result.failure(httpFailure("sendPhoto", bucket, resp, text))
                else Result.success(
                    parse("sendPhoto", text) {
                        it.getAsJsonObject("result")?.get("message_id")?.asInt ?: 0
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Удалить сообщение (например, объявление из канала). */
    suspend fun deleteMessage(chatId: String, messageId: Int): Result<Unit> = withShortRateLimitRetry {
        val bucket = "chat:$chatId"
        try {
            val payload = Gson().toJson(
                mapOf("chat_id" to chatId, "message_id" to messageId)).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("deleteMessage")).post(payload).build()) { resp ->
                val body = resp.body?.string().orEmpty()
                // «message to delete not found» — уже удалено, не ошибка.
                // Остальные 400 («message can't be deleted» — нет прав
                // или пост старше 48 ч) — ошибка: сообщаем, а не молчим.
                if (resp.isSuccessful) Result.success(Unit)
                else if (resp.code == 400 && body.contains("not found", ignoreCase = true)) Result.success(Unit)
                else Result.failure(httpFailure("deleteMessage", bucket, resp, body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Показать «печатает…» в чате. При действующем 429-кулдауне пропускаем, чтобы не усугублять лимит. */
    suspend fun sendChatAction(chatId: Long, action: String = "typing"): Result<Unit> {
        val bucket = "chat:$chatId"
        if (TelegramRateLimit.remainingCooldownSec(token, bucket) > 0L) {
            return Result.success(Unit)
        }
        return try {
            val payload = Gson().toJson(
                mapOf("chat_id" to chatId, "action" to action)).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("sendChatAction")).post(payload).build()) { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(httpFailure("sendChatAction", bucket, resp, resp.body?.string().orEmpty()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Всплывающий ответ на нажатие inline-кнопки (убирает «часики»). */
    suspend fun answerCallbackQuery(callbackId: String, text: String): Result<Unit> = withShortRateLimitRetry {
        try {
            val payloadMap = mutableMapOf<String, Any>("callback_query_id" to callbackId)
            if (text.isNotBlank()) payloadMap["text"] = text.take(180)
            val payload = Gson().toJson(payloadMap).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("answerCallbackQuery")).post(payload).build()) { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(httpFailure("answerCallbackQuery", "callback", resp, resp.body?.string().orEmpty()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Фото-анимация: sendPhoto → editMessageMedia / editMessageCaption.
    // src — ссылка https, file_id Telegram или путь к файлу на телефоне
    // (тогда multipart). Возвращают file_id, чтобы не загружать повторно.
    // ------------------------------------------------------------------

    private fun menuJson(inlineMenu: List<InlineBtn>): String? =
        if (inlineMenu.isEmpty()) null
        else Gson().toJson(mapOf("inline_keyboard" to inlineMenu.layoutRows().map { r -> r.map { it.toApi() } }))

    private fun localFile(src: String): java.io.File? {
        val path = src.removePrefix("file://").removePrefix("file:")
        return if (path.startsWith("/")) java.io.File(path).takeIf { it.isFile } else null
    }

    private fun imageMime(f: java.io.File) = when (f.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }.toMediaType()

    /** file_id самой большой версии фото из ответа Telegram. */
    private fun photoFileId(body: String): String = runCatching {
        JsonParser.parseString(body).asJsonObject.getAsJsonObject("result")
            .getAsJsonArray("photo").last().asJsonObject.get("file_id").asString
    }.getOrDefault("")

    private fun messageIdOf(body: String): Int = runCatching {
        JsonParser.parseString(body).asJsonObject.getAsJsonObject("result").get("message_id").asInt
    }.getOrDefault(0)

    /** Фото-кадр новым сообщением. Результат: message_id и file_id. */
    suspend fun sendPhotoForId(
        chatId: Long,
        src: String,
        caption: String = "",
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Pair<Int, String>> {
        val bucket = "chat:$chatId"
        val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
        if (rem > 0L) {
            return Result.failure(
                TelegramApiException("sendPhoto", 429, "Too Many Requests", rem, "sendPhoto HTTP 429: подождите ещё $rem с")
            )
        }
        return try {
            val file = localFile(src)
            if (file == null && src.startsWith("/")) error("файл картинки не найден: $src")
            val body = if (file != null) {
                val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("caption", caption.take(1024))
                    .addFormDataPart("photo", file.name, file.asRequestBody(imageMime(file)))
                menuJson(inlineMenu)?.let { b.addFormDataPart("reply_markup", it) }
                b.build()
            } else {
                val m = mutableMapOf<String, Any>("chat_id" to chatId, "photo" to src, "caption" to caption.take(1024))
                menuJson(inlineMenu)?.let { m["reply_markup"] = it }
                Gson().toJson(m).toRequestBody(json)
            }
            executeCall(fastClient, Request.Builder().url(url("sendPhoto")).post(body).build()) { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(messageIdOf(text) to photoFileId(text))
                else Result.failure(httpFailure("sendPhoto", bucket, resp, text))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Заменить картинку (и подпись) в сообщении. Результат: file_id. */
    suspend fun editMessageMedia(
        chatId: Long,
        messageId: Int,
        src: String,
        caption: String = "",
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<String> {
        val bucket = "chat:$chatId"
        val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
        if (rem > 0L) {
            return Result.failure(
                TelegramApiException("editMessageMedia", 429, "Too Many Requests", rem, "editMessageMedia HTTP 429: подождите ещё $rem с")
            )
        }
        return try {
            val file = localFile(src)
            if (file == null && src.startsWith("/")) error("файл картинки не найден: $src")
            val media = Gson().toJson(mapOf(
                "type" to "photo",
                "media" to if (file != null) "attach://frame" else src,
                "caption" to caption.take(1024),
            ))
            val body = if (file != null) {
                val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("message_id", messageId.toString())
                    .addFormDataPart("media", media)
                    .addFormDataPart("frame", file.name, file.asRequestBody(imageMime(file)))
                menuJson(inlineMenu)?.let { b.addFormDataPart("reply_markup", it) }
                b.build()
            } else {
                val m = mutableMapOf<String, Any>("chat_id" to chatId, "message_id" to messageId, "media" to media)
                menuJson(inlineMenu)?.let { m["reply_markup"] = it }
                Gson().toJson(m).toRequestBody(json)
            }
            executeCall(fastClient, Request.Builder().url(url("editMessageMedia")).post(body).build()) { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(photoFileId(text))
                else Result.failure(httpFailure("editMessageMedia", bucket, resp, text))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Сменить только подпись под фото (картинка та же). */
    suspend fun editMessageCaption(
        chatId: Long,
        messageId: Int,
        caption: String,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Unit> {
        val bucket = "chat:$chatId"
        val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
        if (rem > 0L) {
            return Result.failure(
                TelegramApiException("editMessageCaption", 429, "Too Many Requests", rem, "editMessageCaption HTTP 429: подождите ещё $rem с")
            )
        }
        return try {
            val m = mutableMapOf<String, Any>("chat_id" to chatId, "message_id" to messageId, "caption" to caption.take(1024))
            menuJson(inlineMenu)?.let { m["reply_markup"] = it }
            executeCall(
                fastClient,
                Request.Builder().url(url("editMessageCaption"))
                    .post(Gson().toJson(m).toRequestBody(json)).build(),
            ) { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(httpFailure("editMessageCaption", bucket, resp, text))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Отредактировать текст своего сообщения (сохраняя inline-кнопки). */
    suspend fun editMessageText(
        chatId: Long,
        messageId: Int,
        text: String,
        inlineMenu: List<InlineBtn> = emptyList(),
        parseMode: String? = null,
        disablePreview: Boolean = false,
    ): Result<Unit> {
        suspend fun attempt(pm: String?): Result<Unit> {
            val bucket = "chat:$chatId"
            val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
            if (rem > 0L) {
                return Result.failure(
                    TelegramApiException("editMessageText", 429, "Too Many Requests", rem, "editMessageText HTTP 429: подождите ещё $rem с")
                )
            }
            return try {
                val payloadMap = mutableMapOf<String, Any>(
                    "chat_id" to chatId,
                    "message_id" to messageId,
                    "text" to text.take(4000),
                )
                if (!pm.isNullOrBlank()) payloadMap["parse_mode"] = pm
                if (disablePreview) payloadMap["link_preview_options"] = mapOf("is_disabled" to true)
                if (inlineMenu.isNotEmpty()) {
                    payloadMap["reply_markup"] = Gson().toJson(
                        mapOf("inline_keyboard" to inlineMenu.layoutRows().map { r -> r.map { it.toApi() } }))
                }
                val payload = Gson().toJson(payloadMap).toRequestBody(json)
                executeCall(fastClient, Request.Builder().url(url("editMessageText")).post(payload).build()) { resp ->
                    // Текст ответа нужен анимации: 429 → retry_after, 400 → «not modified».
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(httpFailure("editMessageText", bucket, resp, body))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        val res = attempt(parseMode?.takeIf { it.isNotBlank() })
        return if (res.isFailure && !parseMode.isNullOrBlank() && isParseEntityError(res.exceptionOrNull())) {
            attempt(null)
        } else res
    }

    /** Заменить только inline-клавиатуру сообщения (для вложенных подменю I-4). */
    suspend fun editMessageReplyMarkup(
        chatId: Long,
        messageId: Int,
        inlineMenu: List<InlineBtn>,
    ): Result<Unit> {
        val bucket = "chat:$chatId"
        val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
        if (rem > 0L) {
            return Result.failure(
                TelegramApiException("editMessageReplyMarkup", 429, "Too Many Requests", rem, "editMessageReplyMarkup HTTP 429: подождите ещё $rem с")
            )
        }
        return try {
            val kbRows = if (inlineMenu.isEmpty()) emptyList()
            else inlineMenu.layoutRows().map { r -> r.map { it.toApi() } }
            val payloadMap = mapOf(
                "chat_id" to chatId,
                "message_id" to messageId,
                "reply_markup" to Gson().toJson(mapOf("inline_keyboard" to kbRows)),
            )
            val payload = Gson().toJson(payloadMap).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("editMessageReplyMarkup")).post(payload).build()) { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(httpFailure("editMessageReplyMarkup", bucket, resp, body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Расширенная отправка медиа, опросов, геолокации, реакций и чатов
    // ------------------------------------------------------------------

    private suspend fun sendJsonMedia(
        method: String,
        field: String,
        chatId: Long,
        mediaRef: String,
        caption: String = "",
        parseMode: String? = null,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
        inlineMenu: List<InlineBtn> = emptyList(),
        supportsCaption: Boolean = true,
    ): Result<Int> = withShortRateLimitRetry {
        suspend fun attempt(pm: String?): Result<Int> {
            val bucket = "chat:$chatId"
            val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
            if (rem > 0L) {
                return Result.failure(
                    TelegramApiException(method, 429, "Too Many Requests", rem, "$method HTTP 429: подождите ещё $rem с")
                )
            }
            return try {
                val file = localFile(mediaRef)
                if (file != null) {
                    val mime = when (file.extension.lowercase()) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        "mp4" -> "video/mp4"
                        "mp3" -> "audio/mpeg"
                        "ogg", "opus" -> "audio/ogg"
                        "pdf" -> "application/pdf"
                        else -> "application/octet-stream"
                    }.toMediaType()
                    val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("chat_id", chatId.toString())
                        .addFormDataPart(field, file.name, file.asRequestBody(mime))
                    if (supportsCaption && caption.isNotBlank()) {
                        b.addFormDataPart("caption", caption.take(1024))
                        if (!pm.isNullOrBlank()) b.addFormDataPart("parse_mode", pm)
                    }
                    if (silent) b.addFormDataPart("disable_notification", "true")
                    if (protectContent) b.addFormDataPart("protect_content", "true")
                    if (replyToMessageId != null && replyToMessageId > 0) {
                        b.addFormDataPart("reply_parameters", Gson().toJson(mapOf(
                            "message_id" to replyToMessageId, "allow_sending_without_reply" to true)))
                    }
                    if (messageThreadId != null && messageThreadId > 0) {
                        b.addFormDataPart("message_thread_id", messageThreadId.toString())
                    }
                    menuJson(inlineMenu)?.let { b.addFormDataPart("reply_markup", it) }
                    executeCall(fastClient, Request.Builder().url(url(method)).post(b.build()).build()) { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) Result.success(messageIdOf(body))
                        else Result.failure(httpFailure(method, bucket, resp, body))
                    }
                } else {
                    val m = mutableMapOf<String, Any>("chat_id" to chatId, field to mediaRef)
                    if (supportsCaption && caption.isNotBlank()) m["caption"] = caption.take(1024)
                    applyMessageOpts(m, if (supportsCaption) pm else null, false, silent, protectContent, replyToMessageId, messageThreadId)
                    menuJson(inlineMenu)?.let { m["reply_markup"] = it }
                    val payload = Gson().toJson(m).toRequestBody(json)
                    executeCall(fastClient, Request.Builder().url(url(method)).post(payload).build()) { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) Result.success(messageIdOf(body))
                        else Result.failure(httpFailure(method, bucket, resp, body))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        val res = attempt(parseMode?.takeIf { it.isNotBlank() })
        if (res.isFailure && !parseMode.isNullOrBlank() && isParseEntityError(res.exceptionOrNull())) {
            attempt(null)
        } else res
    }

    suspend fun sendSticker(
        chatId: Long,
        stickerRef: String,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Int> = sendJsonMedia(
        "sendSticker", "sticker", chatId, stickerRef,
        silent = silent, protectContent = protectContent,
        replyToMessageId = replyToMessageId, messageThreadId = messageThreadId,
        inlineMenu = inlineMenu, supportsCaption = false,
    )

    suspend fun sendAnimation(
        chatId: Long,
        animationRef: String,
        caption: String = "",
        parseMode: String? = null,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Int> = sendJsonMedia(
        "sendAnimation", "animation", chatId, animationRef, caption, parseMode,
        silent, protectContent, replyToMessageId, messageThreadId, inlineMenu,
    )

    suspend fun sendDocument(
        chatId: Long,
        docRef: String,
        caption: String = "",
        parseMode: String? = null,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Int> = sendJsonMedia(
        "sendDocument", "document", chatId, docRef, caption, parseMode,
        silent, protectContent, replyToMessageId, messageThreadId, inlineMenu,
    )

    suspend fun sendVideo(
        chatId: Long,
        videoRef: String,
        caption: String = "",
        parseMode: String? = null,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Int> = sendJsonMedia(
        "sendVideo", "video", chatId, videoRef, caption, parseMode,
        silent, protectContent, replyToMessageId, messageThreadId, inlineMenu,
    )

    suspend fun sendVoice(
        chatId: Long,
        voiceRef: String,
        caption: String = "",
        parseMode: String? = null,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Int> = sendJsonMedia(
        "sendVoice", "voice", chatId, voiceRef, caption, parseMode,
        silent, protectContent, replyToMessageId, messageThreadId, inlineMenu,
    )

    suspend fun sendAudio(
        chatId: Long,
        audioRef: String,
        caption: String = "",
        parseMode: String? = null,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Int> = sendJsonMedia(
        "sendAudio", "audio", chatId, audioRef, caption, parseMode,
        silent, protectContent, replyToMessageId, messageThreadId, inlineMenu,
    )

    /** Единая отправка медиа по типу (photo | sticker | animation | document | video | voice | audio). */
    suspend fun sendMediaByType(
        chatId: Long,
        mediaType: String,
        mediaRef: String,
        caption: String = "",
        parseMode: String? = null,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Int> = when (mediaType.lowercase()) {
        "sticker" -> sendSticker(chatId, mediaRef, silent, protectContent, replyToMessageId, messageThreadId, inlineMenu)
        "animation", "gif" -> sendAnimation(chatId, mediaRef, caption, parseMode, silent, protectContent, replyToMessageId, messageThreadId, inlineMenu)
        "document", "file" -> sendDocument(chatId, mediaRef, caption, parseMode, silent, protectContent, replyToMessageId, messageThreadId, inlineMenu)
        "video" -> sendVideo(chatId, mediaRef, caption, parseMode, silent, protectContent, replyToMessageId, messageThreadId, inlineMenu)
        "voice" -> sendVoice(chatId, mediaRef, caption, parseMode, silent, protectContent, replyToMessageId, messageThreadId, inlineMenu)
        "audio" -> sendAudio(chatId, mediaRef, caption, parseMode, silent, protectContent, replyToMessageId, messageThreadId, inlineMenu)
        else -> sendJsonMedia("sendPhoto", "photo", chatId, mediaRef, caption, parseMode, silent, protectContent, replyToMessageId, messageThreadId, inlineMenu)
    }

    /** Отправить опрос (sendPoll). */
    suspend fun sendPoll(
        chatId: Long,
        question: String,
        options: List<String>,
        isAnonymous: Boolean = true,
        allowsMultipleAnswers: Boolean = false,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Int> = withShortRateLimitRetry {
        val bucket = "chat:$chatId"
        val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
        if (rem > 0L) {
            return@withShortRateLimitRetry Result.failure(
                TelegramApiException("sendPoll", 429, "Too Many Requests", rem, "sendPoll HTTP 429: подождите ещё $rem с")
            )
        }
        val cleanOpts = options.map { it.trim().take(100) }.filter { it.isNotBlank() }.take(10)
        if (question.isBlank() || cleanOpts.size < 2) {
            return@withShortRateLimitRetry Result.failure(IllegalStateException("Для опроса нужен вопрос и минимум 2 варианта ответа"))
        }
        try {
            val payloadMap = mutableMapOf<String, Any>(
                "chat_id" to chatId,
                "question" to question.take(300),
                "options" to cleanOpts.map { mapOf("text" to it) },
                "is_anonymous" to isAnonymous,
                "allows_multiple_answers" to allowsMultipleAnswers,
            )
            applyMessageOpts(payloadMap, null, false, silent, protectContent, replyToMessageId, messageThreadId)
            menuJson(inlineMenu)?.let { payloadMap["reply_markup"] = it }
            val payload = Gson().toJson(payloadMap).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("sendPoll")).post(payload).build()) { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(messageIdOf(body))
                else Result.failure(httpFailure("sendPoll", bucket, resp, body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Отправить альбом фото (sendMediaGroup, 2..10 шт.). */
    suspend fun sendMediaGroup(
        chatId: String,
        sources: List<String>,
        caption: String = "",
        parseMode: String? = null,
        silent: Boolean = false,
        protectContent: Boolean = false,
        messageThreadId: Int? = null,
    ): Result<List<Int>> = withShortRateLimitRetry {
        val items = sources.filter { it.isNotBlank() }.take(10)
        if (items.isEmpty()) return@withShortRateLimitRetry Result.success(emptyList())
        val bucket = "chat:$chatId"
        val rem = TelegramRateLimit.remainingCooldownSec(token, bucket)
        if (rem > 0L) {
            return@withShortRateLimitRetry Result.failure(
                TelegramApiException("sendMediaGroup", 429, "Too Many Requests", rem, "sendMediaGroup HTTP 429: подождите ещё $rem с")
            )
        }
        try {
            val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
            val mediaArr = ArrayList<Map<String, Any>>()
            items.forEachIndexed { idx, src ->
                val file = localFile(src)
                val entry = mutableMapOf<String, Any>(
                    "type" to "photo",
                    "media" to if (file != null) "attach://file_$idx" else src,
                )
                if (idx == 0 && caption.isNotBlank()) {
                    entry["caption"] = caption.take(1024)
                    if (!parseMode.isNullOrBlank()) entry["parse_mode"] = parseMode
                }
                mediaArr.add(entry)
                if (file != null) {
                    b.addFormDataPart("file_$idx", file.name, file.asRequestBody(imageMime(file)))
                }
            }
            b.addFormDataPart("media", Gson().toJson(mediaArr))
            if (silent) b.addFormDataPart("disable_notification", "true")
            if (protectContent) b.addFormDataPart("protect_content", "true")
            if (messageThreadId != null && messageThreadId > 0) {
                b.addFormDataPart("message_thread_id", messageThreadId.toString())
            }
            executeCall(fastClient, Request.Builder().url(url("sendMediaGroup")).post(b.build()).build()) { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) Result.failure(httpFailure("sendMediaGroup", bucket, resp, body))
                else Result.success(parse("sendMediaGroup", body) { root ->
                    root.getAsJsonArray("result")?.mapNotNull { it.asJsonObject.get("message_id")?.asInt }.orEmpty()
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun sendLocation(
        chatId: Long,
        latitude: Double,
        longitude: Double,
        silent: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: Int? = null,
        messageThreadId: Int? = null,
    ): Result<Int> = withShortRateLimitRetry {
        val bucket = "chat:$chatId"
        try {
            val payloadMap = mutableMapOf<String, Any>(
                "chat_id" to chatId, "latitude" to latitude, "longitude" to longitude)
            applyMessageOpts(payloadMap, null, false, silent, protectContent, replyToMessageId, messageThreadId)
            val payload = Gson().toJson(payloadMap).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("sendLocation")).post(payload).build()) { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(messageIdOf(body))
                else Result.failure(httpFailure("sendLocation", bucket, resp, body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun copyMessage(
        chatId: String,
        fromChatId: Long,
        messageId: Int,
        silent: Boolean = false,
        protectContent: Boolean = false,
        messageThreadId: Int? = null,
    ): Result<Int> = withShortRateLimitRetry {
        val bucket = "chat:$chatId"
        try {
            val payloadMap = mutableMapOf<String, Any>(
                "chat_id" to chatId, "from_chat_id" to fromChatId, "message_id" to messageId)
            applyMessageOpts(payloadMap, null, false, silent, protectContent, null, messageThreadId)
            val payload = Gson().toJson(payloadMap).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("copyMessage")).post(payload).build()) { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(messageIdOf(body))
                else Result.failure(httpFailure("copyMessage", bucket, resp, body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun forwardMessage(
        chatId: String,
        fromChatId: Long,
        messageId: Int,
        silent: Boolean = false,
        protectContent: Boolean = false,
    ): Result<Int> = withShortRateLimitRetry {
        val bucket = "chat:$chatId"
        try {
            val payloadMap = mutableMapOf<String, Any>(
                "chat_id" to chatId, "from_chat_id" to fromChatId, "message_id" to messageId)
            if (silent) payloadMap["disable_notification"] = true
            if (protectContent) payloadMap["protect_content"] = true
            val payload = Gson().toJson(payloadMap).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("forwardMessage")).post(payload).build()) { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(messageIdOf(body))
                else Result.failure(httpFailure("forwardMessage", bucket, resp, body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun setMessageReaction(
        chatId: Long,
        messageId: Int,
        emoji: String,
        isBig: Boolean = false,
    ): Result<Unit> {
        val bucket = "chat:$chatId"
        return try {
            val payload = Gson().toJson(mapOf(
                "chat_id" to chatId,
                "message_id" to messageId,
                "reaction" to if (emoji.isBlank()) emptyList<Map<String, String>>()
                else listOf(mapOf("type" to "emoji", "emoji" to emoji)),
                "is_big" to isBig,
            )).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("setMessageReaction")).post(payload).build()) { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(httpFailure("setMessageReaction", bucket, resp, resp.body?.string().orEmpty()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun pinChatMessage(chatId: String, messageId: Int, silent: Boolean = true): Result<Unit> {
        val bucket = "chat:$chatId"
        return try {
            val payload = Gson().toJson(mapOf(
                "chat_id" to chatId,
                "message_id" to messageId,
                "disable_notification" to silent,
            )).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("pinChatMessage")).post(payload).build()) { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(httpFailure("pinChatMessage", bucket, resp, resp.body?.string().orEmpty()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Статус пользователя в чате/канале (getChatMember): creator | administrator | member | restricted | left | kicked. */
    suspend fun getChatMemberStatus(chatIdOrChannel: String, userId: Long): Result<String> {
        return try {
            val payload = Gson().toJson(mapOf(
                "chat_id" to chatIdOrChannel,
                "user_id" to userId,
            )).toRequestBody(json)
            executeCall(fastClient, Request.Builder().url(url("getChatMember")).post(payload).build()) { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) Result.failure(httpFailure("getChatMember", "chat:$chatIdOrChannel", resp, body))
                else Result.success(parse("getChatMember", body) {
                    it.getAsJsonObject("result")?.get("status")?.asString.orEmpty()
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Задать список команд для кнопки «Меню» в Telegram (setMyCommands).
     * Команды нормализуем: без «/», в нижний регистр, до 32 символов.
     */
    suspend fun setMyCommands(commands: List<Pair<String, String>>): Result<Unit> =
        setMyCommandsScoped(commands.map { MenuCommand(command = it.first, description = it.second) })

    /** Задать команды меню с поддержкой scope и language_code (по группам). */
    suspend fun setMyCommandsScoped(commands: List<MenuCommand>): Result<Unit> {
        val rem = TelegramRateLimit.remainingCooldownSec(token, "menu")
        if (rem > 0L) {
            return Result.failure(
                TelegramApiException("setMyCommands", 429, "Too Many Requests", rem, "setMyCommands HTTP 429: подождите ещё $rem с")
            )
        }
        return try {
            val valid = Regex("^[a-z0-9_]{1,32}$")
            val groups = commands.groupBy {
                (it.scope.trim().ifBlank { "default" }) to it.languageCode.trim().lowercase()
            }
            var appliedAny = false
            for ((key, list) in groups) {
                val (scopeType, lang) = key
                val cleaned = list
                    .mapNotNull { mc ->
                        val c = mc.command.removePrefix("/").trim().lowercase()
                            .replace(Regex("[^a-z0-9_]"), "")
                        if (!valid.matches(c)) null
                        else c to mc.description.trim().ifBlank { "Команда /$c" }
                    }
                    .distinctBy { it.first }
                    .take(50)
                if (cleaned.isEmpty()) continue
                appliedAny = true
                val bodyMap = mutableMapOf<String, Any>(
                    "commands" to cleaned.map { (c, d) ->
                        mapOf("command" to c, "description" to d.take(256))
                    }
                )
                if (scopeType != "default" && scopeType.isNotBlank()) {
                    bodyMap["scope"] = mapOf("type" to scopeType)
                }
                if (lang.isNotBlank()) {
                    bodyMap["language_code"] = lang
                }
                val payload = Gson().toJson(bodyMap).toRequestBody(json)
                val res = executeCall(fastClient, Request.Builder().url(url("setMyCommands")).post(payload).build()) { resp ->
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(httpFailure("setMyCommands", "menu", resp, resp.body?.string().orEmpty()))
                }
                if (res.isFailure) return res
            }
            if (!appliedAny) Result.failure(IllegalStateException("нет корректных команд (только a-z, 0-9, _)"))
            else Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Removes a webhook if one was set (no-op otherwise). Unlocks getUpdates. */
    suspend fun deleteWebhook(): Result<Unit> = withShortRateLimitRetry {
        val rem = TelegramRateLimit.remainingCooldownSec(token, "webhook")
        if (rem > 0L) {
            return@withShortRateLimitRetry Result.failure(
                TelegramApiException("deleteWebhook", 429, "Too Many Requests", rem, "deleteWebhook HTTP 429: подождите ещё $rem с")
            )
        }
        try {
            executeCall(fastClient, Request.Builder().url(url("deleteWebhook")).build()) { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(httpFailure("deleteWebhook", "webhook", resp, resp.body?.string().orEmpty()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Безопасное усечение строки до maxBytes в UTF-8 (Telegram callback_data <= 64 байт). */
    private fun utf8Trim(s: String, maxBytes: Int = 64): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val len = ch.toByteArray(Charsets.UTF_8).size
            if (bytes + len > maxBytes) break
            sb.append(ch)
            bytes += len
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Кнопка в формате Bot API: url / web_app / copy_text / switch_inline / callback_data. */
    private fun InlineBtn.toApi(): Map<String, Any> = when {
        action == "web_app" && url.isNotBlank() ->
            mapOf("text" to label, "web_app" to mapOf("url" to url))
        action == "copy_text" && text.isNotBlank() ->
            mapOf("text" to label, "copy_text" to mapOf("text" to text.take(256)))
        action == "switch_inline" ->
            mapOf("text" to label, "switch_inline_query_current_chat" to text.take(256))
        (action == "url" || url.isNotBlank()) && url.isNotBlank() ->
            mapOf("text" to label, "url" to url)
        id.isNotBlank() -> mapOf("text" to label, "callback_data" to utf8Trim(id, 64))
        action.startsWith("lst_") -> mapOf("text" to label, "callback_data" to utf8Trim(action, 64))
        else -> mapOf("text" to label, "callback_data" to ("cb" + label.hashCode()))
    }

    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
