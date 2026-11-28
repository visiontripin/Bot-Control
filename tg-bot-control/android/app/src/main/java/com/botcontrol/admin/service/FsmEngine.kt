package com.botcontrol.admin.service

import android.content.Context
import com.botcontrol.admin.BotControlApp
import com.botcontrol.admin.data.AccessPolicy
import com.botcontrol.admin.data.BotMatcher
import com.botcontrol.admin.data.FsmFlow
import com.botcontrol.admin.data.FsmStep
import com.botcontrol.admin.data.FsmValidator
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.MessageCtx
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.data.telegram.TgCallback
import com.botcontrol.admin.data.telegram.TgMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Пошаговые сценарии (FSM: анкеты, заявки, заказы, опросы).
 * Состояние диалога хранится по ключу "botId:chatId", собранные ответы
 * автоматически сохраняются в переменные пользователя (BotUserEntity.varsJson).
 */
object FsmEngine {

    private data class Session(
        val flowId: String,
        val stepIndex: Int,
        val collected: MutableMap<String, String> = linkedMapOf(),
        val history: MutableList<Int> = mutableListOf(),
        val updatedAtMs: Long = System.currentTimeMillis(),
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    private fun key(botId: Long, chatId: Long) = "$botId:$chatId"

    fun hasActive(botId: Long, chatId: Long): Boolean = sessions.containsKey(key(botId, chatId))

    fun clear(botId: Long, chatId: Long) {
        sessions.remove(key(botId, chatId))
    }

    private fun trimOldSessions() {
        if (sessions.size <= 400) return
        val cutoff = System.currentTimeMillis() - 2L * 3600L * 1000L
        sessions.entries.removeIf { it.value.updatedAtMs < cutoff }
    }

    private fun matchesFlowTrigger(flow: FsmFlow, text: String): Boolean {
        if (!flow.enabled || flow.steps.isEmpty()) return false
        val pat = flow.triggerPattern.trim()
        if (pat.isBlank()) return false
        return when (flow.triggerType.lowercase()) {
            "command" -> BotMatcher.matchesRule("command", pat, text)
            "contains" -> BotMatcher.matchesRule("contains", pat, text)
            else -> BotMatcher.matchesRule("exact", pat, text)
        }
    }

    /** Запуск сценария по имени/id (например, по нажатию inline-кнопки с action == "fsm"). */
    suspend fun startFlowById(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        flowIdOrName: String,
        msgCtx: MessageCtx,
    ): Boolean {
        val flows = store.fsmFlows(botId).filter { it.enabled && it.steps.isNotEmpty() }
        val target = flows.firstOrNull {
            it.id == flowIdOrName ||
                it.name.equals(flowIdOrName, ignoreCase = true) ||
                it.triggerPattern.equals(flowIdOrName, ignoreCase = true)
        } ?: return false
        beginSession(app, store, api, botId, target, msgCtx)
        return true
    }

    /** Проверяет активную FSM-сессию или запуск нового сценария по входящему сообщению. */
    suspend fun handleIncoming(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        msg: TgMessage,
        msgCtx: MessageCtx,
        keyboard: List<String>,
    ): Boolean {
        trimOldSessions()
        val k = key(botId, msg.chatId)
        val text = msg.text.trim()
        val existing = sessions[k]

        // 1) Запуск нового сценария по явному триггеру (даже если старый завис)
        val matchingNew = store.fsmFlows(botId).firstOrNull { matchesFlowTrigger(it, text) }
        if (existing == null) {
            if (matchingNew != null) {
                beginSession(app, store, api, botId, matchingNew, msgCtx)
                return true
            }
            return false
        }

        val flow = store.fsmFlows(botId).firstOrNull { it.id == existing.flowId && it.enabled }
        if (flow == null) {
            sessions.remove(k)
            return false
        }

        // 2) Отмена / назад
        val low = text.lowercase()
        if (low == "/cancel" || low == "отмена" || low == "❌ отмена") {
            sessions.remove(k)
            api.sendMessage(msg.chatId, "❌ Анкета «${flow.name}» отменена.", keyboard = keyboard)
            BotLog.log(botId, "🛑 FSM «${flow.name}»: отменено пользователем ${msg.chatId}")
            return true
        }
        if (low == "/back" || low == "назад" || low == "⬅️ назад") {
            val prevIdx = existing.history.removeLastOrNull()
            if (prevIdx == null) {
                api.sendMessage(msg.chatId, "Это первый шаг. Для отмены отправь /cancel.")
            } else {
                val prevStep = flow.steps.getOrNull(prevIdx)
                if (prevStep != null) existing.collected.remove(prevStep.varName)
                val updated = existing.copy(stepIndex = prevIdx, updatedAtMs = System.currentTimeMillis())
                sessions[k] = updated
                sendStepPrompt(store, api, botId, flow, prevIdx, msgCtx)
            }
            return true
        }

        // 3) Валидация ввода текущего шага
        val step = flow.steps.getOrNull(existing.stepIndex)
        if (step == null) {
            sessions.remove(k)
            return false
        }
        val check = FsmValidator.validate(
            step = step,
            text = text,
            kind = msg.kind,
            photoId = msg.photoId,
            fileId = msg.fileId,
            locationLat = msg.locationLat,
            locationLon = msg.locationLon,
            contactPhone = msg.contactPhone,
        )
        if (!check.ok) {
            api.sendMessage(msg.chatId, "⚠️ ${check.error}", inlineMenu = stepInlineButtons(step, existing.history.isNotEmpty()))
            return true
        }

        acceptStepValue(app, store, api, botId, flow, existing, step, check.value, msgCtx, keyboard)
        return true
    }

    /** Обработка inline-кнопок FSM (fsm_pick_<idx>, fsm_back, fsm_cancel). */
    suspend fun handleCallback(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        cb: TgCallback,
        keyboard: List<String>,
    ): Boolean {
        val data = cb.data
        if (!data.startsWith("fsm_")) return false
        val k = key(botId, cb.chatId)
        val session = sessions[k]
        if (session == null) {
            api.answerCallbackQuery(cb.callbackId, "Сессия анкеты завершена")
            return true
        }
        val flow = store.fsmFlows(botId).firstOrNull { it.id == session.flowId && it.enabled }
        if (flow == null) {
            sessions.remove(k)
            api.answerCallbackQuery(cb.callbackId, "Сценарий выключен")
            return true
        }
        val msgCtx = MessageCtx(
            chatId = cb.chatId,
            userId = if (cb.userId != 0L) cb.userId else cb.chatId,
            firstName = cb.firstName,
            username = cb.username,
            messageId = cb.messageId,
        )
        when {
            data == "fsm_cancel" -> {
                sessions.remove(k)
                api.answerCallbackQuery(cb.callbackId, "Отменено")
                api.editMessageText(cb.chatId, cb.messageId, "❌ Анкета «${flow.name}» отменена.")
                if (keyboard.isNotEmpty()) {
                    api.sendMessage(cb.chatId, "Выберите действие ниже 👇", keyboard = keyboard)
                }
            }
            data == "fsm_back" -> {
                val prevIdx = session.history.removeLastOrNull()
                if (prevIdx == null) {
                    api.answerCallbackQuery(cb.callbackId, "Это первый шаг")
                } else {
                    val prevStep = flow.steps.getOrNull(prevIdx)
                    if (prevStep != null) session.collected.remove(prevStep.varName)
                    sessions[k] = session.copy(stepIndex = prevIdx, updatedAtMs = System.currentTimeMillis())
                    api.answerCallbackQuery(cb.callbackId, "Шаг назад")
                    sendStepPrompt(store, api, botId, flow, prevIdx, msgCtx)
                }
            }
            data.startsWith("fsm_pick_") -> {
                val idx = data.removePrefix("fsm_pick_").toIntOrNull()
                val step = flow.steps.getOrNull(session.stepIndex)
                val choice = if (idx != null && step != null) step.choices.getOrNull(idx) else null
                if (step == null || choice == null) {
                    api.answerCallbackQuery(cb.callbackId, "Вариант устарел")
                } else {
                    api.answerCallbackQuery(cb.callbackId, choice)
                    acceptStepValue(app, store, api, botId, flow, session, step, choice, msgCtx, keyboard)
                }
            }
            else -> api.answerCallbackQuery(cb.callbackId, "")
        }
        return true
    }

    private suspend fun beginSession(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        flow: FsmFlow,
        msgCtx: MessageCtx,
    ) {
        val existingVars = app.repository.userVars(botId, msgCtx.chatId).toMutableMap()
        val s = Session(flowId = flow.id, stepIndex = 0, collected = existingVars)
        sessions[key(botId, msgCtx.chatId)] = s
        BotLog.log(botId, "📋 Старт FSM «${flow.name}» для chat=${msgCtx.chatId}")
        sendStepPrompt(store, api, botId, flow, 0, msgCtx)
    }

    private suspend fun acceptStepValue(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        flow: FsmFlow,
        session: Session,
        step: FsmStep,
        value: String,
        msgCtx: MessageCtx,
        keyboard: List<String>,
    ) {
        val k = key(botId, msgCtx.chatId)
        if (step.varName.isNotBlank()) {
            session.collected[step.varName] = value
            app.repository.mergeUserVars(botId, msgCtx.chatId, mapOf(step.varName to value))
        }
        val nextIdx = FsmValidator.nextStepIndex(flow, session.stepIndex, value)
        if (nextIdx != null) {
            val nextHistory = session.history.toMutableList().apply { add(session.stepIndex) }
            sessions[k] = session.copy(
                stepIndex = nextIdx,
                history = nextHistory,
                updatedAtMs = System.currentTimeMillis(),
            )
            sendStepPrompt(store, api, botId, flow, nextIdx, msgCtx)
            return
        }

        // Завершение диалога
        sessions.remove(k)
        val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()
        val finishMsg = BotMatcher.expandPlaceholders(
            flow.finishText.ifBlank { "✅ Готово! Спасибо, {user}." },
            msgCtx,
            botName,
            vars = session.collected,
        )
        api.sendMessage(msgCtx.chatId, finishMsg, keyboard = keyboard)
        BotLog.log(botId, "✅ FSM «${flow.name}» завершён (chat=${msgCtx.chatId})")

        dispatchSummary(app, store, api, botId, flow, msgCtx, session.collected, botName)
    }

    private suspend fun sendStepPrompt(
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        flow: FsmFlow,
        stepIndex: Int,
        msgCtx: MessageCtx,
    ) {
        val step = flow.steps.getOrNull(stepIndex) ?: return
        val session = sessions[key(botId, msgCtx.chatId)]
        val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()
        val header = "Шаг ${stepIndex + 1} из ${flow.steps.size}"
        val body = BotMatcher.expandPlaceholders(
            step.prompt,
            msgCtx,
            botName,
            vars = session?.collected.orEmpty(),
        )
        val text = "$header\n$body"
        val buttons = stepInlineButtons(step, hasBack = session?.history?.isNotEmpty() == true)
        api.sendMessage(msgCtx.chatId, text, inlineMenu = buttons)
    }

    private fun stepInlineButtons(step: FsmStep, hasBack: Boolean): List<InlineBtn> {
        val out = mutableListOf<InlineBtn>()
        if (step.inputType.equals("button", ignoreCase = true) && step.choices.isNotEmpty()) {
            step.choices.take(10).forEachIndexed { idx, label ->
                out.add(InlineBtn(id = "fsm_pick_$idx", label = label, action = "fsm_pick_$idx", row = (idx / 2) + 1))
            }
        }
        val navRow = (out.maxOfOrNull { it.row } ?: 0) + 1
        if (hasBack) {
            out.add(InlineBtn(id = "fsm_back", label = "⬅️ Назад", action = "fsm_back", row = navRow))
        }
        out.add(InlineBtn(id = "fsm_cancel", label = "❌ Отмена", action = "fsm_cancel", row = navRow))
        return out
    }

    private suspend fun dispatchSummary(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        flow: FsmFlow,
        msgCtx: MessageCtx,
        collected: Map<String, String>,
        botName: String,
    ) {
        val target = flow.notifyTarget.trim().lowercase()
        if (target.isBlank() || target == "none") return

        val summary = FsmValidator.buildSummary(flow, msgCtx, collected, botName)
        val photoIds = flow.steps.filter { it.inputType.equals("photo", ignoreCase = true) }
            .mapNotNull { collected[it.varName]?.takeIf(String::isNotBlank) }

        if (target == "admins" || target == "both") {
            val adminIds = store.adminIds(botId) +
                app.repository.botUsers(botId)
                    .filter { it.role.equals("admin", ignoreCase = true) }
                    .map { it.chatId }
            adminIds.distinct().forEach { adminChat ->
                api.sendMessage(adminChat, summary)
                photoIds.forEach { pid ->
                    api.sendMediaByType(adminChat, "photo", pid, caption = "📸 Вложение к «${flow.name}»")
                }
            }
        }
        if (target == "channel" || target == "both") {
            val ch = store.channelId(botId)
            if (ch.isNotBlank()) {
                api.sendTo(ch, summary)
            }
        }
    }
}
