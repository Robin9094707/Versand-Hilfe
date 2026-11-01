package de.rj.versandhilfe

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun createdDateAndHistoryCannotBeRewrittenByAnEditor() = runBlocking {
        val repo = ParcelRepository(context); repo.load()
        val id = java.util.UUID.randomUUID().toString()
        val first = repo.save(Parcel(id = id, title = "Audit-Test", createdAt = 1, events = listOf(ParcelEvent(text = "Fake"))), false)
        assertTrue(first.createdAt > 1); assertEquals("Paket angelegt", first.events.single().text)
        val edited = repo.save(first.copy(createdAt = 1, events = emptyList(), title = "Geändert"), false)
        assertEquals(first.createdAt, edited.createdAt); assertEquals(2, edited.events.size)
        repo.setStatus(setOf(id), ParcelStatus.SHIPPED, false)
        val shipped = repo.parcels.value.first { it.id == id }
        assertEquals(first.createdAt, shipped.createdAt); assertEquals(3, shipped.events.size)
        assertEquals("Unterwegs", shipped.events.last().text)
        Unit
    }
    @Test fun fourQrCardsFitOnOneA4PdfAndRemainReadable() = runBlocking {
        val codes = listOf("OF|EXAMPLE12|0|Paket bis 2 kg|A|Änne Beispiel||Musterstraße 1|10115|Berlin|Germany|Ben Muster||Testweg 2|20095|Hamburg|Germany||||20095156002000|123456789012|27112026||0||V2.0") + (2..4).map { "QR-PRINT-TEST-$it" }
        val cards = codes.mapIndexed { index, code -> QrPrintCard(Parcel(title = "Testpaket $index", number = index + 1, numberDigits = 2, recipient = "Ben Muster"), code) }
        val file = File(context.cacheDir, "four-cards-test.pdf")
        ParcelPdf.labels(file, cards, 4)
        val rendered = renderPage(file)
        assertEquals(1, rendered.second)
        val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
        try {
            val found = scanner.process(com.google.mlkit.vision.common.InputImage.fromBitmap(rendered.first, 0)).await().mapNotNull { it.rawValue }
            assertEquals(codes.toSet(), found.toSet())
        } finally { scanner.close(); rendered.first.recycle() }
        Unit
    }
    @Test fun largeCameraImagesAreBoundedBeforeLocalRecognition() = runBlocking {
        val raw = "HIGH-RESOLUTION-QR-TEST"
        val bits = com.google.zxing.MultiFormatWriter().encode(raw, com.google.zxing.BarcodeFormat.QR_CODE, 1200, 1200)
        val qr = android.graphics.Bitmap.createBitmap(1200, 1200, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(1200 * 1200) { i -> if (bits[i % 1200, i / 1200]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        qr.setPixels(pixels, 0, 1200, 0, 0, 1200, 1200)
        val photo = android.graphics.Bitmap.createBitmap(3000, 5000, android.graphics.Bitmap.Config.ARGB_8888)
        photo.eraseColor(android.graphics.Color.WHITE)
        android.graphics.Canvas(photo).drawBitmap(qr, 900f, 1800f, null)
        val file = File(context.cacheDir, "large-photo-test.jpg")
        file.outputStream().use { photo.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        photo.recycle(); qr.recycle()
        val bounded = boundedPhoto(file)
        assertTrue(maxOf(bounded.width, bounded.height) <= 2400); bounded.recycle()
        val repo = ParcelRepository(context); repo.load()
        val a = repo.attach(Uri.fromFile(file), AttachmentKind.PHOTO)
        assertTrue(recognize(repo, a).qrCodes.contains(raw))
        Unit
    }
    @Test fun quotaSupportsTwoHundredAndUnlimitedWithoutNetwork() = runBlocking {
        val repo = ParcelRepository(context); repo.load(); repo.resetKeys()
        repo.configureAssistant("Test", "sk-fake-never-sent-key", "test-password-123", true)
        repo.modifyAssistant { it.copy(dailyLimit = 200, calls = 199, usageDate = java.time.LocalDate.now().toString()) }
        repo.reserveAiCall(); assertEquals(200, repo.assistant.value.calls)
        assertTrue(runCatching { repo.reserveAiCall() }.isFailure)
        repo.modifyAssistant { it.copy(dailyLimit = 0, calls = 500) }
        repo.reserveAiCall(); assertEquals(501, repo.assistant.value.calls)
        repo.resetKeys(); repo.modifyAssistant { it.copy(dailyLimit = 20) }
        Unit
    }
    @Test fun tenSecondAlarmFiresWithoutChangingAnyParcel() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")).use { it.readBytes() }
        if (android.os.Build.VERSION.SDK_INT >= 33) android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        val scheduler = ReminderScheduler(context)
        assertTrue("Exact alarm permission must be granted in this device test", scheduler.exactAllowed())
        AlarmSignals.current.value = null
        val started = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("shipping", android.content.Context.MODE_PRIVATE)
        scheduler.test(10)
        withTimeout(20000) { while (prefs.getLong("lastTestAlarmAt", 0) < started) delay(100) }
        assertTrue(prefs.getLong("lastTestAlarmAt", 0) >= started + 9500)
        val channel = context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(REMINDER_CHANNEL)
        assertNull(channel.sound); assertFalse(channel.shouldVibrate())
        scheduler.cancel("test"); AlarmSignals.current.value = null
        Unit
    }
}
