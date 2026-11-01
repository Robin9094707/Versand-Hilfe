package de.rj.versandhilfe

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

const val REMINDER_CHANNEL = "parcel-reminders-silent-v3"
data class AlarmNotice(val parcelId: String, val test: Boolean = false)
object AlarmSignals { val current = MutableStateFlow<AlarmNotice?>(null) }
class ReminderScheduler(private val context: Context) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private fun pending(id: String, test: Boolean = false): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ParcelAlarmReceiver::class.java).setAction("REMIND").setData(Uri.parse("versandhilfe://reminder/$id"))
            .putExtra("parcelId", id).putExtra("test", test), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun exactAllowed() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    fun fullScreenAllowed() = Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    fun cancel(id: String) { alarms.cancel(pending(id)); context.getSystemService(NotificationManager::class.java).cancel(id.hashCode()) }
    @android.annotation.SuppressLint("ScheduleExactAlarm")
    private fun at(id: String, time: Long, test: Boolean = false) {
        val operation = pending(id, test)
        try {
            if (exactAllowed()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
        } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation) }
    }
    fun schedule(p: Parcel, settings: AssistantState) {
        val next = ReminderRules.nextAt(p, settings, System.currentTimeMillis())
        if (next == null) cancel(p.id) else at(p.id, next)
    }
    fun scheduleAll(repo: ParcelRepository) { repo.parcels.value.forEach { schedule(it, repo.assistant.value) } }
    fun test(seconds: Int) { require(seconds in listOf(10, 20)); at("test", System.currentTimeMillis() + seconds * 1000L, true) }
    fun requestExact() { if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun requestFullScreen() { if (Build.VERSION.SDK_INT >= 34) context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { val repo = (context.applicationContext as ShippingApp).repository; repo.load(); ReminderScheduler(context).scheduleAll(repo) } finally { result.finish() }
        }
    }
}
class ParcelAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("parcelId") ?: return
        val test = intent.getBooleanExtra("test", false)
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as ShippingApp).repository
                repo.load()
                val p = repo.parcels.value.find { it.id == id }
                if (!test && (p == null || !repo.assistant.value.reminders || !ParcelFacts.pending(p) || p.snoozedUntil > System.currentTimeMillis())) return@launch
                if (intent.action == "SHIPPED" || intent.action == "SNOOZE") {
                    if (!test && p != null) {
                        if (intent.action == "SHIPPED") repo.setStatus(setOf(id), ParcelStatus.SHIPPED, false) else repo.snooze(id, 1)
                    }
                    context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
                    AlarmSignals.current.value = null
                    return@launch
                }
                if (test) repo.prefs.edit().putLong("lastTestAlarmAt", System.currentTimeMillis()).apply()
                if (!test) {
                    repo.update(id, waitForBackup = false) { it.copy(lastReminderAt = System.currentTimeMillis(), snoozedUntil = 0) }
                    repo.parcels.value.find { it.id == id }?.let { ReminderScheduler(context).schedule(it, repo.assistant.value) }
                }
                show(context, p, test, repo.assistant.value.fullScreenReminders)
                AlarmSignals.current.value = AlarmNotice(id, test)
            } finally { result.finish() }
        }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun show(context: Context, p: Parcel?, test: Boolean, fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = p?.id ?: "test"
        val open = PendingIntent.getActivity(context, 0, Intent(context, ParcelAlarmActivity::class.java)
            .setData(Uri.parse("versandhilfe://alarm/$id")).putExtra("parcelId", id).putExtra("test", test)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(which: String): PendingIntent = PendingIntent.getBroadcast(context, 0, Intent(context, ParcelAlarmReceiver::class.java)
            .setAction(which).setData(Uri.parse("versandhilfe://alarm-action/$id/$which")).putExtra("parcelId", id).putExtra("test", test), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL).setSmallIcon(de.rj.versandhilfe.R.drawable.ic_parcel)
            .setContentTitle(if (test) "Dein Testalarm" else "${p?.code}: Bitte ans Abgeben denken")
            .setContentText(if (test) "So sieht deine leise Paket-Erinnerung aus." else "${p?.title} · ${p?.location?.ifBlank { "Lagerplatz nicht eingetragen" }}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (test) "Öffne den Hinweis, um die Vollbild-Erinnerung und die Schlummerknöpfe auszuprobieren." else "${p?.title}\nFür ${p?.recipient?.ifBlank { "Empfänger noch offen" }}\n${p?.dueDate?.takeIf { !p.aiReviewRequired && it.isNotBlank() }?.let { "Abgeben bis $it" } ?: "Auch ohne Frist: Bitte gib dein Paket rechtzeitig ab."}"))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Abgegeben", action("SHIPPED")).addAction(0, "1 Std. später", action("SNOOZE")).addAction(0, "Mehr …", open)
            .setContentIntent(open).setAutoCancel(true).setGroup("parcel-reminders").setOnlyAlertOnce(false)
        if (fullscreen && ReminderScheduler(context).fullScreenAllowed()) notification.setFullScreenIntent(open, true)
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification.build())
    }
}
class ParcelAlarmActivity : ComponentActivity() {
    private val notice = mutableStateOf(AlarmNotice("test", true))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        accept(intent)
        setContent { VersandTheme { AlarmPage((application as ShippingApp).repository, notice.value) { finish() } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); accept(intent) }
    private fun accept(intent: Intent) {
        notice.value = AlarmNotice(intent.getStringExtra("parcelId") ?: "test", intent.getBooleanExtra("test", false))
        AlarmSignals.current.value = null
    }
}
@Composable fun AlarmPage(repo: ParcelRepository, notice: AlarmNotice, onDone: () -> Unit) {
    val parcels by repo.parcels.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val p = parcels.find { it.id == notice.parcelId }
    LaunchedEffect(Unit) { repo.load() }
    fun act(hours: Int?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (!notice.test && p != null) { if (hours == null) repo.setStatus(setOf(p.id), ParcelStatus.SHIPPED, false) else repo.snooze(p.id, hours) }
                repo.context.getSystemService(NotificationManager::class.java).cancel(notice.parcelId.hashCode())
                AlarmSignals.current.value = null; onDone()
            } catch (e: Exception) { error = e.message.orEmpty(); busy = false }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp)); Icon(Icons.Outlined.Inventory2, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (notice.test) "So erinnern wir dich" else "Bitte dein Paket abgeben", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(if (notice.test) "Das ist nur ein Test. Deine Pakete werden nicht verändert." else p?.let { "${it.code} · ${it.title}\nFür ${it.recipient}\nLiegt bei: ${it.location.ifBlank { "noch nicht eingetragen" }}" } ?: "Dieses Paket ist nicht mehr offen.", style = MaterialTheme.typography.bodyLarge)
            if (p?.dueDate?.isNotBlank() == true && !p.aiReviewRequired) Text("Abgeben bis ${p.dueDate}", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { act(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(if (notice.test) "Abgegeben testen" else "Ich habe es abgegeben") }
            Text("Später erinnern", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..3).forEach { h -> OutlinedButton(onClick = { act(h) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("$h Std.") } } }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDone, enabled = !busy) { Text("Schließen") }; Spacer(Modifier.height(24.dp))
        }
    }
}
