package com.blacksingularities.opensync.backend

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Udział SMB 2/3 (Synology, QNAP, TrueNAS, Samba, Windows). */
class SmbBackend(
    host: String,
    port: Int,
    shareName: String,
    basePath: String,
    user: String,
    password: String,
    domain: String,
) : Backend {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(60, TimeUnit.SECONDS)
            .withSoTimeout(90, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .withEncryptData(false)
            .build(),
    )
    private val connection: Connection
    private val session: Session
    private val share: DiskShare
    private val base = basePath.trim('/').replace('/', '\\')
    private val madeDirs = HashSet<String>()

    init {
        try {
            connection = client.connect(host, port)
            val auth = when {
                user.isBlank() -> AuthenticationContext.anonymous()
                user.equals("guest", true) && password.isEmpty() -> AuthenticationContext.guest()
                else -> AuthenticationContext(user, password.toCharArray(), domain.ifBlank { null })
            }
            session = connection.authenticate(auth)
            share = session.connectShare(shareName) as? DiskShare ?: error("„$shareName” nie jest udziałem dyskowym")
        } catch (e: Exception) {
            runCatching { client.close() }
            throw e
        }
    }

    private fun p(path: String): String {
        val rel = path.trim('/').replace('/', '\\')
        return when {
            base.isEmpty() -> rel
            rel.isEmpty() -> base
            else -> "$base\\$rel"
        }
    }

    private fun mkdirs(dir: String) {
        if (dir.isEmpty() || dir in madeDirs) return
        var acc = ""
        for (part in dir.split('\\')) {
            acc = if (acc.isEmpty()) part else "$acc\\$part"
            if (acc in madeDirs) continue
            if (!share.folderExists(acc)) {
                try {
                    share.mkdir(acc)
                } catch (e: SMBApiException) {
                    if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION) throw e
                }
            }
            madeDirs += acc
        }
    }

    override fun read(path: String): ByteArray {
        val full = p(path)
        if (!share.fileExists(full)) throw NotFoundException(path)
        share.openFile(
            full, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null,
        ).use { f -> return f.inputStream.use { it.readBytes() } }
    }

    override fun write(path: String, data: ByteArray) {
        val full = p(path)
        mkdirs(full.substringBeforeLast('\\', ""))
        val tmp = "$full.part"
        share.openFile(
            tmp, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { f ->
            f.outputStream.use { it.write(data) }
            f.rename(full, true)
        }
    }

    override fun exists(path: String): Boolean {
        val full = p(path)
        return share.fileExists(full) || share.folderExists(full)
    }

    override fun list(dir: String): List<String> {
        val full = p(dir)
        if (!share.folderExists(full)) return emptyList()
        return share.list(full).map { it.fileName }
            .filter { it != "." && it != ".." && !it.endsWith(".part") }
            .sorted()
    }

    override fun delete(path: String) {
        val full = p(path)
        when {
            share.fileExists(full) -> share.rm(full)
            share.folderExists(full) -> share.rmdir(full, true)
        }
    }

    override fun freeSpace(): Long = runCatching { share.shareInformation.freeSpace }.getOrDefault(-1)

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}
