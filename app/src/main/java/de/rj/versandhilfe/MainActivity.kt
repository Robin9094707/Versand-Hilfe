@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package de.rj.versandhilfe

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val received = mutableStateOf<List<Uri>>(emptyList())
    private val openedParcel = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptIntent(intent)
        setContent { VersandTheme { VersandApp((application as ShippingApp).repository, received.value, openedParcel.value, { received.value = emptyList() }, { openedParcel.value = null }) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    @Suppress("DEPRECATION")
    private fun acceptIntent(intent: Intent) {
        openedParcel.value = intent.getStringExtra("parcelId")
        received.value = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList().orEmpty()
            else -> emptyList()
        }.take(20)
    }
}
private val LightPalette = lightColorScheme(
    primary = Color(0xFF7046D3), onPrimary = Color.White, primaryContainer = Color(0xFFEBDDFF), onPrimaryContainer = Color(0xFF29124F),
    secondary = Color(0xFF466B5B), secondaryContainer = Color(0xFFD6F2E3), onSecondaryContainer = Color(0xFF163B2E),
    tertiary = Color(0xFF8A4D32), tertiaryContainer = Color(0xFFFFDCCB),
    background = Color(0xFFFAF8FF), surface = Color(0xFFFAF8FF), surfaceContainer = Color(0xFFF0ECF6),
    surfaceContainerLow = Color(0xFFF5F1FA), surfaceContainerHigh = Color(0xFFEAE5F1), onSurface = Color(0xFF241E2E), onSurfaceVariant = Color(0xFF655D70)
)
@Composable fun VersandTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFD1BCFF), primaryContainer = Color(0xFF50318D), secondaryContainer = Color(0xFF234D3C), tertiaryContainer = Color(0xFF6E3B26)) else LightPalette
    MaterialTheme(colorScheme = colors, typography = Typography(), shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp)), content = content)
}
@Composable fun VersandApp(repo: ParcelRepository, received: List<Uri>, openedParcel: String?, consumeShare: () -> Unit, consumeOpen: () -> Unit) {
    val parcels by repo.parcels.collectAsStateWithLifecycle()
    val loaded by repo.loaded.collectAsStateWithLifecycle()
    val loadError by repo.loadError.collectAsStateWithLifecycle()
    val assistant by repo.assistant.collectAsStateWithLifecycle()
    val alarm by AlarmSignals.current.collectAsStateWithLifecycle()
    var onboarded by rememberSaveable { mutableStateOf(repo.prefs.getBoolean("onboarded", false)) }
    var route by rememberSaveable { mutableStateOf("home") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<FolderInspection?>(null) }
    var pendingShared by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var shareTarget by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    fun message(text: String) { scope.launch { snack.showSnackbar(text) } }
    fun job(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() } catch (e: Exception) { message(e.message ?: "Das hat nicht geklappt. Bitte erneut versuchen.") } finally { busy = false } }
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) message("Benachrichtigungen sind aus. Du kannst sie später in Android aktivieren.") }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) job {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val inspection = repo.inspect(uri)
            if (inspection.archive != null) folder = inspection else { repo.useFolder(inspection, false); onboarded = true }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) job { repo.exportZip(uri); message("ZIP-Backup vollständig exportiert") } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) job { val n = repo.importZip(uri); message("$n Pakete eingelesen und mit deiner Sammlung zusammengeführt") } }
    LaunchedEffect(Unit) { repo.load(); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ReminderScheduler(context).scheduleAll(repo) } }
    LaunchedEffect(openedParcel, loaded) {
        if (loaded && openedParcel != null) { detailId = openedParcel; route = "detail"; consumeOpen() }
    }
    LaunchedEffect(received, loaded, onboarded) {
        if (loaded && onboarded && received.isNotEmpty()) {
            job { pendingShared = received.map { repo.attach(it, AttachmentKind.LABEL) }; shareTarget = true; consumeShare() }
        }
    }
    BackHandler(route in listOf("detail", "settings", "chat", "history", "print")) { route = "home" }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (onboarded && loaded && route in listOf("home", "dropoff", "history", "settings", "chat")) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                listOf(Triple("home", "Pakete", Icons.Outlined.Inventory2), Triple("dropoff", "Abgeben", Icons.Outlined.LocalShipping), Triple("history", "Verlauf", Icons.Outlined.CalendarMonth), Triple("chat", "Pakethelfer", Icons.Outlined.AutoAwesome), Triple("settings", "Sicherung", Icons.Outlined.CloudDone)).forEach { (key, title, icon) ->
                    NavigationBarItem(selected = route == key, onClick = { route = key }, icon = { Icon(icon, null) }, label = { Text(title) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (!onboarded) OnboardingScreen(onChooseFolder = { pickFolder.launch(null) })
            else if (loadError != null) Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Sicherung wiederherstellen", style = MaterialTheme.typography.headlineMedium)
                Text(loadError!!)
                Button(onClick = { import.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("ZIP importieren") }
                OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Ordner wieder verbinden") }
            }
            else if (!assistant.profileComplete && assistant.name.isBlank()) ProfileSetup(repo)
            else AnimatedContent(targetState = route, transitionSpec = {
                (fadeIn(tween(220)) + slideInHorizontally(tween(280)) { it / 12 }) togetherWith (fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { -it / 16 })
            }, label = "Seitenwechsel") { page ->
                when (page) {
                    "home" -> HomeScreen(repo, parcels, onAdd = { editId = null; draftId = java.util.UUID.randomUUID().toString(); route = "edit" }, onOpen = { detailId = it; route = "detail" }, onSettings = { route = "settings" }, onChat = { route = "chat" })
                    "chat" -> ParcelChatScreen(repo, onOpen = { detailId = it; route = "detail" }, onSettings = { route = "settings" })
                    "dropoff" -> DropoffScreen(repo, parcels, onOpen = { detailId = it; route = "detail" }, onStatus = { ids -> job { repo.setStatus(ids, ParcelStatus.SHIPPED, false); message("${ids.size} Pakete als abgegeben markiert") } })
                    "history" -> HistoryScreen(parcels, { detailId = it; route = "detail" }, { route = "print" })
                    "print" -> PrintScreen(repo, parcels, { route = "history" }, ::message)
                    "settings" -> SettingsScreen(repo, onFolder = { pickFolder.launch(null) }, onBackup = { job { if (repo.backup()) message("Sicherung vollständig und geprüft") else message(repo.backupMessage.value) } },
                        onExport = { export.launch("Versandhilfe-${java.time.LocalDate.now()}.zip") }, onImport = { import.launch(arrayOf("application/zip", "application/octet-stream")) }, onMessage = ::message,
                        onNotification = { if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else message("Benachrichtigungen sind verfügbar") })
                    "detail" -> {
                        val parcel = parcels.find { it.id == detailId }
                        if (parcel != null) DetailScreen(repo, parcel, onBack = { route = "home" }, onEdit = { editId = parcel.id; route = "edit" },
                            onDelete = { job { repo.delete(parcel.id); route = "home"; message("Paket entfernt") } },
                            onStatus = { status -> job { repo.setStatus(setOf(parcel.id), status, false) } }, onRefresh = { job { refreshTracking(repo, parcel); message("Tracking aktualisiert") } })
                        else EmptyState("Paket nicht gefunden", "Es wurde möglicherweise bereits entfernt.", "Zur Übersicht", { route = "home" })
                    }
                    "edit" -> {
                        val existing = parcels.find { it.id == editId }
                        val original = existing?.copy(attachments = existing.attachments + pendingShared.filterNot { a -> existing.attachments.any { it.id == a.id } }) ?: Parcel(id = draftId, number = runCatching { allocateParcelNumber(parcels, assistant.numberDigits, assistant.nextPackageNumber) }.getOrDefault(assistant.nextPackageNumber), numberDigits = assistant.numberDigits, attachments = pendingShared)
                        EditorScreen(repo, original, onBack = { pendingShared = emptyList(); route = if (editId == null) "home" else "detail" }, onSave = { parcel ->
                            job {
                                val saved = repo.save(parcel, false); pendingShared = emptyList(); detailId = saved.id; route = "detail"
                                message("${saved.code} gespeichert · Nummer auf das Paket schreiben")
                            }
                        }, onMessage = ::message)
                    }
                }
            }
            if (busy) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .25f)).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 8.dp) { Row(Modifier.padding(28.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(28.dp)); Text("Einen Moment …") } }
            }
        }
    }
    alarm?.let { notice ->
        androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            AlarmPage(repo, notice) { AlarmSignals.current.value = null }
        }
    }
    folder?.let { inspection -> AlertDialog(onDismissRequest = { folder = null }, icon = { Icon(Icons.Outlined.Restore, null) }, title = { Text("Vorhandene Sammlung gefunden") },
        text = { Text("${inspection.archive!!.parcels.size} Pakete in „${inspection.name}“. Vorhandene Daten werden geprüft und mit deinen lokalen Paketen zusammengeführt. Die jeweils neuere Bearbeitung gewinnt.") },
        confirmButton = { Button(onClick = { folder = null; job { repo.useFolder(inspection, true); onboarded = true; message("Sammlung verbunden und geladen") } }) { Text("Sammlung laden") } },
        dismissButton = { TextButton(onClick = { folder = null }) { Text("Abbrechen") } }) }
    if (shareTarget) AlertDialog(onDismissRequest = { shareTarget = false; pendingShared = emptyList() }, title = { Text("Versandlabel zuordnen") },
        text = { androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${pendingShared.size} Anhänge empfangen. Welches Paket gehört dazu?") }
            items(parcels.size) { i -> val p = parcels[i]; TextButton(onClick = { shareTarget = false; editId = p.id; detailId = p.id; route = "edit" }) { Text("${p.code} · ${p.title}") } }
        } }, confirmButton = { Button(onClick = { shareTarget = false; editId = null; draftId = java.util.UUID.randomUUID().toString(); route = "edit" }) { Text("Neues Paket") } }, dismissButton = { TextButton(onClick = { shareTarget = false; pendingShared = emptyList() }) { Text("Abbrechen") } })
}
