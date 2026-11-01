package de.rj.versandhilfe

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ShippingApp : Application() {
    val repository by lazy { ParcelRepository(this) }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("shipping", "Paket-Updates & Fristen", NotificationManager.IMPORTANCE_DEFAULT))
        val request = PeriodicWorkRequestBuilder<ShippingWorker>(1, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("shipping-sync", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
class ApiKeyStore(private val context: Context, private val namespace: String = "tracking") {
    private val prefs = context.getSharedPreferences(if (namespace == "tracking") "tracking-key" else "secret-$namespace", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("shipping-api-$namespace", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("shipping-api-$namespace", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun hasKey() = read().isNotBlank()
    fun read(): String = runCatching {
        val stored = prefs.getString("value", null) ?: return ""
        val parts = stored.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")
    fun save(value: String) {
        if (value.isBlank()) { prefs.edit().clear().apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.trim().toByteArray())
        prefs.edit().putString("value", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
}
data class TrackingUpdate(val status: String, val summary: String)
class TrackingClient(private val key: String) {
    companion object {
        @Volatile private var courierCache: Pair<Long, Map<String, String>>? = null
    }
    private fun courierCode(parcel: Parcel): String {
        if (parcel.customCourierCode.isNotBlank()) return parcel.customCourierCode
        val names = when (parcel.carrier) {
            Carrier.DHL -> listOf("dhl germany")
            Carrier.HERMES -> listOf("hermes germany")
            Carrier.DPD -> listOf("dpd germany", "dpd")
            Carrier.GLS -> listOf("gls germany", "gls")
            Carrier.UPS -> listOf("ups")
            Carrier.FEDEX -> listOf("fedex")
            Carrier.POST -> listOf("deutsche post")
            else -> emptyList()
        }
        val cached = courierCache
        val couriers = if (cached != null && System.currentTimeMillis() - cached.first < 3_600_000) cached.second else {
            val json = request("couriers/all")
            val data = json["data"] as? JsonArray ?: error("Anbieterliste nicht verfügbar. Bitte Anbieter-Code manuell angeben.")
            val values = data.mapNotNull { element ->
                val entry = element.jsonObject
                val name = entry["courier_name"]?.jsonPrimitive?.contentOrNull?.lowercase()
                val code = entry["courier_code"]?.jsonPrimitive?.contentOrNull
                if (name != null && code != null) name to code else null
            }.toMap()
            courierCache = System.currentTimeMillis() to values
            values
        }
        return names.firstNotNullOfOrNull { couriers[it] }
            ?: error("Kein passender Anbieter im API-Konto gefunden. Bitte den TrackingMore-Anbieter-Code beim Paket eintragen.")
    }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private fun request(path: String, body: String? = null): JsonObject {
        val builder = Request.Builder().url("https://api.trackingmore.com/v4/$path").header("Tracking-Api-Key", key)
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        return client.newCall(builder.build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) error("API-Schlüssel oder Berechtigung prüfen.")
            if (response.code == 429) error("API-Limit erreicht. Später erneut versuchen.")
            val content = response.body?.string() ?: error("Leere Tracking-Antwort.")
            require(content.length <= 2_000_000) { "Tracking-Antwort zu groß." }
            val json = AppJson.parseToJsonElement(content).jsonObject
            val code = json["meta"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull ?: response.code
            require(response.isSuccessful || code == 4101) { "Tracking-Anbieter meldet Fehler $code. Nummer, Anbieter-Code und Kontingent prüfen." }
            require(code in 200..299 || code == 4101) { "Tracking-Anbieter meldet Fehler $code." }
            json
        }
    }
    suspend fun track(parcel: Parcel): TrackingUpdate = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Bitte zuerst einen TrackingMore-API-Schlüssel hinterlegen." }
        require(parcel.apiCourier.isNotBlank() && parcel.trackingNumber.isNotBlank()) { "Sendungsnummer und Anbieter fehlen." }
        val resolvedCourier = courierCode(parcel)
        val body = buildJsonObject { put("tracking_number", parcel.trackingNumber); put("courier_code", resolvedCourier) }.toString()
        request("trackings/create", body)
        val n = java.net.URLEncoder.encode(parcel.trackingNumber, "UTF-8")
        val c = java.net.URLEncoder.encode(resolvedCourier, "UTF-8")
        val json = request("trackings/get?tracking_numbers=$n&courier_code=$c")
        val data = json["data"]
        val item = when (data) {
            is JsonArray -> data.firstOrNull()?.jsonObject
            is JsonObject -> (data["items"] as? JsonArray)?.firstOrNull()?.jsonObject ?: data
            else -> null
        } ?: error("Noch keine Tracking-Daten verfügbar.")
        val status = item["delivery_status"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val latest = item["latest_event"]?.let { if (it is JsonPrimitive) it.contentOrNull else null }.orEmpty()
        val translated = when (status.lowercase()) {
            "delivered" -> "Zugestellt"
            "transit", "in_transit" -> "Unterwegs"
            "pickup", "out_for_delivery" -> "In Zustellung / Abholung"
            "exception" -> "Zustellproblem – bitte prüfen"
            "notfound", "pending" -> "Noch keine Sendungsereignisse"
            "inforeceived" -> "Versanddaten angekündigt"
            "expired" -> "Verfolgung abgelaufen"
            "undelivered" -> "Zustellung nicht erfolgreich"
            else -> status.ifBlank { "Status noch unbekannt" }
        }
        TrackingUpdate(status, latest.ifBlank { translated })
    }
}
suspend fun refreshTracking(repo: ParcelRepository, parcel: Parcel) {
    try {
        val result = TrackingClient(ApiKeyStore(repo.context).read()).track(parcel)
        var changed = false
        repo.update(parcel.id) { current ->
            // Ignore stale responses if the number, provider, or opt-in changed during a request.
            if (current.trackingNumber != parcel.trackingNumber || current.apiCourier != parcel.apiCourier || !current.autoTrack) current
            else {
                changed = current.trackingSummary != result.summary
                val nextStatus = when (result.status.lowercase()) {
                    "delivered" -> ParcelStatus.DELIVERED
                    "transit", "in_transit", "pickup", "out_for_delivery" -> if (current.status == ParcelStatus.DELIVERED) current.status else ParcelStatus.SHIPPED
                    else -> current.status
                }
                current.copy(trackingSummary = result.summary, trackingCheckedAt = System.currentTimeMillis(), trackingError = "", status = nextStatus,
                    events = if (changed) (listOf(ParcelEvent(text = result.summary, source = "TrackingMore")) + current.events).take(100) else current.events)
            }
        }
        if (changed) notifyParcel(repo.context, parcel.id.hashCode(), parcel.code, result.summary, parcel.id)
    } catch (e: Exception) {
        repo.update(parcel.id) { if (it.trackingNumber == parcel.trackingNumber && it.apiCourier == parcel.apiCourier) it.copy(trackingError = e.message ?: "Tracking fehlgeschlagen") else it }
        throw e
    }
}
fun notifyParcel(context: Context, notificationId: Int, title: String, text: String, parcelId: String? = null) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val intent = Intent(context, MainActivity::class.java).putExtra("parcelId", parcelId)
    val pi = PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, "shipping").setSmallIcon(R.drawable.ic_parcel).setContentTitle(title).setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build()
    NotificationManagerCompat.from(context).notify(notificationId, notification)
}
class ShippingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as ShippingApp).repository
        repo.load()
        if (repo.loadError.value != null) return Result.failure()
        var failed = !repo.backup()
        if (ApiKeyStore(applicationContext).hasKey()) {
            repo.parcels.value.filter { it.autoTrack && it.status != ParcelStatus.DELIVERED && it.trackingNumber.isNotBlank() && it.apiCourier.isNotBlank() && System.currentTimeMillis() - it.trackingCheckedAt > 6 * 60 * 60 * 1000 }.forEach {
                if (isStopped) return Result.retry()
                runCatching { refreshTracking(repo, it) }.onFailure { failed = true }
                kotlinx.coroutines.delay(1100)
            }
        }
        val today = java.time.LocalDate.now().toString()
        val assistant = repo.assistant.value
        if (assistant.reminders && java.time.LocalTime.now().hour >= assistant.reminderHour && repo.prefs.getString("reminderDate", "") != today) {
            val dueParcels = repo.parcels.value.filter { it.isDue() }
            val due = dueParcels.size
            if (due > 0) notifyParcel(applicationContext, 100, "Noch $due Paket${if (due > 1) "e" else ""} abgeben", "${assistant.name.takeIf { it.isNotBlank() }?.let { "$it, " }.orEmpty()}${dueParcels.take(3).joinToString { "${it.code} ${it.title}" }}: bitte die bestätigten Abgabefristen prüfen.", dueParcels.firstOrNull()?.id)
            repo.prefs.edit().putString("reminderDate", today).apply()
        }
        return if (failed && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
