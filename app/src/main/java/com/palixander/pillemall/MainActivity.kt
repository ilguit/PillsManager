@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.palixander.pillemall

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.combine
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var link by mutableStateOf<Intent?>(null)
    private var resumed by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        updateAlarmWindow(intent)
        enableEdgeToEdge()
        link = intent
        setContent {
            PillTheme {
                PillsScreen(application as PillsApp, link, resumed, { link = null }, this)
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); updateAlarmWindow(intent); link = intent }
    override fun onResume() { super.onResume(); resumed++ }
    private fun updateAlarmWindow(intent: Intent) {
        val alarm = intent.getBooleanExtra("alarm", false)
        setShowWhenLocked(alarm)
        setTurnScreenOn(alarm)
    }
}
data class Snapshot(val profiles: List<Profile> = emptyList(), val prescriptions: List<Prescription> = emptyList(), val intakes: List<Intake> = emptyList(), val loaded: Boolean = false)
private data class NotificationTarget(val scheduled: Long?, val alarm: Boolean)
private fun timeLabel(i: Intake, resources: android.content.res.Resources): String {
    val instant = Instant.ofEpochMilli(i.scheduled)
    val local = instant.atZone(ZoneId.systemDefault())
    val original = instant.atZone(ZoneId.of(i.zone))
    val formatter = DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withLocale(resources.configuration.locales[0])
    return local.format(formatter) + if (local.offset != original.offset) " · ${original.format(formatter)} (${i.zone})" else ""
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PillsScreen(app: PillsApp, link: Intent?, resumed: Int, consumeLink: () -> Unit, activity: ComponentActivity) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val dao = app.repository.dao
    val flow = remember { combine(dao.profiles(), dao.prescriptions(), dao.intakes()) { p, r, i -> Snapshot(p, r, i, true) } }
    val data by flow.collectAsState(Snapshot())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tab by remember { mutableIntStateOf(0) }
    var editProfile by remember { mutableStateOf<Profile?>(null) }
    var editPrescription by remember { mutableStateOf<Prescription?>(null) }
    var group by remember { mutableStateOf<Set<String>?>(null) }
    var correcting by remember { mutableStateOf(false) }
    var backdating by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Profile?>(null) }
    var deleteIntake by remember { mutableStateOf<Intake?>(null) }
    var deleteCourse by remember { mutableStateOf<Prescription?>(null) }
    var historyMenu by remember { mutableStateOf<Intake?>(null) }
    var archive by remember { mutableStateOf<Prescription?>(null) }
    var archivedProfileIds by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<String?>(null) }
    var historyWeekStart by remember {
        mutableStateOf(LocalDate.now().let { it.minusDays((it.dayOfWeek.value - 1).toLong()) })
    }
    var permissions by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notificationTarget by remember { mutableStateOf<NotificationTarget?>(null) }
    var notificationActionPerformed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { app.update(action = action) }; now = System.currentTimeMillis() }
            catch (e: Exception) { error = resources.errorMessage(e, R.string.save_failed) }
            finally { busy = false }
        }
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissions = app.reminders.enabled() && app.reminders.exact()
        scope.launch {
            withContext(Dispatchers.IO) { app.update(deliver = true) }
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumed) {
        permissions = app.reminders.enabled() && app.reminders.exact()
        // Catch up reminders whose alarm was suppressed while notifications were disabled,
        // the app was force-stopped, or the device delayed background work.
        withContext(Dispatchers.IO) { app.update(deliver = true) }
        now = System.currentTimeMillis()
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); withContext(Dispatchers.IO) { app.update(deliver = true) }; now = System.currentTimeMillis() } }
    LaunchedEffect(link, data.intakes) {
        if (link?.data != null && data.loaded) {
            val scheduled = link.getLongExtra("scheduled", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }
            notificationTarget = NotificationTarget(scheduled, link.getBooleanExtra("alarm", false))
            notificationActionPerformed = false
            correcting = false; backdating = false; tab = 0; consumeLink()
        }
    }
    val notificationRows = notificationTarget?.let { target ->
        data.intakes.filter { (target.scheduled == null || it.scheduled == target.scheduled) && Schedule.status(it, now) == Status.WAITING }
    }.orEmpty()
    val needsFullScreenAccess = data.prescriptions.any { !it.archived && it.reminderLevel == Reminders.Level.ALARM.name } && !app.reminders.canUseFullScreen()
    LaunchedEffect(notificationTarget, notificationRows, notificationActionPerformed) {
        if (notificationTarget != null && notificationActionPerformed && notificationRows.isEmpty()) activity.finish()
    }
    if (notificationTarget != null) {
        NotificationIntakeScreen(
            rows = notificationRows,
            profiles = data.profiles,
            busy = busy,
            close = activity::finish,
            markTaken = { ids ->
                notificationActionPerformed = true
                app.reminders.silence(notificationTarget?.scheduled)
                act { app.repository.mark(ids, "TAKEN", System.currentTimeMillis()) }
            },
            snooze = if (notificationTarget?.scheduled != null) {{
                val scheduled = notificationTarget!!.scheduled!!
                notificationActionPerformed = true
                app.reminders.silence(scheduled)
                app.scope.launch { app.operationLock.withLock { app.reminders.snooze(scheduled) } }
                activity.finish()
            }} else null,
            moreActions = { ids ->
                group = ids
                correcting = false
                backdating = false
            }
        )
        group?.let { ids ->
            val rows = data.intakes.filter { it.id in ids }.sortedWith(compareBy<Intake> { it.scheduled }.thenBy { it.name.lowercase() })
            ConfirmDialog(rows, data.profiles, now, false, backdating, { group = null }) { selected, decision, actual ->
                notificationActionPerformed = true
                app.reminders.silence(notificationTarget?.scheduled)
                act { app.repository.mark(selected, decision, actual) }
                group = null
            }
        }
        error?.let { text -> AlertDialog(onDismissRequest = { error = null }, title = { Text(resources.getString(R.string.action_failed)) }, text = { Text(text) }, confirmButton = { TextButton(colors = PillActionColors.neutral(), onClick = { error = null }) { Text(resources.getString(R.string.understood)) } }) }
        return
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        PillHeader(
            title = listOf(resources.getString(R.string.today), resources.getString(R.string.history), resources.getString(R.string.profiles))[tab],
            subtitle = if (tab == 0) LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(resources.configuration.locales[0])) else null
        )
    }, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            val nav = listOf(Triple(resources.getString(R.string.upcoming), R.drawable.ic_today, resources.getString(R.string.upcoming_description)), Triple(resources.getString(R.string.history), R.drawable.ic_history, resources.getString(R.string.history_description)), Triple(resources.getString(R.string.profiles), R.drawable.ic_profiles, resources.getString(R.string.profiles_description)))
            nav.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = tab == index,
                    onClick = { tab = index },
                    icon = { Icon(ImageVector.vectorResource(item.second), contentDescription = item.third) },
                    label = { Text(item.first) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer)
                )
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (tab == 1) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column { Text(resources.getString(R.string.show_for), style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(resources.getString(R.string.all)) })
                                data.profiles.forEach { p -> FilterChip(selected = filter == p.id, onClick = { filter = p.id }, label = { Text(p.name) }) }
                            }
                    }
                    val currentWeekStart = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
                    val historyWeekEnd = historyWeekStart.plusDays(6)
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            FilledTonalIconButton(onClick = { historyWeekStart = historyWeekStart.minusWeeks(1) }) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_chevron_left), contentDescription = resources.getString(R.string.previous_week))
                            }
                            Text(
                                resources.getString(R.string.date_range, displayDate(historyWeekStart, resources), displayDate(historyWeekEnd, resources)),
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                            FilledTonalIconButton(
                                enabled = historyWeekStart.isBefore(currentWeekStart),
                                onClick = { historyWeekStart = historyWeekStart.plusWeeks(1) }
                            ) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_chevron_right), contentDescription = resources.getString(R.string.next_week))
                            }
                        } }
                }
            }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (tab == 0 && (!permissions || needsFullScreenAccess)) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = RoundedCornerShape(14.dp), color = PillColors.warning.copy(alpha = .12f)) { Icon(ImageVector.vectorResource(R.drawable.ic_bell), null, Modifier.padding(10.dp), tint = PillColors.warning) }
                            Column { Text(resources.getString(R.string.reminders_setup), style = MaterialTheme.typography.titleMedium); Text(resources.getString(R.string.reminders_subtitle), style = MaterialTheme.typography.bodyMedium) }
                        }
                        Text(resources.getString(R.string.reminders_explanation), style = MaterialTheme.typography.bodyMedium)
                        Column(horizontalAlignment = Alignment.Start) {
                        TextButton(colors = PillActionColors.neutral(), onClick = {
                            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                        }) { Text(resources.getString(R.string.notifications)) }
                        TextButton(colors = PillActionColors.neutral(), onClick = { activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}"))) }) { Text(resources.getString(R.string.exact_reminders)) }
                        if (needsFullScreenAccess && android.os.Build.VERSION.SDK_INT >= 34) TextButton(colors = PillActionColors.neutral(), onClick = {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${activity.packageName}")))
                        }) { Text(resources.getString(R.string.full_screen_alarms)) }
                        }
                    }
                }
            }
            if (data.profiles.isEmpty()) item {
                EmptyState(
                    title = resources.getString(R.string.profile_empty_title),
                    text = resources.getString(R.string.profile_empty_body),
                    action = resources.getString(R.string.add_profile),
                    onClick = { editProfile = Profile(name = "") }
                )
            }
            if (tab == 0) {
                val timeline = Presentation.timeline(data.intakes, data.profiles, now)
                val upcoming = timeline.filter { entry -> entry.rows.any { Schedule.status(it, now) in listOf(Status.WAITING, Status.PLANNED) } }
                if (upcoming.isEmpty() && data.profiles.isNotEmpty()) item { EmptyState(resources.getString(R.string.schedule_empty_title), resources.getString(R.string.schedule_empty_body), resources.getString(R.string.open_profiles)) { tab = 2 } }
                val sections = upcoming.mapIndexed { index, entry -> (if (index == 0) resources.getString(R.string.upcoming_section) else null) to entry }
                items(sections, key = { "${it.second.profileId}/${it.second.scheduled}" }) { (sectionTitle, entry) ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectionTitle?.let { Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
                        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        if (entry.rows.any { Schedule.status(it, now) == Status.WAITING }) HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
                        val actionable = entry.rows.filter { Schedule.status(it, now) in listOf(Status.WAITING, Status.PLANNED) }
                        val waiting = actionable.filter { Schedule.status(it, now) == Status.WAITING }
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(entry.scheduled)), style = PillTypography.time.copy(fontSize = 26.sp), color = MaterialTheme.colorScheme.onSurface)
                                    Text(displayDate(Instant.ofEpochMilli(entry.scheduled).atZone(ZoneId.systemDefault()).toLocalDate(), resources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    entry.rows.map { Schedule.status(it, now) }.distinct().forEach { StatusBadge(it) }
                                }
                                TimelineContents(entry.rows, data.profiles.find { it.id == entry.profileId }?.name ?: "")
                                if (waiting.isEmpty() && actionable.isNotEmpty()) FilledTonalButton(colors = PillActionColors.confirmTonal(), onClick = {
                                    group = actionable.map { it.id }.toSet(); correcting = false; backdating = false
                                }) { Text(resources.getString(R.string.taken_advance)) }
                            }
                            if (waiting.isNotEmpty()) {
                                Column(Modifier.width(112.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.3f)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    CompactIntakeAction(R.drawable.ic_check, resources.getString(R.string.taken), colors = ButtonDefaults.buttonColors(containerColor = PillColors.button, contentColor = PillColors.onButton)) {
                                        act { app.repository.mark(waiting.map { it.id }.toSet(), "TAKEN", now) }
                                    }
                                    CompactIntakeAction(R.drawable.ic_taken_earlier, resources.getString(R.string.taken_earlier_short), description = resources.getString(R.string.taken_earlier), colors = PillActionColors.confirmTonal()) {
                                        group = waiting.map { it.id }.toSet(); correcting = false; backdating = true
                                    }
                                    CompactIntakeAction(R.drawable.ic_skip, resources.getString(R.string.skip), colors = PillActionColors.neutral()) {
                                        act { app.repository.mark(waiting.map { it.id }.toSet(), "MISSED", now) }
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
            if (tab == 2) {
                item { BackupControls(app, enabled = !busy && data.loaded) }
                item {
                    Button(colors = ButtonDefaults.buttonColors(containerColor = PillColors.button, contentColor = PillColors.onButton), onClick = { editProfile = Profile(name = "") }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                        Text("＋", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.width(8.dp)); Text(resources.getString(R.string.add_profile))
                    }
                }
                items(data.profiles, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AdaptiveDetailsRow(details = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(p.name.trim().take(1).uppercase(), Modifier.padding(horizontal = 15.dp, vertical = 10.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.headlineSmall)
                                    val activeCount = data.prescriptions.count { it.profileId == p.id && !it.archived }
                                    Text(resources.getQuantityString(R.plurals.active_courses, activeCount, activeCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }, actions = {
                            IconButton(onClick = { editProfile = p }) {
                                Icon(painterResource(R.drawable.ic_edit), contentDescription = resources.getString(R.string.edit_profile, p.name))
                            }
                            IconButton(onClick = { delete = p }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = resources.getString(R.string.delete_profile, p.name), tint = MaterialTheme.colorScheme.error)
                            }
                        })
                        val showArchived = p.id in archivedProfileIds
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(colors = PillActionColors.confirmTonal(), onClick = {
                                archivedProfileIds = archivedProfileIds - p.id
                                editPrescription = Prescription(profileId = p.id, name = "", dose = "", times = "09:00", start = LocalDate.now().toString(), end = null, zone = ZoneId.systemDefault().id, generatedUntil = now)
                            }) {
                                Text(resources.getString(R.string.add_medicine))
                            }
                            FilterChip(selected = showArchived, onClick = {
                                archivedProfileIds = if (showArchived) archivedProfileIds - p.id else archivedProfileIds + p.id
                            }, label = { Text(resources.getString(R.string.medicine_archive)) }, leadingIcon = {
                                Icon(painterResource(R.drawable.ic_archive), contentDescription = null, modifier = Modifier.size(18.dp))
                            })
                        }
                        val visiblePrescriptions = data.prescriptions.filter { it.profileId == p.id && it.archived == showArchived }
                        if (showArchived && visiblePrescriptions.isEmpty()) {
                            Text(resources.getString(R.string.medicine_archive_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        visiblePrescriptions
                            .sortedWith(compareBy<Prescription> { it.name.lowercase() }.thenBy { it.id })
                            .forEach { r ->
                            val ended = r.end?.let { LocalDate.parse(it).isBefore(Instant.ofEpochMilli(now).atZone(ZoneId.of(r.zone)).toLocalDate()) } == true
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AdaptiveDetailsRow(details = {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.widthIn(min = 64.dp, max = 88.dp * LocalDensity.current.fontScale).padding(end = 8.dp)) {
                                        Text(
                                            r.times.split(",").map(LocalTime::parse).distinct().sorted().joinToString("\n"),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                                        Text(r.dose, style = MaterialTheme.typography.bodyLarge)
                                        Text(resources.getString(R.string.course_dates, displayDate(LocalDate.parse(r.start), resources), r.end?.let { displayDate(LocalDate.parse(it), resources) } ?: resources.getString(R.string.no_end)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }, actions = {
                                if (r.archived) {
                                    IconButton(onClick = { editPrescription = r.copy(id = java.util.UUID.randomUUID().toString(), start = LocalDate.now(ZoneId.of(r.zone)).toString(), end = null, archived = false, generatedUntil = now) }) {
                                        Icon(painterResource(R.drawable.ic_unarchive), contentDescription = resources.getString(R.string.repeat_course), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (!r.archived && !ended) Row {
                                    IconButton(onClick = { editPrescription = r }) {
                                        Icon(painterResource(R.drawable.ic_edit), contentDescription = resources.getString(R.string.edit_medicine, r.name))
                                    }
                                    IconButton(onClick = { archive = r }) {
                                        Icon(painterResource(R.drawable.ic_archive), contentDescription = resources.getString(R.string.archive_course, r.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            })
                            if (r.archived || ended) {
                                StatusPill(if (r.archived) resources.getString(R.string.archived) else resources.getString(R.string.course_finished))
                                if (!r.archived) {
                                    TextButton(colors = PillActionColors.neutral(), onClick = { editPrescription = r.copy(id = java.util.UUID.randomUUID().toString(), start = LocalDate.now(ZoneId.of(r.zone)).toString(), end = null, archived = false, generatedUntil = now) }) { Text(resources.getString(R.string.repeat_course)) }
                                }
                            }
                        }
                    } }
                }
            }
            if (tab == 1) {
                val historyWeekEnd = historyWeekStart.plusDays(6)
                val history = data.intakes.filter {
                    val day = Instant.ofEpochMilli(it.scheduled).atZone(ZoneId.systemDefault()).toLocalDate()
                    (filter == null || it.profileId == filter) &&
                        !day.isBefore(historyWeekStart) && !day.isAfter(historyWeekEnd) &&
                        Schedule.status(it, now) in listOf(Status.TAKEN, Status.MISSED, Status.CANCELLED)
                }.sortedByDescending { it.scheduled }
                if (history.isEmpty()) item { EmptyState(resources.getString(R.string.history_empty_title), resources.getString(R.string.history_empty_body)) }
                items(history, key = { it.id }) { i ->
                    Card(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { historyMenu = i }), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            ProfileMedicineLine(data.profiles.find { it.id == i.profileId }?.name ?: "", i.name, i.dose, Modifier.weight(1f).padding(end = 8.dp))
                            StatusBadge(Schedule.status(i, now))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(timeLabel(i, resources), style = MaterialTheme.typography.bodySmall)
                                i.takenAt?.let { Text(resources.getString(R.string.actual_time, displayDateTime(it, resources)), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    } }
                }
            }
        }
        }
    }
    editProfile?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        FormDialog(resources.getString(R.string.profile), { editProfile = null }, { if (name.isNotBlank()) { act { dao.saveProfile(p.copy(name = name.trim())) }; editProfile = null } }, name.isNotBlank()) {
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(resources.getString(R.string.name)) }, singleLine = true)
        }
    }
    editPrescription?.let { p -> PrescriptionDialog(p, { editPrescription = null }) { value -> act { app.repository.save(value) }; editPrescription = null } }
    delete?.let { p -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(resources.getString(R.string.delete_profile_title, p.name)) }, text = { Text(resources.getString(R.string.delete_profile_body)) }, confirmButton = { TextButton(colors = PillActionColors.destructive(), onClick = { act { dao.deleteProfile(p.id) }; delete = null }) { Text(resources.getString(R.string.delete)) } }, dismissButton = { TextButton(colors = PillActionColors.neutral(), onClick = { delete = null }) { Text(resources.getString(R.string.cancel)) } }) }
    deleteIntake?.let { intake ->
        AlertDialog(
            onDismissRequest = { deleteIntake = null },
            title = { Text(resources.getString(R.string.delete_intake_title)) },
            text = { Text(resources.getString(R.string.delete_intake_body, intake.name, displayDateTime(intake.scheduled, resources))) },
            confirmButton = { TextButton(colors = PillActionColors.destructive(), onClick = { act { app.repository.deleteHistoryIntake(intake.id) }; deleteIntake = null }) { Text(resources.getString(R.string.delete)) } },
            dismissButton = { TextButton(colors = PillActionColors.neutral(), onClick = { deleteIntake = null }) { Text(resources.getString(R.string.cancel)) } }
        )
    }
    historyMenu?.let { intake ->
        ModalBottomSheet(onDismissRequest = { historyMenu = null }) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                Text(intake.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                ListItem(
                    headlineContent = { Text(resources.getString(R.string.edit)) },
                    modifier = Modifier.clickable {
                        group = setOf(intake.id); correcting = true; backdating = true; historyMenu = null
                    }
                )
                ListItem(
                    headlineContent = { Text(resources.getString(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { historyMenu = null; deleteIntake = intake }
                )
            }
        }
    }
    archive?.let { p ->
        val hasReachedIntake = data.intakes.any { it.prescriptionId == p.id && (it.scheduled <= now || it.decision != null) }
        AlertDialog(
            onDismissRequest = { archive = null },
            title = { Text(resources.getString(if (hasReachedIntake) R.string.archive_course_title else R.string.delete_empty_course_title, p.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (hasReachedIntake) resources.getString(R.string.archive_course_body) else resources.getString(R.string.delete_empty_course_body, p.name))
                    if (hasReachedIntake) OutlinedButton(colors = PillActionColors.destructive(),
                        onClick = { archive = null; deleteCourse = p },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(resources.getString(R.string.delete_course), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(colors = if (hasReachedIntake) PillActionColors.confirm() else PillActionColors.destructive(), onClick = { act { app.repository.archive(p.id) }; archive = null }) {
                Text(resources.getString(if (hasReachedIntake) R.string.finish else R.string.delete))
            } },
            dismissButton = { TextButton(colors = PillActionColors.neutral(), onClick = { archive = null }) { Text(resources.getString(R.string.cancel)) } }
        )
    }
    deleteCourse?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteCourse = null },
            title = { Text(resources.getString(R.string.delete_course_title, p.name)) },
            text = { Text(resources.getString(R.string.delete_course_body)) },
            confirmButton = { TextButton(colors = PillActionColors.destructive(), onClick = { act { app.repository.deleteCourse(p.id) }; deleteCourse = null }) {
                Text(resources.getString(R.string.delete_course))
            } },
            dismissButton = { TextButton(colors = PillActionColors.neutral(), onClick = { deleteCourse = null }) { Text(resources.getString(R.string.cancel)) } }
        )
    }
    group?.let { ids ->
        val rows = data.intakes.filter { it.id in ids }.sortedWith(compareBy<Intake> { it.scheduled }.thenBy { it.name.lowercase() })
        ConfirmDialog(rows, data.profiles, now, correcting, backdating, { group = null }) { selected, decision, actual ->
            val correction = correcting
            act { app.repository.mark(selected, decision, actual, correction) }; group = null
        }
    }
    error?.let { text -> AlertDialog(onDismissRequest = { error = null }, title = { Text(resources.getString(R.string.action_failed)) }, text = { Text(text) }, confirmButton = { TextButton(colors = PillActionColors.neutral(), onClick = { error = null }) { Text(resources.getString(R.string.understood)) } }) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NotificationIntakeScreen(
    rows: List<Intake>,
    profiles: List<Profile>,
    busy: Boolean,
    close: () -> Unit,
    markTaken: (Set<String>) -> Unit,
    snooze: (() -> Unit)?,
    moreActions: (Set<String>) -> Unit
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    BackHandler(onBack = close)
    val profileNames = profiles.associate { it.id to it.name }
    val groups = rows.groupBy { it.profileId }.entries.sortedBy { profileNames[it.key]?.lowercase() ?: "" }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        PillHeader(title = resources.getString(R.string.reminder_title), close = close)
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            rows.map { it.scheduled }.distinct().singleOrNull()?.let { scheduled ->
                item { Text(android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(scheduled)), style = PillTypography.time) }
            }
            if (snooze != null && groups.isNotEmpty()) item {
                OutlinedButton(colors = PillActionColors.neutral(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy, onClick = snooze) {
                    Text(resources.getString(R.string.snooze_ten_minutes))
                }
            }
            if (groups.isEmpty()) item { EmptyState(resources.getString(R.string.intake_complete_title), resources.getString(R.string.intake_complete_body)) }
            items(groups, key = { it.key }) { (profileId, medicines) ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(profileNames[profileId].orEmpty().take(1).uppercase(), Modifier.padding(horizontal = 15.dp, vertical = 10.dp), style = MaterialTheme.typography.titleLarge)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(profileNames[profileId].orEmpty(), style = MaterialTheme.typography.headlineSmall)
                                Text(resources.getQuantityString(R.plurals.medicines_now, medicines.size, medicines.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        medicines.sortedBy { it.name.lowercase() }.forEach { medicine ->
                            MedicineDoseRow(medicine.name, medicine.dose)
                        }
                        val medicineIds = medicines.map { it.id }.toSet()
                        Button(colors = ButtonDefaults.buttonColors(containerColor = PillColors.button, contentColor = PillColors.onButton), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy, onClick = { markTaken(medicineIds) }) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_check), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(resources.getString(R.string.mark_taken))
                        }
                        OutlinedButton(colors = PillActionColors.neutral(), modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { moreActions(medicineIds) }) {
                            Text(resources.getString(R.string.other_time_or_skip))
                        }
                    }
                }
            }
            if (groups.size > 1) item {
                FilledTonalButton(colors = PillActionColors.confirmTonal(),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && rows.isNotEmpty(),
                    onClick = { markTaken(rows.map { it.id }.toSet()) }
                ) { Text(resources.getString(R.string.mark_all_profiles)) }
                TextButton(colors = PillActionColors.neutral(),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && rows.isNotEmpty(),
                    onClick = { moreActions(rows.map { it.id }.toSet()) }
                ) { Text(resources.getString(R.string.other_options_all)) }
            }
        }
    }
}
@Composable private fun EmptyState(title: String, text: String, action: String? = null, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .72f)) {
                Icon(ImageVector.vectorResource(R.drawable.ic_pill), null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.let { Button(colors = ButtonDefaults.buttonColors(containerColor = PillColors.button, contentColor = PillColors.onButton), onClick = onClick, modifier = Modifier.padding(top = 4.dp)) { Text(it) } }
        }
    }
}
@Composable private fun StatusPill(text: String) {
    Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun StatusBadge(status: Status) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val foreground = when (status) {
        Status.TAKEN -> PillColors.success
        Status.WAITING -> PillColors.warning
        Status.PLANNED, Status.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        Status.MISSED -> MaterialTheme.colorScheme.error
    }
    Surface(color = MaterialTheme.colorScheme.surface, contentColor = foreground, shape = RoundedCornerShape(5.dp), border = BorderStroke(1.dp, foreground.copy(alpha = .65f))) {
        Text(status.label(resources), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}
@Composable private fun CompactIntakeAction(icon: Int, label: String, description: String = label, colors: ButtonColors, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = description },
        colors = colors,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        }
    }
}
@Composable private fun TimelineContents(rows: List<Intake>, profileName: String) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(profileName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { i ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MedicineDoseRow(i.name, i.dose, notificationIcon = true)
                i.takenAt?.let { Text(resources.getString(R.string.taken_at, displayDateTime(it, resources)), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
@Composable private fun MedicineDoseRow(medicine: String, dose: String, modifier: Modifier = Modifier, notificationIcon: Boolean = false) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (notificationIcon) Icon(
            painterResource(R.drawable.ic_notification),
            contentDescription = null,
            modifier = Modifier.size(24.dp).align(Alignment.CenterVertically),
            tint = androidx.compose.ui.res.colorResource(R.color.notification_accent)
        ) else Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(ImageVector.vectorResource(R.drawable.ic_pill), null, Modifier.padding(8.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(medicine, style = MaterialTheme.typography.titleMedium)
            Text(resources.getString(R.string.dose, dose), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun ProfileMedicineLine(profileName: String, medicine: String, dose: String, modifier: Modifier = Modifier) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(profileName, style = MaterialTheme.typography.titleMedium)
        Text(medicine, style = MaterialTheme.typography.bodyLarge)
        Text(resources.getString(R.string.dose, dose), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun FormDialog(title: String, close: () -> Unit, save: () -> Unit, valid: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }, confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = PillColors.button, contentColor = PillColors.onButton), onClick = save, enabled = valid) { Text(resources.getString(R.string.save)) } }, dismissButton = { TextButton(colors = PillActionColors.neutral(), onClick = close) { Text(resources.getString(R.string.cancel)) } })
}
@Composable private fun PrescriptionDialog(p: Prescription, close: () -> Unit, save: (Prescription) -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    var name by remember { mutableStateOf(p.name) }; var dose by remember { mutableStateOf(p.dose) }
    var times by remember { mutableStateOf(p.times.split(",").filter { it.isNotBlank() }.map(LocalTime::parse).distinct().sorted()) }
    var start by remember { mutableStateOf(p.start) }; var end by remember { mutableStateOf(p.end ?: "") }
    var reminderLevel by remember { mutableStateOf(runCatching { Reminders.Level.valueOf(p.reminderLevel) }.getOrDefault(Reminders.Level.QUIET)) }
    var reminderSound by remember { mutableStateOf(p.reminderSound) }
    val context = LocalContext.current
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked = result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            if (picked != null) reminderSound = picked.toString()
        }
    }
    fun chooseRingtone() {
        val type = if (reminderLevel == Reminders.Level.ALARM) android.media.RingtoneManager.TYPE_ALARM else android.media.RingtoneManager.TYPE_NOTIFICATION
        val existing = reminderSound?.let(Uri::parse) ?: android.media.RingtoneManager.getDefaultUri(type)
        ringtonePicker.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, type)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, resources.getString(R.string.choose_reminder_sound))
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        })
    }
    val soundTitle = reminderSound?.let { value -> runCatching { android.media.RingtoneManager.getRingtone(context, Uri.parse(value))?.getTitle(context) }.getOrNull() }
    fun pickDate(value: String, set: (String) -> Unit) {
        val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now(ZoneId.of(p.zone)))
        DatePickerDialog(context, { _, y, m, d -> set(LocalDate.of(y, m + 1, d).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).showWithActionColors()
    }
    fun pickTime(current: LocalTime = LocalTime.of(9, 0), replace: LocalTime? = null) {
        TimePickerDialog(context, { _, h, m ->
            val selected = LocalTime.of(h, m)
            times = (times.filterNot { it == replace } + selected).distinct().sorted()
        }, current.hour, current.minute, android.text.format.DateFormat.is24HourFormat(context)).showWithActionColors()
    }
    val valid = runCatching { require(name.isNotBlank() && dose.isNotBlank() && times.isNotEmpty()); val first = LocalDate.parse(start); require(end.isBlank() || !LocalDate.parse(end).isBefore(first)) }.isSuccess
    FormDialog(resources.getString(R.string.prescription), close, { save(p.copy(name = name, dose = dose, times = times.joinToString(","), start = start, end = end.takeIf { it.isNotBlank() }, reminderLevel = reminderLevel.name, reminderSound = reminderSound)) }, valid) {
        OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(resources.getString(R.string.medicine_name)) })
        OutlinedTextField(dose, { dose = it }, modifier = Modifier.fillMaxWidth(), label = { Text(resources.getString(R.string.dose_hint)) })
        Text(resources.getString(R.string.intake_time), style = MaterialTheme.typography.titleSmall)
        times.forEach { time ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(colors = PillActionColors.neutral(), onClick = { pickTime(time, time) }, modifier = Modifier.weight(1f)) { Text(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time.atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))) }
                IconButton(onClick = { times = times - time }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = resources.getString(R.string.remove_time, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time.atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))))
                }
            }
        }
        OutlinedButton(colors = PillActionColors.neutral(), onClick = { pickTime() }) { Text(resources.getString(R.string.add_time)) }
        Text(resources.getString(R.string.reminder_level), style = MaterialTheme.typography.titleSmall)
        Reminders.Level.entries.forEach { level ->
            val title = when (level) {
                Reminders.Level.QUIET -> R.string.reminder_level_quiet
                Reminders.Level.NOTICEABLE -> R.string.reminder_level_noticeable
                Reminders.Level.ALARM -> R.string.reminder_level_alarm
            }
            val description = when (level) {
                Reminders.Level.QUIET -> R.string.reminder_level_quiet_description
                Reminders.Level.NOTICEABLE -> R.string.reminder_level_noticeable_description
                Reminders.Level.ALARM -> R.string.reminder_level_alarm_description
            }
            Row(Modifier.fillMaxWidth().clickable { reminderLevel = level }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = reminderLevel == level, onClick = { reminderLevel = level })
                Column { Text(resources.getString(title), style = MaterialTheme.typography.bodyLarge); Text(resources.getString(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (reminderLevel != Reminders.Level.QUIET) {
            OutlinedButton(colors = PillActionColors.neutral(), onClick = ::chooseRingtone, modifier = Modifier.fillMaxWidth()) {
                Text(resources.getString(R.string.reminder_sound, soundTitle ?: resources.getString(R.string.system_default)))
            }
            if (reminderSound != null) TextButton(colors = PillActionColors.neutral(), onClick = { reminderSound = null }) { Text(resources.getString(R.string.use_system_default)) }
        }
        Text(resources.getString(R.string.daily_zone, p.zone), style = MaterialTheme.typography.bodySmall)
        Text(resources.getString(R.string.course_start), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(colors = PillActionColors.neutral(), modifier = Modifier.fillMaxWidth(), onClick = { pickDate(start) { start = it } }) {
            Text(displayDate(LocalDate.parse(start), resources))
        }
        Text(resources.getString(R.string.course_end), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(colors = PillActionColors.neutral(), modifier = Modifier.fillMaxWidth(), onClick = { pickDate(end.ifBlank { start }) { end = it } }) {
            Text(end.takeIf { it.isNotBlank() }?.let { displayDate(LocalDate.parse(it), resources) } ?: resources.getString(R.string.no_end))
        }
        if (end.isNotBlank()) TextButton(colors = PillActionColors.neutral(), onClick = { end = "" }) { Text(resources.getString(R.string.no_end)) }
        if (!valid) Text(resources.getString(R.string.prescription_invalid), color = MaterialTheme.colorScheme.error)
    }
}
@Composable private fun ConfirmDialog(rows: List<Intake>, profiles: List<Profile>, now: Long, correction: Boolean, initiallyEditingTime: Boolean, close: () -> Unit, save: (Set<String>, String?, Long) -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    var selected by remember(rows.map { it.id }) { mutableStateOf(rows.map { it.id }.toSet()) }
    var actual by remember { mutableStateOf(Instant.ofEpochMilli(if (correction) rows.firstOrNull()?.takenAt ?: now else now).atZone(ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0)) }
    var editActual by remember(rows.map { it.id }, correction, initiallyEditingTime) { mutableStateOf(correction || initiallyEditingTime) }
    val actualMillis = actual.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val context = LocalContext.current
    fun changeActual(date: LocalDate? = null, time: LocalTime? = null) {
        actual = LocalDateTime.of(date ?: actual.toLocalDate(), time ?: actual.toLocalTime())
    }
    AlertDialog(onDismissRequest = close, shape = MaterialTheme.shapes.large, title = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (correction) resources.getString(R.string.correct_intake) else resources.getString(R.string.mark_intake))
            if (rows.isNotEmpty()) Text(
                if (rows.map { it.profileId }.distinct().size == 1) profiles.find { it.id == rows.first().profileId }?.name.orEmpty() else resources.getString(R.string.multiple_profiles),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (rows.isEmpty()) Text(resources.getString(R.string.intake_closed))
            rows.groupBy { it.profileId }.forEach { (profileId, list) ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(profiles.find { it.id == profileId }?.name ?: "", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            if (list.size > 1) TextButton(colors = PillActionColors.neutral(), onClick = { selected = selected + list.map { it.id } }) { Text(resources.getString(R.string.select_all)) }
                        }
                        list.forEach { i -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Checkbox(checked = i.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + i.id else selected - i.id })
                            Column(Modifier.weight(1f).padding(top = 7.dp)) {
                                Text(i.name, style = MaterialTheme.typography.titleMedium)
                                Text(resources.getString(R.string.dose, i.dose), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(timeLabel(i, resources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } }
                    }
                }
            }
            if (rows.isNotEmpty()) {
                val selectedRows = rows.filter { it.id in selected }
                val scheduled = selectedRows.map { it.scheduled }.distinct().singleOrNull()
                if (scheduled != null && scheduled <= now) Button(colors = ButtonDefaults.buttonColors(containerColor = PillColors.button, contentColor = PillColors.onButton),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedRows.isNotEmpty(),
                    onClick = { save(selected, "TAKEN", scheduled) }
                ) { Text(resources.getString(R.string.taken_on_time)) }
                if (!correction) FilledTonalButton(colors = PillActionColors.confirmTonal(), modifier = Modifier.fillMaxWidth(), enabled = selected.isNotEmpty(), onClick = { save(selected, "TAKEN", now) }) { Text(resources.getString(R.string.taken_now)) }
                if (!editActual) OutlinedButton(colors = PillActionColors.neutral(), modifier = Modifier.fillMaxWidth(), onClick = { editActual = true }) { Text(resources.getString(R.string.choose_other_time)) }
                if (editActual) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(resources.getString(R.string.actual_time_title), style = MaterialTheme.typography.titleMedium)
                    Text(resources.getString(R.string.date), style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(colors = PillActionColors.neutral(), modifier = Modifier.fillMaxWidth(), onClick = {
                        val value = actual.toLocalDate()
                        DatePickerDialog(context, { _, y, m, d -> changeActual(date = LocalDate.of(y, m + 1, d)) }, value.year, value.monthValue - 1, value.dayOfMonth).showWithActionColors()
                    }) { Text(displayDate(actual.toLocalDate(), resources)) }
                    Text(resources.getString(R.string.pick_time), style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(colors = PillActionColors.neutral(), modifier = Modifier.fillMaxWidth(), onClick = {
                        val value = actual.toLocalTime()
                        TimePickerDialog(context, { _, h, m -> changeActual(time = LocalTime.of(h, m)) }, value.hour, value.minute, android.text.format.DateFormat.is24HourFormat(context)).showWithActionColors()
                    }) { Text(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(actualMillis))) }
                    Text(resources.getString(R.string.phone_zone, ZoneId.systemDefault().id), style = MaterialTheme.typography.bodySmall)
                    if (actualMillis > now) Text(resources.getString(R.string.actual_time_invalid), color = MaterialTheme.colorScheme.error)
                    Button(colors = ButtonDefaults.buttonColors(containerColor = PillColors.button, contentColor = PillColors.onButton), modifier = Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && actualMillis <= now, onClick = { save(selected, "TAKEN", actualMillis) }) { Text(resources.getString(R.string.save_actual_time)) }
                }
                if (correction || rows.filter { it.id in selected }.all { Schedule.status(it, now) == Status.WAITING }) OutlinedButton(colors = PillActionColors.neutral(), modifier = Modifier.fillMaxWidth(), enabled = selected.isNotEmpty(), onClick = { save(selected, "MISSED", now) }) { Text(resources.getString(R.string.mark_missed)) }
                if (correction) TextButton(colors = PillActionColors.neutral(), onClick = { save(selected, null, now) }, enabled = selected.isNotEmpty()) { Text(resources.getString(R.string.undo_mark)) }
            }
        }
    }, confirmButton = { TextButton(colors = PillActionColors.neutral(), onClick = close) { Text(resources.getString(R.string.close)) } })
}

/** A content-sized header keeps translated titles and enlarged text visible. */
@Composable private fun PillHeader(title: String, subtitle: String? = null, close: (() -> Unit)? = null) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    var showCover by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (showCover) PillCoverDialog { showCover = false }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            if (close != null) IconButton(onClick = close, modifier = Modifier.align(Alignment.Start)) {
                Icon(ImageVector.vectorResource(R.drawable.ic_close), resources.getString(R.string.close))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.widthIn(max = 280.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.pill_wordmark),
                        contentDescription = resources.getString(R.string.app_name),
                        modifier = Modifier.fillMaxWidth(1f / 1.5f).heightIn(min = 48.dp).clickable { showCover = true }
                    )
                }
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Preserve inline actions where they fit; give names their full width on small/large-text layouts. */
@Composable private fun AdaptiveDetailsRow(details: @Composable () -> Unit, actions: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || fontScale > 1.3f) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                details()
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) { actions() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { details() }
                actions()
            }
        }
    }
}
