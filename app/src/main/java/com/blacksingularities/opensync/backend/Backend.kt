package com.blacksingularities.opensync.backend

import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException

/**
 * Magazyn plików repozytorium. Ścieżki są względne wobec katalogu repozytorium i rozdzielane „/”.
 * Zapis jest atomowy (plik tymczasowy + zmiana nazwy), więc przerwany transfer nie zostawia
 * uszkodzonego bloku pod docelową nazwą.
 */
interface Backend : Closeable {
    fun read(path: String): ByteArray
    fun write(path: String, data: ByteArray)
    fun exists(path: String): Boolean
    /** Nazwy plików i katalogów w [dir]; pusta lista, gdy katalog nie istnieje. */
    fun list(dir: String): List<String>
    fun delete(path: String)
    /** Wolne miejsce w bajtach albo -1, gdy nieznane. */
    fun freeSpace(): Long = -1
    override fun close() {}
}

class NotFoundException(path: String) : FileNotFoundException("Brak pliku: $path")

fun parentOf(path: String) = path.substringBeforeLast('/', "")
fun nameOf(path: String) = path.substringAfterLast('/')
fun joinPath(vararg parts: String) = parts.flatMap { it.split('/') }.filter { it.isNotEmpty() }.joinToString("/")

/** Katalog lokalny – testy jednostkowe i kopie na kartę SD z dostępem plikowym. */
class FileBackend(private val root: File) : Backend {
    init { root.mkdirs() }

    private fun f(path: String) = File(root, path)

    override fun read(path: String): ByteArray {
        val file = f(path)
        if (!file.isFile) throw NotFoundException(path)
        return file.readBytes()
    }

    override fun write(path: String, data: ByteArray) {
        val target = f(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.writeBytes(data)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) error("Nie można zapisać $path")
    }

    override fun exists(path: String) = f(path).exists()
    override fun list(dir: String): List<String> = f(dir).list()?.filterNot { it.endsWith(".part") }?.sorted() ?: emptyList()
    override fun delete(path: String) { f(path).delete() }
    override fun freeSpace() = root.usableSpace
}
