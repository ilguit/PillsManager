package com.palixander.pillsmanager

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun BackupControls(app: PillsApp, enabled: Boolean) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<BackupData?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f, label = "backup chevron")
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = resources.errorMessage(e, R.string.file_failed) }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run {
            withContext(NonCancellable + Dispatchers.IO) {
                val snapshot = app.operationLock.withLock { app.repository.exportBackup() }
                val bytes = BackupFormat.encode(snapshot).toByteArray(Charsets.UTF_8)
                if (bytes.size > BackupFormat.MAX_BYTES) throw LocalizedException(R.string.export_too_large)
                val output = app.contentResolver.openOutputStream(uri, "wt") ?: throw LocalizedException(R.string.file_write_failed)
                output.use { it.write(bytes) }
            }
            message = resources.getString(R.string.export_complete)
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            pending = withContext(Dispatchers.IO) {
                val input = app.contentResolver.openInputStream(uri) ?: throw LocalizedException(R.string.file_open_failed)
                input.use(BackupFormat::read)
            }
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    resources.getString(R.string.backup_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                ) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Icon(
                            ImageVector.vectorResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp).rotate(chevronRotation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (expanded) {
                Text(resources.getString(R.string.backup_description), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally)) {
                    OutlinedButton(enabled = enabled && !busy, onClick = {
                        export.launch("pillsmanager-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))}.json")
                    }) { Text(resources.getString(R.string.export)) }
                    OutlinedButton(enabled = enabled && !busy, onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(resources.getString(R.string.backup_import)) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
    pending?.let { backup ->
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text(resources.getString(R.string.import_title)) },
            text = { Text(resources.getString(R.string.import_body, backup.profiles.size, backup.prescriptions.size, backup.prescriptions.count { it.archived }, backup.intakes.size)) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                run {
                    app.restoreBackup(backup)
                    pending = null
                    message = resources.getString(R.string.import_complete)
                }
            }) { Text(resources.getString(R.string.import_confirm)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text(resources.getString(R.string.cancel)) } }
        )
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text(resources.getString(R.string.backup_title)) },
            text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text(resources.getString(R.string.understood)) } })
    }
}
