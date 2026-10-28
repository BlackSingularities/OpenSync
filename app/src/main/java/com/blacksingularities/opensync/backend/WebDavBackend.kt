package com.blacksingularities.opensync.backend

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** WebDAV (Nextcloud, ownCloud, Synology WebDAV Server, Apache mod_dav, rclone serve). */
class WebDavBackend(baseUrl: String, user: String, password: String, insecureTls: Boolean) : Backend {
    private val base: HttpUrl = baseUrl.trim().trimEnd('/').plus("/").toHttpUrl()
    private val auth = if (user.isNotBlank()) Credentials.basic(user, password, Charsets.UTF_8) else null
    private val client: OkHttpClient
    private val madeDirs = HashSet<String>()

    init {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false)
        if (insecureTls) {
            // Świadomy wybór użytkownika: certyfikat samopodpisany w sieci domowej.
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
            b.sslSocketFactory(ctx.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
        }
        client = b.build()
    }

    private fun url(path: String, dir: Boolean = false): HttpUrl {
        val b = base.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        if (dir) b.addPathSegment("")
        return b.build()
    }

    private fun req(u: HttpUrl) = Request.Builder().url(u).apply { auth?.let { header("Authorization", it) } }

    private fun call(r: Request): Response = client.newCall(r).execute()

    private fun Response.check(what: String, vararg ok: Int): Response {
        if (code in 200..299 || code in ok) return this
        val c = code
        close()
        throw IOException(
            when (c) {
                401 -> "Serwer odrzucił login lub hasło"
                403 -> "Brak uprawnień ($what)"
                404 -> "Nie znaleziono ($what)"
                507 -> "Brak miejsca na serwerze"
                else -> "Serwer odpowiedział kodem $c ($what)"
            },
        )
    }

    private fun mkdirs(dir: String) {
        if (dir.isEmpty() || dir in madeDirs) return
        var acc = ""
        for (part in dir.split('/')) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            if (acc in madeDirs) continue
            call(req(url(acc, true)).method("MKCOL", null).build()).use { r ->
                // 405 = katalog już istnieje.
                if (r.code !in 200..299 && r.code != 405 && r.code != 301) r.check("MKCOL $acc")
            }
            madeDirs += acc
        }
    }

    override fun read(path: String): ByteArray {
        call(req(url(path)).get().build()).use { r ->
            if (r.code == 404) throw NotFoundException(path)
            r.check(path)
            return r.body!!.bytes()
        }
    }

    override fun write(path: String, data: ByteArray) {
        mkdirs(parentOf(path))
        val tmp = "$path.part"
        call(req(url(tmp)).put(data.toRequestBody("application/octet-stream".toMediaType())).build()).use { it.check("PUT $path") }
        call(
            req(url(tmp)).method("MOVE", null)
                .header("Destination", url(path).toString())
                .header("Overwrite", "T")
                .build(),
        ).use { it.check("MOVE $path") }
    }

    override fun exists(path: String): Boolean =
        call(req(url(path)).head().build()).use { it.code in 200..299 } || propfind(path) != null

    override fun list(dir: String): List<String> = propfind(dir)?.filterNot { it.endsWith(".part") }?.sorted() ?: emptyList()

    private fun propfind(dir: String): List<String>? {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val target = url(dir, true)
        call(
            req(target).method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1").build(),
        ).use { r ->
            if (r.code == 404) return null
            r.check("PROPFIND $dir")
            val selfPath = URLDecoder.decode(target.encodedPath, "UTF-8").trimEnd('/')
            return hrefs(r.body!!.string())
                .map { href ->
                    // href bywa pełnym adresem albo samą ścieżką.
                    val encoded = if (href.contains("://")) href.toHttpUrl().encodedPath else href
                    URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8").trimEnd('/')
                }
                .filter { it != selfPath && it.isNotEmpty() }
                .map { it.substringAfterLast('/') }
        }
    }

    private fun hrefs(xml: String): List<String> {
        val out = ArrayList<String>()
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(StringReader(xml))
        var inHref = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> inHref = p.name.equals("href", true)
                XmlPullParser.TEXT -> if (inHref) out += p.text.trim()
                XmlPullParser.END_TAG -> inHref = false
            }
            p.next()
        }
        return out
    }

    override fun delete(path: String) {
        call(req(url(path)).delete().build()).use { r -> if (r.code != 404) r.check("DELETE $path") }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
