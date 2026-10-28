package com.blacksingularities.opensync.backend

import android.content.Context
import android.net.Uri
import com.blacksingularities.opensync.data.DestType
import com.blacksingularities.opensync.data.Destination
import com.blacksingularities.opensync.data.Secrets
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object Backends {
    /** Otwiera połączenie z celem; [subPath] to katalog repozytorium wewnątrz celu. */
    fun open(context: Context, d: Destination, subPath: String): Backend {
        val password = Secrets.reveal(d.password)
        return when (d.type) {
            DestType.SMB -> SmbBackend(d.host.trim(), d.effectivePort, d.share.trim('/', '\\', ' '), joinPath(d.path, subPath), d.user.trim(), password, d.domain.trim())
            DestType.SFTP -> SftpBackend(d.host.trim(), d.effectivePort, joinPath(d.path, subPath).let { if (d.path.startsWith("/")) "/$it" else it }, d.user.trim(), password, Secrets.reveal(d.privateKey), d.hostKey)
            DestType.WEBDAV -> WebDavBackend(d.url.trimEnd('/') + "/" + subPath.trim('/'), d.user.trim(), password, d.insecureTls)
            DestType.FOLDER -> SafBackend(context, Uri.parse(d.folderUri), subPath)
        }
    }

    /** Czy host odpowiada na porcie usługi (szybki test przed Wake-on-LAN i przed kopią). */
    fun reachable(d: Destination, timeoutMs: Int = 2500): Boolean {
        if (d.type == DestType.FOLDER) return true
        val host = if (d.type == DestType.WEBDAV) runCatching { Uri.parse(d.url).host }.getOrNull() ?: return false else d.host.trim()
        val port = if (d.type == DestType.WEBDAV) {
            val u = Uri.parse(d.url)
            if (u.port > 0) u.port else if (u.scheme == "http") 80 else 443
        } else d.effectivePort
        return runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        }.getOrDefault(false)
    }
}

/** Wake-on-LAN: „magiczny pakiet” budzący uśpiony NAS przed kopią. */
object WakeOnLan {
    fun parseMac(mac: String): ByteArray? {
        val hex = mac.trim().split(':', '-', '.').joinToString("").takeIf { it.length == 12 } ?: return null
        return runCatching { ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    fun send(mac: String, broadcast: String) {
        val m = parseMac(mac) ?: error("Nieprawidłowy adres MAC")
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xff.toByte()
        for (i in 0 until 16) System.arraycopy(m, 0, packet, 6 + i * 6, 6)
        val target = InetAddress.getByName(broadcast.ifBlank { "255.255.255.255" })
        DatagramSocket().use { s ->
            s.broadcast = true
            for (port in intArrayOf(9, 7)) s.send(DatagramPacket(packet, packet.size, target, port))
        }
    }
}
