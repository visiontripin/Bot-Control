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
    val autoReactEmoji: String = "",   // авто-реакция на входящее сообщение (Stage 3)
    val targetThreadId: Int? = null,   // конкретная тема форума для ответа (Stage 3)
    val chatScope: String = "",        // "" | "private" | "group" | "channel" (Stage 3)
) {
    val isDefault: Boolean
        get() = parseMode.isBlank() && !disablePreview && !silent && !protectContent &&
            !replyTo && !wholeWord && mediaType.isBlank() && mediaRef.isBlank() &&
            pollQuestion.isBlank() && pollOptions.isEmpty() && pollAnonymous &&
            !pollMultiple && !removeKeyboard && !adminOnly && requiredRole.isBlank() &&
            !skipSubCheck && autoReactEmoji.isBlank() && targetThreadId == null && chatScope.isBlank()
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

/** Контекст входящего сообщения для движка правил, плейсхолдеров, LLM и JS-скриптов. */
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
    val caption: String = "",
    val mimeType: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val localMediaPath: String = "",
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

    fun groupModeration(json: String): GroupModerationConfig =
        if (json.isBlank()) GroupModerationConfig()
        else load(json, object : TypeToken<GroupModerationConfig>() {}, GroupModerationConfig())

    fun inlineConfig(json: String): InlineModeConfig =
        if (json.isBlank()) InlineModeConfig()
        else load(json, object : TypeToken<InlineModeConfig>() {}, InlineModeConfig())

    fun productList(json: String): List<DigitalProduct> =
        if (json.isBlank()) emptyList()
        else load(json, object : TypeToken<List<DigitalProduct>>() {}, emptyList())

    fun orderList(json: String): List<StarOrder> =
        if (json.isBlank()) emptyList()
        else load(json, object : TypeToken<List<StarOrder>>() {}, emptyList())

    fun webAppConfig(json: String): WebAppConfig =
        if (json.isBlank()) WebAppConfig()
        else load(json, object : TypeToken<WebAppConfig>() {}, WebAppConfig())

    fun integrationsConfig(json: String): IntegrationsConfig =
        if (json.isBlank()) IntegrationsConfig()
        else load(json, object : TypeToken<IntegrationsConfig>() {}, IntegrationsConfig())
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
            .replace("{caption}", ctx.caption)
            .replace("{kind}", ctx.kind)
            .replace("{file_id}", ctx.fileId)
            .replace("{file_name}", ctx.fileName)
            .replace("{local_path}", ctx.localMediaPath)

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

/** Настройки групп, каналов, тем форумов, реакций, опросов и авто-модерации на бота (Stage 3). */
data class GroupModerationConfig(
    val welcomeEnabled: Boolean = false,
    val welcomeText: String = "👋 Добро пожаловать, {user}!",
    val welcomeDeleteAfterSec: Int = 0,
    val captchaEnabled: Boolean = false,
    val captchaPrompt: String = "👋 {user}, подтвердите, что вы не робот — нажмите кнопку ниже в течение {timeout} мин.",
    val captchaButtonText: String = "✅ Я не робот",
    val captchaTimeoutMin: Int = 5,
    val captchaActionOnTimeout: String = "kick", // kick | mute
    val autoApproveJoinRequests: Boolean = false,
    val joinRequestWelcome: String = "",
    val antispamEnabled: Boolean = false,
    val blockLinks: Boolean = false,
    val allowedLinkDomains: List<String> = emptyList(),
    val stopWords: List<String> = emptyList(),
    val antispamAction: String = "delete", // delete | mute | ban
    val muteDurationMin: Int = 60,
    val antispamWarnText: String = "⚠️ {user}, сообщение удалено правилами чата.",
    val floodLimitCount: Int = 0,
    val floodWindowSec: Int = 10,
    val defaultThreadId: Int? = null,
    val logChatId: String = "",
    val reactionTriggerEmoji: String = "",
    val reactionTriggerReply: String = "",
    val pollAnswerReply: String = "",
)

/** Результат проверки сообщения в группе фильтрами антиспама. */
data class ModerationVerdict(
    val violation: Boolean,
    val reason: String = "",   // stopword | link | flood
    val matched: String = "",
    val action: String = "",   // delete | mute | ban
)

/** Правила модерации групп, фильтрации по типу чата и маршрутизации тем форума (чистый Kotlin). */
object ModerationPolicy {
    private val urlRegex = Regex(
        """(?i)(?:https?://|www\.|(?:t\.me|telegram\.me)/)[^\s<>()]+|(?<![\w.])@[a-zA-Z][a-zA-Z0-9_]{3,}""",
    )

    fun isGroupChat(chatType: String): Boolean =
        chatType.equals("group", ignoreCase = true) ||
            chatType.equals("supergroup", ignoreCase = true)

    fun matchesChatScope(chatScope: String, chatType: String): Boolean = when (chatScope.trim().lowercase()) {
        "" -> true
        "private" -> chatType.isBlank() || chatType.equals("private", ignoreCase = true)
        "group", "groups" -> isGroupChat(chatType)
        "channel" -> chatType.equals("channel", ignoreCase = true)
        else -> true
    }

    fun extractUrls(text: String): List<String> =
        if (text.isBlank()) emptyList()
        else urlRegex.findAll(text).map { it.value.trimEnd('.', ',', ';', ')', '!') }.toList()

    fun domainOf(rawUrl: String): String {
        val clean = rawUrl.trim().lowercase()
        if (clean.startsWith("@")) return clean
        val noScheme = clean
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
        return noScheme.substringBefore('/').substringBefore('?').substringBefore(':')
    }

    fun isUrlAllowed(url: String, allowedDomains: List<String>): Boolean {
        if (allowedDomains.isEmpty()) return false
        val host = domainOf(url)
        if (host.isBlank()) return false
        if (host.startsWith("@")) {
            return allowedDomains.any { it.trim().lowercase() == host }
        }
        return allowedDomains.any { raw ->
            val dom = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
            dom.isNotBlank() && !dom.startsWith("@") && (host == dom || host.endsWith(".$dom"))
        }
    }

    fun checkMessage(
        config: GroupModerationConfig,
        text: String,
        isAdmin: Boolean,
        chatType: String,
    ): ModerationVerdict {
        if (!config.antispamEnabled || isAdmin || !isGroupChat(chatType)) {
            return ModerationVerdict(false)
        }
        val normalized = text.lowercase()
        for (rawWord in config.stopWords) {
            val w = rawWord.trim().lowercase()
            if (w.isNotBlank() && normalized.contains(w)) {
                return ModerationVerdict(
                    violation = true,
                    reason = "stopword",
                    matched = w,
                    action = config.antispamAction.ifBlank { "delete" },
                )
            }
        }
        if (config.blockLinks) {
            for (u in extractUrls(text)) {
                if (!isUrlAllowed(u, config.allowedLinkDomains)) {
                    return ModerationVerdict(
                        violation = true,
                        reason = "link",
                        matched = u.take(80),
                        action = config.antispamAction.ifBlank { "delete" },
                    )
                }
            }
        }
        return ModerationVerdict(false)
    }

    fun resolveThreadId(
        ruleTargetThreadId: Int?,
        incomingThreadId: Int?,
        defaultGroupThreadId: Int?,
        isGroupChat: Boolean,
    ): Int? = when {
        ruleTargetThreadId != null && ruleTargetThreadId > 0 -> ruleTargetThreadId
        incomingThreadId != null && incomingThreadId > 0 -> incomingThreadId
        isGroupChat && defaultGroupThreadId != null && defaultGroupThreadId > 0 -> defaultGroupThreadId
        else -> null
    }
}

/** Трекер частоты сообщений (антифлуд по ключу botId:chatId:userId). */
object FloodTracker {
    private val windows = ConcurrentHashMap<String, MutableList<Long>>()

    fun recordAndCheck(
        botId: Long,
        chatId: Long,
        userId: Long,
        limitCount: Int,
        windowSec: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (limitCount <= 0 || windowSec <= 0 || userId == 0L) return false
        val key = "$botId:$chatId:$userId"
        val cutoff = nowMs - windowSec.coerceIn(1, 300) * 1000L
        val list = windows.getOrPut(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it < cutoff }
            list.add(nowMs)
            return list.size > limitCount
        }
    }

    fun reset(botId: Long) {
        val prefix = "$botId:"
        windows.keys.filter { it.startsWith(prefix) }.forEach { windows.remove(it) }
    }
}

