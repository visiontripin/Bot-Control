package com.botcontrol.admin.service

import com.botcontrol.admin.data.telegram.TelegramUpdatesParser
import com.botcontrol.admin.data.telegram.TgUpdatesBatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Очередь входящих обновлений от опционального локального Webhook-адаптера
 * (`POST /telegram-webhook/<botId>` в FileServer). Обрабатывается циклом
 * [LocalBotService] наравне с long polling `getUpdates`.
 */
object WebhookQueue {
    private val queues = ConcurrentHashMap<Long, ConcurrentLinkedQueue<String>>()

    fun enqueue(botId: Long, rawUpdateJson: String): Boolean {
        if (botId <= 0L || rawUpdateJson.isBlank()) return false
        val q = queues.getOrPut(botId) { ConcurrentLinkedQueue() }
        if (q.size >= 500) q.poll()
        q.add(rawUpdateJson)
        return true
    }

    fun drain(botId: Long): List<TgUpdatesBatch> {
        val q = queues[botId] ?: return emptyList()
        val out = ArrayList<TgUpdatesBatch>()
        while (true) {
            val raw = q.poll() ?: break
            val batch = TelegramUpdatesParser.parseSingleUpdate(raw)
            if (batch.totalCount > 0) out.add(batch)
        }
        return out
    }

    fun pendingCount(botId: Long): Int = queues[botId]?.size ?: 0

    fun clear(botId: Long) {
        queues.remove(botId)
    }
}
