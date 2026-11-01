package de.rj.versandhilfe

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Exists only in debug builds; guarded by the system MANAGE_DOCUMENTS permission. */
class FixtureDocumentsProvider : DocumentsProvider() {
    private val base get() = File(context!!.filesDir, "saf-fixture").apply { mkdirs() }
    private fun file(id: String): File {
        val f = if (id == "root") base else File(base, id.removePrefix("root/"))
        require(f.canonicalPath == base.canonicalPath || f.canonicalPath.startsWith(base.canonicalPath + "/"))
        return f
    }
    private fun id(f: File) = if (f == base) "root" else "root/" + f.relativeTo(base).path
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS))
        val row = c.newRow()
        c.columnNames.forEach { col -> row.add(col, when (col) { Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> "root"; Root.COLUMN_TITLE -> "Test documents"; Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE; else -> null }) }
        return c
    }
    private fun columns(projection: Array<out String>?) = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    private fun add(c: MatrixCursor, f: File) {
        val row = c.newRow()
        c.columnNames.forEach { col -> row.add(col, when (col) {
            Document.COLUMN_DOCUMENT_ID -> id(f)
            Document.COLUMN_DISPLAY_NAME -> f.name
            Document.COLUMN_MIME_TYPE -> if (f.isDirectory) Document.MIME_TYPE_DIR else when (f.extension) { "json" -> "application/json"; "pdf" -> "application/pdf"; else -> "image/png" }
            Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0
            Document.COLUMN_SIZE -> f.length()
            Document.COLUMN_LAST_MODIFIED -> f.lastModified()
            else -> null
        }) }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(columns(projection)).also { add(it, file(documentId)) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(columns(projection)).also { c -> file(parentDocumentId).listFiles().orEmpty().forEach { add(c, it) } }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(!displayName.contains('/') && displayName != "..")
        val target = File(file(parentDocumentId), displayName)
        if (mimeType == Document.MIME_TYPE_DIR) check(target.mkdir()) else check(target.createNewFile())
        return id(target)
    }
    override fun deleteDocument(documentId: String) { file(documentId).deleteRecursively() }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath + "/")
}
