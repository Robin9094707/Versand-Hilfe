package de.rj.versandhilfe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream
import java.io.File

object VintedHelpState {
    val connected = MutableStateFlow(false)
    val captures = MutableStateFlow(0)
    val status = MutableStateFlow("Noch nicht eingeschaltet")
}
data class ScreenQrCandidate(val code: String, val png: ByteArray, val context: String)
object QrCrop {
    /** Save only the actual QR rectangle, surrounded by a newly drawn white quiet zone. */
    fun png(screen: Bitmap, bounds: Rect): ByteArray {
        val rect = Rect(bounds)
        require(rect.intersect(0, 0, screen.width, screen.height) && rect.width() > 5 && rect.height() > 5)
        val border = (maxOf(rect.width(), rect.height()) * .14f).toInt().coerceAtLeast(16)
        val output = Bitmap.createBitmap(rect.width() + border * 2, rect.height() + border * 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output); canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(screen, rect, Rect(border, border, border + rect.width(), border + rect.height()), Paint().apply { isFilterBitmap = false })
            return ByteArrayOutputStream().use { bytes -> output.compress(Bitmap.CompressFormat.PNG, 100, bytes); bytes.toByteArray() }
        } finally { output.recycle() }
    }
}
private data class Preview(val windowId: Int, val signature: String, val context: String)

