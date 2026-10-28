package com.blacksingularities.opensync.crypto

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Kryptografia repozytorium.
 *
 * - Hasło → klucz szyfrujący klucz (KEK) przez PBKDF2-HMAC-SHA256 z solą repozytorium.
 * - Losowy klucz główny (64 B: 32 B do AES-256-GCM, 32 B do HMAC) jest zapisany w repozytorium
 *   zaszyfrowany KEK-iem, więc zmiana hasła nie wymaga ponownego szyfrowania danych.
 * - Identyfikator bloku to HMAC-SHA256 jego treści – serwer nie widzi skrótów plików, a ten sam
 *   fragment trafia do repozytorium tylko raz (deduplikacja).
 * - Blok na dysku: [wersja=1][nonce 12 B][AES-GCM(flaga kompresji + dane) + tag 16 B],
 *   z identyfikatorem jako AAD, więc bloków nie da się podmienić miejscami.
 */
object Crypto {
    const val KEY_AAD = "opensync-master-key"
    const val DEFAULT_ITERATIONS = 600_000
    private const val FORMAT = 1
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    class MasterKey(bytes: ByteArray) {
        init { require(bytes.size == 64) { "Nieprawidłowy klucz główny" } }
        internal val encKey = SecretKeySpec(bytes.copyOfRange(0, 32), "AES")
        internal val macKey = SecretKeySpec(bytes.copyOfRange(32, 64), "HmacSHA256")
        val raw: ByteArray = bytes.copyOf()
    }

    class WrongPasswordException : Exception("Nieprawidłowe hasło repozytorium")
    class CorruptedException(what: String) : Exception("Uszkodzone dane: $what")

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun newMasterKey() = MasterKey(randomBytes(64))

    fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun seal(key: SecretKeySpec, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        return ByteArray(1 + NONCE + ct.size).also {
            it[0] = FORMAT.toByte()
            System.arraycopy(nonce, 0, it, 1, NONCE)
            System.arraycopy(ct, 0, it, 1 + NONCE, ct.size)
        }
    }

    fun open(key: SecretKeySpec, sealed: ByteArray, aad: ByteArray, what: String): ByteArray {
        if (sealed.size < 1 + NONCE + 16 || sealed[0].toInt() != FORMAT) throw CorruptedException(what)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 1, NONCE))
        c.updateAAD(aad)
        return try {
            c.doFinal(sealed, 1 + NONCE, sealed.size - 1 - NONCE)
        } catch (e: AEADBadTagException) {
            throw CorruptedException(what)
        }
    }

    fun wrapMaster(kek: SecretKeySpec, master: MasterKey) = seal(kek, master.raw, KEY_AAD.toByteArray())

    fun unwrapMaster(kek: SecretKeySpec, wrapped: ByteArray): MasterKey = try {
        MasterKey(open(kek, wrapped, KEY_AAD.toByteArray(), "klucz"))
    } catch (e: CorruptedException) {
        throw WrongPasswordException()
    }

    /** Identyfikator bloku: HMAC-SHA256 treści (hex). */
    fun blobId(master: MasterKey, data: ByteArray, off: Int = 0, len: Int = data.size): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(master.macKey)
        mac.update(data, off, len)
        return mac.doFinal().toHex()
    }

    /** Szyfruje blok; [level] 0 = bez kompresji, 1–9 = Deflate (zlib). */
    fun encryptBlob(master: MasterKey, id: String, data: ByteArray, off: Int, len: Int, level: Int): ByteArray {
        var flag = 0
        var payload = data
        var pOff = off
        var pLen = len
        if (level > 0) {
            val packed = deflate(data, off, len, level)
            // Kompresja tylko wtedy, gdy realnie coś daje (min. 3%).
            if (packed.size < len * 0.97) {
                flag = 1; payload = packed; pOff = 0; pLen = packed.size
            }
        }
        val plain = ByteArray(1 + pLen)
        plain[0] = flag.toByte()
        System.arraycopy(payload, pOff, plain, 1, pLen)
        return seal(master.encKey, plain, id.toByteArray())
    }

    fun decryptBlob(master: MasterKey, id: String, sealed: ByteArray): ByteArray {
        val plain = open(master.encKey, sealed, id.toByteArray(), "blok $id")
        val data = when (plain[0].toInt()) {
            0 -> plain.copyOfRange(1, plain.size)
            1 -> inflate(plain, 1, plain.size - 1)
            else -> throw CorruptedException("blok $id")
        }
        if (blobId(master, data) != id) throw CorruptedException("blok $id")
        return data
    }

    /** Dokument (manifest kopii) – szyfrowany jak blok, z nazwą jako AAD. */
    fun encryptDoc(master: MasterKey, name: String, json: String, level: Int = 6): ByteArray {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return encryptBlob(master, "doc:$name", bytes, 0, bytes.size, level)
    }

    fun decryptDoc(master: MasterKey, name: String, sealed: ByteArray): String {
        val plain = open(master.encKey, sealed, "doc:$name".toByteArray(), name)
        val data = when (plain[0].toInt()) {
            0 -> plain.copyOfRange(1, plain.size)
            1 -> inflate(plain, 1, plain.size - 1)
            else -> throw CorruptedException(name)
        }
        return data.toString(Charsets.UTF_8)
    }

    fun deflate(data: ByteArray, off: Int, len: Int, level: Int): ByteArray {
        val d = Deflater(level.coerceIn(1, 9))
        d.setInput(data, off, len)
        d.finish()
        val out = ByteArrayOutputStream(len / 2 + 64)
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    fun inflate(data: ByteArray, off: Int, len: Int): ByteArray {
        val i = Inflater()
        i.setInput(data, off, len)
        val out = ByteArrayOutputStream(len * 3)
        val buf = ByteArray(64 * 1024)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            out.write(buf, 0, n)
        }
        i.end()
        return out.toByteArray()
    }

    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}

fun ByteArray.toHex(): String {
    val chars = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(chars[v ushr 4]).append(chars[v and 0x0f])
    }
    return sb.toString()
}
