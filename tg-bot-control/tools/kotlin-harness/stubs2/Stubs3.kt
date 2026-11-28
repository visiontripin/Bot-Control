package com.botcontrol.admin.data

import com.botcontrol.admin.data.local.BotRuleEntity
import com.botcontrol.admin.data.local.BotUserEntity

class BotRepository {
    var rules: List<BotRuleEntity> = emptyList()
    var rulesByBot: Map<Long, List<BotRuleEntity>> = emptyMap()
    var usersByBot: Map<Long, List<BotUserEntity>> = emptyMap()
    suspend fun botRules(botId: Long = -1L): List<BotRuleEntity> = rulesByBot[botId] ?: rules
    suspend fun botUsers(botId: Long): List<BotUserEntity> = usersByBot[botId].orEmpty()
}
