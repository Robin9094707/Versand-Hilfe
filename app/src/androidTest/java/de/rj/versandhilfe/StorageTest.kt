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
    @Test fun safFolderRestoresAllFilesAndSkipsInterruptedSnapshot() = runBlocking {
        val repo = ParcelRepository(context)
        repo.load()
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri("${context.packageName}.fixture", "root")
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)!!
        val sub = root.createDirectory("case-${UUID.randomUUID()}")!!
        val uri = android.provider.DocumentsContract.buildTreeDocumentUri("${context.packageName}.fixture", android.provider.DocumentsContract.getDocumentId(sub.uri))
        context.grantUriPermission(context.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        repo.useFolder(repo.inspect(uri), false)
        val fixture = File(context.cacheDir, "folder-image.png")
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        fixture.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val att = repo.attach(Uri.fromFile(fixture), AttachmentKind.PHOTO)
        val item = repo.save(Parcel(title = "Dauerhaft", attachments = listOf(att)))
        assertTrue(repo.backup())
        val snapshots = sub.findFile("Versandhilfe")!!.findFile("snapshots")!!
        val corrupt = snapshots.createFile("application/json", "9999999999999-interrupted.json")!!
        context.contentResolver.openOutputStream(corrupt.uri)!!.use { it.write("{truncated".toByteArray()) }
        val inspection = repo.inspect(uri)
        assertTrue(inspection.archive!!.parcels.any { it.id == item.id })
        repo.delete(item.id)
        File(repo.assets, att.file).delete()
        repo.useFolder(inspection, true)
        val fresh = ParcelRepository(context)
        fresh.load()
        assertTrue(fresh.parcels.value.any { it.id == item.id })
        assertEquals(att.sha256, digest(File(fresh.assets, att.file).readBytes()))
        repo.prefs.edit().remove("folder").remove("folderName").commit()
        Unit
    }
    @Test fun importedQrImageIsDecodedOnDevice() = runBlocking {
        val repo = ParcelRepository(context)
        repo.load()
        val url = "https://www.dhl.de/de/privatkunden?piececode=00340434123456789012"
        val matrix = com.google.zxing.MultiFormatWriter().encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 600, 600)
        val bitmap = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until 600) for (x in 0 until 600) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val file = File(context.cacheDir, "qr.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val attachment = repo.attach(Uri.fromFile(file), AttachmentKind.LABEL)
        val scan = recognize(repo, attachment)
        assertTrue(scan.codes.contains(url))
        assertTrue(scan.candidates.any { it.number == "00340434123456789012" && it.carrier == Carrier.DHL })
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
