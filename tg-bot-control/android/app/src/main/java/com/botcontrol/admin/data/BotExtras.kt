package com.botcontrol.admin.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Кнопка под конкретным сообщением (inline keyboard в Telegram).
 * Поддерживает: текст, набор, вкл/выкл напоминаний, JS-скрипт, анимацию,
 * кубик, URL-ссылку, Mini App (web_app), копирование текста (copy_text),
 * inline-запрос в чат (switch_inline), локальный ИИ (llm), медиа (media),
 * вложенное подменю (submenu) и возврат (back).
 */
data class InlineBtn(
    val id: String = "",
    val label: String = "",
    val toast: String = "",
    val action: String = "text", // text | pack | reminders_on | reminders_off | script | anim | dice | url | web_app | copy_text | switch_inline | llm | media | submenu | back
    val packId: String = "",
    val text: String = "",
    val script: String = "",
    /** Если задан — кнопка-ссылка (например tg://user?id=…) или URL для web_app. */
    val url: String = "",
    /** Ряд под сообщением: 0 — авто (по 2 в ряд, как раньше), 1..N — номер ряда. */
    val row: Int = 0,
    /** true — заменить текст сообщения с кнопкой, а не присылать новое. */
    val edit: Boolean = false,
    /** Всплывашка, если напоминания уже в нужном состоянии («Уже работает»). */
    val toastNoChange: String = "",
    /** Вложенное меню для action == "submenu" (I-4). */
    val submenu: List<InlineBtn> = emptyList(),
    /** Имя/id целевого меню или правила для action == "submenu" (опционально). */
    val menuTarget: String = "",
    /** Тип медиа для action == "media": photo | sticker | animation | document | video | voice | audio. */
    val mediaType: String = "",
    /** file_id или URL медиа для action == "media". */
    val mediaRef: String = "",
    /** Форматирование текста ответа: "" | "HTML" | "MarkdownV2". */
    val parseMode: String = "",
) {
    val isUrl: Boolean get() = action == "url" || (url.isNotBlank() && action != "web_app")
}

/**
 * Раскладка кнопок по рядам Telegram. Старые меню (у всех row == 0)
 * раскладываются по 2 в ряд, как раньше; явные ряды — как в коде бота.
 */
fun List<InlineBtn>.layoutRows(): List<List<InlineBtn>> {
    if (isEmpty()) return emptyList()
    if (all { it.row <= 0 }) return chunked(2)
    val explicit = filter { it.row > 0 }.groupBy { it.row }.toSortedMap().values.map { it.toList() }
    val loose = filter { it.row <= 0 }.map { listOf(it) }
    return explicit + loose
}

/** Явные номера рядов 1..N для меню (для редактора и экспорта). */
fun List<InlineBtn>.withExplicitRows(): List<InlineBtn> =
    layoutRows().flatMapIndexed { index, row -> row.map { it.copy(row = index + 1) } }

/** Событие расписания напоминаний. */
data class ScheduleEvent(
    val id: String = "",
    val hour: Int = 9,
    val minute: Int = 0,
    val text: String = "",
    val days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7), // 1=Пн … 7=Вс
    val enabled: Boolean = true,
    val menu: List<InlineBtn> = emptyList(),
    /** Отправлять в канал публикаций (см. «Настройки бота → Канал»). */
    val toChannel: Boolean = false,
    /** Случайный разброс времени срабатывания ±N минут (U-3, 0..60). */
    val jitterMin: Int = 0,
    /** Форматирование текста события: "" | "HTML" | "MarkdownV2". */
    val parseMode: String = "",
    /** id топика форума супергруппы (если отправка в конкретную тему). */
    val threadId: Int? = null,
    /** Одноразовая дата отправки "YYYY-MM-DD" (пусто = обычное повторение по дням недели). */
    val onceDate: String = "",
) {
    fun timeLabel(): String = "%02d:%02d".format(hour, minute)

    fun daysLabel(): String {
        if (onceDate.isNotBlank()) return "разово $onceDate"
        return when (days.sorted().joinToString("")) {
            "1234567" -> "ежедневно"
            "12345" -> "будни"
            "67" -> "выходные"
            else -> days.sorted()
                .map { DAY_NAMES.getOrElse(it - 1) { "?" } }
                .joinToString(", ")
        }
    }

    /** Вариант текста при чередовании через « | » по номеру дня в году. */
    fun pickVariant(dayOfYear: Int): String {
        val variants = text.split(" | ").map { it.trim() }.filter { it.isNotBlank() }
        return if (variants.isEmpty()) text else variants[Math.floorMod(dayOfYear, variants.size)]
    }

    companion object {
        val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    }
}

