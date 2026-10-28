package com.blacksingularities.opensync.backend

import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet

/**
 * SFTP przez SSH. Klucz hosta jest zapamiętywany przy pierwszym połączeniu (TOFU), a każda
 * zmiana odcisku przerywa połączenie – chroni przed podstawieniem serwera w sieci.
 */
class SftpBackend(
    host: String,
    port: Int,
    basePath: String,
    user: String,
    password: String,
    privateKey: String,
    expectedHostKey: String,
) : Backend {
    private val ssh = SSHClient(AndroidConfig())
    private val sftp: SFTPClient
    private val base = basePath.trimEnd('/')
    private val madeDirs = HashSet<String>()

    /** Odcisk klucza hosta (SHA256:…) widziany przy tym połączeniu. */
    var seenHostKey: String = ""
        private set

    init {
        ssh.connectTimeout = 20_000
        ssh.timeout = 60_000
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                seenHostKey = fingerprint(key)
                return expectedHostKey.isBlank() || expectedHostKey == seenHostKey
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            ssh.connect(host, port)
            if (privateKey.isNotBlank()) {
                val finder = if (password.isNotEmpty()) PasswordUtils.createOneOff(password.toCharArray()) else null
                ssh.authPublickey(user, ssh.loadKeys(privateKey, null, finder))
            } else {
                ssh.authPassword(user, password)
            }
            sftp = ssh.newSFTPClient()
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            if (expectedHostKey.isNotBlank() && seenHostKey.isNotBlank() && seenHostKey != expectedHostKey) {
                throw SecurityException("Klucz serwera zmienił się ($seenHostKey). Jeśli to zamierzone, wyczyść zapamiętany klucz w ustawieniach celu.")
            }
            throw e
        }
    }

    private fun p(path: String): String {
        val rel = path.trim('/')
        return when {
            base.isEmpty() -> rel.ifEmpty { "." }
            rel.isEmpty() -> base
            else -> "$base/$rel"
        }
    }

    private fun mkdirs(dir: String) {
        if (dir.isEmpty() || dir in madeDirs) return
        if (sftp.statExistence(dir) == null) sftp.mkdirs(dir)
        madeDirs += dir
    }

    override fun read(path: String): ByteArray {
        val full = p(path)
        if (sftp.statExistence(full) == null) throw NotFoundException(path)
        sftp.open(full, EnumSet.of(OpenMode.READ)).use { rf ->
            return rf.RemoteFileInputStream().use { it.readBytes() }
        }
    }

    override fun write(path: String, data: ByteArray) {
        val full = p(path)
        mkdirs(full.substringBeforeLast('/', ""))
        val tmp = "$full.part"
        sftp.open(tmp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
            rf.RemoteFileOutputStream(0, 16).use { it.write(data) }
        }
        try {
            sftp.rename(tmp, full, EnumSet.of(RenameFlags.OVERWRITE))
        } catch (e: Exception) {
            // Serwery SFTP v3 (OpenSSH) nie nadpisują przy zmianie nazwy.
            if (sftp.statExistence(full) != null) sftp.rm(full)
            sftp.rename(tmp, full)
        }
    }

    override fun exists(path: String) = sftp.statExistence(p(path)) != null

    override fun list(dir: String): List<String> {
        val full = p(dir)
        if (sftp.statExistence(full) == null) return emptyList()
        return sftp.ls(full).map { it.name }.filter { it != "." && it != ".." && !it.endsWith(".part") }.sorted()
    }

    override fun delete(path: String) {
        val full = p(path)
        val attrs = sftp.statExistence(full) ?: return
        if (attrs.type == FileMode.Type.DIRECTORY) {
            sftp.ls(full).forEach { delete(joinPath(path, it.name)) }
            sftp.rmdir(full)
        } else {
            sftp.rm(full)
        }
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }

    companion object {
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
