package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.FsmFlow
import com.botcontrol.admin.data.FsmStep
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.ui.components.ConfirmHost
import com.botcontrol.admin.ui.components.ConfirmRequest
import com.botcontrol.admin.ui.components.DropdownField
import com.botcontrol.admin.ui.components.SectionTitle
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Визуальный конструктор пошаговых диалогов (FSM: анкеты, заявки, заказы, квизы).
 */
@Composable
fun FsmScreen(localStore: LocalBotStore, onBack: () -> Unit) {
    val confirm = remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmHost(confirm)
    val scope = rememberCoroutineScope()
    var botId by remember { mutableStateOf(0L) }
    var flows by remember { mutableStateOf<List<FsmFlow>>(emptyList()) }
    var editing by remember { mutableStateOf<FsmFlow?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            val id = localStore.activeBotId()
            botId = id
            flows = localStore.fsmFlows(id)
        }
    }
    LaunchedEffect(Unit) { load() }

    suspend fun saveFlows(next: List<FsmFlow>) {
        localStore.setFsmFlows(next, botId)
        flows = next
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Пошаговые анкеты", style = MaterialTheme.typography.titleLarge)
        }
        Text("Сценарии «вопрос → проверка формата → следующий шаг» без кода. Работают команды /back (назад) и /cancel (отмена), ответы сохраняются в {переменные} пользователя.",
            style = MaterialTheme.typography.bodySmall)

        SectionTitle("Сценарии бота")
        if (flows.isEmpty()) {
            Text("Пока нет анкет. Нажми «+ Новая анкета» или создай шаблон заявки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        flows.forEach { flow ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable { editing = flow },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(flow.name.ifBlank { flow.triggerPattern }, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Триггер: ${flow.triggerPattern} (${flow.triggerType}) · шагов: ${flow.steps.size} · итог: ${flow.notifyTarget.ifBlank { "—" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(flow.enabled, onCheckedChange = { on ->
                        scope.launch {
                            saveFlows(flows.map { if (it.id == flow.id) it.copy(enabled = on) else it })
                        }
                    })
                    TextButton(onClick = {
                        confirm.value = ConfirmRequest("Удалить анкету?", flow.name.ifBlank { flow.triggerPattern }) {
                            scope.launch { saveFlows(flows.filterNot { it.id == flow.id }) }
                        }
                    }) { Text("✕") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Новая анкета")
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = {
            scope.launch {
                val sample = FsmFlow(
                    id = "fsm_" + UUID.randomUUID().toString().substring(0, 6),
                    name = "Заявка на звонок",
                    triggerType = "command",
                    triggerPattern = "/order",
                    enabled = true,
                    steps = listOf(
                        FsmStep(id = "s1", prompt = "Как вас зовут?", inputType = "text", varName = "client_name"),
                        FsmStep(id = "s2", prompt = "Укажите номер телефона (например +7 999 123-45-67):", inputType = "phone", varName = "client_phone"),
                        FsmStep(id = "s3", prompt = "Какая услуга интересует?", inputType = "button", varName = "service", choices = listOf("Консультация", "Заказ", "Поддержка")),
                    ),
                    finishText = "✅ Заявка принята, {client_name}! Мы свяжемся с вами по {client_phone}.",
                    notifyTarget = "admins",
                )
                saveFlows(flows + sample)
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("📋 Добавить пример «Заявка (3 шага)»")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAdd || editing != null) {
        FsmFlowDialog(
            initial = editing,
            onDismiss = { showAdd = false; editing = null },
            onSave = { saved ->
                scope.launch {
                    val next = if (editing == null) {
                        flows + saved.copy(id = "fsm_" + UUID.randomUUID().toString().substring(0, 8))
                    } else {
                        flows.map { if (it.id == editing!!.id) saved.copy(id = it.id) else it }
                    }
                    saveFlows(next)
                    showAdd = false
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun FsmFlowDialog(
    initial: FsmFlow?,
    onDismiss: () -> Unit,
    onSave: (FsmFlow) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "Анкета") }
    var triggerType by remember { mutableStateOf(initial?.triggerType ?: "command") }
    var triggerPattern by remember { mutableStateOf(initial?.triggerPattern ?: "/form") }
    var finishText by remember { mutableStateOf(initial?.finishText ?: "✅ Анкета отправлена! Спасибо, {user}.") }
    var notifyTarget by remember { mutableStateOf(initial?.notifyTarget ?: "admins") }
    var summaryTemplate by remember { mutableStateOf(initial?.summaryTemplate ?: "") }
    var steps by remember {
        mutableStateOf(
            initial?.steps ?: listOf(
                FsmStep(id = "step1", prompt = "Напишите ваше имя:", inputType = "text", varName = "name")
            )
        )
    }
    var editingStepIdx by remember { mutableStateOf<Int?>(null) }
    var addingStep by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новый сценарий FSM" else "Редактировать анкету") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("Название анкеты") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("Запуск по сообщению:", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("command" to "Команда /…", "exact" to "Кнопка/точно", "contains" to "Содержит").forEach { (t, lbl) ->
                        FilterChip(selected = triggerType == t, onClick = { triggerType = t }, label = { Text(lbl) })
                    }
                }
                OutlinedTextField(
                    triggerPattern, { triggerPattern = it },
                    label = { Text("Триггер (напр. /form или «Оставить заявку»)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Куда отправить итог анкеты:", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "admins" to "Админам",
                        "channel" to "В канал",
                        "both" to "И туда и туда",
                        "none" to "Только юзеру",
                    ).forEach { (tgt, lbl) ->
                        FilterChip(selected = notifyTarget == tgt, onClick = { notifyTarget = tgt }, label = { Text(lbl) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Шаги (${steps.size}):", style = MaterialTheme.typography.titleSmall)
                steps.forEachIndexed { idx, st ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { editingStepIdx = idx }) {
                                Text("${idx + 1}. {${st.varName}} [${st.inputType}]",
                                    style = MaterialTheme.typography.labelMedium)
                                Text(st.prompt.take(60), style = MaterialTheme.typography.bodySmall)
                            }
                            if (idx > 0) {
                                TextButton(onClick = {
                                    val m = steps.toMutableList()
                                    val tmp = m[idx - 1]; m[idx - 1] = m[idx]; m[idx] = tmp
                                    steps = m
                                }) { Text("↑") }
                            }
                            TextButton(onClick = { editingStepIdx = idx }) { Text("✎") }
                            if (steps.size > 1) {
                                TextButton(onClick = { steps = steps.filterIndexed { i, _ -> i != idx } }) { Text("✕") }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { addingStep = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Добавить шаг")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    finishText, { finishText = it },
                    label = { Text("Сообщение пользователю в конце") },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    summaryTemplate, { summaryTemplate = it },
                    label = { Text("Шаблон сводки для админа (пусто = автосписок {переменных})") },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        FsmFlow(
                            id = initial?.id.orEmpty(),
                            name = name.trim(),
                            triggerType = triggerType,
                            triggerPattern = triggerPattern.trim(),
                            enabled = initial?.enabled ?: true,
                            steps = steps,
                            finishText = finishText.trim(),
                            notifyTarget = notifyTarget,
                            summaryTemplate = summaryTemplate.trim(),
                        )
                    )
                },
                enabled = triggerPattern.isNotBlank() && steps.isNotEmpty(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (addingStep || editingStepIdx != null) {
        val idx = editingStepIdx
        val initStep = idx?.let { steps.getOrNull(it) } ?: FsmStep(
            id = "step${steps.size + 1}",
            varName = "field${steps.size + 1}",
        )
        FsmStepDialog(
            initial = initStep,
            otherStepIds = steps.map { it.id } + "DONE",
            onDismiss = { addingStep = false; editingStepIdx = null },
            onSave = { saved ->
                steps = if (idx == null) steps + saved else steps.mapIndexed { i, s -> if (i == idx) saved else s }
                addingStep = false
                editingStepIdx = null
            },
        )
    }
}

@Composable
private fun FsmStepDialog(
    initial: FsmStep,
    otherStepIds: List<String>,
    onDismiss: () -> Unit,
    onSave: (FsmStep) -> Unit,
) {
    var stepId by remember { mutableStateOf(initial.id.ifBlank { "step1" }) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var inputType by remember { mutableStateOf(initial.inputType) }
    var varName by remember { mutableStateOf(initial.varName.ifBlank { "answer" }) }
    var choicesText by remember { mutableStateOf(initial.choices.joinToString("\n")) }
    var errorText by remember { mutableStateOf(initial.errorText) }
    var nextStepId by remember { mutableStateOf(initial.nextStepId) }
    var branchText by remember {
        mutableStateOf(initial.branchMap.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Шаг анкеты") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    prompt, { prompt = it },
                    label = { Text("Вопрос пользователю") },
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        varName, { v -> varName = v.replace(Regex("[^a-zA-Z0-9_а-яА-ЯёЁ]"), "").take(32) },
                        label = { Text("Переменная {var}") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        stepId, { v -> stepId = v.replace(Regex("\\s+"), "").take(24) },
                        label = { Text("ID шага") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                DropdownField(
                    value = inputType,
                    options = listOf("text", "number", "phone", "photo", "location", "button", "any"),
                    label = "Ожидаемый тип ответа",
                    display = {
                        when (it) {
                            "number" -> "Число"
                            "phone" -> "Телефон"
                            "photo" -> "Фото"
                            "location" -> "Геопозиция"
                            "button" -> "Кнопки выбора"
                            "any" -> "Любое (текст/файл)"
                            else -> "Текст"
                        }
                    },
                    onSelect = { inputType = it },
                )
                if (inputType == "button") {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        choicesText, { choicesText = it },
                        label = { Text("Варианты кнопок (по одному на строке)") },
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    errorText, { errorText = it },
                    label = { Text("Текст при неверном формате (необязательно)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    branchText, { branchText = it },
                    label = { Text("Ветвление: Ответ=ID_шага (напр. Нет=DONE)") },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
                Text("Доступные ID: ${otherStepIds.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            val branchMap = branchText.lines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null
                else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }.filter { it.first.isNotBlank() && it.second.isNotBlank() }.toMap()
            TextButton(
                onClick = {
                    onSave(
                        FsmStep(
                            id = stepId.ifBlank { "step1" },
                            prompt = prompt.trim(),
                            inputType = inputType,
                            varName = varName.ifBlank { "answer" },
                            choices = choicesText.lines().map { it.trim() }.filter { it.isNotBlank() },
                            errorText = errorText.trim(),
                            nextStepId = nextStepId.trim(),
                            branchMap = branchMap,
                        )
                    )
                },
                enabled = prompt.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
