import com.botcontrol.admin.data.telegram.TelegramApiException
import com.botcontrol.admin.data.telegram.TelegramRateLimit

private var failed = 0

private fun check(name: String, ok: Boolean) {
    println((if (ok) "ok   " else "FAIL ") + name)
    if (!ok) failed++
}

fun main() {
    // 1. JSON parameters.retry_after + description on getMe
    val body429 = """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 42","parameters":{"retry_after":42}}"""
    val eMe = TelegramRateLimit.buildHttpError("getMe", 429, body429)
    check("parse retry_after from JSON parameters", eMe.retryAfterSec == 42L)
    check("isRateLimit is true on 429", eMe.isRateLimit && !eMe.isAuthError)
    val meMsg = eMe.message.orEmpty()
    check("getMe 429 message explains token limit and VPN/Wi-Fi",
        meMsg.contains("429") && meMsg.contains("42 с") && meMsg.contains("VPN"))

    // 2. Retry-After HTTP header fallback
    val e2 = TelegramRateLimit.buildHttpError("sendMessage", 429, "", retryAfterHeader = "17")
    check("parse retry_after from Retry-After header", e2.retryAfterSec == 17L)
    check("sendMessage 429 includes method and wait time",
        e2.message.orEmpty().contains("sendMessage") && e2.message.orEmpty().contains("17 с"))

    // 2b. Non-JSON 429 on getMe (e.g. proxy/CDN) distinguishes from Telegram JSON
    val eProxy = TelegramRateLimit.buildHttpError("getMe", 429, "<html>Too Many Requests</html>", retryAfterHeader = "8")
    check("non-JSON 429 on getMe does not falsely claim token limit",
        eProxy.retryAfterSec == 8L &&
            eProxy.message.orEmpty().contains("8 с") &&
            eProxy.message.orEmpty().contains("сеть") &&
            !eProxy.message.orEmpty().contains("VPN"))

    // 3. Description-only fallback
    val e3 = TelegramRateLimit.buildHttpError(
        "getMe", 429,
        """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 9"}"""
    )
    check("parse retry_after from JSON description", e3.retryAfterSec == 9L)

    // 4. Retry-After = 401 or 404 MUST NOT be treated as auth error
    val e401 = TelegramRateLimit.buildHttpError(
        "getUpdates", 429,
        """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 401","parameters":{"retry_after":401}}"""
    )
    check("429 with retry_after=401 isRateLimit && !isAuthError",
        e401.isRateLimit && !e401.isAuthError && e401.retryAfterSec == 401L)

    val auth401 = TelegramApiException("getMe", 401, "Unauthorized", message = "Unauthorized (401)")
    val auth404 = TelegramApiException("getMe", 404, "Not Found", message = "Not Found (404)")
    check("real 401/404 are isAuthError", auth401.isAuthError && auth404.isAuthError && !auth401.isRateLimit)

    // 5. Backoff computation
    check("backoff honors retry_after + 1s cushion", TelegramRateLimit.backoffSec(10L, 1) == 11L)
    check("backoff clamps minSec=15 when retry_after=0 streak=1", TelegramRateLimit.backoffSec(0L, 1, minSec = 15L) == 15L)
    check("backoff grows exponentially without retry_after",
        TelegramRateLimit.backoffSec(0L, 1, minSec = 15L) == 15L &&
            TelegramRateLimit.backoffSec(0L, 2, minSec = 15L) == 30L &&
            TelegramRateLimit.backoffSec(0L, 3, minSec = 15L) == 60L &&
            TelegramRateLimit.backoffSec(0L, 4, minSec = 15L) == 120L &&
            TelegramRateLimit.backoffSec(0L, 6, minSec = 15L, maxSec = 300L) == 300L)

    // 6. Token key & bucket cooldown isolation
    val tA = "12345:TEST_TOKEN_ALPHA"
    val tB = "67890:TEST_TOKEN_BETA"
    val kA = TelegramRateLimit.tokenKey(tA)
    check("tokenKey does not expose raw token", kA.length == 24 && !kA.contains("TEST_TOKEN"))
    check("tokenKey differs for different tokens", kA != TelegramRateLimit.tokenKey(tB))

    TelegramRateLimit.clearCooldowns(tA)
    TelegramRateLimit.clearCooldowns(tB)
    val t0 = 1_000_000L
    TelegramRateLimit.recordCooldown(tA, "poll", 12L, nowMs = t0)
    check("cooldown active on same token + bucket",
        TelegramRateLimit.remainingCooldownSec(tA, "poll", nowMs = t0 + 3_000L) in 9L..10L)
    check("cooldown isolated by bucket",
        TelegramRateLimit.remainingCooldownSec(tA, "chat:123", nowMs = t0 + 3_000L) == 0L)
    check("cooldown isolated by token",
        TelegramRateLimit.remainingCooldownSec(tB, "poll", nowMs = t0 + 3_000L) == 0L)
    check("cooldown expires after retry_after + 1s",
        TelegramRateLimit.remainingCooldownSec(tA, "poll", nowMs = t0 + 13_500L) == 0L)

    TelegramRateLimit.recordCooldown(tA, "me", 30L, nowMs = t0)
    TelegramRateLimit.clearCooldowns(tA)
    check("clearCooldowns clears token cooldowns",
        TelegramRateLimit.remainingCooldownSec(tA, "me", nowMs = t0 + 1_000L) == 0L)

    if (failed > 0) {
        System.err.println("RATE LIMIT TESTS FAILED: $failed")
        kotlin.system.exitProcess(1)
    }
    println("RATE LIMIT OK")
}
