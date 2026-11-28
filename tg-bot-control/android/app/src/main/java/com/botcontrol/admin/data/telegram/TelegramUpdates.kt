package com.botcontrol.admin.data.telegram

/** Участник чата из событий new_chat_members / left_chat_member / chat_member. */
data class TgChatParticipant(
    val userId: Long = 0L,
    val firstName: String = "",
    val username: String = "",
    val isBot: Boolean = false,
)

/** Одно входящее сообщение или пост канала (включая правки, медиа, форумы и входы). */
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
    /** Stage 3: признаки правки, поста канала, заголовка чата, ботов, тем форума и новых участников. */
    val isEdited: Boolean = false,
    val isChannelPost: Boolean = false,
    val chatTitle: String = "",
    val senderIsBot: Boolean = false,
    val isForumTopic: Boolean = false,
    val newMembers: List<TgChatParticipant> = emptyList(),
    val leftMember: TgChatParticipant? = null,
)

/** Нажатие inline-кнопки (callback_query). */
data class TgCallback(
    val updateId: Long,
    val callbackId: String,
    val chatId: Long,
    val messageId: Int,
    val data: String,
    val firstName: String,
    val userId: Long = 0L,
    val username: String = "",
)

/** Событие изменения статуса участника или самого бота (my_chat_member / chat_member). */
data class TgChatMemberUpdate(
    val updateId: Long,
    val chatId: Long,
    val chatTitle: String = "",
    val chatType: String = "",
    val isMyChatMember: Boolean = false,
    val actorUserId: Long = 0L,
    val targetUserId: Long = 0L,
    val targetFirstName: String = "",
    val targetUsername: String = "",
    val targetIsBot: Boolean = false,
    val oldStatus: String = "",
    val newStatus: String = "",
)

/** Заявка на вступление в закрытую группу или канал (chat_join_request). */
data class TgJoinRequest(
    val updateId: Long,
    val chatId: Long,
    val chatTitle: String = "",
    val chatType: String = "",
    val userId: Long,
    val firstName: String = "",
    val username: String = "",
    val bio: String = "",
    val inviteLinkName: String = "",
)

/** Изменение реакции на сообщение (message_reaction). */
data class TgMessageReaction(
    val updateId: Long,
    val chatId: Long,
    val messageId: Int,
    val userId: Long = 0L,
    val firstName: String = "",
    val username: String = "",
    val oldEmojis: List<String> = emptyList(),
    val newEmojis: List<String> = emptyList(),
)

/** Обновление состояния опроса (poll). */
data class TgPollUpdate(
    val updateId: Long,
    val pollId: String,
    val question: String = "",
    val options: List<Pair<String, Int>> = emptyList(),
    val totalVoterCount: Int = 0,
    val isClosed: Boolean = false,
)

/** Голос пользователя в неанонимном опросе (poll_answer). */
data class TgPollAnswer(
    val updateId: Long,
    val pollId: String,
    val userId: Long = 0L,
    val firstName: String = "",
    val username: String = "",
    val optionIds: List<Int> = emptyList(),
)

/** Полный пакет разобранных обновлений getUpdates или Webhook. */
data class TgUpdatesBatch(
    val messages: List<TgMessage> = emptyList(),
    val callbacks: List<TgCallback> = emptyList(),
    val memberUpdates: List<TgChatMemberUpdate> = emptyList(),
    val joinRequests: List<TgJoinRequest> = emptyList(),
    val reactions: List<TgMessageReaction> = emptyList(),
    val pollUpdates: List<TgPollUpdate> = emptyList(),
    val pollAnswers: List<TgPollAnswer> = emptyList(),
    val nextOffset: Long = 0L,
) {
    val totalCount: Int
        get() = messages.size + callbacks.size + memberUpdates.size +
            joinRequests.size + reactions.size + pollUpdates.size + pollAnswers.size
}

/** Сведения о чате/группе/канале (getChat). */
data class TgChatInfo(
    val id: Long,
    val title: String = "",
    val type: String = "",
    val username: String = "",
    val description: String = "",
    val isForum: Boolean = false,
    val memberCount: Int? = null,
)

/** Администратор чата/группы (getChatAdministrators). */
data class TgChatAdmin(
    val userId: Long,
    val firstName: String = "",
    val username: String = "",
    val status: String = "",
    val customTitle: String = "",
)

/**
 * Компактный RFC 8259 JSON-парсер без внешних библиотек: работает одинаково
 * на Android и в offline JVM-харнессе [Stage3Test].
 */