/** Именованный набор случайных ответов (шутки, сарказм…). */
data class ReplyPack(
    val id: String = "",
    val name: String = "",
    val items: List<String> = emptyList(),
    /**
     * Бот-владелец (набор пришёл импортом скрипта этого бота). 0 = общий
     * набор библиотеки (создан вручную или старыми версиями). Импорт
     * обновляет только наборы своего бота — чужие не перезаписывает.
     */
    val ownerBotId: Long = 0L,
    /** true — не повторять фразы, пока не пройдёт весь список (U-2 shuffle-bag). */
    val shuffleBag: Boolean = false,
)

/** Команда главного меню Telegram (кнопка «Меню» слева от поля ввода). */
data class MenuCommand(
    val command: String = "",
    val description: String = "",
    /** Область видимости: "" / "default" | "all_private_chats" | "all_group_chats" | "all_chat_administrators". */
    val scope: String = "",
    /** Код языка ISO 639-1 (например "ru", "en"), пусто = для всех языков. */
    val languageCode: String = "",
)

/**
 * Дополнительные настройки правила ответа (хранятся JSON-строкой в BotRuleEntity.options).
 * Пустая строка / значения по умолчанию = старое поведение без лишних миграций Room.
 */
data class RuleOptions(
    val parseMode: String = "",        // "" | "HTML" | "MarkdownV2"
    val disablePreview: Boolean = false,
    val silent: Boolean = false,
    val protectContent: Boolean = false,
    val replyTo: Boolean = false,
    val wholeWord: Boolean = false,    // для type == "contains": по целым словам
    val mediaType: String = "",        // photo | sticker | animation | document | video | voice | audio
    val mediaRef: String = "",         // file_id или https:// URL
    val pollQuestion: String = "",
    val pollOptions: List<String> = emptyList(),
    val pollAnonymous: Boolean = true,
    val pollMultiple: Boolean = false,
    val removeKeyboard: Boolean = false,
    val adminOnly: Boolean = false,
    val requiredRole: String = "",     // "" | "vip" | "admin"
    val skipSubCheck: Boolean = false, // разрешить без проверки подписки на канал
) {
    val isDefault: Boolean
        get() = parseMode.isBlank() && !disablePreview && !silent && !protectContent &&
            !replyTo && !wholeWord && mediaType.isBlank() && mediaRef.isBlank() &&
            pollQuestion.isBlank() && pollOptions.isEmpty() && pollAnonymous &&
            !pollMultiple && !removeKeyboard && !adminOnly && requiredRole.isBlank() && !skipSubCheck
}

/** Настройки Reply-клавиатуры внизу чата. */
data class KeyboardOptions(
    val columns: Int = 2,
    val oneTime: Boolean = false,
    val placeholder: String = "",
    val contactButton: String = "",
    val locationButton: String = "",
) {
    val isDefault: Boolean
        get() = columns == 2 && !oneTime && placeholder.isBlank() &&
            contactButton.isBlank() && locationButton.isBlank()
}

/** Контекст входящего сообщения для движка правил, плейсхолдеров и JS-скриптов. */
data class MessageCtx(
    val chatId: Long = 0L,
    val userId: Long = 0L,
    val firstName: String = "",
    val username: String = "",
    val chatType: String = "",
    val messageId: Int = 0,
    val threadId: Int? = null,
    val text: String = "",
    val replyToText: String = "",
    val kind: String = "",
    val fileId: String = "",
)

/** Gson-хелперы для списков в DataStore и опций правил. */
object BotJson {
    private val gson = Gson()

    fun <T> load(json: String, type: TypeToken<T>, fallback: T): T =
        try {
            gson.fromJson(json, type.type) ?: fallback
        } catch (_: Throwable) {
            fallback
        }

    fun save(value: Any?): String = gson.toJson(value)

    fun stringList(json: String): List<String> =
        load(json, object : TypeToken<List<String>>() {}, emptyList())

