package com.blacksingularities.opensync.backend

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
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
    private val client = newClient()
    private val connection: Connection
    private val session: Session
    private val share: DiskShare
    private val base = basePath.trim('/').replace('/', '\\')
    private val madeDirs = HashSet<String>()

    init {
        try {
            connection = client.connect(host, port)
            session = connection.authenticate(auth(user, password, domain))
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

/**
 * Klient SMB odporny na Androida: kryptografia przez BouncyCastle (dostawca JCE w Androidzie nie ma
 * MD4/AES-CMAC i przy kluczu sesji gościa kończył się błędem „Key.getEncoded() on null”), podpisywanie
 * tylko gdy serwer go wymaga.
 */
internal fun newClient() = SMBClient(
    SmbConfig.builder()
        .withTimeout(60, TimeUnit.SECONDS)
        .withSoTimeout(90, TimeUnit.SECONDS)
        .withMultiProtocolNegotiate(true)
        .withSecurityProvider(BCSecurityProvider())
        .withSigningRequired(false)
        .withEncryptData(false)
        .build(),
)

/** Puste konto = gość (np. publiczne udziały WD My Cloud), inaczej logowanie NTLM. */
internal fun auth(user: String, password: String, domain: String): AuthenticationContext = when {
    user.isBlank() || (user.equals("guest", true) && password.isEmpty()) -> AuthenticationContext.guest()
    else -> AuthenticationContext(user, password.toCharArray(), domain.ifBlank { null })
}

/** Przeglądanie serwera SMB: lista udziałów, podfoldery i tworzenie nowych – bez wpisywania ścieżek. */
object SmbBrowser {
    private fun <T> withSession(host: String, port: Int, user: String, password: String, domain: String, block: (Session) -> T): T =
        newClient().use { client ->
            client.connect(host, port).use { conn ->
                val session = conn.authenticate(auth(user, password, domain))
                try { block(session) } finally { runCatching { session.close() } }
            }
        }

    /** Udziały dyskowe (bez ukrytych typu IPC$, ADMIN$). */
    fun shares(host: String, port: Int, user: String, password: String, domain: String): List<String> =
        withSession(host, port, user, password, domain) { s ->
            ServerService(SMBTransportFactories.SRVSVC.getTransport(s)).shares1
                .filter { (it.type and 0xFFFF) == 0 && it.netName?.endsWith("$") == false }
                .map { it.netName }
                .sortedBy { it.lowercase() }
        }

    /** Podfoldery [path] (rozdzielane „/”) w udziale [share]. */
    fun folders(host: String, port: Int, user: String, password: String, domain: String, share: String, path: String): List<String> =
        withSession(host, port, user, password, domain) { s ->
            (s.connectShare(share) as? DiskShare ?: error("„$share” nie jest udziałem dyskowym")).use { d ->
                d.list(path.trim('/').replace('/', '\\'))
                    .filter { it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L }
                    .map { it.fileName }
                    .filter { it != "." && it != ".." && !it.startsWith(".") && !it.startsWith("@") && !it.startsWith("#") }
                    .sortedBy { it.lowercase() }
            }
        }

    fun mkdir(host: String, port: Int, user: String, password: String, domain: String, share: String, path: String) {
        withSession(host, port, user, password, domain) { s ->
            (s.connectShare(share) as DiskShare).use { it.mkdir(path.trim('/').replace('/', '\\')) }
        }
    }
}
