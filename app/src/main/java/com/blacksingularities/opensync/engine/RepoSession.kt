package com.blacksingularities.opensync.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.blacksingularities.opensync.backend.Backend
import com.blacksingularities.opensync.backend.Backends
import com.blacksingularities.opensync.backend.WakeOnLan
import com.blacksingularities.opensync.data.Destination
import com.blacksingularities.opensync.data.Job
import com.blacksingularities.opensync.data.Secrets
import com.blacksingularities.opensync.data.Store
import com.blacksingularities.opensync.repo.Repository
import kotlinx.coroutines.delay
import java.io.Closeable
import java.io.File

/** Otwarte połączenie z repozytorium zadania. */
class RepoSession(val backend: Backend, val repo: Repository, val destination: Destination) : Closeable {
    override fun close() {
        runCatching { repo.unlock() }
        runCatching { backend.close() }
    }

    class SkipException(message: String) : Exception(message)

    companion object {
        fun cacheRoot(context: Context) = File(context.filesDir, "repos")

        fun destinationOf(context: Context, job: Job): Destination =
            Store.get(context).destination(job.destinationId) ?: error("Zadanie „${job.name}” nie ma wybranego miejsca kopii")

        /**
         * Łączy się z celem: sprawdza sieć, w razie potrzeby budzi NAS przez Wake-on-LAN
         * i otwiera (lub zakłada) repozytorium.
         */
        suspend fun open(
            context: Context,
            job: Job,
            createIfMissing: Boolean,
            status: (String) -> Unit = {},
        ): RepoSession {
            val d = destinationOf(context, job)
            val password = Secrets.reveal(job.repoPassword)
            if (password.isEmpty()) error("Ustaw hasło szyfrowania w zadaniu „${job.name}”")
            ensureReachable(context, d, status)
            status("Łączenie z ${d.name.ifBlank { d.describe() }}")
            val backend = Backends.open(context, d, job.repoPath)
            try {
                val repo = Repository(backend, cacheRoot(context))
                status("Otwieranie repozytorium")
                if (createIfMissing) repo.openOrInit(password) else repo.open(password)
                return RepoSession(backend, repo, d)
            } catch (e: Exception) {
                backend.close()
                throw e
            }
        }

        suspend fun ensureReachable(context: Context, d: Destination, status: (String) -> Unit) {
            if (Backends.reachable(d)) return
            if (d.wolMac.isNotBlank()) {
                status("Budzenie urządzenia (Wake-on-LAN)")
                runCatching { WakeOnLan.send(d.wolMac, d.wolBroadcast.ifBlank { broadcastAddress(context) }) }
                val until = System.currentTimeMillis() + d.wolWaitSec.coerceIn(10, 600) * 1000L
                var resend = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < until) {
                    delay(3000)
                    if (Backends.reachable(d)) return
                    if (System.currentTimeMillis() > resend) {
                        runCatching { WakeOnLan.send(d.wolMac, d.wolBroadcast.ifBlank { broadcastAddress(context) }) }
                        resend = System.currentTimeMillis() + 20_000
                    }
                }
            }
            throw SkipException("${d.name.ifBlank { d.describe() }} jest niedostępny w tej sieci")
        }

        /** Nazwa bieżącej sieci Wi-Fi (wymaga uprawnienia do lokalizacji) albo null. */
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        fun currentSsid(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            val ssid = runCatching { wm.connectionInfo.ssid }.getOrNull()?.trim('"') ?: return null
            return ssid.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        }

        @Suppress("DEPRECATION")
        fun broadcastAddress(context: Context): String {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            val dhcp = runCatching { wm.dhcpInfo }.getOrNull() ?: return "255.255.255.255"
            if (dhcp.ipAddress == 0) return "255.255.255.255"
            val bc = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
            return (0..3).joinToString(".") { ((bc shr (it * 8)) and 0xff).toString() }
        }
    }
}