object MiniJson {
    private class Reader(private val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i] in charArrayOf(' ', '\t', '\r', '\n')) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) error("unexpected EOF")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> num()
                else -> error("unexpected char '$c' at $i")
            }
        }

        private fun obj(): Map<String, Any?> {
            i++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (i < s.length) {
                skipWs()
                if (s[i] != '"') error("expected string key at $i")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') error("expected ':' at $i")
                i++
                map[k] = value()
                skipWs()
                if (i >= s.length) error("unterminated object")
                if (s[i] == '}') {
                    i++
                    return map
                }
                if (s[i] != ',') error("expected ',' or '}' at $i")
                i++
            }
            error("unterminated object")
        }

        private fun arr(): List<Any?> {
            i++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (i < s.length) {
                list.add(value())
                skipWs()
                if (i >= s.length) error("unterminated array")
                if (s[i] == ']') {
                    i++
                    return list
                }
                if (s[i] != ',') error("expected ',' or ']' at $i")
                i++
            }
            error("unterminated array")
        }

        private fun str(): String {
            i++ // '"'
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) error("bad escape")
                        when (val esc = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) error("bad unicode escape")
                                val code = s.substring(i, i + 4).toInt(16)
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("unterminated string")
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) error("expected $word at $i")
            i += word.length
            return v
        }

        private fun num(): Number {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isFloat = false
            if (i < s.length && s[i] == '.') {
                isFloat = true
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val raw = s.substring(start, i)
            return if (isFloat) raw.toDouble() else raw.toLong()
        }
    }

    fun parseObject(raw: String): Map<String, Any?>? =
        runCatching { Reader(raw).value() as? Map<String, Any?> }.getOrNull()
}

/**
 * Единый разбор обновлений Telegram Bot API (для getUpdates и локального Webhook-адаптера).
 * Чистый Kotlin без зависимости от Android SDK / OkHttp.
 */
object TelegramUpdatesParser {
    val DEFAULT_ALLOWED_UPDATES = listOf(
        "message",
        "edited_message",
        "channel_post",
        "edited_channel_post",
        "callback_query",
        "my_chat_member",
        "chat_member",
        "chat_join_request",
        "message_reaction",
        "poll",
        "poll_answer",
    )

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun arr(v: Any?): List<Any?>? = v as? List<Any?>
    private fun str(m: Map<String, Any?>?, k: String): String = m?.get(k) as? String ?: ""
    private fun long(m: Map<String, Any?>?, k: String): Long = (m?.get(k) as? Number)?.toLong() ?: 0L
    private fun int(m: Map<String, Any?>?, k: String): Int = (m?.get(k) as? Number)?.toInt() ?: 0
    private fun optInt(m: Map<String, Any?>?, k: String): Int? = (m?.get(k) as? Number)?.toInt()
    private fun optDbl(m: Map<String, Any?>?, k: String): Double? = (m?.get(k) as? Number)?.toDouble()
    private fun bool(m: Map<String, Any?>?, k: String): Boolean = m?.get(k) as? Boolean ?: false

    fun parseGetUpdatesResponse(rawJson: String, offset: Long = 0L): TgUpdatesBatch {
        val root = MiniJson.parseObject(rawJson) ?: return TgUpdatesBatch(nextOffset = offset)
        val result = arr(root["result"]) ?: return TgUpdatesBatch(nextOffset = offset)
        return parseUpdateMaps(result.mapNotNull { obj(it) }, offset)
    }

    fun parseSingleUpdate(rawUpdateJson: String): TgUpdatesBatch {
        val upd = MiniJson.parseObject(rawUpdateJson) ?: return TgUpdatesBatch()
        return parseUpdateMaps(listOf(upd), 0L)
    }