    fun stringMap(json: String): Map<String, String> =
        if (json.isBlank() || json == "{}") emptyMap()
        else load(json, object : TypeToken<Map<String, String>>() {}, emptyMap())

    fun fsmList(json: String): List<FsmFlow> =
        if (json.isBlank()) emptyList()
        else load(json, object : TypeToken<List<FsmFlow>>() {}, emptyList())

    fun scheduleList(json: String): List<ScheduleEvent> =
        load(json, object : TypeToken<List<ScheduleEvent>>() {}, emptyList())

    fun packList(json: String): List<ReplyPack> =
        load(json, object : TypeToken<List<ReplyPack>>() {}, emptyList())

    fun menu(json: String): List<InlineBtn> =
        load(json, object : TypeToken<List<InlineBtn>>() {}, emptyList())

    fun menuCommandList(json: String): List<MenuCommand> =
        load(json, object : TypeToken<List<MenuCommand>>() {}, emptyList())

    fun ruleOptions(json: String): RuleOptions =
        if (json.isBlank()) RuleOptions()
        else load(json, object : TypeToken<RuleOptions>() {}, RuleOptions())

    fun keyboardOptions(json: String): KeyboardOptions =
        if (json.isBlank()) KeyboardOptions()
        else load(json, object : TypeToken<KeyboardOptions>() {}, KeyboardOptions())
}

/** Детерминированные id кнопок (рекурсивно, включая вложенные submenu):
 *  пустой callback_data Telegram отвергает (HTTP 400). */
fun List<InlineBtn>.withIds(): List<InlineBtn> = map { b ->
    val fixedSub = if (b.submenu.isNotEmpty()) b.submenu.withIds() else b.submenu
    val resolvedId = if (b.id.isNotBlank()) b.id else btnHashId(b)
    if (resolvedId == b.id && fixedSub === b.submenu) b
    else b.copy(id = resolvedId, submenu = fixedSub)
}

/** Рекурсивный поиск нажатой кнопки в меню и вложенных подменю; возвращает (кнопка, её уровень меню). */
fun List<InlineBtn>.findButtonRecursive(data: String): Pair<InlineBtn, List<InlineBtn>>? {
    val withIdsNow = this.withIds()
    withIdsNow.firstOrNull { it.id == data }?.let { return it to withIdsNow }
    for (btn in withIdsNow) {
        if (btn.submenu.isNotEmpty()) {
            btn.submenu.findButtonRecursive(data)?.let { return it }
        }
    }
    return null
}

private fun btnHashId(b: InlineBtn): String {
    val base = b.label + "|" + b.action + "|" + b.packId + "|" + b.text
    val extra = listOf(b.menuTarget, b.mediaType, b.mediaRef).filter { it.isNotBlank() }.joinToString("|")
    val raw = if (extra.isEmpty()) base else "$base|$extra"
    return "cb" + md8(raw)
}

