package com.blacksingularities.opensync.backend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Folder wybrany przez użytkownika (Storage Access Framework): pamięć telefonu, karta SD,
 * dysk USB OTG albo dostawca dokumentów innej aplikacji.
 */
class SafBackend(private val context: Context, treeUri: Uri, basePath: String) : Backend {
    private val resolver = context.contentResolver
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri) ?: error("Folder niedostępny – wybierz go ponownie")
    private val base = basePath.trim('/')
    private val dirs = HashMap<String, DocumentFile>()

    init {
        if (!root.canWrite()) error("Brak prawa zapisu w wybranym folderze – wybierz go ponownie")
    }

    private fun full(path: String) = joinPath(base, path)

    private fun dir(path: String, create: Boolean): DocumentFile? {
        if (path.isEmpty()) return root
        dirs[path]?.let { return it }
        val parent = dir(parentOf(path), create) ?: return null
        val name = nameOf(path)
        val found = parent.findFile(name)?.takeIf { it.isDirectory }
            ?: if (create) parent.createDirectory(name) else null
        if (found != null) dirs[path] = found
        return found
    }

    private fun file(path: String): DocumentFile? {
        val f = full(path)
        return dir(parentOf(f), false)?.findFile(nameOf(f))
    }

    override fun read(path: String): ByteArray {
        val f = file(path)?.takeIf { it.isFile } ?: throw NotFoundException(path)
        return resolver.openInputStream(f.uri)?.use { it.readBytes() } ?: throw NotFoundException(path)
    }

    override fun write(path: String, data: ByteArray) {
        val f = full(path)
        val parent = dir(parentOf(f), true) ?: error("Nie można utworzyć katalogu ${parentOf(f)}")
        val name = nameOf(f)
        val tmpName = "$name.part"
        parent.findFile(tmpName)?.delete()
        val tmp = parent.createFile("application/octet-stream", tmpName) ?: error("Nie można utworzyć $path")
        resolver.openOutputStream(tmp.uri, "wt")?.use { it.write(data) } ?: error("Nie można zapisać $path")
        parent.findFile(name)?.delete()
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, tmp.uri, name) }.getOrNull()
        if (renamed == null) {
            // Dostawca bez zmiany nazwy: zapis bezpośredni.
            tmp.delete()
            val target = parent.createFile("application/octet-stream", name) ?: error("Nie można utworzyć $path")
            resolver.openOutputStream(target.uri, "wt")?.use { it.write(data) } ?: error("Nie można zapisać $path")
        }
    }

    override fun exists(path: String): Boolean = file(path) != null || dir(full(path), false) != null

    override fun list(dir: String): List<String> =
        dir(full(dir), false)?.listFiles()?.mapNotNull { it.name }?.filterNot { it.endsWith(".part") }?.sorted() ?: emptyList()

    override fun delete(path: String) {
        file(path)?.delete()
        dirs.keys.removeAll { it == full(path) || it.startsWith(full(path) + "/") }
    }
}