    fun parseUpdateMaps(updates: List<Map<String, Any?>>, offset: Long = 0L): TgUpdatesBatch {
        val messages = mutableListOf<TgMessage>()
        val callbacks = mutableListOf<TgCallback>()
        val memberUpdates = mutableListOf<TgChatMemberUpdate>()
        val joinRequests = mutableListOf<TgJoinRequest>()
        val reactions = mutableListOf<TgMessageReaction>()
        val pollUpdates = mutableListOf<TgPollUpdate>()
        val pollAnswers = mutableListOf<TgPollAnswer>()
        var maxId = if (offset > 0) offset - 1 else 0L

        for (u in updates) {
            val updateId = long(u, "update_id")
            if (updateId > maxId) maxId = updateId

            parseMessageField(u, updateId, "message", isEdited = false, isChannel = false)?.let { messages.add(it); continue }
            parseMessageField(u, updateId, "edited_message", isEdited = true, isChannel = false)?.let { messages.add(it); continue }
            parseMessageField(u, updateId, "channel_post", isEdited = false, isChannel = true)?.let { messages.add(it); continue }
            parseMessageField(u, updateId, "edited_channel_post", isEdited = true, isChannel = true)?.let { messages.add(it); continue }

            obj(u["callback_query"])?.let { cb ->
                val message = obj(cb["message"])
                val chat = obj(message?.get("chat"))
                val from = obj(cb["from"])
                if (chat != null) {
                    callbacks.add(
                        TgCallback(
                            updateId = updateId,
                            callbackId = str(cb, "id"),
                            chatId = long(chat, "id"),
                            messageId = int(message, "message_id"),
                            data = str(cb, "data"),
                            firstName = str(from, "first_name"),
                            userId = long(from, "id"),
                            username = str(from, "username"),
                        )
                    )
                }
                continue
            }

            obj(u["my_chat_member"])?.let { cm ->
                parseChatMember(cm, updateId, isMy = true)?.let(memberUpdates::add)
                continue
            }
            obj(u["chat_member"])?.let { cm ->
                parseChatMember(cm, updateId, isMy = false)?.let(memberUpdates::add)
                continue
            }
            obj(u["chat_join_request"])?.let { jr ->
                val chat = obj(jr["chat"])
                val from = obj(jr["from"])
                val inv = obj(jr["invite_link"])
                if (chat != null && from != null) {
                    joinRequests.add(
                        TgJoinRequest(
                            updateId = updateId,
                            chatId = long(chat, "id"),
                            chatTitle = str(chat, "title"),
                            chatType = str(chat, "type"),
                            userId = long(from, "id"),
                            firstName = str(from, "first_name"),
                            username = str(from, "username"),
                            bio = str(jr, "bio"),
                            inviteLinkName = str(inv, "name"),
                        )
                    )
                }
                continue
            }
            obj(u["message_reaction"])?.let { mr ->
                val chat = obj(mr["chat"])
                val user = obj(mr["user"]) ?: obj(mr["actor_chat"])
                if (chat != null) {
                    reactions.add(
                        TgMessageReaction(
                            updateId = updateId,
                            chatId = long(chat, "id"),
                            messageId = int(mr, "message_id"),
                            userId = long(user, "id"),
                            firstName = str(user, "first_name").ifBlank { str(user, "title") },
                            username = str(user, "username"),
                            oldEmojis = extractReactionEmojis(arr(mr["old_reaction"])),
                            newEmojis = extractReactionEmojis(arr(mr["new_reaction"])),
                        )
                    )
                }
                continue
            }
            obj(u["poll"])?.let { p ->
                val opts = arr(p["options"]).orEmpty().mapNotNull { o ->
                    obj(o)?.let { str(it, "text") to int(it, "voter_count") }
                }
                pollUpdates.add(
                    TgPollUpdate(
                        updateId = updateId,
                        pollId = str(p, "id"),
                        question = str(p, "question"),
                        options = opts,
                        totalVoterCount = int(p, "total_voter_count"),
                        isClosed = bool(p, "is_closed"),
                    )
                )
                continue
            }
            obj(u["poll_answer"])?.let { pa ->
                val user = obj(pa["user"]) ?: obj(pa["voter_chat"])
                val optIds = arr(pa["option_ids"]).orEmpty().mapNotNull { (it as? Number)?.toInt() }
                pollAnswers.add(
                    TgPollAnswer(
                        updateId = updateId,
                        pollId = str(pa, "poll_id"),
                        userId = long(user, "id"),
                        firstName = str(user, "first_name").ifBlank { str(user, "title") },
                        username = str(user, "username"),
                        optionIds = optIds,
                    )
                )
            }
        }

        return TgUpdatesBatch(
            messages = messages,
            callbacks = callbacks,
            memberUpdates = memberUpdates,
            joinRequests = joinRequests,
            reactions = reactions,
            pollUpdates = pollUpdates,
            pollAnswers = pollAnswers,
            nextOffset = maxId + 1,
        )
    }

    private fun extractReactionEmojis(list: List<Any?>?): List<String> =
        list.orEmpty().mapNotNull { item ->
            val m = obj(item) ?: return@mapNotNull null
            when (str(m, "type")) {
                "emoji" -> str(m, "emoji").takeIf { it.isNotBlank() }
                "custom_emoji" -> str(m, "custom_emoji_id").takeIf { it.isNotBlank() }?.let { ":$it:" }
                "paid" -> "⭐"
                else -> null
            }
        }