/** No gestures, clicks, credentials, continuous recording or network access are performed by this service. */
class VintedQrService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val budget = PreviewScanBudget()
    private val scanner by lazy { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    private val repo get() = (application as ShippingApp).repository
    private var ready = false
    private var alive = true
    private var scanning = false
    private var importing = false
    private var scene = ""
    private var ignoredScene = ""
    private var overlay: View? = null
    private var timeout: Runnable? = null
    private var delayedScan: Runnable? = null
    private var contentEvent = false
    private var resumeScan: Runnable? = null
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "vintedQrConsent" || key == "vintedQrPauseUntil") {
            if (!enabled()) stopPreview()
            resumeScan?.let(handler::removeCallbacks)
            val remaining = pauseUntil - System.currentTimeMillis()
            if (remaining > 0) resumeScan = Runnable { if (alive && enabled()) { ignoredScene = ""; budget.reset(); scheduleScan(1100) } }.also { handler.postDelayed(it, remaining + 200) }
            else if (enabled() && foregroundPackage() == VintedQrPolicy.PACKAGE) scheduleScan(1100)
        }
    }
    private val baseEvents = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
    private val consent get() = repo.prefs.getBoolean("vintedQrConsent", false)
    private val pauseUntil get() = repo.prefs.getLong("vintedQrPauseUntil", 0)
    private fun enabled() = ready && consent && repo.assistant.value.vintedQrHelp && System.currentTimeMillis() >= pauseUntil
    private fun unlocked() = getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun interest(vinted: Boolean) {
        val info = serviceInfo ?: return
        val desired = if (vinted) baseEvents or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED else baseEvents
        if (info.eventTypes != desired) { info.eventTypes = desired; info.packageNames = null; serviceInfo = info }
    }
    override fun onServiceConnected() {
        VintedHelpState.connected.value = true
        repo.prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        if (Build.VERSION.SDK_INT < 30) { VintedHelpState.status.value = "Diese Hilfe benötigt Android 11 oder neuer."; disableSelf(); return }
        scope.launch {
            repo.load()
            var indexed = false
            ready = true
            repo.assistant.collect { settings ->
                if (settings.vintedQrHelp && consent && !indexed) { ready = false; indexOlderLabels(); indexed = true; ready = true; preferenceListener.onSharedPreferenceChanged(repo.prefs, "vintedQrPauseUntil") }
                if (!settings.vintedQrHelp || !consent) stopPreview()
                VintedHelpState.status.value = if (settings.vintedQrHelp && consent) "Bereit · öffne die Versand-QR-Vorschau in Vinted" else "In Versandhilfe ausgeschaltet"
            }
        }
    }
    /** Old labels are decoded once locally so that upgraded collections also take part in duplicate checks. */
    private suspend fun indexOlderLabels() {
        VintedHelpState.status.value = "Vorhandene Versandlabels werden einmalig lokal abgeglichen …"
        repo.parcels.value.toList().forEach { p ->
            p.attachments.filter { it.hasKind(AttachmentKind.LABEL) && it.rawCodes.isEmpty() && !it.localCodeScanned }.forEach { a ->
                val scan = runCatching { recognize(repo, a) }.getOrNull() ?: return@forEach
                repo.update(p.id, false) { current -> current.copy(attachments = current.attachments.map { if (it.id == a.id) it.copy(rawCodes = scan.codes, qrCodes = scan.qrCodes, localCodeScanned = true) else it }) }
                delay(50)
            }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !alive) return
        if (!enabled() || !unlocked()) { stopPreview(); interest(false); return }
        // Global window events provide only the foreground package; other apps' text is never traversed.
        val windowEvent = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val pkg = if (windowEvent) foregroundPackage() else event.packageName?.toString()
        if (!windowEvent && pkg != VintedQrPolicy.PACKAGE) return
        if (pkg != VintedQrPolicy.PACKAGE) { stopPreview(); interest(false); return }
        interest(true)
        if (overlay != null || importing) return
        contentEvent = contentEvent || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        scheduleScan(1100)
    }
    private fun applicationRoot(): AccessibilityNodeInfo? {
        val current = windows
        val root = current.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.root
        current.forEach { @Suppress("DEPRECATION") it.recycle() }
        return root ?: rootInActiveWindow
    }
    private fun foregroundPackage(): String? {
        val root = applicationRoot() ?: return null
        return try { root.packageName?.toString() } finally { @Suppress("DEPRECATION") root.recycle() }
    }
    private fun preview(): Preview? {
        if (!enabled() || !unlocked()) return null
        val visibleWindows = windows
        val inputShown = visibleWindows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        visibleWindows.forEach { @Suppress("DEPRECATION") it.recycle() }
        if (inputShown) return null
        val root = applicationRoot() ?: return null
        try {
            if (root.packageName?.toString() != VintedQrPolicy.PACKAGE) return null
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            var editable = false; var count = 0
            val height = resources.displayMetrics.heightPixels
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (++count > 350 || depth > 24 || !node.isVisibleToUser) return
                if (node.isEditable || node.isPassword) editable = true
                @Suppress("DEPRECATION")
                nodes += AccessibilityNodeInfo.obtain(node)
                repeat(minOf(node.childCount, 100)) { index -> node.getChild(index)?.let { child -> try { visit(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() } } }
            }
            var headings: List<String> = emptyList()
            var all: List<String> = emptyList()
            try {
                // First inspect node flags only. Never request editable/password text.
                visit(root, 0)
                if (editable) return null
                headings = nodes.filter { node ->
                    val rect = Rect(); node.getBoundsInScreen(rect)
                    rect.top >= 0 && rect.bottom <= (height * .22f).toInt() && rect.height() < height * .12f
                }.mapNotNull { it.text?.toString()?.trim()?.take(500) }.filter { it.isNotBlank() }
                if (!VintedQrPolicy.eligible(VintedQrPolicy.PACKAGE, headings, headings, false, true)) return null
                // Read remaining visible text only after a known shipping heading was found.
                all = nodes.mapNotNull { it.text?.toString()?.trim()?.take(500) }.filter { it.isNotBlank() }
            } finally { nodes.forEach { @Suppress("DEPRECATION") it.recycle() } }
            if (!VintedQrPolicy.eligible(VintedQrPolicy.PACKAGE, headings, all, false, true)) return null
            val context = VintedQrPolicy.safeContext(all)
            val signature = digest((root.windowId.toString() + all.joinToString("\n")).toByteArray())
            return Preview(root.windowId, signature, context)
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }
    private fun scheduleScan(delayMs: Long) {
        delayedScan?.let(handler::removeCallbacks)
        delayedScan = Runnable { if (Build.VERSION.SDK_INT >= 30) capture() }.also { handler.postDelayed(it, delayMs) }
    }
    @RequiresApi(30)
    private fun capture() {
        if (scanning || importing || overlay != null) return
        val current = preview() ?: run { stopPreview(); return }
        if (scene != current.signature) { scene = current.signature; ignoredScene = "" }
        if (scene == ignoredScene || !budget.allow(scene, SystemClock.elapsedRealtime(), contentEvent)) return
        contentEvent = false; scanning = true
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                var wrapped: Bitmap? = null
                var image: Bitmap? = null
                try {
                    wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    image = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                } finally { wrapped?.recycle(); buffer.close() }
                val source = image
                if (source == null || !alive || preview()?.signature != current.signature) { source?.recycle(); scanning = false; return }
                val factor = (1800f / maxOf(source.width, source.height)).coerceAtMost(1f)
                val scaled = if (factor < 1f) Bitmap.createScaledBitmap(source, (source.width * factor).toInt(), (source.height * factor).toInt(), true).also { source.recycle() } else source
                scanner.process(InputImage.fromBitmap(scaled, 0)).addOnSuccessListener(mainExecutor) { codes ->
                    if (!alive || preview()?.signature != current.signature || overlay != null) return@addOnSuccessListener
                    val readable = codes.filter { !it.rawValue.isNullOrBlank() && it.rawValue!!.length <= 12000 && it.boundingBox != null }
                    val fresh = readable.firstOrNull { VintedQrPolicy.duplicate(repo.parcels.value, listOf(it.rawValue!!)) == null }
                    if (fresh != null) {
                        val candidate = ScreenQrCandidate(fresh.rawValue!!, QrCrop.png(scaled, fresh.boundingBox!!), current.context)
                        ignoredScene = current.signature
                        ask(candidate)
                    } else if (readable.isNotEmpty()) {
                        ignoredScene = current.signature
                        VintedHelpState.status.value = "Dieser Versand-QR ist bereits in deiner Sammlung."
                    }
                }.addOnCompleteListener(mainExecutor) { scaled.recycle(); scanning = false; if (overlay == null && alive) scheduleScan(1800) }
            }
            override fun onFailure(errorCode: Int) {
                scanning = false
                VintedHelpState.status.value = "Vorschau konnte nicht gelesen werden. Du kannst den Screenshot weiterhin mit Versandhilfe teilen."
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) scheduleScan(1800)
            }
        }
        // App-window capture avoids covering overlays on newer Android versions. Android 11–13 only offer display capture.
        try {
            VintedHelpState.captures.value++
            if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(current.windowId, mainExecutor, callback)
            else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        } catch (_: RuntimeException) { scanning = false; VintedHelpState.status.value = "Bildschirmfreigabe nicht verfügbar. Bitte in Android prüfen." }
    }
    private fun ask(candidate: ScreenQrCandidate) {
        VintedHelpState.status.value = "Neuer Versand-QR erkannt · wartet auf deine Bestätigung"
        panel("Neuer Versand-QR erkannt", "Aus Vinted. Möchtest du den QR-Code mit den lesbaren Versandangaben übernehmen?", listOf(
            "Übernehmen" to { acceptCandidate(candidate) },
            "1 Minute Ruhe" to { pauseOneMinute() },
            "Nicht jetzt" to { dismissOverlay() }
        ))
    }
    private fun acceptCandidate(candidate: ScreenQrCandidate) {
        if (importing || !enabled()) return
        importing = true; dismissOverlay()
        scope.launch {
            var temp: File? = null
            try {
                val already = VintedQrPolicy.duplicate(repo.parcels.value, listOf(candidate.code))
                val saved = already ?: run {
                    temp = withContext(Dispatchers.IO) { File(cacheDir, "shared/vinted-${java.util.UUID.randomUUID()}.png").apply { parentFile?.mkdirs(); writeBytes(candidate.png) } }
                    val attachment = repo.attach(android.net.Uri.fromFile(temp!!), AttachmentKind.LABEL).copy(name = "Vinted-Versand-QR.png", rawCodes = listOf(candidate.code), qrCodes = listOf(candidate.code), importText = candidate.context, localCodeScanned = true)
                    val draft = applyLocalCodes(Parcel(title = "Vinted-Versand", attachments = listOf(attachment), screenReviewPending = true, aiReviewRequired = true), attachment, listOf(candidate.code), listOf(candidate.code))
                    repo.save(draft, false, deduplicateCodes = true)
                }
                VintedHelpState.status.value = "${saved.code} ist gespeichert · Angaben in Versandhilfe prüfen"
                if (foregroundPackage() == VintedQrPolicy.PACKAGE && unlocked()) panel("${saved.code} ist übernommen", "Der QR-Code gehört jetzt zu diesem Paket. Artikel, Lagerplatz und fehlende Angaben kannst du in Versandhilfe ergänzen.", listOf(
                    "Angaben prüfen" to { dismissOverlay(); startActivity(Intent(this@VintedQrService, MainActivity::class.java).putExtra("editParcelId", saved.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) },
                    "Weiter in Vinted" to { dismissOverlay() }
                ))
            } catch (_: Exception) { VintedHelpState.status.value = "Übernahme nicht möglich. Bitte Screenshot über Teilen hinzufügen." }
            finally { temp?.delete(); importing = false }
        }
    }
    private fun pauseOneMinute() {
        repo.prefs.edit().putLong("vintedQrPauseUntil", System.currentTimeMillis() + 60000).apply()
        dismissOverlay(); delayedScan?.let(handler::removeCallbacks)
        VintedHelpState.status.value = "Vinted-QR-Hilfe pausiert für eine Minute"
    }
    private fun panel(title: String, message: String, actions: List<Pair<String, () -> Unit>>) {
        dismissOverlay()
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) Color.rgb(246, 241, 255) else Color.rgb(36, 30, 46)
        val background = if (dark) Color.rgb(36, 30, 46) else Color.rgb(250, 248, 255)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(12))
            this.background = GradientDrawable().apply { setColor(background); cornerRadius = dp(26).toFloat(); setStroke(dp(1), Color.rgb(155, 127, 210)) }
            elevation = dp(10).toFloat()
        }
        box.addView(TextView(this).apply { text = title; textSize = 20f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
        box.addView(TextView(this).apply { text = message; textSize = 15f; setTextColor(ink); setPadding(0, dp(10), 0, dp(10)) })
        actions.forEachIndexed { index, (label, action) -> box.addView(Button(this).apply {
            text = label; isAllCaps = false; minHeight = dp(48)
            setTextColor(if (index == 0) Color.WHITE else ink)
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (index == 0) Color.rgb(112, 70, 211) else if (dark) Color.rgb(64, 53, 80) else Color.rgb(235, 221, 255))
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2)) }
        val params = WindowManager.LayoutParams(resources.displayMetrics.widthPixels - dp(28), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(36) }
        try { getSystemService(WindowManager::class.java).addView(box, params); overlay = box }
        catch (_: Exception) { VintedHelpState.status.value = "Hinweis konnte nicht angezeigt werden. Bitte Screenshot über Teilen hinzufügen." }
        timeout = Runnable { dismissOverlay() }.also { handler.postDelayed(it, 30000) }
    }
    private fun dismissOverlay() {
        timeout?.let(handler::removeCallbacks); timeout = null
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; overlay = null
    }
    private fun stopPreview() {
        delayedScan?.let(handler::removeCallbacks); delayedScan = null
        dismissOverlay(); scene = ""; ignoredScene = ""; budget.reset()
    }
    override fun onInterrupt() { stopPreview() }
    override fun onDestroy() { alive = false; repo.prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener); stopPreview(); handler.removeCallbacksAndMessages(null); scope.cancel(); scanner.close(); VintedHelpState.connected.value = false; super.onDestroy() }
}
