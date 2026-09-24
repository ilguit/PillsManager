package com.palixander.pillsmanager

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var link by mutableStateOf<Intent?>(null)
    private var resumed by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        link = intent
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme(
                primary = Color(0xFF87D8C8), onPrimary = Color(0xFF00382F),
                primaryContainer = Color(0xFF005046), onPrimaryContainer = Color(0xFFA4F2E1),
                secondary = Color(0xFFC6C9FF), secondaryContainer = Color(0xFF3D4277),
                background = Color(0xFF101413), surface = Color(0xFF171C1B),
                surfaceVariant = Color(0xFF252B29), outlineVariant = Color(0xFF3D4542)
            ) else lightColorScheme(
                primary = Color(0xFF006B5C), onPrimary = Color.White,
                primaryContainer = Color(0xFFA7F2DF), onPrimaryContainer = Color(0xFF00201A),
                secondary = Color(0xFF50558A), secondaryContainer = Color(0xFFE1E2FF),
                background = Color(0xFFF7F9F6), surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFEDF1EE), outlineVariant = Color(0xFFD9E1DD),
                error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6)
            )
            val typography = Typography(
                headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
                headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            )
            MaterialTheme(colorScheme = colors, typography = typography, shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp)
            )) {
                PillsScreen(application as PillsApp, link, resumed, { link = null }, this)
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); link = intent }
    override fun onResume() { super.onResume(); resumed++ }
}
data class Snapshot(val profiles: List<Profile> = emptyList(), val prescriptions: List<Prescription> = emptyList(), val intakes: List<Intake> = emptyList(), val loaded: Boolean = false)
private data class NotificationTarget(val scheduled: Long?)
private val stamp = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
private fun timeLabel(i: Intake, resources: android.content.res.Resources): String {
    val instant = Instant.ofEpochMilli(i.scheduled)
    val local = instant.atZone(ZoneId.systemDefault())
    val original = instant.atZone(ZoneId.of(i.zone))
    val formatter = DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withLocale(resources.configuration.locales[0])
    return local.format(formatter) + if (local.offset != original.offset) " · ${original.format(formatter)} (${i.zone})" else ""
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var archive by remember { mutableStateOf<Prescription?>(null) }
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
            notificationTarget = NotificationTarget(scheduled)
            notificationActionPerformed = false
            correcting = false; backdating = false; tab = 0; consumeLink()
        }
    }
    val notificationRows = notificationTarget?.let { target ->
        data.intakes.filter { (target.scheduled == null || it.scheduled == target.scheduled) && Schedule.status(it, now) == Status.WAITING }
    }.orEmpty()
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
                act { app.repository.mark(ids, "TAKEN", System.currentTimeMillis()) }
            },
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
                act { app.repository.mark(selected, decision, actual) }
                group = null
            }
        }
        error?.let { text -> AlertDialog(onDismissRequest = { error = null }, title = { Text(resources.getString(R.string.action_failed)) }, text = { Text(text) }, confirmButton = { TextButton(onClick = { error = null }) { Text(resources.getString(R.string.understood)) } }) }
        return
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        CenterAlignedTopAppBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(listOf(resources.getString(R.string.today), resources.getString(R.string.profiles), resources.getString(R.string.history))[tab], style = MaterialTheme.typography.titleLarge)
                    if (tab == 0) Text(LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(resources.configuration.locales[0])), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
    }, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            val nav = listOf(Triple(resources.getString(R.string.upcoming), R.drawable.ic_today, resources.getString(R.string.upcoming_description)), Triple(resources.getString(R.string.profiles), R.drawable.ic_profiles, resources.getString(R.string.profiles_description)), Triple(resources.getString(R.string.history), R.drawable.ic_history, resources.getString(R.string.history_description)))
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
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (tab == 0 && !permissions) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.error.copy(alpha = .12f)) { Icon(ImageVector.vectorResource(R.drawable.ic_bell), null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.error) }
                            Column { Text(resources.getString(R.string.reminders_setup), style = MaterialTheme.typography.titleMedium); Text(resources.getString(R.string.reminders_subtitle), style = MaterialTheme.typography.bodyMedium) }
                        }
                        Text(resources.getString(R.string.reminders_explanation), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                        }) { Text(resources.getString(R.string.notifications)) }
                        TextButton(onClick = { activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}"))) }) { Text(resources.getString(R.string.exact_reminders)) }
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
                if (timeline.isEmpty() && data.profiles.isNotEmpty()) item { EmptyState(resources.getString(R.string.schedule_empty_title), resources.getString(R.string.schedule_empty_body), resources.getString(R.string.open_profiles)) { tab = 1 } }
                val upcoming = timeline.filter { entry -> entry.rows.any { Schedule.status(it, now) in listOf(Status.WAITING, Status.PLANNED) } }
                val recentlyTaken = timeline.filterNot { it in upcoming }
                val sections = upcoming.mapIndexed { index, entry -> (if (index == 0) resources.getString(R.string.upcoming_section) else null) to entry } +
                    recentlyTaken.mapIndexed { index, entry -> (if (index == 0) resources.getString(R.string.recently_taken) else null) to entry }
                items(sections, key = { "${it.second.profileId}/${it.second.scheduled}" }) { (sectionTitle, entry) ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectionTitle?.let { Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
                        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(entry.scheduled)), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                    Text(displayDate(Instant.ofEpochMilli(entry.scheduled).atZone(ZoneId.systemDefault()).toLocalDate(), resources), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    entry.rows.map { Schedule.status(it, now) }.distinct().forEach { StatusBadge(it) }
                                }
                            }
                            TimelineContents(entry.rows, data.profiles.find { it.id == entry.profileId }?.name ?: "")
                            val actionable = entry.rows.filter { Schedule.status(it, now) in listOf(Status.WAITING, Status.PLANNED) }
                            val waiting = actionable.filter { Schedule.status(it, now) == Status.WAITING }
                            if (waiting.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = { act { app.repository.mark(waiting.map { it.id }.toSet(), "TAKEN", now) } }, shape = MaterialTheme.shapes.small) { Icon(ImageVector.vectorResource(R.drawable.ic_check), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(resources.getString(R.string.taken)) }
                                    TextButton(onClick = {
                                        group = waiting.map { it.id }.toSet(); correcting = false; backdating = true
                                    }) { Text(resources.getString(R.string.taken_earlier)) }
                                    TextButton(onClick = { act { app.repository.mark(waiting.map { it.id }.toSet(), "MISSED", now) } }) { Text(resources.getString(R.string.skip)) }
                                }
                            } else if (actionable.isNotEmpty()) FilledTonalButton(onClick = {
                                group = actionable.map { it.id }.toSet(); correcting = false; backdating = false
                            }) { Text(resources.getString(R.string.taken_advance)) }
                        }
                    }
                    }
                }
            }
            if (tab == 1) {
                item { BackupControls(app, enabled = !busy && data.loaded) }
                item {
                    Button(onClick = { editProfile = Profile(name = "") }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                        Text("＋", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.width(8.dp)); Text(resources.getString(R.string.add_profile))
                    }
                }
                items(data.profiles, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(p.name.trim().take(1).uppercase(), Modifier.padding(horizontal = 15.dp, vertical = 10.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.headlineSmall)
                                val activeCount = data.prescriptions.count { it.profileId == p.id && !it.archived }
                                Text(resources.getQuantityString(R.plurals.active_courses, activeCount, activeCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editProfile = p }) {
                                Icon(painterResource(R.drawable.ic_edit), contentDescription = resources.getString(R.string.edit_profile, p.name))
                            }
                            IconButton(onClick = { delete = p }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = resources.getString(R.string.delete_profile, p.name), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { editPrescription = Prescription(profileId = p.id, name = "", dose = "", times = "09:00", start = LocalDate.now().toString(), end = null, zone = ZoneId.systemDefault().id, generatedUntil = now) }) {
                            Text(resources.getString(R.string.add_medicine))
                        }
                        data.prescriptions.filter { it.profileId == p.id && !it.archived }
                            .sortedWith(compareBy<Prescription> { it.name.lowercase() }.thenBy { it.id })
                            .forEach { r ->
                            val ended = r.end?.let { LocalDate.parse(it).isBefore(Instant.ofEpochMilli(now).atZone(ZoneId.of(r.zone)).toLocalDate()) } == true
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.width(64.dp)) {
                                    Text(
                                        r.times.split(",").map(LocalTime::parse).distinct().sorted().joinToString(" · "),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                                    Text(r.dose, style = MaterialTheme.typography.bodyLarge)
                                    Text(resources.getString(R.string.course_dates, displayDate(LocalDate.parse(r.start), resources), r.end?.let { displayDate(LocalDate.parse(it), resources) } ?: resources.getString(R.string.no_end)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!r.archived && !ended) Row {
                                    IconButton(onClick = { editPrescription = r }) {
                                        Icon(painterResource(R.drawable.ic_edit), contentDescription = resources.getString(R.string.edit_medicine, r.name))
                                    }
                                    IconButton(onClick = { archive = r }) {
                                        Icon(painterResource(R.drawable.ic_archive), contentDescription = resources.getString(R.string.archive_course, r.name), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            if (r.archived || ended) {
                                StatusPill(if (r.archived) resources.getString(R.string.archived) else resources.getString(R.string.course_finished))
                                TextButton(onClick = { editPrescription = r.copy(id = java.util.UUID.randomUUID().toString(), start = LocalDate.now(ZoneId.of(r.zone)).toString(), end = null, archived = false, generatedUntil = now) }) { Text(resources.getString(R.string.repeat_course)) }
                            }
                        }
                    } }
                }
            }
            if (tab == 2) {
                item {
                    Column { Text(resources.getString(R.string.show_for), style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(resources.getString(R.string.all)) })
                            data.profiles.forEach { p -> FilterChip(selected = filter == p.id, onClick = { filter = p.id }, label = { Text(p.name) }) }
                        }
                    }
                }
                val currentWeekStart = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
                val historyWeekEnd = historyWeekStart.plusDays(6)
                item {
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
                val history = data.intakes.filter {
                    val day = Instant.ofEpochMilli(it.scheduled).atZone(ZoneId.systemDefault()).toLocalDate()
                    (filter == null || it.profileId == filter) &&
                        !day.isBefore(historyWeekStart) && !day.isAfter(historyWeekEnd) &&
                        Schedule.status(it, now) in listOf(Status.TAKEN, Status.MISSED, Status.CANCELLED)
                }.sortedByDescending { it.scheduled }
                if (history.isEmpty()) item { EmptyState(resources.getString(R.string.history_empty_title), resources.getString(R.string.history_empty_body)) }
                items(history, key = { it.id }) { i ->
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            ProfileMedicineLine(data.profiles.find { it.id == i.profileId }?.name ?: "", i.name, i.dose, Modifier.weight(1f).padding(end = 8.dp))
                            StatusBadge(Schedule.status(i, now))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(timeLabel(i, resources), style = MaterialTheme.typography.bodySmall)
                                i.takenAt?.let { Text(resources.getString(R.string.actual_time, displayDateTime(it, resources)), style = MaterialTheme.typography.bodySmall) }
                            }
                            TextButton(onClick = { group = setOf(i.id); correcting = true; backdating = true }) { Text(resources.getString(R.string.edit)) }
                        }
                    } }
                }
            }
        }
    }
    editProfile?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        FormDialog(resources.getString(R.string.profile), { editProfile = null }, { if (name.isNotBlank()) { act { dao.saveProfile(p.copy(name = name.trim())) }; editProfile = null } }, name.isNotBlank()) {
            OutlinedTextField(name, { name = it }, label = { Text(resources.getString(R.string.name)) }, singleLine = true)
        }
    }
    editPrescription?.let { p -> PrescriptionDialog(p, { editPrescription = null }) { value -> act { app.repository.save(value) }; editPrescription = null } }
    delete?.let { p -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(resources.getString(R.string.delete_profile_title, p.name)) }, text = { Text(resources.getString(R.string.delete_profile_body)) }, confirmButton = { TextButton(onClick = { act { dao.deleteProfile(p.id) }; delete = null }) { Text(resources.getString(R.string.delete)) } }, dismissButton = { TextButton(onClick = { delete = null }) { Text(resources.getString(R.string.cancel)) } }) }
    archive?.let { p -> AlertDialog(onDismissRequest = { archive = null }, title = { Text(resources.getString(R.string.archive_course_title, p.name)) }, text = { Text(resources.getString(R.string.archive_course_body)) }, confirmButton = { TextButton(onClick = { act { app.repository.archive(p.id) }; archive = null }) { Text(resources.getString(R.string.finish)) } }, dismissButton = { TextButton(onClick = { archive = null }) { Text(resources.getString(R.string.cancel)) } }) }
    group?.let { ids ->
        val rows = data.intakes.filter { it.id in ids }.sortedWith(compareBy<Intake> { it.scheduled }.thenBy { it.name.lowercase() })
        ConfirmDialog(rows, data.profiles, now, correcting, backdating, { group = null }) { selected, decision, actual ->
            val correction = correcting
            act { app.repository.mark(selected, decision, actual, correction) }; group = null
        }
    }
    error?.let { text -> AlertDialog(onDismissRequest = { error = null }, title = { Text(resources.getString(R.string.action_failed)) }, text = { Text(text) }, confirmButton = { TextButton(onClick = { error = null }) { Text(resources.getString(R.string.understood)) } }) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NotificationIntakeScreen(
    rows: List<Intake>,
    profiles: List<Profile>,
    busy: Boolean,
    close: () -> Unit,
    markTaken: (Set<String>) -> Unit,
    moreActions: (Set<String>) -> Unit
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    BackHandler(onBack = close)
    val profileNames = profiles.associate { it.id to it.name }
    val groups = rows.groupBy { it.profileId }.entries.sortedBy { profileNames[it.key]?.lowercase() ?: "" }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(
            title = { Text(resources.getString(R.string.reminder_title), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = close) { Icon(ImageVector.vectorResource(R.drawable.ic_close), resources.getString(R.string.close)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
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
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
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
                        Button(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy, onClick = { markTaken(medicineIds) }) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_check), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(resources.getString(R.string.mark_taken))
                        }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { moreActions(medicineIds) }) {
                            Text(resources.getString(R.string.other_time_or_skip))
                        }
                    }
                }
            }
            if (groups.size > 1) item {
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && rows.isNotEmpty(),
                    onClick = { markTaken(rows.map { it.id }.toSet()) }
                ) { Text(resources.getString(R.string.mark_all_profiles)) }
                TextButton(
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f))
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .72f)) {
                Icon(ImageVector.vectorResource(R.drawable.ic_pill), null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.let { Button(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) { Text(it) } }
        }
    }
}
@Composable private fun StatusPill(text: String) {
    Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun StatusBadge(status: Status) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val dark = isSystemInDarkTheme()
    val (background, foreground) = when (status) {
        Status.TAKEN -> if (dark) Color(0xFF163D2B) to Color(0xFFA0EDBD) else Color(0xFFD5F5E2) to Color(0xFF155532)
        Status.WAITING -> if (dark) Color(0xFF49370C) to Color(0xFFFFDD8C) else Color(0xFFFFEAB3) to Color(0xFF664900)
        Status.PLANNED -> if (dark) Color(0xFF173553) to Color(0xFFA9D2FF) else Color(0xFFDCEBFF) to Color(0xFF164E83)
        Status.MISSED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        Status.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(99.dp)) {
        Text(status.label(resources), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}
@Composable private fun TimelineContents(rows: List<Intake>, profileName: String) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(profileName, style = MaterialTheme.typography.titleLarge)
        rows.forEach { i ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MedicineDoseRow(i.name, i.dose)
                i.takenAt?.let { Text(resources.getString(R.string.taken_at, displayDateTime(it, resources)), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
@Composable private fun MedicineDoseRow(medicine: String, dose: String, modifier: Modifier = Modifier) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
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
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }, confirmButton = { TextButton(onClick = save, enabled = valid) { Text(resources.getString(R.string.save)) } }, dismissButton = { TextButton(onClick = close) { Text(resources.getString(R.string.cancel)) } })
}
@Composable private fun PrescriptionDialog(p: Prescription, close: () -> Unit, save: (Prescription) -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    var name by remember { mutableStateOf(p.name) }; var dose by remember { mutableStateOf(p.dose) }
    var times by remember { mutableStateOf(p.times.split(",").filter { it.isNotBlank() }.map(LocalTime::parse).distinct().sorted()) }
    var start by remember { mutableStateOf(p.start) }; var end by remember { mutableStateOf(p.end ?: "") }
    val context = LocalContext.current
    fun pickDate(value: String, set: (String) -> Unit) {
        val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now(ZoneId.of(p.zone)))
        DatePickerDialog(context, { _, y, m, d -> set(LocalDate.of(y, m + 1, d).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    fun pickTime(current: LocalTime = LocalTime.of(9, 0), replace: LocalTime? = null) {
        TimePickerDialog(context, { _, h, m ->
            val selected = LocalTime.of(h, m)
            times = (times.filterNot { it == replace } + selected).distinct().sorted()
        }, current.hour, current.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    val valid = runCatching { require(name.isNotBlank() && dose.isNotBlank() && times.isNotEmpty()); val first = LocalDate.parse(start); require(end.isBlank() || !LocalDate.parse(end).isBefore(first)) }.isSuccess
    FormDialog(resources.getString(R.string.prescription), close, { save(p.copy(name = name, dose = dose, times = times.joinToString(","), start = start, end = end.takeIf { it.isNotBlank() })) }, valid) {
        OutlinedTextField(name, { name = it }, label = { Text(resources.getString(R.string.medicine_name)) })
        OutlinedTextField(dose, { dose = it }, label = { Text(resources.getString(R.string.dose_hint)) })
        Text(resources.getString(R.string.intake_time), style = MaterialTheme.typography.titleSmall)
        times.forEach { time ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickTime(time, time) }, modifier = Modifier.weight(1f)) { Text(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time.atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))) }
                IconButton(onClick = { times = times - time }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = resources.getString(R.string.remove_time, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time.atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))))
                }
            }
        }
        OutlinedButton(onClick = { pickTime() }) { Text(resources.getString(R.string.add_time)) }
        Text(resources.getString(R.string.daily_zone, p.zone), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(start, { start = it }, label = { Text(resources.getString(R.string.course_start)) }, trailingIcon = { TextButton(onClick = { pickDate(start) { start = it } }) { Text(resources.getString(R.string.date)) } })
        OutlinedTextField(end, { end = it }, label = { Text(resources.getString(R.string.course_end)) }, trailingIcon = { TextButton(onClick = { pickDate(end) { end = it } }) { Text(resources.getString(R.string.date)) } }, supportingText = { Text(resources.getString(R.string.course_end_hint)) })
        if (!valid) Text(resources.getString(R.string.prescription_invalid), color = MaterialTheme.colorScheme.error)
    }
}
@Composable private fun ConfirmDialog(rows: List<Intake>, profiles: List<Profile>, now: Long, correction: Boolean, initiallyEditingTime: Boolean, close: () -> Unit, save: (Set<String>, String?, Long) -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    var selected by remember(rows.map { it.id }) { mutableStateOf(rows.map { it.id }.toSet()) }
    var actual by remember { mutableStateOf(Instant.ofEpochMilli(if (correction) rows.firstOrNull()?.takenAt ?: now else now).atZone(ZoneId.systemDefault()).format(stamp)) }
    var editActual by remember(rows.map { it.id }, correction, initiallyEditingTime) { mutableStateOf(correction || initiallyEditingTime) }
    val parsed = runCatching { LocalDateTime.parse(actual, stamp).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    val context = LocalContext.current
    fun changeActual(date: LocalDate? = null, time: LocalTime? = null) {
        val current = runCatching { LocalDateTime.parse(actual, stamp) }.getOrDefault(LocalDateTime.now())
        actual = LocalDateTime.of(date ?: current.toLocalDate(), time ?: current.toLocalTime()).format(stamp)
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
                            if (list.size > 1) TextButton(onClick = { selected = selected + list.map { it.id } }) { Text(resources.getString(R.string.select_all)) }
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
                if (scheduled != null && scheduled <= now) Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedRows.isNotEmpty(),
                    onClick = { save(selected, "TAKEN", scheduled) }
                ) { Text(resources.getString(R.string.taken_on_time)) }
                if (!correction) FilledTonalButton(modifier = Modifier.fillMaxWidth(), enabled = selected.isNotEmpty(), onClick = { save(selected, "TAKEN", now) }) { Text(resources.getString(R.string.taken_now)) }
                if (!editActual) OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { editActual = true }) { Text(resources.getString(R.string.choose_other_time)) }
                if (editActual) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(resources.getString(R.string.actual_time_title), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(actual, { actual = it }, label = { Text(resources.getString(R.string.actual_time_hint)) }, supportingText = { Text(resources.getString(R.string.phone_zone, ZoneId.systemDefault().id)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val value = runCatching { LocalDateTime.parse(actual, stamp).toLocalDate() }.getOrDefault(LocalDate.now())
                            DatePickerDialog(context, { _, y, m, d -> changeActual(date = LocalDate.of(y, m + 1, d)) }, value.year, value.monthValue - 1, value.dayOfMonth).show()
                        }) { Text(resources.getString(R.string.pick_date)) }
                        TextButton(onClick = {
                            val value = runCatching { LocalDateTime.parse(actual, stamp).toLocalTime() }.getOrDefault(LocalTime.now())
                            TimePickerDialog(context, { _, h, m -> changeActual(time = LocalTime.of(h, m)) }, value.hour, value.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
                        }) { Text(resources.getString(R.string.pick_time)) }
                    }
                    if (parsed == null || parsed > now) Text(resources.getString(R.string.actual_time_invalid), color = MaterialTheme.colorScheme.error)
                    Button(modifier = Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && parsed != null && parsed <= now, onClick = { save(selected, "TAKEN", parsed!!) }) { Text(resources.getString(R.string.save_actual_time)) }
                }
                if (correction || rows.filter { it.id in selected }.all { Schedule.status(it, now) == Status.WAITING }) OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = selected.isNotEmpty(), onClick = { save(selected, "MISSED", now) }) { Text(resources.getString(R.string.mark_missed)) }
                if (correction) TextButton(onClick = { save(selected, null, now) }, enabled = selected.isNotEmpty()) { Text(resources.getString(R.string.undo_mark)) }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(resources.getString(R.string.close)) } })
}
