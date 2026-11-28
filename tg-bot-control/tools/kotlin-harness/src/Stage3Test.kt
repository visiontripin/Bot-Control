import com.botcontrol.admin.data.*
import com.botcontrol.admin.data.telegram.*

private var s3failed = 0
private fun s3check(name: String, ok: Boolean) {
    println((if (ok) "ok   " else "FAIL ") + name)
    if (!ok) s3failed++
}

fun main() {
    // 1. DEFAULT_ALLOWED_UPDATES покрывает все 11 типов апдейтов Stage 3
    val expectedAllowed = listOf(
        "message", "edited_message", "channel_post", "edited_channel_post",
        "callback_query", "my_chat_member", "chat_member", "chat_join_request",
        "message_reaction", "poll", "poll_answer",
    )
    s3check("allowed_updates contains all 11 Stage 3 update types",
        expectedAllowed.all { it in TelegramUpdatesParser.DEFAULT_ALLOWED_UPDATES } &&
            TelegramUpdatesParser.DEFAULT_ALLOWED_UPDATES.size >= 11)

    // 2. Комплексный getUpdates JSON со всеми новыми полями и типами
    val updatesJson = """
    {
      "ok": true,
      "result": [
        {
          "update_id": 1001,
          "message": {
            "message_id": 55,
            "message_thread_id": 777,
            "from": {"id": 501, "first_name": "Алиса", "username": "alice", "is_bot": false},
            "chat": {"id": -100999888, "title": "Форум Клуб", "type": "supergroup"},
            "text": "Привет в теме!",
            "new_chat_members": [
              {"id": 602, "first_name": "Боб", "username": "bob_new", "is_bot": false}
            ]
          }
        },
        {
          "update_id": 1002,
          "edited_message": {
            "message_id": 56,
            "from": {"id": 501, "first_name": "Алиса"},
            "chat": {"id": -100999888, "title": "Форум Клуб", "type": "supergroup"},
            "text": "Отредактировано с https://evil.tld"
          }
        },
        {
          "update_id": 1003,
          "channel_post": {
            "message_id": 12,
            "chat": {"id": -100111222, "title": "Новости", "type": "channel"},
            "caption": "Свежий выпуск \"Сегодня\"",
            "photo": [
              {"file_id": "small_p"},
              {"file_id": "big_p"}
            ]
          }
        },
        {
          "update_id": 1004,
          "edited_channel_post": {
            "message_id": 13,
            "chat": {"id": -100111222, "title": "Новости", "type": "channel"},
            "text": "Обновленный пост"
          }
        },
        {
          "update_id": 1005,
          "callback_query": {
            "id": "cb_99",
            "from": {"id": 701, "first_name": "Карл", "username": "carl"},
            "message": {"message_id": 88, "chat": {"id": -100999888, "type": "supergroup"}},
            "data": "cap_-100999888_701"
          }
        },
        {
          "update_id": 1006,
          "my_chat_member": {
            "chat": {"id": -100999888, "title": "Форум Клуб", "type": "supergroup"},
            "from": {"id": 1, "first_name": "Owner"},
            "old_chat_member": {"status": "member", "user": {"id": 999, "first_name": "Bot", "is_bot": true}},
            "new_chat_member": {"status": "administrator", "user": {"id": 999, "first_name": "Bot", "is_bot": true}}
          }
        },
        {
          "update_id": 1007,
          "chat_member": {
            "chat": {"id": -100999888, "title": "Форум Клуб", "type": "supergroup"},
            "from": {"id": 1, "first_name": "Owner"},
            "old_chat_member": {"status": "left", "user": {"id": 808, "first_name": "Дина", "username": "dina"}},
            "new_chat_member": {"status": "member", "user": {"id": 808, "first_name": "Дина", "username": "dina"}}
          }
        },
        {
          "update_id": 1008,
          "chat_join_request": {
            "chat": {"id": -100333444, "title": "Закрытый чат", "type": "supergroup"},
            "from": {"id": 909, "first_name": "Ева", "username": "eva"},
            "invite_link": {"invite_link": "https://t.me/+secret", "name": "VIP"}
          }
        },
        {
          "update_id": 1009,
          "message_reaction": {
            "chat": {"id": -100999888, "type": "supergroup"},
            "message_id": 55,
            "user": {"id": 501, "first_name": "Алиса", "username": "alice"},
            "old_reaction": [{"type": "emoji", "emoji": "👍"}],
            "new_reaction": [{"type": "emoji", "emoji": "🔥"}, {"type": "custom_emoji", "custom_emoji_id": "123"}]
          }
        },
        {
          "update_id": 1010,
          "poll": {
            "id": "poll_77",
            "question": "Любимый язык?",
            "total_voter_count": 5,
            "is_closed": true,
            "options": [
              {"text": "Kotlin", "voter_count": 4},
              {"text": "Python", "voter_count": 1}
            ]
          }
        },
        {
          "update_id": 1011,
          "poll_answer": {
            "poll_id": "poll_77",
            "user": {"id": 501, "first_name": "Алиса", "username": "alice"},
            "option_ids": [0, 1]
          }
        }
      ]
    }
    """.trimIndent()

    val batch = TelegramUpdatesParser.parseGetUpdatesResponse(updatesJson, 1000L)
    s3check("nextOffset advances to max+1 (1012)", batch.nextOffset == 1012L)
    s3check("4 messages parsed (message + edited + channel_post + edited_channel_post)", batch.messages.size == 4)
    val m0 = batch.messages[0]
    s3check("message threadId, chatTitle, newMembers parsed",
        m0.messageThreadId == 777 && m0.chatTitle == "Форум Клуб" &&
            m0.newMembers.size == 1 && m0.newMembers[0].userId == 602L && m0.newMembers[0].firstName == "Боб")
    val m1 = batch.messages[1]
    s3check("edited_message flagged isEdited", m1.isEdited && !m1.isChannelPost && m1.messageId == 56)
    val m2 = batch.messages[2]
    s3check("channel_post photo & unescaped caption",
        m2.isChannelPost && !m2.isEdited && m2.photoId == "big_p" && m2.caption == "Свежий выпуск \"Сегодня\"")
    val m3 = batch.messages[3]
    s3check("edited_channel_post flagged both", m3.isChannelPost && m3.isEdited)
    s3check("callback_query parsed",
        batch.callbacks.size == 1 && batch.callbacks[0].userId == 701L && batch.callbacks[0].data == "cap_-100999888_701")
    s3check("my_chat_member & chat_member parsed",
        batch.memberUpdates.size == 2 &&
            batch.memberUpdates[0].isMyChatMember && batch.memberUpdates[0].newStatus == "administrator" &&
            !batch.memberUpdates[1].isMyChatMember && batch.memberUpdates[1].targetUserId == 808L)
    s3check("chat_join_request parsed",
        batch.joinRequests.size == 1 && batch.joinRequests[0].userId == 909L && batch.joinRequests[0].inviteLinkName == "VIP")
    s3check("message_reaction parsed emojis",
        batch.reactions.size == 1 && batch.reactions[0].oldEmojis == listOf("👍") &&
            batch.reactions[0].newEmojis == listOf("🔥", ":123:"))
    s3check("poll update parsed",
        batch.pollUpdates.size == 1 && batch.pollUpdates[0].totalVoterCount == 5 &&
            batch.pollUpdates[0].isClosed && batch.pollUpdates[0].options[0] == ("Kotlin" to 4))
    s3check("poll_answer parsed",
        batch.pollAnswers.size == 1 && batch.pollAnswers[0].pollId == "poll_77" &&
            batch.pollAnswers[0].optionIds == listOf(0, 1))

    // 3. Webhook single update parsing
    val webhookRaw = """{"update_id":2020,"chat_join_request":{"chat":{"id":-1007,"title":"W","type":"supergroup"},"from":{"id":33,"first_name":"Мария"}}}"""
    val whBatch = TelegramUpdatesParser.parseSingleUpdate(webhookRaw)
    s3check("parseSingleUpdate webhook works",
        whBatch.joinRequests.size == 1 && whBatch.joinRequests[0].userId == 33L && whBatch.nextOffset == 2021L)

    // 4. ModerationPolicy: stop-words, links, allowed domains, admin bypass, thread resolution
    val modCfg = GroupModerationConfig(
        antispamEnabled = true,
        blockLinks = true,
        allowedLinkDomains = listOf("example.com", "github.com"),
        stopWords = listOf("казино", "криптосигнал"),
        antispamAction = "mute",
        defaultThreadId = 900,
    )
    s3check("Moderation bypasses private chat",
        !ModerationPolicy.checkMessage(modCfg, "казино https://evil.com", isAdmin = false, chatType = "private").violation)
    s3check("Moderation bypasses group admin",
        !ModerationPolicy.checkMessage(modCfg, "казино https://evil.com", isAdmin = true, chatType = "supergroup").violation)
    s3check("Moderation catches stopword",
        ModerationPolicy.checkMessage(modCfg, "Залетай в КАЗИНО сейчас", isAdmin = false, chatType = "supergroup")
            .let { it.violation && it.reason == "stopword" && it.action == "mute" })
    s3check("Moderation catches disallowed link & t.me/@handle",
        ModerationPolicy.checkMessage(modCfg, "пиши @spam_channel", isAdmin = false, chatType = "group")
            .let { it.violation && it.reason == "link" } &&
            ModerationPolicy.checkMessage(modCfg, "смотри https://bad.site/promo", isAdmin = false, chatType = "supergroup").violation)
    s3check("Moderation allows whitelisted domain & subdomain",
        !ModerationPolicy.checkMessage(modCfg, "код тут https://docs.example.com/guide", isAdmin = false, chatType = "supergroup").violation)
    s3check("matchesChatScope filters private/group/channel",
        ModerationPolicy.matchesChatScope("", "private") &&
            ModerationPolicy.matchesChatScope("private", "") &&
            ModerationPolicy.matchesChatScope("group", "supergroup") &&
            !ModerationPolicy.matchesChatScope("group", "private") &&
            ModerationPolicy.matchesChatScope("channel", "channel") &&
            !ModerationPolicy.matchesChatScope("channel", "group"))
    s3check("resolveThreadId priority & private protection",
        ModerationPolicy.resolveThreadId(42, 10, 900, true) == 42 &&
            ModerationPolicy.resolveThreadId(null, 10, 900, true) == 10 &&
            ModerationPolicy.resolveThreadId(null, null, 900, true) == 900 &&
            ModerationPolicy.resolveThreadId(null, null, 900, false) == null)

    // 5. FloodTracker: per-botId isolation & burst detection
    FloodTracker.reset(1L)
    FloodTracker.reset(2L)
    val f1 = (1..3).map { FloodTracker.recordAndCheck(1L, -100L, 55L, limitCount = 2, windowSec = 10, nowMs = 1000L + it) }
    val bot2SameUser = FloodTracker.recordAndCheck(2L, -100L, 55L, limitCount = 2, windowSec = 10, nowMs = 1005L)
    s3check("FloodTracker triggers on N+1th message & isolates botId",
        f1 == listOf(false, false, true) && !bot2SameUser)

    // 6. CaptchaPolicy & RuleOptions Stage 3 defaults
    val cbId = CaptchaPolicy.callbackData(-100999888L, 602L)
    s3check("CaptchaPolicy roundtrip",
        CaptchaPolicy.parseCallback(cbId) == (-100999888L to 602L) &&
            CaptchaPolicy.parseCallback("other_btn") == null)
    s3check("RuleOptions.isDefault considers Stage 3 fields",
        RuleOptions().isDefault &&
            !RuleOptions(autoReactEmoji = "🔥").isDefault &&
            !RuleOptions(targetThreadId = 55).isDefault &&
            !RuleOptions(chatScope = "group").isDefault)

    if (s3failed > 0) {
        println("STAGE3 FAILED: $s3failed")
        kotlin.system.exitProcess(1)
    } else {
        println("STAGE3 ALL OK (20 checks)")
    }
}
