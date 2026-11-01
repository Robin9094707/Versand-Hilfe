package de.rj.versandhilfe

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun zipRoundTripCopiesFilesAndSurvivesNewRepositoryInstance() = runBlocking {
        val repo = ParcelRepository(context)
        repo.load()
        val image = File(context.cacheDir, "sample.png")
        val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val attachment = repo.attach(Uri.fromFile(image), AttachmentKind.LABEL)
        val parcel = repo.save(Parcel(title = "Roundtrip-${UUID.randomUUID()}", attachments = listOf(attachment)))
        val zip = File(context.cacheDir, "roundtrip.zip")
        repo.exportZip(Uri.fromFile(zip))
        repo.delete(parcel.id)
        repo.importZip(Uri.fromFile(zip))
        val fresh = ParcelRepository(context)
        fresh.load()
        val restored = fresh.parcels.value.first { it.id == parcel.id }
        assertEquals(parcel.attachments, restored.attachments)
        assertEquals(attachment.sha256, digest(File(fresh.assets, attachment.file).readBytes()))
        assertFalse(fresh.backup()) // No SAF folder selected: local save succeeds and failure is visible.
        assertTrue(fresh.backupMessage.value.contains("Nur auf diesem Gerät"))
    }
    @Test fun unsafeZipLeavesCollectionUntouched() = runBlocking {
        val repo = ParcelRepository(context)
        repo.load()
        val before = repo.parcels.value
        val bad = File(context.cacheDir, "malicious.zip")
        ZipOutputStream(bad.outputStream()).use { it.putNextEntry(ZipEntry("../escape")); it.write("bad".toByteArray()); it.closeEntry() }
        assertTrue(runCatching { repo.importZip(Uri.fromFile(bad)) }.isFailure)
        assertEquals(before, repo.parcels.value)
        assertFalse(File(context.cacheDir.parentFile, "escape").exists())
    }
}
