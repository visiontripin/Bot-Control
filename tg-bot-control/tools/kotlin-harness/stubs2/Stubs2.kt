package com.botcontrol.admin.data.local

data class BotRuleEntity(
    val id: Int = 0,
    val type: String = "",
    val pattern: String = "",
    val responseText: String = "",
    val enabled: Boolean = true,
    val actionType: String = "text",
    val script: String = "",
    val packId: String = "",
    val menu: String = "",
    val botId: Long = 1L,
    val options: String = "",
)

data class BotUserEntity(
    val botId: Long,
    val chatId: Long,
    val userId: Long = 0L,
    val username: String = "",
    val firstName: String = "",
    val chatType: String = "",
    val role: String = "user",
    val tags: String = "",
    val isBlocked: Boolean = false,
    val isBanned: Boolean = false,
    val varsJson: String = "{}",
    val firstSeenAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val msgCount: Int = 0,
)

data class BotKvEntity(
    val botId: Long,
    val scopeKey: String,
    val kvKey: String,
    val kvValue: String = "",
    val updatedAt: Long = 0L,
)
