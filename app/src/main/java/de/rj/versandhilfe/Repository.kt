package de.rj.versandhilfe

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MAX_FILE = 40L * 1024 * 1024
private const val MAX_ARCHIVE = 500L * 1024 * 1024
private fun InputStream.readLimited(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16384)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        require(total <= limit) { "Datei ist zu groß (max. ${limit / 1024 / 1024} MB)." }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

data class FolderInspection(val uri: Uri, val archive: Archive?, val name: String)
class ParcelRepository(val context: Context) {
    val prefs = context.getSharedPreferences("shipping", Context.MODE_PRIVATE)
    val assets = File(context.filesDir, "assets").apply { mkdirs() }
    private val manifest = AtomicFile(File(context.filesDir, "parcels.json"))
    private val mutex = Mutex()
    private val _parcels = MutableStateFlow<List<Parcel>>(emptyList())
    val parcels = _parcels.asStateFlow()
    val backupMessage = MutableStateFlow("Sicherung wird geprüft …")
    val loaded = MutableStateFlow(false)
    val loadError = MutableStateFlow<String?>(null)
    val folderName get() = prefs.getString("folderName", "Kein Ordner gewählt")!!
    val folderUri get() = prefs.getString("folder", null)?.let(Uri::parse)
    val lastBackup get() = prefs.getLong("lastBackup", 0)

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded.value) return@withLock
            try {
                if (manifest.baseFile.exists() || File(manifest.baseFile.path + ".bak").exists()) {
                    val archive = manifest.openRead().use { AppJson.decodeFromString<Archive>(it.readLimited(8L * 1024 * 1024).decodeToString()) }
                    validateArchive(archive)
                    _parcels.value = archive.parcels
                }
                backupMessage.value = if (prefs.getBoolean("dirty", false)) "Änderungen noch nicht im Sicherungsordner" else if (lastBackup > 0) "Im gewählten Ordner gesichert" else "Noch keine Sicherung"
            } catch (e: Exception) {
                loadError.value = "Lokale Daten konnten nicht gelesen werden. Bitte ein Backup importieren. ${e.message.orEmpty()}"
            }
            loaded.value = true
        }
    }
    private fun writeLocal(items: List<Parcel>) {
        val stream = manifest.startWrite()
        try {
            stream.write(AppJson.encodeToString(Archive(parcels = items)).toByteArray())
            manifest.finishWrite(stream)
        } catch (e: Exception) { manifest.failWrite(stream); throw e }
        _parcels.value = items
        loadError.value = null
        prefs.edit().putBoolean("dirty", true).apply()
    }
    suspend fun save(parcel: Parcel): Parcel = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(loadError.value == null) { "Bitte zuerst die Daten aus einer Sicherung wiederherstellen." }
            val previous = _parcels.value.find { it.id == parcel.id }
            val number = previous?.number ?: ((_parcels.value.maxOfOrNull { it.number } ?: 0) + 1)
            val item = parcel.copy(number = number, updatedAt = System.currentTimeMillis())
            writeLocal(listOf(item) + _parcels.value.filterNot { it.id == item.id })
            backupLocked()
            item
        }
    }
    suspend fun update(id: String, transform: (Parcel) -> Parcel) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _parcels.value.firstOrNull { it.id == id } ?: return@withLock
            writeLocal(_parcels.value.map { if (it.id == id) transform(current).copy(updatedAt = System.currentTimeMillis()) else it })
            backupLocked()
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { writeLocal(_parcels.value.filterNot { it.id == id }); backupLocked() }
    }
    suspend fun setStatus(ids: Set<String>, status: ParcelStatus) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeLocal(_parcels.value.map {
                if (it.id in ids && it.status != status) it.copy(status = status, updatedAt = System.currentTimeMillis(), events = listOf(ParcelEvent(text = status.label)) + it.events) else it
            })
            backupLocked()
        }
    }
    suspend fun attach(uri: Uri, kind: AttachmentKind): Attachment = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Anhang"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited(MAX_FILE) } ?: error("Datei nicht lesbar.")
        val ext = when {
            bytes.take(4).toByteArray().contentEquals("%PDF".toByteArray()) -> "pdf"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "jpg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes.copyOfRange(1, 4).decodeToString() == "PNG" -> "png"
            bytes.size >= 12 && bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "webp"
            else -> error("Bitte ein JPG-, PNG-, WebP-Bild oder eine PDF auswählen.")
        }
        val hash = digest(bytes)
        val file = "$hash.$ext"
        if (!File(assets, file).exists()) File(assets, file).writeBytes(bytes)
        Attachment(name = name, mime = if (ext == "pdf") "application/pdf" else "image/${if (ext == "jpg") "jpeg" else ext}", file = file, sha256 = hash, kind = kind)
    }
    private fun root(uri: Uri) = DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canRead() && it.canWrite() }
        ?: error("Der Ordner ist nicht erreichbar. Bitte erneut auswählen.")
    private fun documentDir(root: DocumentFile, name: String) = root.findFile(name)?.also { require(it.isDirectory) { "$name ist kein Ordner." } }
        ?: root.createDirectory(name) ?: error("Ordner konnte nicht erstellt werden.")
    suspend fun inspect(uri: Uri): FolderInspection = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val folder = root(uri)
        val snapshots = folder.findFile("Versandhilfe")?.findFile("snapshots")?.listFiles()?.filter { it.name?.endsWith(".json") == true }?.sortedByDescending { it.name }.orEmpty()
        var archive: Archive? = null
        for (doc in snapshots) {
            archive = runCatching {
                val a = context.contentResolver.openInputStream(doc.uri)!!.use { AppJson.decodeFromString<Archive>(it.readLimited(8L * 1024 * 1024).decodeToString()) }
                validateArchive(a)
                // A truncated or interrupted snapshot is never offered for restoration.
                val assetRoot = folder.findFile("Versandhilfe")?.findFile("assets")
                a.parcels.flatMap { it.attachments }.distinctBy { it.file }.forEach { att ->
                    val source = assetRoot?.findFile(att.file) ?: error("Anhang fehlt.")
                    val bytes = context.contentResolver.openInputStream(source.uri)!!.use { it.readLimited(MAX_FILE) }
                    require(digest(bytes) == att.sha256)
                }
                a
            }.getOrNull()
            if (archive != null) break
        }
        require(snapshots.isEmpty() || archive != null) { "Keine vollständige Sicherung gefunden. Der Ordner wurde nicht verändert." }
        FolderInspection(uri, archive, folder.name ?: "Sicherungsordner")
    }
    suspend fun useFolder(inspection: FolderInspection, restore: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (restore && inspection.archive != null) {
                val source = root(inspection.uri).findFile("Versandhilfe")!!.findFile("assets")!!
                inspection.archive.parcels.flatMap { it.attachments }.distinctBy { it.file }.forEach { a ->
                    val bytes = context.contentResolver.openInputStream(source.findFile(a.file)!!.uri)!!.use { it.readLimited(MAX_FILE) }
                    require(digest(bytes) == a.sha256) { "Ein Anhang ist beschädigt." }
                    File(assets, a.file).writeBytes(bytes)
                }
                writeLocal(mergeParcels(_parcels.value, inspection.archive.parcels))
            }
            prefs.edit().putString("folder", inspection.uri.toString()).putString("folderName", inspection.name).putBoolean("onboarded", true).commit()
            backupLocked()
        }
    }
    suspend fun backup(): Boolean = withContext(Dispatchers.IO) { mutex.withLock { backupLocked() } }
    private fun backupLocked(): Boolean {
        return try {
            require(loadError.value == null) { "Lokale Daten zuerst wiederherstellen." }
            val base = documentDir(root(folderUri ?: error("Bitte einen Sicherungsordner wählen.")), "Versandhilfe")
            val remoteAssets = documentDir(base, "assets")
            val snapshots = documentDir(base, "snapshots")
            _parcels.value.flatMap { it.attachments }.distinctBy { it.file }.forEach { a ->
                var remote = remoteAssets.findFile(a.file)
                val local = File(assets, a.file)
                require(local.exists() && digest(local.readBytes()) == a.sha256) { "Ein lokaler Anhang fehlt oder ist beschädigt." }
                val valid = remote?.let { d -> runCatching { context.contentResolver.openInputStream(d.uri)!!.use { digest(it.readLimited(MAX_FILE)) == a.sha256 } }.getOrDefault(false) } ?: false
                if (!valid) {
                    remote?.delete()
                    remote = remoteAssets.createFile(a.mime, a.file) ?: error("Anhang konnte nicht gesichert werden.")
                    context.contentResolver.openOutputStream(remote.uri, "wt")!!.use { out -> local.inputStream().use { it.copyTo(out) } }
                    val verified = context.contentResolver.openInputStream(remote.uri)!!.use { digest(it.readLimited(MAX_FILE)) }
                    require(verified == a.sha256) { "Sicherungsprüfung fehlgeschlagen." }
                }
            }
            val json = AppJson.encodeToString(Archive(parcels = _parcels.value)).toByteArray()
            val snapshot = snapshots.createFile("application/json", "${System.currentTimeMillis()}-${UUID.randomUUID()}.json") ?: error("Sicherung konnte nicht erstellt werden.")
            try {
                context.contentResolver.openOutputStream(snapshot.uri, "wt")!!.use { it.write(json) }
                val check = context.contentResolver.openInputStream(snapshot.uri)!!.use { it.readLimited(8L * 1024 * 1024) }
                require(check.contentEquals(json)) { "Sicherungsprüfung fehlgeschlagen." }
            } catch (e: Exception) { snapshot.delete(); throw e }
            // Retain the newest ten complete metadata revisions; assets remain for older snapshots.
            snapshots.listFiles().filter { it.name?.endsWith(".json") == true }.sortedByDescending { it.name }.drop(10).forEach { it.delete() }
            prefs.edit().putLong("lastBackup", System.currentTimeMillis()).putBoolean("dirty", false).apply()
            backupMessage.value = "Alle Änderungen im Ordner gesichert"
            true
        } catch (e: Exception) {
            prefs.edit().putBoolean("dirty", true).apply()
            backupMessage.value = "Nur auf diesem Gerät gespeichert · ${e.message.orEmpty()}"
            false
        }
    }
    suspend fun exportZip(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(loadError.value == null) { "Lokale Daten zuerst wiederherstellen." }
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Export nicht möglich.")
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("versandhilfe.json")); zip.write(AppJson.encodeToString(Archive(parcels = _parcels.value)).toByteArray()); zip.closeEntry()
                _parcels.value.flatMap { it.attachments }.distinctBy { it.file }.forEach { a ->
                    val bytes = File(assets, a.file).readBytes()
                    require(digest(bytes) == a.sha256) { "Anhang beschädigt." }
                    zip.putNextEntry(ZipEntry("assets/${a.file}")); zip.write(bytes); zip.closeEntry()
                }
            }
        }
    }
    suspend fun importZip(uri: Uri): Int = withContext(Dispatchers.IO) {
        // Stage and validate everything before touching the current collection.
        val stage = File(context.cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var total = 0L
            var count = 0
            val seen = mutableSetOf<String>()
            val stream = context.contentResolver.openInputStream(uri) ?: error("Backup nicht lesbar.")
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 2000) { "Zu viele Dateien im Backup." }
                    require(seen.add(entry.name)) { "Doppelte Datei im Backup." }
                    require(!entry.isDirectory && (entry.name == "versandhilfe.json" || entry.name.matches(Regex("assets/[a-f0-9]{64}\\.(jpg|png|webp|pdf)")))) { "Ungültiger Pfad im Backup." }
                    val bytes = zip.readLimited(if (entry.name == "versandhilfe.json") 8L * 1024 * 1024 else MAX_FILE)
                    total += bytes.size
                    require(total <= MAX_ARCHIVE) { "Backup ist größer als 500 MB." }
                    val target = File(stage, entry.name).apply { parentFile?.mkdirs() }
                    target.writeBytes(bytes)
                }
            }
            val archive = AppJson.decodeFromString<Archive>(File(stage, "versandhilfe.json").readText())
            validateArchive(archive)
            archive.parcels.flatMap { it.attachments }.forEach { a ->
                val file = File(stage, "assets/${a.file}")
                require(file.exists() && digest(file.readBytes()) == a.sha256) { "Ein Anhang fehlt oder ist beschädigt. Es wurde nichts importiert." }
            }
            mutex.withLock {
                archive.parcels.flatMap { it.attachments }.distinctBy { it.file }.forEach { a -> File(stage, "assets/${a.file}").copyTo(File(assets, a.file), overwrite = true) }
                writeLocal(mergeParcels(_parcels.value, archive.parcels))
                backupLocked()
            }
            archive.parcels.size
        } finally { stage.deleteRecursively() }
    }
}