/** Кодирование/разбор callback_data капчи: "cap_<chatId>_<userId>". */
object CaptchaPolicy {
    fun callbackData(chatId: Long, userId: Long): String = "cap_${chatId}_$userId"

    fun parseCallback(data: String): Pair<Long, Long>? {
        if (!data.startsWith("cap_")) return null
        val rest = data.removePrefix("cap_")
        val sep = rest.lastIndexOf('_')
        if (sep <= 0) return null
        val chatId = rest.substring(0, sep).toLongOrNull() ?: return null
        val userId = rest.substring(sep + 1).toLongOrNull() ?: return null
        return chatId to userId
    }
}

// ------------------------------------------------------------------
// Stage 4 (v2.1.0): Inline-режим, Telegram Stars, Mini Apps и HTTP API
// ------------------------------------------------------------------

/** Карточка результата inline-режима (@bot запрос). */
data class InlineArticleItem(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val replyText: String = "",
    val parseMode: String = "",
    val keywords: String = "",
)

/** Настройки Inline Mode на бота (per-botId). */
data class InlineModeConfig(
    val enabled: Boolean = false,
    val includePacks: Boolean = true,
    val includeRules: Boolean = true,
    val switchPmText: String = "",
    val cacheTimeSec: Int = 10,
    val customItems: List<InlineArticleItem> = emptyList(),
)