    private fun parseParticipant(m: Map<String, Any?>?): TgChatParticipant? {
        if (m == null) return null
        return TgChatParticipant(
            userId = long(m, "id"),
            firstName = str(m, "first_name"),
            username = str(m, "username"),
            isBot = bool(m, "is_bot"),
        )
    }

    private fun parseChatMember(cm: Map<String, Any?>, updateId: Long, isMy: Boolean): TgChatMemberUpdate? {
        val chat = obj(cm["chat"]) ?: return null
        val from = obj(cm["from"])
        val oldM = obj(cm["old_chat_member"])
        val newM = obj(cm["new_chat_member"])
        val targetUser = obj(newM?.get("user")) ?: obj(oldM?.get("user"))
        return TgChatMemberUpdate(
            updateId = updateId,
            chatId = long(chat, "id"),
            chatTitle = str(chat, "title"),
            chatType = str(chat, "type"),
            isMyChatMember = isMy,
            actorUserId = long(from, "id"),
            targetUserId = long(targetUser, "id"),
            targetFirstName = str(targetUser, "first_name"),
            targetUsername = str(targetUser, "username"),
            targetIsBot = bool(targetUser, "is_bot"),
            oldStatus = str(oldM, "status"),
            newStatus = str(newM, "status"),
        )
    }

    private fun parseMessageField(
        u: Map<String, Any?>,
        updateId: Long,
        field: String,
        isEdited: Boolean,
        isChannel: Boolean,
    ): TgMessage? {
        val message = obj(u[field]) ?: return null
        val chat = obj(message["chat"]) ?: return null
        val kind = when {
            message["text"] is String -> ""
            message["photo"] != null -> "фото"
            message["sticker"] != null -> "стикер"
            message["voice"] != null -> "голосовое"
            message["video_note"] != null -> "видеосообщение"
            message["video"] != null -> "видео"
            message["animation"] != null -> "гифка"
            message["document"] != null -> "файл"
            message["audio"] != null -> "аудио"
            message["location"] != null -> "геолокация"
            message["contact"] != null -> "контакт"
            message["new_chat_members"] != null || message["left_chat_member"] != null -> ""
            else -> "вложение"
        }
        val photoId = if (kind == "фото") {
            obj(arr(message["photo"])?.lastOrNull())?.let { str(it, "file_id") } ?: ""
        } else ""
        val attachFileId = when (kind) {
            "фото" -> photoId
            "стикер" -> str(obj(message["sticker"]), "file_id")
            "гифка" -> str(obj(message["animation"]), "file_id")
            "файл" -> str(obj(message["document"]), "file_id")
            "видео" -> str(obj(message["video"]), "file_id")
            "голосовое" -> str(obj(message["voice"]), "file_id")
            "аудио" -> str(obj(message["audio"]), "file_id")
            "видеосообщение" -> str(obj(message["video_note"]), "file_id")
            else -> ""
        }
        val from = obj(message["from"])
        val replyMsg = obj(message["reply_to_message"])
        val replyText = str(replyMsg, "text").ifBlank { str(replyMsg, "caption") }
        val loc = obj(message["location"])
        val contact = obj(message["contact"])
        val newMembers = arr(message["new_chat_members"]).orEmpty().mapNotNull { parseParticipant(obj(it)) }
        val leftMember = parseParticipant(obj(message["left_chat_member"]))
        val chatTitle = str(chat, "title")
        val firstName = str(from, "first_name").ifBlank {
            str(message, "author_signature").ifBlank { chatTitle }
        }
        return TgMessage(
            updateId = updateId,
            chatId = long(chat, "id"),
            text = str(message, "text"),
            firstName = firstName,
            kind = kind,
            photoId = photoId,
            username = str(from, "username"),
            userId = long(from, "id"),
            chatType = str(chat, "type"),
            messageId = int(message, "message_id"),
            messageThreadId = optInt(message, "message_thread_id"),
            caption = str(message, "caption"),
            fileId = attachFileId,
            replyToText = replyText,
            locationLat = optDbl(loc, "latitude"),
            locationLon = optDbl(loc, "longitude"),
            contactPhone = str(contact, "phone_number"),
            isEdited = isEdited,
            isChannelPost = isChannel,
            chatTitle = chatTitle,
            senderIsBot = bool(from, "is_bot"),
            isForumTopic = bool(chat, "is_forum") || bool(message, "is_topic_message"),
            newMembers = newMembers,
            leftMember = leftMember,
        )
    }
}