private fun md8(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(8)

/** Сопоставление правил, извлечение аргументов команд и подстановка плейсхолдеров. */
object BotMatcher {

    fun matchesRule(
        type: String,
        pattern: String,
        text: String,
        wholeWord: Boolean = false,
    ): Boolean = when (type) {
        "command" -> {
            val first = text.trimStart().split(Regex("\\s+")).firstOrNull().orEmpty()
            val expected = if (pattern.startsWith("/")) pattern else "/$pattern"
            first.substringBefore("@").equals(expected, ignoreCase = true)
        }
        "button" -> text.equals(pattern, ignoreCase = true)
        "regex" -> {
            if (pattern.isBlank()) false
            else runCatching {
                Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .containsMatchIn(text)
            }.getOrDefault(false)
        }
        else -> {
            if (pattern.isBlank()) false
            else if (wholeWord && pattern.any { it.isLetterOrDigit() }) {
                Regex(
                    "(?<![\\p{L}\\p{N}_])" + Regex.escape(pattern) + "(?![\\p{L}\\p{N}_])",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(text)
            } else {
                text.contains(pattern, ignoreCase = true)
            }
        }
    }

    fun extractCommandArgsRaw(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return ""
        return trimmed.replaceFirst(Regex("^/\\S+\\s*"), "").trim()
    }

    fun extractCommandArgs(text: String): List<String> {
        val raw = extractCommandArgsRaw(text)
        return if (raw.isBlank()) emptyList() else raw.split(Regex("\\s+"))
    }

    fun expandPlaceholders(
        template: String,
        ctx: MessageCtx,
        botUsername: String = "",
        nowMs: Long = System.currentTimeMillis(),
        vars: Map<String, String> = emptyMap(),
    ): String {
        if (!template.contains('{')) return template
        val argsRaw = extractCommandArgsRaw(ctx.text)
        val args = if (argsRaw.isBlank()) emptyList() else argsRaw.split(Regex("\\s+"))
        val rawUser = ctx.username.removePrefix("@")
        val usernameVal = if (rawUser.isNotBlank()) "@$rawUser" else ctx.firstName
        val userIdVal = (if (ctx.userId != 0L) ctx.userId else ctx.chatId).toString()
        val rawBot = botUsername.removePrefix("@")
        val botVal = if (rawBot.isNotBlank()) "@$rawBot" else ""

        var out = template
            .replace("@{username}", usernameVal)
            .replace("@{bot}", botVal)
            .replace("{user}", ctx.firstName)
            .replace("{username}", usernameVal)
            .replace("{user_id}", userIdVal)
            .replace("{userId}", userIdVal)
            .replace("{chat_id}", ctx.chatId.toString())
            .replace("{chatId}", ctx.chatId.toString())
            .replace("{text}", ctx.text)
            .replace("{bot}", botVal)
            .replace("{args}", argsRaw)
            .replace("{arg1}", args.getOrElse(0) { "" })
            .replace("{arg2}", args.getOrElse(1) { "" })
            .replace("{arg3}", args.getOrElse(2) { "" })

        if (vars.isNotEmpty() && out.contains('{')) {
            vars.forEach { (k, v) ->
                if (k.isNotBlank()) out = out.replace("{$k}", v)
            }
        }

        if (out.contains("{date}") || out.contains("{time}")) {
            val d = Date(nowMs)
            if (out.contains("{date}")) {
                out = out.replace("{date}", SimpleDateFormat("dd.MM.yyyy", Locale.US).format(d))
            }
            if (out.contains("{time}")) {
                out = out.replace("{time}", SimpleDateFormat("HH:mm", Locale.US).format(d))
            }
        }
        return out
    }
}

/**
 * Перебор элементов набора без повторов до полного исчерпания списка (U-2).
 * Хранит оставшиеся индексы по ключу "botId:packId".
 */
object ShuffleBag {
    private val bags = ConcurrentHashMap<String, MutableList<Int>>()
    private val sizes = ConcurrentHashMap<String, Int>()

    fun next(
        botId: Long,
        packId: String,
        items: List<String>,
        rng: Random = Random.Default,
    ): String {
        if (items.isEmpty()) return ""
        if (items.size == 1) return items[0]
        val key = "$botId:$packId"
        val idx = synchronized(bags) {
            var bag = bags[key]
            if (bag == null || bag.isEmpty() || sizes[key] != items.size) {
                bag = items.indices.toMutableList()
                bag.shuffle(rng)
                bags[key] = bag
                sizes[key] = items.size
            }
            bag.removeAt(bag.lastIndex)
        }
        return items[idx]
    }

    fun reset(botId: Long, packId: String? = null) {
        synchronized(bags) {
            val prefix = if (packId == null) "$botId:" else "$botId:$packId"
            val keys = bags.keys.filter { if (packId == null) it.startsWith(prefix) else it == prefix }
            keys.forEach {
                bags.remove(it)
                sizes.remove(it)
            }
        }
    }
}

/** Детерминированный дневной сдвиг (jitter ±N минут) для событий расписания (U-3). */
object ScheduleJitter {
    fun offsetMinutes(eventId: String, year: Int, dayOfYear: Int, jitterMin: Int): Int {
        val j = jitterMin.coerceIn(0, 60)
        if (j == 0) return 0
        val digest = MessageDigest.getInstance("MD5")
            .digest("$eventId:$year:$dayOfYear".toByteArray())
        val hash = ((digest[0].toInt() and 0xFF) shl 24) or
            ((digest[1].toInt() and 0xFF) shl 16) or
            ((digest[2].toInt() and 0xFF) shl 8) or
            (digest[3].toInt() and 0xFF)
        val span = 2 * j + 1
        return Math.floorMod(hash, span) - j
    }

    fun effectiveMinute(
        hour: Int,
        minute: Int,
        eventId: String,
        year: Int,
        dayOfYear: Int,
        jitterMin: Int,
    ): Int {
        val base = hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)
        return (base + offsetMinutes(eventId, year, dayOfYear, jitterMin)).coerceIn(0, 23 * 60 + 59)
    }
}

/** Объявление пользователя (режим «Объявления», публикация в канал). */
data class Listing(
    val id: String = "",
    val chatId: Long = 0L,
    val firstName: String = "",
    val description: String = "",
    val contactType: String = "msg",  // msg | phone
    val contactValue: String = "",
    /** Имена файлов фото в bot_<id>/media. */
    val photos: List<String> = emptyList(),
    /** id сообщений объявления в канале (для удаления/переопределения). */
    val messageIds: List<Int> = emptyList(),
    val createdAt: Long = 0L,
    val status: String = "active",    // active | taken
)

/** Шаг пошагового диалога (FSM: анкеты, заявки, заказы, квизы). */
data class FsmStep(
    val id: String = "",
    val prompt: String = "",
    /** Тип ожидаемого ответа: text | number | phone | photo | location | button | any */
    val inputType: String = "text",
    /** Имя переменной {var}, в которую сохраняется ввод пользователя. */
    val varName: String = "answer",
    /** Варианты кнопок для inputType == "button" (или подсказок). */
    val choices: List<String> = emptyList(),
    /** Сообщение при неверном формате ответа. */
    val errorText: String = "",
    /** Следующий id шага (пусто = по порядку; "DONE" = завершить). */
    val nextStepId: String = "",
    /** Ветвление: значение ответа -> id следующего шага или "DONE". */
    val branchMap: Map<String, String> = emptyMap(),
)

/** Пошаговый сценарий (FSM-поток). */
data class FsmFlow(
    val id: String = "",
    val name: String = "",
    val triggerType: String = "command",  // command | button | contains
    val triggerPattern: String = "/form",
    val enabled: Boolean = true,
    val steps: List<FsmStep> = emptyList(),
    val finishText: String = "✅ Анкета отправлена! Спасибо, {user}.",
    /** Куда отправлять итог: "" (никуда) | "admins" | "channel" | "both". */
    val notifyTarget: String = "admins",
    val summaryTemplate: String = "",
)

/** Результат проверки ответа пользователя на шаге FSM. */
data class FsmValidationResult(
    val ok: Boolean,
    val value: String = "",
    val error: String = "",
)

/** Валидация ввода и построение итоговой сводки FSM (чистый Kotlin, без Android SDK). */
object FsmValidator {
    private val phoneRe = Regex("^[+]?[\\d\\s\\-()]{7,20}$")

    fun validate(
        step: FsmStep,
        text: String,
        kind: String = "",
        photoId: String = "",
        fileId: String = "",
        locationLat: Double? = null,
        locationLon: Double? = null,
        contactPhone: String = "",
    ): FsmValidationResult {
        val t = text.trim()
        return when (step.inputType.lowercase()) {
            "number" -> {
                val norm = t.replace(',', '.')
                if (norm.toDoubleOrNull() != null) FsmValidationResult(true, norm)
                else FsmValidationResult(false, error = step.errorText.ifBlank { "Введите число (например: 3 или 12.5)." })
            }
            "phone" -> {
                val p = contactPhone.trim().ifBlank { t }
                if (phoneRe.matches(p)) FsmValidationResult(true, p)
                else FsmValidationResult(false, error = step.errorText.ifBlank { "Укажите корректный номер телефона (например: +7 999 123-45-67)." })
            }
            "photo" -> {
                val pid = photoId.ifBlank { if (kind == "фото") fileId else "" }
                if (pid.isNotBlank()) FsmValidationResult(true, pid)
                else FsmValidationResult(false, error = step.errorText.ifBlank { "Пожалуйста, отправьте фотографию." })
            }
            "location" -> {
                if (locationLat != null && locationLon != null) {
                    FsmValidationResult(true, "$locationLat,$locationLon")
                } else {
                    FsmValidationResult(false, error = step.errorText.ifBlank { "Отправьте геопозицию (скрепка → Локация)." })
                }
            }
            "button" -> {
                if (step.choices.isEmpty()) {
                    if (t.isNotBlank()) FsmValidationResult(true, t)
                    else FsmValidationResult(false, error = step.errorText.ifBlank { "Выберите один из вариантов." })
                } else {
                    val matched = step.choices.firstOrNull { it.equals(t, ignoreCase = true) }
                    if (matched != null) FsmValidationResult(true, matched)
                    else FsmValidationResult(false, error = step.errorText.ifBlank { "Нажмите одну из кнопок: ${step.choices.joinToString(", ")}" })
                }
            }
            "any" -> {
                val v = t.ifBlank { fileId.ifBlank { photoId } }
                if (v.isNotBlank()) FsmValidationResult(true, v)
                else FsmValidationResult(false, error = step.errorText.ifBlank { "Отправьте сообщение или вложение." })
            }
            else -> {
                if (t.isNotBlank() && kind.isBlank()) FsmValidationResult(true, t.take(2000))
                else if (t.isNotBlank()) FsmValidationResult(true, t.take(2000))
                else FsmValidationResult(false, error = step.errorText.ifBlank { "Пожалуйста, напишите ответ текстом." })
            }
        }
    }

    fun nextStepIndex(flow: FsmFlow, currentIndex: Int, value: String): Int? {
        val cur = flow.steps.getOrNull(currentIndex) ?: return null
        val branch = cur.branchMap.entries.firstOrNull { it.key.equals(value.trim(), ignoreCase = true) }?.value
            ?: cur.nextStepId.takeIf { it.isNotBlank() }
        if (branch != null) {
            if (branch.equals("DONE", ignoreCase = true)) return null
            val idx = flow.steps.indexOfFirst { it.id == branch }
            if (idx >= 0) return idx
        }
        val nxt = currentIndex + 1
        return if (nxt < flow.steps.size) nxt else null
    }

    fun buildSummary(
        flow: FsmFlow,
        ctx: MessageCtx,
        collected: Map<String, String>,
        botUsername: String = "",
    ): String {
        if (flow.summaryTemplate.isNotBlank()) {
            return BotMatcher.expandPlaceholders(flow.summaryTemplate, ctx, botUsername, vars = collected)
        }
        val sb = StringBuilder("📋 Новая заявка: ${flow.name}\n")
        val who = buildString {
            append(ctx.firstName.ifBlank { "Пользователь" })
            if (ctx.username.isNotBlank()) append(" (@${ctx.username.removePrefix("@")})")
            append(" [id:${if (ctx.userId != 0L) ctx.userId else ctx.chatId}]")
        }
        sb.append("👤 От: ").append(who).append('\n')
        flow.steps.forEach { st ->
            val v = collected[st.varName].orEmpty()
            if (v.isNotBlank()) {
                val label = st.varName
                val shown = if (st.inputType == "photo") "[фото: ${v.take(18)}…]" else v
                sb.append("• ").append(label).append(": ").append(shown).append('\n')
            }
        }
        return sb.toString().trim()
    }
}

/** Политика ролей, админов и подписки на обязательный канал. */
object AccessPolicy {
    private val subscribedStatuses = setOf("creator", "administrator", "member", "restricted")

    fun parseAdminIds(raw: String): Set<Long> =
        raw.split(Regex("[,;\\s]+"))
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
            .toSet()

    fun isAdmin(userId: Long, chatId: Long, userRole: String, adminIds: Set<Long>): Boolean =
        userRole.equals("admin", ignoreCase = true) ||
            (userId > 0L && userId in adminIds) ||
            (chatId > 0L && chatId in adminIds)

    fun canUseRule(opts: RuleOptions, isAdmin: Boolean, userRole: String): Boolean {
        if (opts.adminOnly && !isAdmin) return false
        val req = opts.requiredRole.trim().lowercase()
        if (req.isBlank()) return true
        if (isAdmin) return true
        val role = userRole.trim().lowercase()
        return when (req) {
            "admin" -> role == "admin"
            "vip" -> role == "vip" || role == "admin"
            else -> role == req
        }
    }

    fun isMemberSubscribed(status: String): Boolean =
        status.trim().lowercase() in subscribedStatuses
}