/** Цифровой товар или доступ за Telegram Stars (currency = XTR). */
data class DigitalProduct(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val priceStars: Int = 10,
    val deliverText: String = "✅ Спасибо за оплату {title}! Ваш доступ активирован.",
    val deliverMediaType: String = "",
    val deliverMediaRef: String = "",
    val grantRole: String = "", // "" | "vip" | "admin"
    val grantTag: String = "",
    val active: Boolean = true,
)

/** Запись об успешном платеже Telegram Stars (для истории и refundStarPayment). */
data class StarOrder(
    val id: String = "",
    val userId: Long = 0L,
    val username: String = "",
    val productId: String = "",
    val title: String = "",
    val amountStars: Int = 0,
    val chargeId: String = "",
    val createdAtSec: Long = 0L,
    val refunded: Boolean = false,
)

/** Настройки Telegram Mini App (локальная витрина / внешний HTTPS Mini App). */
data class WebAppConfig(
    val localMiniAppEnabled: Boolean = false,
    val title: String = "Витрина бота",
    val introText: String = "Выберите товар или оставьте заявку — данные придут прямо в чат.",
    val submitButtonText: String = "Отправить в бот",
    val externalUrl: String = "",
    val submitReplyTemplate: String = "✅ Заявка из Mini App получена:\n{data}",
    val sendSubmitToAdmin: Boolean = true,
)

/** Внешние HTTP-интеграции (белый список доменов) и обработка входящих медиа. */
data class IntegrationsConfig(
    val httpAllowedDomains: List<String> = emptyList(),
    val httpTimeoutSec: Int = 8,
    val webhookOnPaymentUrl: String = "",
    val webhookOnLeadUrl: String = "",
    val passIncomingMediaToLlm: Boolean = true,
)

/** Упрощённое представление правила для чистого Kotlin-матчера Inline-режима. */
data class InlineRuleEntry(
    val id: Int,
    val pattern: String,
    val responseText: String,
    val actionType: String,
    val packId: String,
    val parseMode: String = "",
    val adminOnly: Boolean = false,
    val enabled: Boolean = true,
)

/** Чистый Kotlin-построитель карточек inline_query из каталога, правил и наборов. */
data class BuiltInlineArticle(
    val id: String,
    val title: String,
    val description: String,
    val messageText: String,
    val parseMode: String = "",
)

