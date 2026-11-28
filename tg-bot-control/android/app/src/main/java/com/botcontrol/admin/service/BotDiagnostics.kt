package com.botcontrol.admin.service

import android.content.Context
import android.os.Build
import com.botcontrol.admin.CrashLog
import com.botcontrol.admin.data.BotFiles
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.llm.DeviceLlm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Единый сборщик диагностики приложения (U-X3).
 *
 * Отчёт предназначен для отправки в поддержку или самостоятельного разбора сбоев:
 * токены ботов, пароли, значения приватных KV и тексты переписок пользователей в отчёт
 * НИКОГДА не попадают (при необходимости маскируются по шаблону BotFather).
 */
object BotDiagnostics {

    private val TOKEN_PATTERN = Regex("""\b\d{8,10}:[A-Za-z0-9_-]{30,45}\b""")

    fun sanitize(text: String): String =
        TOKEN_PATTERN.replace(text, "<TOKEN_REDACTED>")

    suspend fun buildReport(
        context: Context,
        localStore: LocalBotStore?,
        repository: BotRepository?,
    ): String {
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val pkgInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val vName = pkgInfo?.versionName ?: "?"
        val vCode = if (pkgInfo != null) {
            if (Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode else @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
        } else -1L

        sb.appendLine("=== BotControl Diagnostics ===")
        sb.appendLine("Generated: ${df.format(Date())}")
        sb.appendLine("App: ${context.packageName} v$vName (code $vCode)")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("Service states: ${LocalBotService.states.value.size}")
        sb.appendLine()

        if (localStore != null) {
            val profiles = localStore.profiles()
            val states = LocalBotService.states.value
            sb.appendLine("--- Bots (${profiles.size}) ---")
            for (p in profiles) {
                val st = states[p.id]
                val rulesCount = repository?.botRules(p.id)?.size ?: -1
                val usersCount = repository?.botUsers(p.id)?.size ?: -1
                val schedCount = localStore.schedule(p.id).size
                val fsmCount = localStore.fsmFlows(p.id).size
                val prodCount = localStore.digitalProducts(p.id).size
                val inlineCfg = localStore.inlineConfig(p.id)
                val intCfg = localStore.integrationsConfig(p.id)
                val filesCount = BotFiles.kinds.sumOf { k -> BotFiles.list(context, p.id, k).size }
                val err = sanitize(st?.lastError.orEmpty()).ifBlank { "none" }
                sb.appendLine(
                    "Bot #${p.id} (@${p.username.ifBlank { "?" }}) enabled=${p.enabled} " +
                        "running=${st?.running == true} processed=${st?.processed ?: 0} " +
                        "rules=$rulesCount users=$usersCount sched=$schedCount fsm=$fsmCount " +
                        "starsProducts=$prodCount files=$filesCount inline=${inlineCfg.enabled} " +
                        "httpDomains=${intCfg.httpAllowedDomains.size} lastError=$err",
                )
                st?.problems?.forEach { (k, v) ->
                    sb.appendLine("  problem[$k]: ${sanitize(v)}")
                }
            }
            sb.appendLine()
        }

        val crash = CrashLog.last(context)
        if (crash.isNotBlank()) {
            sb.appendLine("--- Last Crash ---")
            sb.appendLine(sanitize(crash))
            sb.appendLine()
        }

        val logs = DeviceLlm.log.value.takeLast(80)
        sb.appendLine("--- Recent Log (${logs.size} lines) ---")
        val timeFmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
        for (entry in logs) {
            sb.appendLine("${timeFmt.format(Date(entry.epochSec * 1000))}  ${sanitize(entry.text)}")
        }
        return sb.toString()
    }
}
