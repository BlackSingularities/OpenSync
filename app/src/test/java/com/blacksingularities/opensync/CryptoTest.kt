package com.blacksingularities.opensync

import com.blacksingularities.opensync.crypto.Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private val master = Crypto.newMasterKey()

    @Test
    fun blobRoundTripWithAndWithoutCompression() {
        val text = "Ala ma kota. ".repeat(5000).toByteArray()
        val random = Crypto.randomBytes(100_000)
        for (data in listOf(text, random, ByteArray(0))) {
            for (level in listOf(0, 6)) {
                val id = Crypto.blobId(master, data)
                val sealed = Crypto.encryptBlob(master, id, data, 0, data.size, level)
                assertArrayEquals(data, Crypto.decryptBlob(master, id, sealed))
            }
        }
        val id = Crypto.blobId(master, text)
        assertTrue(Crypto.encryptBlob(master, id, text, 0, text.size, 6).size < text.size / 10)
    }

    @Test
    fun sameDataSameIdDifferentCiphertext() {
        val data = "dane".toByteArray()
        val id = Crypto.blobId(master, data)
        assertEquals(id, Crypto.blobId(master, data))
        assertNotEquals(
            Crypto.encryptBlob(master, id, data, 0, data.size, 0).toList(),
            Crypto.encryptBlob(master, id, data, 0, data.size, 0).toList(),
        )
        assertNotEquals(id, Crypto.blobId(Crypto.newMasterKey(), data))
    }

    @Test(expected = Crypto.CorruptedException::class)
    fun tamperedBlobIsRejected() {
        val data = "ważne dane".toByteArray()
        val id = Crypto.blobId(master, data)
        val sealed = Crypto.encryptBlob(master, id, data, 0, data.size, 0)
        sealed[sealed.size - 3] = (sealed[sealed.size - 3].toInt() xor 1).toByte()
        Crypto.decryptBlob(master, id, sealed)
    }

    @Test(expected = Crypto.CorruptedException::class)
    fun swappedBlobIsRejected() {
        val a = "a".toByteArray()
        val b = "b".toByteArray()
        val sealedB = Crypto.encryptBlob(master, Crypto.blobId(master, b), b, 0, 1, 0)
        Crypto.decryptBlob(master, Crypto.blobId(master, a), sealedB)
    }

    @Test
    fun keyWrapping() {
        val salt = Crypto.randomBytes(32)
        val wrapped = Crypto.wrapMaster(Crypto.deriveKek("hasło123".toCharArray(), salt, 1000), master)
        val back = Crypto.unwrapMaster(Crypto.deriveKek("hasło123".toCharArray(), salt, 1000), wrapped)
        assertArrayEquals(master.raw, back.raw)
    }

    @Test(expected = Crypto.WrongPasswordException::class)
    fun wrongPassword() {
        val salt = Crypto.randomBytes(32)
        val wrapped = Crypto.wrapMaster(Crypto.deriveKek("dobre-haslo".toCharArray(), salt, 1000), master)
        Crypto.unwrapMaster(Crypto.deriveKek("złe-hasło".toCharArray(), salt, 1000), wrapped)
    }
}
