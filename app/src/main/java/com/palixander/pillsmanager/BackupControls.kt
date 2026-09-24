package com.palixander.pillsmanager

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun BackupControls(app: PillsApp, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<BackupData?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Не удалось выполнить операцию с файлом" }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run {
            withContext(NonCancellable + Dispatchers.IO) {
                val snapshot = app.operationLock.withLock { app.repository.exportBackup() }
                val bytes = BackupFormat.encode(snapshot).toByteArray(Charsets.UTF_8)
                require(bytes.size <= BackupFormat.MAX_BYTES) { "Слишком много данных для одного файла (максимум 32 МБ)" }
                val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось открыть файл для записи")
                output.use { it.write(bytes) }
            }
            message = "Экспорт завершён. Сохранены все профили, курсы, приёмы и история, включая архивные записи."
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            pending = withContext(Dispatchers.IO) {
                val input = app.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")
                input.use(BackupFormat::read)
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Перенос данных", style = MaterialTheme.typography.titleMedium)
            Text("Все профили, курсы, приёмы и история, включая архивные.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = enabled && !busy, onClick = {
                    export.launch("pillsmanager-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))}.json")
                }) { Text("Экспорт") }
                OutlinedButton(enabled = enabled && !busy, onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Импорт") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    pending?.let { backup ->
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text("Заменить данные?") },
            text = { Text("В файле: профилей — ${backup.profiles.size}, курсов — ${backup.prescriptions.size} (архивных — ${backup.prescriptions.count { it.archived }}), записей приёмов — ${backup.intakes.size}.\n\nВсе текущие профили, курсы и история будут заменены данными из файла. Перед заменой можно отменить импорт и сделать экспорт текущих данных.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                run {
                    app.restoreBackup(backup)
                    pending = null
                    message = "Импорт завершён. Данные восстановлены, напоминания обновлены."
                }
            }) { Text("Заменить и импортировать") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("Отмена") } }
        )
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text("Перенос данных") },
            text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("Понятно") } })
    }
}