object InlineCatalogMatcher {
    fun buildArticles(
        query: String,
        config: InlineModeConfig,
        rules: List<InlineRuleEntry>,
        packs: List<ReplyPack>,
        products: List<DigitalProduct>,
        botId: Long,
        ctx: MessageCtx,
        botUsername: String = "",
        maxResults: Int = 25,
    ): List<BuiltInlineArticle> {
        if (!config.enabled) return emptyList()
        val q = query.trim().lowercase()
        val out = ArrayList<BuiltInlineArticle>()
        val seenIds = HashSet<String>()

        fun addArticle(rawId: String, title: String, desc: String, body: String, pm: String = "") {
            if (out.size >= maxResults) return
            if (title.isBlank() || body.isBlank()) return
            val id = rawId.take(64).ifBlank { "inl_${out.size}" }
            if (!seenIds.add(id)) return
            val expanded = BotMatcher.expandPlaceholders(body, ctx.copy(text = "/inline ${query.trim()}"), botUsername)
            out.add(
                BuiltInlineArticle(
                    id = id,
                    title = title.take(128),
                    description = desc.take(200),
                    messageText = expanded.take(4000),
                    parseMode = pm,
                ),
            )
        }

        for (item in config.customItems) {
            val hit = q.isBlank() ||
                item.title.lowercase().contains(q) ||
                item.description.lowercase().contains(q) ||
                item.keywords.lowercase().contains(q) ||
                item.replyText.lowercase().contains(q)
            if (hit) {
                addArticle(
                    rawId = "item_${item.id.ifBlank { md8(item.title) }}",
                    title = item.title,
                    desc = item.description.ifBlank { item.replyText.take(80) },
                    body = item.replyText,
                    pm = item.parseMode,
                )
            }
        }

        if (config.includeRules) {
            for (r in rules) {
                if (!r.enabled || r.adminOnly) continue
                if (r.actionType !in setOf("text", "pack")) continue
                val body = if (r.actionType == "pack") {
                    val p = packs.firstOrNull { it.id == r.packId }
                    p?.items?.firstOrNull().orEmpty()
                } else r.responseText
                if (body.isBlank()) continue
                val hit = q.isBlank() ||
                    r.pattern.lowercase().contains(q) ||
                    body.lowercase().contains(q)
                if (hit) {
                    addArticle(
                        rawId = "rule_${r.id}",
                        title = r.pattern,
                        desc = body.take(90),
                        body = body,
                        pm = r.parseMode,
                    )
                }
            }
        }

        if (config.includePacks) {
            val ownOrShared = packs.filter { it.ownerBotId == 0L || it.ownerBotId == botId }
            for (p in ownOrShared) {
                if (p.items.isEmpty()) continue
                val nameMatch = q.isBlank() || p.name.lowercase().contains(q)
                val matchingItems = if (nameMatch) p.items.take(3)
                else p.items.filter { it.lowercase().contains(q) }.take(3)
                matchingItems.forEachIndexed { idx, line ->
                    addArticle(
                        rawId = "pack_${md8(p.id + "_" + idx + "_" + line.take(20))}",
                        title = "${p.name}: ${line.take(40)}",
                        desc = line.take(90),
                        body = line,
                    )
                }
            }
        }

        for (prod in products.filter { it.active }) {
            val hit = q.isBlank() ||
                prod.title.lowercase().contains(q) ||
                prod.description.lowercase().contains(q)
            if (hit) {
                val botTag = botUsername.removePrefix("@")
                val linkHint = if (botTag.isNotBlank()) "\n\n👉 Оформить в боте: https://t.me/$botTag?start=buy_${prod.id}" else ""
                addArticle(
                    rawId = "prod_${prod.id.ifBlank { md8(prod.title) }}",
                    title = "⭐ ${prod.title} (${prod.priceStars} XTR)",
                    desc = prod.description.ifBlank { "Цифровой товар за ${prod.priceStars} звёзд" },
                    body = "⭐ <b>${prod.title}</b> — ${prod.priceStars} Stars\n${prod.description}$linkHint",
                    pm = "HTML",
                )
            }
        }
        return out
    }
}

/** Проверка белого списка доменов для исходящих HTTP-запросов скриптов и вебхуков. */
object HttpAllowlistPolicy {
    fun parseDomains(raw: String): List<String> =
        raw.split(",", "\n", ";", " ")
            .map { normalizeHost(it) }
            .filter { it.isNotBlank() }
            .distinct()

    private fun normalizeHost(input: String): String {
        val s = input.trim().lowercase()
        if (s.isBlank()) return ""
        val afterScheme = if ("://" in s) s.substringAfter("://") else s
        return afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')
            .trim()
    }

    fun isLocalHost(host: String): Boolean =
        host == "localhost" ||
            host == "127.0.0.1" ||
            host.startsWith("192.168.") ||
            host.startsWith("10.")

    fun isUrlAllowed(rawUrl: String, allowedDomains: List<String>): Boolean {
        if (allowedDomains.isEmpty()) return false
        val trimmed = rawUrl.trim()
        val lower = trimmed.lowercase()
        val isHttps = lower.startsWith("https://")
        val isHttp = lower.startsWith("http://")
        if (!isHttps && !isHttp) return false
        val authority = trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank() || '@' in authority) return false
        val host = authority.substringBefore(':').lowercase()
        if (host.isBlank()) return false
        if (isHttp && !isLocalHost(host)) return false
        return allowedDomains.any { rawAllowed ->
            val dom = normalizeHost(rawAllowed)
            dom.isNotBlank() && (host == dom || host.endsWith(".$dom"))
        }
    }
}

/** Политика счетов Telegram Stars (XTR) и проверки pre_checkout_query. */
object PaymentPolicy {
    fun productPayload(productId: String): String = "prod_${productId.trim()}"

    fun parseProductPayload(payload: String): String? =
        if (payload.startsWith("prod_")) payload.removePrefix("prod_").takeIf { it.isNotBlank() } else null

    fun verifyPreCheckout(
        currency: String,
        totalAmount: Int,
        payload: String,
        products: List<DigitalProduct>,
    ): Pair<Boolean, String> {
        if (totalAmount <= 0) return false to "Некорректная сумма платежа"
        val prodId = parseProductPayload(payload) ?: return true to ""
        val prod = products.firstOrNull { it.id == prodId }
            ?: return false to "Товар больше недоступен"
        if (!prod.active) return false to "Товар временно снят с продажи"
        if (currency.equals("XTR", ignoreCase = true) && prod.priceStars != totalAmount) {
            return false to "Цена товара изменилась (${prod.priceStars} XTR)"
        }
        return true to ""
    }
}
