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
        automation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").close()
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        val scheduler = ReminderScheduler(context)
        org.junit.Assume.assumeTrue(scheduler.exactAllowed())
        AlarmSignals.current.value = null
        scheduler.test(10)
        withTimeout(20000) { while (AlarmSignals.current.value?.test != true) delay(100) }
        assertEquals("test", AlarmSignals.current.value!!.parcelId)
        scheduler.cancel("test"); AlarmSignals.current.value = null
        Unit
    }
}
