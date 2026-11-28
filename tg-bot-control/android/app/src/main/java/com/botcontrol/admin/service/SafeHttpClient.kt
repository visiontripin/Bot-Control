package com.botcontrol.admin.service

import com.botcontrol.admin.data.HttpAllowlistPolicy
import com.botcontrol.admin.data.IntegrationsConfig
import com.botcontrol.admin.llm.ScriptHttpBridge
import com.botcontrol.admin.llm.ScriptHttpResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Безопасный исходящий HTTP-клиент для JS-скриптов (`http.get/post`) и вебхуков CRM/таблиц.
 * Запросы разрешены ТОЛЬКО на домены из белого списка [IntegrationsConfig.httpAllowedDomains].
 */
object SafeHttpClient {

    private const val MAX_BODY_CHARS = 128_000

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    fun execute(
        method: String,
        url: String,
        config: IntegrationsConfig,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ScriptHttpResponse {
        val cleanUrl = url.trim()
        if (!HttpAllowlistPolicy.isUrlAllowed(cleanUrl, config.httpAllowedDomains)) {
            val reason = if (config.httpAllowedDomains.isEmpty()) {
                "белый список доменов пуст (настройте в «⚡ Инлайн, Stars, Mini App и API»)"
            } else {
                "URL не входит в белый список разрешённых доменов или не HTTPS"
            }
            return ScriptHttpResponse(ok = false, status = 0, body = "", error = reason)
        }
        val m = method.uppercase()
        if (m != "GET" && m != "POST") {
            return ScriptHttpResponse(ok = false, status = 0, body = "", error = "разрешены только GET и POST")
        }
        val timeoutSec = config.httpTimeoutSec.coerceIn(2, 30).toLong()
        val callClient = if (timeoutSec == 15L) client else client.newBuilder()
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        return runCatching {
            val builder = Request.Builder().url(cleanUrl)
            var contentTypeOverride: String? = null
            headers.entries.take(16).forEach { (k, v) ->
                val cleanKey = k.trim()
                if (cleanKey.isNotBlank() && !cleanKey.equals("Host", ignoreCase = true)) {
                    if (cleanKey.equals("Content-Type", ignoreCase = true)) {
                        contentTypeOverride = v.take(200)
                    } else {
                        builder.header(cleanKey, v.take(2000))
                    }
                }
            }
            if (m == "POST") {
                val payload = (body ?: "").take(256_000)
                val ct = contentTypeOverride?.takeIf { it.isNotBlank() } ?: "application/json; charset=utf-8"
                builder.post(payload.toRequestBody(ct.toMediaType()))
            } else {
                builder.get()
            }
            callClient.newCall(builder.build()).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty().take(MAX_BODY_CHARS)
                ScriptHttpResponse(
                    ok = resp.isSuccessful,
                    status = resp.code,
                    body = respBody,
                    error = if (resp.isSuccessful) "" else "HTTP ${resp.code}",
                )
            }
        }.getOrElse { e ->
            ScriptHttpResponse(
                ok = false,
                status = 0,
                body = "",
                error = e.message?.take(200) ?: e.javaClass.simpleName,
            )
        }
    }

    fun bridgeFor(config: IntegrationsConfig, maxRequests: Int = 5): ScriptHttpBridge =
        object : ScriptHttpBridge {
            private var count = 0
            override fun request(
                method: String,
                url: String,
                body: String?,
                headers: Map<String, String>,
            ): ScriptHttpResponse {
                if (count >= maxRequests) {
                    return ScriptHttpResponse(
                        ok = false,
                        status = 0,
                        body = "",
                        error = "превышен лимит ($maxRequests запросов за запуск скрипта)",
                    )
                }
                count++
                return execute(method, url, config, body, headers)
            }
        }
}
