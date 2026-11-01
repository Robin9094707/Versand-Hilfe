package de.rj.versandhilfe

import android.app.UiAutomation
import android.graphics.*
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VintedAssistTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val code = "OF|TESTVINT|0|Paket bis 2 kg|A|Anna Beispiel||Musterweg 1|10115|Berlin|Germany|Ben Muster||Testweg 2|20095|Hamburg|Germany||||20095156002000|123456789012|27112026||0||V2.0"
    @Test fun cropContainsTheQrAndExcludesSurroundingScreen() = runBlocking {
        val matrix = com.google.zxing.MultiFormatWriter().encode(code, com.google.zxing.BarcodeFormat.QR_CODE, 500, 500)
        val bitmap = Bitmap.createBitmap(700, 1400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        for (y in 0 until 500) for (x in 0 until 500) bitmap.setPixel(100 + x, 500 + y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        val png = QrCrop.png(bitmap, Rect(100, 500, 600, 1000));bitmap.recycle()
        val crop = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertTrue(crop.width < 700);assertTrue(crop.height < 1400);assertEquals(Color.WHITE, crop.getPixel(0, 0))
        val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
        try { assertTrue(scanner.process(com.google.mlkit.vision.common.InputImage.fromBitmap(crop, 0)).await().any { it.rawValue == code }) }
        finally { scanner.close();crop.recycle() }
        Unit
    }
    @Test fun confirmedDuplicateImportReturnsTheExistingRecordAtomically() = runBlocking {
        val repo = ParcelRepository(context);repo.load()
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "dedup-test.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) };bitmap.recycle()
        val a = repo.attach(Uri.fromFile(file), AttachmentKind.LABEL).copy(rawCodes = listOf("DEDUP-ATOMIC-TEST"), qrCodes = listOf("DEDUP-ATOMIC-TEST"), importText = "Versandlabel", localCodeScanned = true)
        val first = repo.save(Parcel(title = "Duplikattest", attachments = listOf(a), screenReviewPending = true), false, true)
        val second = repo.save(Parcel(title = "Kein zweites Paket", attachments = listOf(a)), false, true)
        assertEquals(first.id, second.id)
        assertEquals(1, repo.parcels.value.count { it.attachments.any { a -> "DEDUP-ATOMIC-TEST" in a.rawCodes } })
        Unit
    }
    @Test fun accessibilityImportRequiresShippingPreviewConsentAndConfirmationAndSkipsDuplicates() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val device = UiDevice.getInstance(inst)
        val oldServices = device.executeShellCommand("settings get secure enabled_accessibility_services").trim()
        val repo = (context.applicationContext as ShippingApp).repository
        repo.load()
        repo.prefs.edit().putBoolean("vintedQrConsent", true).putLong("vintedQrPauseUntil", 0).commit()
        repo.modifyAssistant { it.copy(vintedQrHelp = true) }
        try {
            device.executeShellCommand("settings put secure enabled_accessibility_services ${context.packageName}/.VintedQrService")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
            withTimeout(20000) { while (!VintedHelpState.connected.value) delay(100) }
            device.executeShellCommand("am start -W -n fr.vinted/.PreviewActivity --ez private true")
            assertTrue(device.wait(Until.hasObject(By.text("Test-Login · Passwort")), 10000))
            val before = VintedHelpState.captures.value
            delay(2500)
            assertEquals(before, VintedHelpState.captures.value)
            device.findObject(By.text("Versandvorschau öffnen")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Übernehmen")), 30000))
            assertNull(VintedQrPolicy.duplicate(repo.parcels.value, listOf(code)))
            device.findObject(By.text("1 Minute Ruhe")).click()
            assertTrue(device.wait(Until.gone(By.text("Übernehmen")), 5000))
            assertTrue(repo.prefs.getLong("vintedQrPauseUntil", 0) > System.currentTimeMillis() + 50000)
            repo.prefs.edit().putLong("vintedQrPauseUntil", 0).commit()
            assertTrue(device.wait(Until.hasObject(By.text("Übernehmen")), 15000))
            val calls = repo.assistant.value.calls
            device.findObject(By.text("Übernehmen")).click()
            withTimeout(15000) { while (VintedQrPolicy.duplicate(repo.parcels.value, listOf(code)) == null) delay(100) }
            val saved = VintedQrPolicy.duplicate(repo.parcels.value, listOf(code))!!
            assertEquals("Ben Muster", saved.recipient);assertTrue(saved.screenReviewPending)
            assertEquals(ParcelStatus.DRAFT, saved.status);assertTrue(saved.attachments.single().importText.contains("27.11.2026"))
            assertEquals(calls, repo.assistant.value.calls)
            assertTrue(device.wait(Until.hasObject(By.text("Weiter in Vinted")), 10000))
            device.findObject(By.text("Weiter in Vinted")).click()
            device.pressHome();delay(500)
            device.executeShellCommand("am force-stop fr.vinted")
            device.executeShellCommand("am start -W -n fr.vinted/.PreviewActivity")
            delay(4000)
            assertFalse(device.hasObject(By.text("Übernehmen")))
            assertEquals(1, repo.parcels.value.count { p -> p.attachments.any { code in it.rawCodes } })
        } finally {
            device.pressHome()
            repo.prefs.edit().putBoolean("vintedQrConsent", false).commit()
            repo.modifyAssistant { it.copy(vintedQrHelp = false) }
            device.executeShellCommand("settings put secure enabled_accessibility_services ${if (oldServices.isBlank() || oldServices == "null") "null" else oldServices}")
            device.executeShellCommand("settings put secure accessibility_enabled ${if (oldServices.isBlank() || oldServices == "null") "0" else "1"}")
            Configurator.getInstance().setUiAutomationFlags(0)
        }
        Unit
    }
}
